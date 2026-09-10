#!/bin/bash
# Despliega/actualiza el nodo Krypta en un VPS Linux bajo systemd.
# Ejecútalo DESDE la Mac (no en el VPS), con el repo delante:
#
#   bash infra/node/deploy-vps.sh root@1.2.3.4            # amd64 (por defecto)
#   bash infra/node/deploy-vps.sh usuario@mivps arm64     # VPS ARM (Ampere, Graviton…)
#
# Requiere acceso SSH con sudo (o root). Qué hace:
#   1. Sube el binario Linux de dist/ a /usr/local/bin/krypta-node
#   2. Crea el usuario de sistema `krypta` y /var/lib/krypta (identidad + buzón)
#   3. Fija los buffers UDP de QUIC (sysctl) y la retención del journal (30 días)
#   4. Instala/recarga la unidad systemd (Restart=always, arranca en el boot)
#   5. Abre los puertos en ufw si está activo
#   6. Imprime el PeerID y el multiaddr de bootstrap para pegar en la app
#
# Es idempotente: relanzarlo actualiza el binario y reinicia el servicio. NO toca el
# node.key existente, así que el PeerID se conserva entre despliegues.
set -euo pipefail

HOST="${1:-}"
ARCH="${2:-amd64}"
if [ -z "$HOST" ]; then
  echo "uso: bash infra/node/deploy-vps.sh usuario@host [amd64|arm64]" >&2
  exit 1
fi

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
BIN_SRC="$SRC_DIR/dist/krypta-node-linux-$ARCH"
UNIT_SRC="$SRC_DIR/krypta-node.service"

echo "==> binario origen: $BIN_SRC"
[ -f "$BIN_SRC" ] || {
  echo "ERROR: no existe $BIN_SRC. Compílalo con:"
  echo "  cd infra/node && GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=linux GOARCH=$ARCH \\"
  echo "    go1.22.12 build -o dist/krypta-node-linux-$ARCH ."
  exit 1
}

# Mismas comprobaciones que en Catalina: que el binario no sea uno viejo sin buzón/wake.
# `grep -a` sobre el fichero, sin tubería: `strings | grep -q` revienta con set -o pipefail
# (grep cierra la tubería al primer acierto y strings muere con SIGPIPE).
grep -a -q mailboxdir "$BIN_SRC"    || { echo "ERROR: binario sin buzón (-mailboxdir)"; exit 1; }
grep -a -q "krypta/wake" "$BIN_SRC" || { echo "ERROR: binario sin wake (/krypta/wake)"; exit 1; }
grep -a -q quicport "$BIN_SRC"      || { echo "ERROR: binario sin -quicport (recompila dist/)"; exit 1; }

echo "==> subiendo binario y unidad a $HOST"
scp -q "$BIN_SRC" "$HOST:/tmp/krypta-node.new"
scp -q "$UNIT_SRC" "$HOST:/tmp/krypta-node.service"

echo "==> instalando en el VPS (pide sudo)"
ssh -t "$HOST" 'sudo bash -s' <<'REMOTE'
set -euo pipefail

# Usuario de sistema sin shell ni home: solo corre el proceso.
if ! id krypta >/dev/null 2>&1; then
  echo "  creando usuario de sistema krypta"
  useradd --system --no-create-home --shell /usr/sbin/nologin krypta
fi

# Para el servicio antes de reemplazar el binario en uso (si ya existía).
systemctl stop krypta-node 2>/dev/null || true

install -m 0755 /tmp/krypta-node.new /usr/local/bin/krypta-node
install -m 0644 /tmp/krypta-node.service /etc/systemd/system/krypta-node.service
rm -f /tmp/krypta-node.new /tmp/krypta-node.service

mkdir -p /var/lib/krypta
chown krypta:krypta /var/lib/krypta
chmod 0700 /var/lib/krypta

