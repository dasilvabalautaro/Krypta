#!/bin/bash
# Pone Caddy delante del `ws` local del nodo para servir `wss` en el 443 con certificado de
# Let's Encrypt. Ejecútalo DESDE la Mac, después de deploy-vps.sh y con el registro DNS ya
# creado (A → IP del VPS, **proxy desactivado / nube gris** en Cloudflare):
#
#   bash infra/node/deploy-caddy.sh root@216.128.169.83 krypta-sp.neto.chat
#   bash infra/node/deploy-caddy.sh root@163.245.192.235 krypta-dal.neto.chat
#
# Por qué existe: es la vía para redes que solo dejan salir por el 443 (WiFi de hotel, empresa,
# universidad). Hasta el 10 sep 2026 la daban los nodos domésticos tras Cloudflare Tunnel; al
# retirarlos de DEFAULT_BOOTSTRAP esos usuarios se quedaban sin nodo. Con nube gris el tráfico
# va directo al VPS: Cloudflare solo resuelve el nombre.
#
# Decisiones:
#   · Repositorio oficial de Caddy (cloudsmith), no el paquete de Ubuntu (2.6, de 2022).
#   · Certificado por TLS-ALPN en el 443 (`disable_http_challenge`): el 80 sigue cerrado en ufw.
#   · SIN log de accesos (ninguna directiva `log` en el sitio) y el log general con un filtro
#     que borra IP, puerto y cabeceras del cliente de cualquier línea de error: la regla de los
#     nodos es que su log no guarda identificadores de usuarios (docs/security-model.md §6.5).
#   · Valida el Caddyfile antes de instalarlo; si no valida, no toca el que hay.
#   · Sirve `/.well-known/security.txt` (RFC 9116) desde infra/node/security.txt: el contacto
#     de seguridad tiene que estar en un dominio del proyecto, y estos son los públicos. Todo
#     lo demás va al nodo como antes. Ojo al campo Expires: hay que renovarlo antes de que venza
#     y volver a lanzar este script en los dos VPS.
#
# Idempotente: relanzarlo reescribe el Caddyfile, el security.txt y recarga.
set -euo pipefail

HOST="${1:-}"
DOMAIN="${2:-}"
if [ -z "$HOST" ] || [ -z "$DOMAIN" ]; then
  echo "uso: bash infra/node/deploy-caddy.sh usuario@host dominio" >&2
  exit 1
fi

IP=$(ssh -o BatchMode=yes "$HOST" 'curl -4 -s --max-time 5 https://ifconfig.me')
RESOLVED=$(dig +short A "$DOMAIN" @1.1.1.1 | tail -1)
if [ "$RESOLVED" != "$IP" ]; then
  echo "ERROR: $DOMAIN resuelve a '$RESOLVED' y el VPS es '$IP'." >&2
  echo "       Crea el registro A con el proxy de Cloudflare DESACTIVADO (nube gris)." >&2
  exit 1
fi

SECURITY_TXT="$(dirname "$0")/security.txt"
if [ ! -f "$SECURITY_TXT" ]; then
  echo "ERROR: falta $SECURITY_TXT" >&2
  exit 1
fi
scp -o BatchMode=yes -q "$SECURITY_TXT" "$HOST:/tmp/security.txt.krypta"

ssh -o BatchMode=yes "$HOST" "DOMAIN=$DOMAIN bash -s" <<'REMOTE'
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
if ! command -v caddy >/dev/null; then
  apt-get -o DPkg::Lock::Timeout=180 -qq install -y debian-keyring debian-archive-keyring apt-transport-https curl gnupg >/dev/null
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --batch --yes --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt > /etc/apt/sources.list.d/caddy-stable.list
  chmod o+r /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list
  apt-get -o DPkg::Lock::Timeout=180 -qq update
  apt-get -o DPkg::Lock::Timeout=180 -qq install -y caddy >/dev/null
fi
echo "  caddy $(caddy version | cut -d' ' -f1)"

install -D -m 0644 /tmp/security.txt.krypta /etc/caddy/www/.well-known/security.txt
rm -f /tmp/security.txt.krypta

cat > /tmp/Caddyfile.krypta <<EOF
{
	log default {
		format filter {
			wrap console
			request>remote_ip delete
			request>remote_port delete
			request>client_ip delete
			request>headers delete
		}
	}
}

# wss/443 de respaldo para redes que solo dejan salir por 443. Sin log de accesos (ninguna
# directiva 'log' en el sitio) y el log general filtrado: no se guardan IPs de usuarios.
$DOMAIN {
	tls {
		issuer acme {
			disable_http_challenge
		}
	}
	# Contacto de seguridad (RFC 9116), desde infra/node/security.txt.
	handle /.well-known/security.txt {
		root * /etc/caddy/www
		header Content-Type "text/plain; charset=utf-8"
		file_server
	}
	handle {
		reverse_proxy 127.0.0.1:8081
	}
}
EOF
caddy validate --config /tmp/Caddyfile.krypta --adapter caddyfile >/dev/null
install -m 0644 /tmp/Caddyfile.krypta /etc/caddy/Caddyfile
rm -f /tmp/Caddyfile.krypta
systemctl enable caddy >/dev/null 2>&1
systemctl reload caddy 2>/dev/null || systemctl restart caddy

if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow 443/tcp >/dev/null
fi
echo "  Caddyfile aplicado"
REMOTE

echo "==> esperando el certificado de $DOMAIN (hasta 3 min)…"
for _ in $(seq 36); do
  if echo | openssl s_client -connect "$DOMAIN:443" -servername "$DOMAIN" 2>/dev/null \
      | openssl x509 -noout -issuer 2>/dev/null | grep -q "Let's Encrypt"; then
    echo | openssl s_client -connect "$DOMAIN:443" -servername "$DOMAIN" 2>/dev/null \
      | openssl x509 -noout -issuer -enddate | sed 's/^/  /'
    if curl -fsS --max-time 10 "https://$DOMAIN/.well-known/security.txt" | grep -q '^Contact: '; then
      echo "  security.txt servido: https://$DOMAIN/.well-known/security.txt"
    else
      echo "AVISO: https://$DOMAIN/.well-known/security.txt no responde con un Contact:" >&2
    fi
    echo
    echo "Comprueba libp2p por wss y añade la línea a Libp2pNode.DEFAULT_BOOTSTRAP:"
    echo "  bash infra/node/check-nodes.sh -v /dns4/$DOMAIN/tcp/443/wss/p2p/<PeerID>"
    exit 0
  fi
  sleep 5
done
echo "ERROR: sin certificado de Let's Encrypt en 3 min. Mira: ssh $HOST journalctl -u caddy -n 50" >&2
exit 1
