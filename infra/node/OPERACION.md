# Operar el nodo de São Paulo (VPS)

Guía del día a día del nodo primario: dónde vive cada cosa, cómo entrar y qué mirar cuando
algo va mal. El **despliegue** (compilar, instalar, systemd, puertos) está en la sección
"Nodo primario en un VPS Linux" de [README.md](README.md); aquí se da
por hecho que ya está montado.

| | |
|---|---|
| Proveedor / región | Vultr, São Paulo |
| IP | `216.128.169.83` |
| Hostname | `krypta-node-saopaulo` |
| SO | Ubuntu 24.04 LTS |
| PeerID | `12D3KooWBwcbXveKDSf4LrH9DYnwMDAyagkzh2uPYZyWkeoVMuk5` |

Es la **primera línea** de `Libp2pNode.DEFAULT_BOOTSTRAP`, o sea el nodo primario: `MailboxPut`
deposita en el primero vivo. El respaldo es el VPS de Dallas (segunda línea desde el 10 sep
2026; antes lo eran el Mac y el PC Windows), y como el bridge retira
y escucha de *todos* los nodos, que este se caiga no corta la entrega — solo la empeora.

> **Nodo de respaldo en Dallas (desde el 10 sep 2026).** InterServer, `163.245.192.235`,
> PeerID `12D3KooWQf7ZM3kXxc76XEN3Aj8gxhYorSKViPEGF4zepQuMQVCM`, Ubuntu 24.04.4 (1 vCPU /
> 1,9 GB). Todo lo de esta guía vale igual para él (`ssh root@163.245.192.235`, mismas rutas,
> mismo `deploy-vps.sh`), con tres diferencias: **no hay rsyslog** (el log solo está en el
> journal), **`ufw` sí está activo** (22/tcp, 4001/tcp+udp, 443/tcp), y el SSH **no acepta
> contraseña** (`/etc/ssh/sshd_config.d/00-krypta-hardening.conf`) — así que si se pierde la
> clave de la Mac la única entrada es la consola del panel de InterServer. `node.key`
> respaldada en `~/keystores/krypta/krypta-node-dallas.key`. Es la **segunda línea** de
> `DEFAULT_BOOTSTRAP` desde el 10 sep 2026, en lugar del Mac y el Windows. Detalles en la
> sección «Nodo de respaldo» del [README](README.md).

> **Caddy en los dos VPS (desde el 10 sep 2026)** sirve `wss/443` para redes que solo dejan
> salir por ese puerto: `krypta-sp.neto.chat` y `krypta-dal.neto.chat` (registros A en
> Cloudflare con **nube gris** — si alguien activa el proxy, el certificado deja de renovarse y
> el tráfico vuelve a pasar por Cloudflare). Se reinstala con `deploy-caddy.sh`. Comprobaciones:
>
> ```bash
> systemctl status caddy
> journalctl -u caddy -n 30 --no-pager      # renovaciones del certificado; nunca IPs de clientes
> echo | openssl s_client -connect krypta-sp.neto.chat:443 -servername krypta-sp.neto.chat 2>/dev/null | openssl x509 -noout -enddate
> ```
>
> Caddy escucha también en el 80, pero `ufw` lo cierra al exterior a propósito: el certificado
> se obtiene por TLS-ALPN en el 443. No abras el 80.

## Qué hay en la máquina

Esto es todo; no hay base de datos ni nada más.

| Ruta | Qué es |
|---|---|
| `/usr/local/bin/krypta-node` | El binario Go (~38 MB). Lo reemplaza `deploy-vps.sh` en cada despliegue |
| `/var/lib/krypta/node.key` | **La identidad del nodo** (68 bytes). De aquí sale el PeerID que llevan los móviles |
| `/var/lib/krypta/mailbox/` | Los sobres E2EE en tránsito, un subdirectorio por destinatario |
| `/etc/systemd/system/krypta-node.service` | La unidad que lo mantiene vivo y lo arranca en el boot |

## Cómo entrar

Desde la terminal de la Mac, sin contraseña (la clave SSH ya está puesta):

```bash
ssh root@216.128.169.83
```

Si SSH no responde —por ejemplo, tras equivocarse con `ufw` y cerrarse la puerta—, el panel de
Vultr tiene un botón **"View Console"** que abre una consola por navegador, conectada por debajo
del firewall. Es la red de seguridad: por eso conviene no deshabilitar el acceso por contraseña
de root sin haber probado antes esa consola.