# Buffers UDP para quic-go. Con el default de Ubuntu (~208 KiB) avisa al arrancar "failed to
# sufficiently increase receive buffer size" y el caudal QUIC queda capado bajo carga. Mismo
# valor que el nodo de Nyx en Secaucus, donde ya está comprobado.
if [ ! -f /etc/sysctl.d/99-krypta-quic.conf ]; then
  echo "  fijando net.core.{rmem,wmem}_max para QUIC"
  cat > /etc/sysctl.d/99-krypta-quic.conf <<'SYSCTL'
# Krypta: buffers UDP para quic-go (ver infra/node/OPERACION.md)
net.core.rmem_max=7500000
net.core.wmem_max=7500000
SYSCTL
fi
sysctl -q --system >/dev/null 2>&1 || true

# Retención del journal. Por defecto journald solo borra por TAMAÑO (10 % del disco), así que
# todo lo que el nodo haya impreso se queda meses: el 10 sep 2026 aún guardaba líneas con
# PeerIDs de agosto. 30 días conserva lo que sirve para detectar una intrusión (SSH, sistema);
# MaxFileSec=1week hace que se archive por semanas, porque journald solo borra ficheros
# archivados enteros cuya última entrada ya caducó — con el default de un mes, lo caducado
# podía seguir vivo semanas de más dentro del fichero activo. Afecta a todo el journal de la
# máquina, que en un VPS dedicado al nodo es lo que se quiere.
JOURNALD_CONF=/etc/systemd/journald.conf.d/krypta-retencion.conf
JOURNALD_WANT='[Journal]
MaxRetentionSec=30day
MaxFileSec=1week'
if [ "$(cat "$JOURNALD_CONF" 2>/dev/null)" != "$JOURNALD_WANT" ]; then
  echo "  fijando retención del journal a 30 días"
  mkdir -p /etc/systemd/journald.conf.d
  printf '%s\n' "$JOURNALD_WANT" > "$JOURNALD_CONF"
  systemctl restart systemd-journald
fi

systemctl daemon-reload
systemctl enable --now krypta-node

# Firewall: TCP/UDP 4001 (libp2p) y 443 (wss, si pones Caddy/nginx delante).
# El 8081 es ws EN CLARO: no se abre al exterior a propósito.
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  echo "  ufw activo: abriendo 4001/tcp, 4001/udp, 443/tcp"
  ufw allow 4001/tcp >/dev/null
  ufw allow 4001/udp >/dev/null
  ufw allow 443/tcp  >/dev/null
else
  echo "  (ufw no activo: abre 4001/tcp, 4001/udp y 443/tcp en el firewall del proveedor)"
fi

sleep 4
echo
echo "===== estado ====="
systemctl is-active krypta-node && systemctl is-enabled krypta-node
echo
echo "===== log (últimas líneas) ====="
journalctl -u krypta-node -n 20 --no-pager
echo
echo "===== ¿escucha? ====="
(ss -lntup 2>/dev/null || netstat -lntup 2>/dev/null) | grep -E ":4001|:8081" || echo "(NADA escuchando — revisa el log)"
REMOTE

echo
echo "==================================================================="
echo "Siguiente paso: coge el PeerID del log de arriba y comprueba desde aquí"
echo
echo "  # TCP directo (sin TLS, el camino simple con IP pública):"
echo "  MBX_ADDR=/ip4/<IP_DEL_VPS>/tcp/4001/p2p/<PeerID> \\"
echo "    go test -run TestMailboxFetchAgainstLiveNode -v ./native-bridge/libp2p/"
echo
echo "Y en la app, Ajustes → Nodos WAN (bootstrap), una línea por nodo:"
echo "  /ip4/<IP_DEL_VPS>/tcp/4001/p2p/<PeerID>"
echo "  /ip4/<IP_DEL_VPS>/udp/4001/quic-v1/p2p/<PeerID>     (QUIC, ya con puerto fijo)"
echo "  /dns4/<tu-dominio>/tcp/443/wss/p2p/<PeerID>          (si montas Caddy, ver README)"
echo
echo "Cuando el nodo esté validado, pásalo a Libp2pNode.DEFAULT_BOOTSTRAP como nodo primario."
echo "==================================================================="
