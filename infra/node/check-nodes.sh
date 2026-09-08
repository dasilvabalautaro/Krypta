#!/bin/bash
# Chequeo de salud de los nodos de Krypta. Pensado para ejecutarse desatendido (cron/launchd):
# calla cuando todo va bien y grita cuando algo falla, con código de salida distinto de cero.
#
#   bash infra/node/check-nodes.sh            # los nodos de DEFAULT_BOOTSTRAP
#   bash infra/node/check-nodes.sh -v         # detalle también cuando todo va bien
#   bash infra/node/check-nodes.sh --notify   # además, aviso del sistema si algo falla (macOS)
#   bash infra/node/check-nodes.sh /ip4/…/p2p/…   # un nodo concreto
#
# Para que se ejecute solo, ver chat.neto.krypta.check.plist (launchd, cada 15 min) o, en el
# propio VPS, una línea de cron equivalente — está en la sección "Vigilancia" del README.
#
# Por qué existe: hasta ahora, si un nodo se caía o se quedaba con un binario viejo, nadie se
# enteraba. El 8 sep 2026 el VPS —el nodo primario— pasó dos días sirviendo el binario anterior
# al anti-abuso y solo se descubrió mirando a mano. Esto lo detecta en segundos.
#
# Qué comprueba en cada nodo, reutilizando las sondas que ya existen en el puente Go:
#   · buzón   → responde /krypta/mbx/get (si no, o está caído o el binario es viejo)
#   · wake    → responde /krypta/wake y manda su saludo
#   · relay   → ofrece reserva CON límites finitos; sin límites = binario anterior al anti-abuso
#   · vuelta  → depósito y retirada reales, byte a byte (usa identidades efímeras, se limpia solo)
set -uo pipefail

export PATH="/usr/local/bin:$HOME/go/bin:$PATH"
cd "$(dirname "$0")/../../native-bridge/libp2p" || exit 2

VERBOSE=0
NOTIFY=0
NODES=()
for arg in "$@"; do
  case "$arg" in
    -v|--verbose) VERBOSE=1 ;;
    --notify) NOTIFY=1 ;;
    /*) NODES+=("$arg") ;;
    *) echo "uso: check-nodes.sh [-v] [--notify] [multiaddr…]" >&2; exit 2 ;;
  esac
done

# Sin argumentos: los mismos nodos que usa la app, leídos de la constante para que no puedan
# quedarse desincronizados con ella.
if [ ${#NODES[@]} -eq 0 ]; then
  KT="../src/main/java/chat/neto/krypta/nativebridge/Libp2pNode.kt"
  while IFS= read -r line; do NODES+=("$line"); done < <(
    sed -n '/const val DEFAULT_BOOTSTRAP/,/^$/p' "$KT" | grep -o '/[^"]*p2p/12D3KooW[A-Za-z0-9]*'
  )
fi
if [ ${#NODES[@]} -eq 0 ]; then
  echo "no se pudo determinar la lista de nodos" >&2
  exit 2
fi

# probe <nombre> <VAR=addr> <TestName> → 0 si pasa; deja el detalle en $DETAIL
DETAIL=""
probe() {
  local label="$1" env_assignment="$2" test_name="$3" out
  out=$(env "$env_assignment" go test . -run "$test_name" -count=1 -v 2>&1)
  if grep -q "^--- PASS" <<<"$out"; then
    DETAIL=$(grep -o "OK: .*" <<<"$out" | head -1)
    return 0
  fi
  DETAIL=$(grep -E "^\s+\S+\.go:[0-9]+:" <<<"$out" | head -1 | sed 's/^[[:space:]]*//')
  [ -n "$DETAIL" ] || DETAIL="$(tail -2 <<<"$out" | head -1)"
  return 1
}

FAILED=0
echo "krypta-check $(date -u '+%Y-%m-%d %H:%M UTC') · ${#NODES[@]} nodo(s)"
for addr in "${NODES[@]}"; do
  short=$(sed -E 's#^/(ip4|dns4)/([^/]+)/.*#\2#' <<<"$addr")
  problems=()
  results=()
  for check in "buzón:MBX_ADDR:TestMailboxFetchAgainstLiveNode" \
               "wake:WAKE_ADDR:TestWakeAgainstLiveNode" \
               "relay:RELAY_ADDR:TestRelayLimitsAgainstLiveNode" \
               "vuelta:MBX_ADDR:TestMailboxRoundTripAgainstLiveNode"; do
    name="${check%%:*}"; rest="${check#*:}"; var="${rest%%:*}"; test_name="${rest#*:}"
    if probe "$name" "$var=$addr" "$test_name"; then
      results+=("$name ok")
    else
      results+=("$name FALLA")
      problems+=("$name: $DETAIL")
    fi
  done

  summary=$(printf '%s · ' "${results[@]}"); summary=${summary% · }
  if [ ${#problems[@]} -eq 0 ]; then
    [ "$VERBOSE" -eq 1 ] && printf '  OK  %-24s %s\n' "$short" "$summary"
  else
    FAILED=1
    printf '  !!  %-24s %s\n' "$short" "$summary"
    for p in "${problems[@]}"; do printf '        %s\n' "$p"; done
  fi
done

if [ "$FAILED" -eq 0 ]; then
  [ "$VERBOSE" -eq 1 ] && echo "todo correcto"
  exit 0
fi
echo "REVISAR: al menos un nodo falla. Runbook en infra/node/README.md" >&2
# Aviso a la vista: ejecutándose desde launchd, nadie mira el log hasta que algo va mal, que
# es justo cuando ya es tarde. Best-effort — si no hay sesión gráfica, no pasa nada.
if [ "$NOTIFY" -eq 1 ] && command -v osascript >/dev/null 2>&1; then
  osascript -e 'display notification "Al menos un nodo falla el chequeo" with title "Krypta"' \
    >/dev/null 2>&1 || true
fi
exit 1