## Comandos de diagnóstico

```bash
# ¿Está vivo?
systemctl status krypta-node

# Los logs. El binario no escribe a ningún fichero: todo va al journal de systemd.
journalctl -u krypta-node -n 50 --no-pager    # últimas 50 líneas
journalctl -u krypta-node -f                  # en vivo, como un tail -f
journalctl -u krypta-node --since "1 hour ago"
# En Ubuntu el journal es persistente y rsyslog copia cada línea a /var/log/syslog: lo que el
# nodo imprime se queda en disco. Por eso no imprime identificadores de usuario (ver abajo).

# ¿Hay correo pendiente en el buzón?
find /var/lib/krypta/mailbox -type f | wc -l   # nº de sobres sin retirar
ls -la /var/lib/krypta/mailbox/                # un directorio por destinatario

# ¿Quién está conectado al nodo ahora mismo?
ss -tn state established '( sport = :4001 )'

# ¿Está escuchando donde debe? (4001 tcp+udp y 8081 ws local)
ss -tulnp | grep krypta-node

# Reiniciar (systemd lo revive solo si se cae, esto es para forzarlo)
systemctl restart krypta-node
```

Sobre el buzón: que esté **vacío es lo normal**, no señal de avería. Un sobre solo existe entre
que el emisor lo deposita y el destinatario lo retira; el `sweep()` del nodo pasa cada hora y
borra lo caducado (TTL 7 días) y los directorios que quedan vacíos.

Sobre las conexiones: que `ss` no devuelva ninguna fila tampoco es alarmante. Los móviles no
mantienen una conexión permanente por TCP directo — el stream de wake se recicla y los OEM
agresivos (Transsion/TECNO, Xiaomi) suspenden la red con la pantalla apagada. Para comprobar
que un móvil concreto llega al nodo, lo fiable es abrir la app en ese móvil y mirar si aparece
su conexión.

## Comprobar el nodo desde la Mac (sondas)

Sin tocar el servidor. Estas cuatro son las que se usaron para validarlo antes de promoverlo a
primario (rutas **desde la raíz del repo**, no desde este directorio):

```bash
export PATH="/usr/local/bin:$HOME/go/bin:$PATH"
cd native-bridge/libp2p
ADDR=/ip4/216.128.169.83/tcp/4001/p2p/12D3KooWBwcbXveKDSf4LrH9DYnwMDAyagkzh2uPYZyWkeoVMuk5

MBX_ADDR=$ADDR  go test -run TestMailboxFetchAgainstLiveNode -v ./...      # ¿responde el buzón?
WAKE_ADDR=$ADDR go test -run TestWakeAgainstLiveNode -v ./...              # ¿responde el wake?
MBX_ADDR=$ADDR  go test -run TestMailboxRoundTripAgainstLiveNode -v ./...  # ciclo completo real
PING_ADDR=$ADDR go test -run TestPingAgainstLiveNode -v ./...              # latencia
```

Referencia de latencia medida el 7 ago 2026 desde La Paz: **p50 = 107 ms, p95 = 119 ms**. Si
algún día sale bastante peor, es señal de problema de red o de que el VPS está saturado.

## Actualizar el binario

Desde la Mac, con el repo delante (rutas **desde la raíz del repo**). Es idempotente y **no
toca `node.key`**, así que el PeerID se conserva:

```bash
cd infra/node
GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go1.22.12 build -o dist/krypta-node-linux-amd64 .
bash deploy-vps.sh root@216.128.169.83
```

Ojo con una asimetría fácil de olvidar: el Mac y el PC Windows corren **el mismo `main.go`**. Un
cambio de comportamiento del nodo (por ejemplo, ponerle topes al relay) desplegado solo aquí
hace que una llamada se comporte distinto según por qué relay pase, y como no se controla cuál
escoge, sale un fallo intermitente difícil de diagnosticar. O se despliegan los tres, o se
asume la diferencia a propósito.

## Tres cosas que conviene tener claras

**El buzón no se puede leer, y eso es lo correcto.** Si abres un `.json` de
`/var/lib/krypta/mailbox/` verás `id`, `from`, `ts` y un `blob` en base64 que es puro
ciphertext. Ni el dueño del servidor puede descifrarlo: es lo que promete la §3 de la
[política de privacidad](../../docs/politica-privacidad.html), y aquí se puede comprobar a ojo.

