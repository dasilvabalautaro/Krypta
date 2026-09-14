#!/usr/bin/env bash
# Regenera native-bridge/libs/krypta-p2p.aar desde el módulo Go (go-libp2p) con gomobile.
#
# Requisitos:
#   - Go >= 1.26 (brew install go)
#   - gomobile + gobind:  go install golang.org/x/mobile/cmd/{gomobile,gobind}@latest
#   - Android NDK instalado (este proyecto se compiló con 26.1.10909125)
#
# Nota clave: go-libp2p depende de github.com/wlynxg/anet, que usa //go:linkname contra
# un símbolo no exportado de net. Go >= 1.23 lo bloquea, por eso es OBLIGATORIO
# -ldflags="-checklinkname=0" o el enlazado falla con "invalid reference to net.zoneCache".
#
# Segunda bandera OBLIGATORIA: -extldflags=-Wl,-z,max-page-size=16384. Play exige soporte
# de páginas de 16 KB para apps con targetSdk >= 35 desde el 1 nov 2025, y el NDK 26 enlaza
# los segmentos a 4 KB (0x1000) por defecto. Sin esto libgojni.so sale a 4 KB y el AAB se
# rechaza / no arranca en dispositivos de 16 KB. Verificar tras compilar:
#   llvm-readelf -l <so> | grep LOAD   → la última columna debe ser 0x4000
set -euo pipefail

export PATH="/usr/local/bin:$HOME/go/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/26.1.10909125}"

cd "$(dirname "$0")"

go mod tidy
# El directorio de salida solo contiene ficheros ignorados por git (el AAR no se versiona), así
# que **no existe en un clon limpio** y gomobile fallaba al final, tras compilar las cuatro ABI,
# con "open ../libs/krypta-p2p.aar: no such file or directory". Lo destapó el 14 sep 2026 la
# compilación desde un clon de la etiqueta `revision-externa-1`: el camino que la documentación
# da para un clon nuevo no funcionaba, y en la máquina del autor nunca se notó porque el
# directorio ya estaba ahí.
mkdir -p ../libs
gomobile bind \
  -target=android \
  -androidapi 30 \
  -ldflags="-checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" \
  -javapkg=chat.neto.krypta \
  -o ../libs/krypta-p2p.aar \
  .

# --- Acota el proguard de consumidor que mete gomobile -----------------------------------
# gomobile escribe dentro del AAR un proguard.txt de consumidor derivado de -javapkg:
#
#     -keep class go.** { *; }
#     -keep class chat.neto.krypta.** { *; }
#
# La segunda regla es MUCHO más ancha de lo necesario: el paquete bindeado es
# chat.neto.krypta.bridge, pero el prefijo cubre `chat.neto.krypta.**`, o sea **la app
# entera**. Efecto real (detectado el 2 sep 2026 al revisar el mapping.txt): R8 no ofuscaba,
# ni optimizaba, ni podaba NADA del código propio de Krypta —ni los nombres de miembros—,
# solo el de las librerías. Las reglas de consumidor de un AAR se aplican sin que nadie las
# vea en app/proguard-rules.pro, así que esto es invisible salvo que se audite la config
# final de R8 (app/build/outputs/mapping/release/configuration.txt).
#
# Lo que de verdad hace falta preservar para el puente JNI ya está en
# app/proguard-rules.pro: go.**, chat.neto.krypta.bridge.** y las implementaciones de
# go.Seq$Proxy. Aquí se reescribe la regla al paquete realmente bindeado.
echo "Acotando el proguard de consumidor del AAR a chat.neto.krypta.bridge.**"
AAR_ABS="$(cd ../libs && pwd)/krypta-p2p.aar"
PG_TMP="$(mktemp -d)"
cat > "$PG_TMP/proguard.txt" <<'PROGUARD'
-keep class go.** { *; }
-keep class chat.neto.krypta.bridge.** { *; }
PROGUARD
# `zip` reemplaza la entrada existente dentro del AAR (que es un zip). Hay que ejecutarlo
# desde el directorio del fichero para que la entrada quede en la raíz del AAR, sin ruta.
(cd "$PG_TMP" && zip -q "$AAR_ABS" proguard.txt)
rm -rf "$PG_TMP"

echo "proguard.txt del AAR:"
unzip -p ../libs/krypta-p2p.aar proguard.txt | sed 's/^/    /'

echo "AAR regenerado en native-bridge/libs/krypta-p2p.aar"
ls -lh ../libs/krypta-p2p.aar
