#!/bin/bash
# Chequeo de salud de los nodos de Krypta. Pensado para ejecutarse desatendido (cron/launchd):
# calla cuando todo va bien y grita cuando algo falla, con código de salida distinto de cero.
#
#   bash infra/node/check-nodes.sh            # los nodos de DEFAULT_BOOTSTRAP
#   bash infra/node/check-nodes.sh -v         # detalle también cuando todo va bien
#   bash infra/node/check-nodes.sh --notify   # además, aviso del sistema al cambiar el estado (macOS)
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
#   · ciego   → sirve el depósito ciego (v2): la etiqueta en vez del PeerID, y sin remitente
#
# Coste (12 sep 2026): las sondas se compilan **una vez** en un binario de test y se ejecutan
# de ahí. Antes cada sonda era un `go test` que volvía a enlazar libp2p: 20 sondas, 70 s y 84 s
# de CPU por pasada, casi un 10 % de un núcleo sostenido en la Mac a razón de una cada 15 min.
# Ahora el binario se reconstruye solo si cambió algún .go, go.mod o go.sum.
#
# Avisos (12 sep 2026): con --notify se avisa **al cambiar el estado** —cuando algo empieza a
# fallar, cuando cambia lo que falla y cuando se recupera—, no en cada pasada. Un aviso cada 15
# minutos durante una caída acaba silenciado; uno por cambio se lee. El estado se guarda en
# $KRYPTA_CHECK_STATE (por defecto ~/Library/Caches/krypta-check.state).
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

notify() {
  [ "$NOTIFY" -eq 1 ] && command -v osascript >/dev/null 2>&1 || return 0
  # Best-effort: si no hay sesión gráfica (launchd sin usuario), no pasa nada.
  osascript -e "display notification \"$2\" with title \"Krypta\" subtitle \"$1\" sound name \"$3\"" \
    >/dev/null 2>&1 || true
}

# Binario de sondas, compilado una vez y reutilizado mientras el código no cambie.
CACHE_DIR="${TMPDIR:-/tmp}/krypta-check"
BIN="$CACHE_DIR/probes.test"
mkdir -p "$CACHE_DIR"
if [ ! -x "$BIN" ] || [ -n "$(find . -maxdepth 1 \( -name '*.go' -o -name 'go.mod' -o -name 'go.sum' \) -newer "$BIN" | head -1)" ]; then
  if ! build_out=$(go test -c -o "$BIN.tmp" . 2>&1); then
    echo "krypta-check $(date -u '+%Y-%m-%d %H:%M UTC') · no compilan las sondas:" >&2
    echo "$build_out" | tail -5 >&2
    rm -f "$BIN.tmp"
    notify "No compilan las sondas" "check-nodes.sh no puede comprobar los nodos" "Basso"
    exit 2
  fi
  mv "$BIN.tmp" "$BIN"
fi

# probe <nombre> <VAR=addr> <TestName> → 0 si pasa; deja el detalle en $DETAIL
DETAIL=""
probe() {
  local label="$1" env_assignment="$2" test_name="$3" out
  out=$(env "$env_assignment" "$BIN" -test.run "^${test_name}\$" -test.count=1 -test.v 2>&1)
  if grep -q "^--- PASS" <<<"$out"; then
    DETAIL=$(grep -o "OK: .*" <<<"$out" | head -1)
    return 0
  fi
  DETAIL=$(grep -E "^\s+\S+\.go:[0-9]+:" <<<"$out" | head -1 | sed 's/^[[:space:]]*//')
  [ -n "$DETAIL" ] || DETAIL="$(tail -2 <<<"$out" | head -1)"
  return 1
}

FAILED=0
FAILING=()   # "nodo:sonda", para decidir si el estado cambió
echo "krypta-check $(date -u '+%Y-%m-%d %H:%M UTC') · ${#NODES[@]} nodo(s)"
for addr in "${NODES[@]}"; do
  short=$(sed -E 's#^/(ip4|dns4)/([^/]+)/.*#\2#' <<<"$addr")
  problems=()
  results=()
  for check in "buzón:MBX_ADDR:TestMailboxFetchAgainstLiveNode" \
               "wake:WAKE_ADDR:TestWakeAgainstLiveNode" \
               "relay:RELAY_ADDR:TestRelayLimitsAgainstLiveNode" \
               "vuelta:MBX_ADDR:TestMailboxRoundTripAgainstLiveNode" \
               "ciego:MBX_ADDR:TestBlindMailboxAgainstLiveNode"; do
    name="${check%%:*}"; rest="${check#*:}"; var="${rest%%:*}"; test_name="${rest#*:}"
    if probe "$name" "$var=$addr" "$test_name"; then
      results+=("$name ok")
    else
      results+=("$name FALLA")
      problems+=("$name: $DETAIL")
      FAILING+=("$short:$name")
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

# Aviso solo al cambiar el estado. La firma es la lista ordenada de "nodo:sonda" que fallan.
#
# Solo el modo desatendido (--notify) lee y escribe el estado. Una pasada a mano no lo toca:
# el 12 sep 2026 una comprobación manual de un solo nodo, lanzada segundos después de que la
# pasada de launchd hubiera registrado una caída, sobrescribió el estado con "todo bien" — y la
# vigilancia se quedó sin saber que tenía que avisar de la recuperación. Además, una pasada
# con nodos concretos compararía su firma contra la de la lista completa.
if [ "$NOTIFY" -eq 1 ]; then
  STATE="${KRYPTA_CHECK_STATE:-$HOME/Library/Caches/krypta-check.state}"
  mkdir -p "$(dirname "$STATE")"
  current=""
  [ ${#FAILING[@]} -gt 0 ] && current=$(printf '%s\n' "${FAILING[@]}" | sort | tr '\n' ' ' | sed 's/ $//')
  previous=$(cat "$STATE" 2>/dev/null || true)
  if [ "$current" != "$previous" ]; then
    if [ -n "$current" ]; then
      nodes=$(printf '%s\n' "${FAILING[@]}" | cut -d: -f1 | sort -u | tr '\n' ' ' | sed 's/ $//')
      notify "Falla un nodo" "$nodes — ver /tmp/krypta-check.log" "Basso"
    elif [ -n "$previous" ]; then
      notify "Nodos recuperados" "Todos los nodos pasan el chequeo" "Glass"
    fi
    printf '%s' "$current" > "$STATE"
  fi
fi

if [ "$FAILED" -eq 0 ]; then
  [ "$VERBOSE" -eq 1 ] && echo "todo correcto"
  exit 0
fi
echo "REVISAR: al menos un nodo falla. Runbook en infra/node/README.md" >&2
exit 1