**`node.key` es lo único irreemplazable de la máquina.** El binario se recompila, la unidad
systemd está en el repo, el buzón es tránsito. Pero si esa clave se pierde el nodo cambia de
PeerID y los móviles ya instalados dejan de encontrarlo: habría que publicar otra versión de la
app. **Ya está respaldada (8 ago 2026)** en `~/keystores/krypta/krypta-node-saopaulo.key` en la
Mac del autor, verificada (mismo SHA-256 y deriva el PeerID real, no es solo un fichero
copiado). Para restaurar: ponerla en `/var/lib/krypta/node.key` con dueño `krypta:krypta` y
permisos `600` **antes** de arrancar el servicio — si arranca sin ella, se genera una identidad
nueva y el PeerID cambia.

**No edites ficheros con el servicio corriendo.** El proceso tiene su estado en memoria y puede
sobrescribir lo que toques. Si hay que cambiar algo de `/var/lib/krypta/`: `systemctl stop
krypta-node`, el cambio, y `systemctl start krypta-node`.

**El log no debe contener identificadores de usuario.** Es lo que promete la §5 de la política
de privacidad («no se guarda ningún registro más allá de eso»), y aquí «registro» incluye el
journal. Hasta el 10 sep 2026 no se cumplía: el handler de `/krypta/msg` imprimía el PeerID
remitente de cada mensaje directo, y la máquina tenía **12 de esas líneas desde el 7 ago**, en
el journal y en `/var/log/syslog`. Ahora el nodo solo imprime el arranque (su propio PeerID y
sus direcciones) y errores fatales; `-debugmsg` recupera el volcado antiguo, **solo para un nodo
local de pruebas**. Si algún día añades una línea de log, que no lleve PeerIDs, IPs ni
etiquetas de buzón — `TestMsgHandlerNoRegistraIdentificadores` cubre el caso que ya pasó.
Comprobación rápida en la máquina:

```bash
journalctl -u krypta-node --no-pager | grep -oE "12D3KooW[1-9A-Za-z]{44}" | sort -u   # solo el del propio nodo
```

## Pendientes en esta máquina

- **Registros antiguos con PeerIDs.** El binario del 10 sep se desplegó ese mismo día y
  `/var/log/syslog*` **ya está purgado** (12 líneas en `syslog`, `syslog.1` y `syslog.4.gz`;
  permisos `syslog:adm 640` conservados). Queda **el journal**, que no permite borrar líneas
  sueltas: se decidió **dejarlo rotar** en vez de vaciarlo entero, porque eso también borraría
  los logs de SSH y del sistema, que son los que sirven para detectar una intrusión. Sin límite
  de antigüedad journald solo borraba al llegar al 10 % del disco (se habrían ido hacia
  noviembre), así que desde el 10 sep `deploy-vps.sh` fija
  `/etc/systemd/journald.conf.d/krypta-retencion.conf` (`MaxRetentionSec=30day`,
  `MaxFileSec=1week`) y el journal se rotó a mano ese día: la última línea con PeerID es del
  6 sep y el fichero que la contiene acaba el 10 sep, así que **se borra hacia el 10 oct 2026**.
  Si hubiera que repetir la purga del syslog, el detalle que importa es el **HUP a rsyslog**:
  reescribir el fichero crea uno nuevo, y sin HUP rsyslog sigue escribiendo en el viejo, ya
  borrado.
  ```bash
  PAT='message from 12D3KooW'
  for f in /var/log/syslog*; do
    [ "$(zgrep -c "$PAT" "$f")" = 0 ] && continue
    case "$f" in *.gz) zcat "$f" | grep -v "$PAT" | gzip > "$f.p" ;; *) grep -v "$PAT" "$f" > "$f.p" ;; esac
    chown --reference="$f" "$f.p"; chmod --reference="$f" "$f.p"; mv "$f.p" "$f"
  done
  systemctl kill -s HUP rsyslog.service
  ```
- ~~**Topes finitos al relay.**~~ Hechos el 8 sep 2026 (8 GiB / 6 h por conexión relayada y
  cupos de reservas subidos, ver [main.go](main.go)).
- ~~**`net.core.rmem_max` bajo.**~~ Arreglado el 10 sep 2026: `deploy-vps.sh` escribe
  `/etc/sysctl.d/99-krypta-quic.conf` (`rmem_max`/`wmem_max` = 7 500 000, el mismo valor que el
  nodo de Nyx) y quic-go ya no avisa *"failed to sufficiently increase receive buffer size"* al
  arrancar.
