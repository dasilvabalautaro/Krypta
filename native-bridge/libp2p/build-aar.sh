#!/usr/bin/env bash
# Regenera native-bridge/libs/krypta-p2p.aar desde el módulo Go (go-libp2p) con gomobile, de
# forma **reproducible**: el mismo commit, con las mismas herramientas, da el mismo AAR byte a
# byte, desde cualquier carpeta y con la caché de Go vacía. Así cualquiera puede comprobar que un
# AAR publicado sale de un commit concreto: lo compila y compara el sha256.
#
# Por qué hace falta todo lo de abajo (medido el 14 sep 2026; ver
# docs/PLAN-privacidad-y-confianza.md §4.3). El AAR de antes no se podía ligar a su fuente:
#   - libgojni.so llevaba ~1600 rutas de la máquina (caché de módulos, carpeta del código,
#     directorio temporal de gomobile)  → -trimpath;
#   - quedaban 2, las de la directiva `replace` que gomobile escribe apuntando a la carpeta del
#     módulo, que -trimpath no quita  → se compila siempre desde una ruta fija (BUILD_DIR);
#   - ningún identificador de commit, y un Version() escrito a mano y desfasado
#     → se inyecta el commit con -ldflags -X y el script comprueba que está dentro;
#   - la entrada proguard.txt se reescribía con la hora del momento  → fecha fija en UTC;
#   - herramientas sin fijar (gomobile @latest, `go mod tidy` que podía tocar go.mod)
#     → versiones comprobadas y `go mod tidy -diff`, que solo comprueba.
# Con eso, dos compilaciones desde dos clones en rutas distintas y cachés vacías dieron el mismo
# AAR. Límite honesto: se ha comprobado en una sola máquina (macOS, Intel). Entre sistemas
# anfitrión distintos (el NDK trae binarios distintos para macOS y Linux) no se ha probado.
#
# Requisitos. Los que cambian los bytes se comprueban, y el script falla si no se cumplen:
#   - Go: la versión de la directiva `toolchain` de go.mod (con GOTOOLCHAIN=auto, Go la descarga).
#   - gomobile y gobind: la versión de golang.org/x/mobile que fija go.mod. El script los instala
#     en un directorio propio y los pone primero en el PATH (gomobile busca gobind en el PATH).
#   - Android NDK 26.1.10909125.
#   - JDK 25 (javac compila las clases Java que van en el AAR).
#   - git, tar y zip.
#
# Nota clave: go-libp2p depende de github.com/wlynxg/anet, que usa //go:linkname contra
# un símbolo no exportado de net. Go >= 1.23 lo bloquea, por eso es OBLIGATORIO
# -ldflags="-checklinkname=0" o el enlazado falla con "invalid reference to net.zoneCache".
#
# Segunda bandera OBLIGATORIA: -extldflags=-Wl,-z,max-page-size=16384. Play exige soporte
# de páginas de 16 KB para apps con targetSdk >= 35 desde el 1 nov 2025, y el NDK 26 enlaza
# los segmentos a 4 KB (0x1000) por defecto. Sin esto libgojni.so sale a 4 KB y el AAB se
# rechaza / no arranca en dispositivos de 16 KB. El script lo comprueba en las cuatro ABI.
set -euo pipefail

NDK_VERSION=26.1.10909125
JDK_MAJOR=25
# Ruta fija desde la que se compila. gomobile genera un go.mod con `replace` a la carpeta del
# módulo y esa ruta queda dentro de libgojni.so: compilar siempre desde la misma es lo que hace
# que dos checkouts en carpetas distintas den los mismos bytes.
BUILD_DIR=/tmp/krypta-aar

export PATH="/usr/local/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# Siempre el NDK fijado, **ignorando un ANDROID_NDK_HOME heredado**. Antes era
# `${ANDROID_NDK_HOME:-…/26.1…}`, y en la máquina del autor `~/.zprofile` exporta el NDK 25.2:
# **todos los AAR hasta el 14 sep 2026 se compilaron con el NDK 25.2** (clang 14.0.7, según la
# sección .comment de libgojni.so), mientras la documentación decía 26.1. Una variable de
# entorno puesta para otro proyecto no debe poder cambiar el binario sin que se note.
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"

MOD_DIR="$(cd "$(dirname "$0")" && pwd)"
LIBS_DIR="$(cd "$MOD_DIR/.." && pwd)/libs"
# El directorio de salida solo contiene ficheros ignorados por git (el AAR no se versiona), así
# que **no existe en un clon limpio** y gomobile fallaba al final, tras compilar las cuatro ABI,
# con "open ../libs/krypta-p2p.aar: no such file or directory". Lo destapó el 14 sep 2026 la
# compilación desde un clon de la etiqueta `revision-externa-1`.
mkdir -p "$LIBS_DIR"
cd "$MOD_DIR"

# Variables de entorno que cambiarían los bytes sin que se notara.
unset GOFLAGS GOEXPERIMENT GOARM GOARM64 GOAMD64 CGO_CFLAGS CGO_CPPFLAGS CGO_CXXFLAGS CGO_LDFLAGS

falla() { echo "ERROR: $*" >&2; exit 1; }
sha256() { if command -v shasum >/dev/null; then shasum -a 256 "$1"; else sha256sum "$1"; fi; }

echo "== Herramientas"
WANT_GO="$(awk '/^toolchain /{print $2}' go.mod)"
[ -n "$WANT_GO" ] || falla "go.mod no fija la directiva 'toolchain'"
GO_VERSION="$(go env GOVERSION)"
[ "$GO_VERSION" = "$WANT_GO" ] || falla "Go es $GO_VERSION y go.mod pide $WANT_GO"

NDK_REV="$(awk -F' = ' '/^Pkg.Revision/{print $2}' "$ANDROID_NDK_HOME/source.properties" 2>/dev/null || true)"
[ "$NDK_REV" = "$NDK_VERSION" ] || falla "el NDK de $ANDROID_NDK_HOME es '$NDK_REV'; hace falta $NDK_VERSION"
LLVM_BIN="$(dirname "$(ls "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-readelf | head -1)")"

JAVAC_VERSION="$(javac -version 2>&1 | awk '{print $2}')"
[ "${JAVAC_VERSION%%.*}" = "$JDK_MAJOR" ] || falla "javac es '$JAVAC_VERSION'; hace falta el JDK $JDK_MAJOR"

MOBILE_VERSION="$(go list -m -f '{{.Version}}' golang.org/x/mobile)"
TOOLS_BIN="${TMPDIR:-/tmp}/krypta-gomobile-$MOBILE_VERSION-$GO_VERSION"
if [ ! -x "$TOOLS_BIN/gomobile" ] || [ ! -x "$TOOLS_BIN/gobind" ]; then
  GOBIN="$TOOLS_BIN" go install "golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION" \
    "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"
fi
export PATH="$TOOLS_BIN:$PATH"
echo "go $GO_VERSION · x/mobile $MOBILE_VERSION · NDK $NDK_REV · javac $JAVAC_VERSION"

echo "== Dependencias"
go mod tidy -diff >/dev/null || falla "go.mod/go.sum no están al día: ejecuta 'go mod tidy' y confirma el cambio"
go mod verify >/dev/null || falla "la caché de módulos no coincide con go.sum"

echo "== Commit"
if COMMIT="$(git rev-parse HEAD 2>/dev/null)"; then
  # Cambios sin confirmar en el módulo (o ficheros nuevos): el AAR ya no sale de ese commit, y
  # se dice en el propio binario.
  [ -z "$(git status --porcelain -- .)" ] || COMMIT="$COMMIT-modificado"
else
  COMMIT=desconocido
fi
echo "$COMMIT"

echo "== Copia a la ruta fija $BUILD_DIR"
LOCK="$BUILD_DIR.lock"
mkdir "$LOCK" 2>/dev/null || falla "otra compilación está usando $BUILD_DIR (si no es así, borra $LOCK)"
trap 'rm -rf "$LOCK"' EXIT
rm -rf "$BUILD_DIR" && mkdir -p "$BUILD_DIR/libp2p"
if [ "$COMMIT" != desconocido ]; then
  # Solo lo que ve git (versionado, o nuevo y no ignorado): nada suelto de la carpeta entra.
  git ls-files -z -co --exclude-standard -- . | tar --null -T - -cf - | tar -xf - -C "$BUILD_DIR/libp2p"
else
  cp -R "$MOD_DIR/." "$BUILD_DIR/libp2p"
fi

echo "== gomobile bind"
OUT="$BUILD_DIR/krypta-p2p.aar"
(cd "$BUILD_DIR/libp2p" && GOFLAGS=-mod=readonly gomobile bind \
  -target=android \
  -androidapi 30 \
  -trimpath \
  -ldflags="-checklinkname=0 -X chat.neto.krypta/nativego.buildCommit=$COMMIT -extldflags=-Wl,-z,max-page-size=16384" \
  -javapkg=chat.neto.krypta \
  -o "$OUT" \
  .)

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
#
# Y se reescribe **con fecha fija**: `zip` guarda la hora de modificación del fichero, así que
# hacerlo con la del momento daba un AAR distinto cada vez. `touch -t` interpreta la hora en la
# zona local, de ahí que los dos pasos vayan en UTC; `-X` quita los atributos extra (uid, gid,
# marcas de tiempo ampliadas).
echo "== Acotando el proguard de consumidor del AAR a chat.neto.krypta.bridge.**"
PG_TMP="$(mktemp -d)"
printf '%s\n' '-keep class go.** { *; }' '-keep class chat.neto.krypta.bridge.** { *; }' > "$PG_TMP/proguard.txt"
(cd "$PG_TMP" && TZ=UTC touch -t 198001010000 proguard.txt && TZ=UTC zip -X -q "$OUT" proguard.txt)
rm -rf "$PG_TMP"

echo "== Comprobaciones"
CHECK="$(mktemp -d)"
(cd "$CHECK" && unzip -q "$OUT")
for so in "$CHECK"/jni/*/libgojni.so; do
  abi="$(basename "$(dirname "$so")")"
  align="$("$LLVM_BIN/llvm-readelf" -l "$so" | awk '/LOAD/{print $NF}' | sort -u | tr '\n' ' ')"
  [ "$align" = "0x4000 " ] || falla "$abi: segmentos alineados a '$align', se esperaba 0x4000"
  # A un fichero y no por tubería: con `pipefail`, `llvm-strings | grep -q` falla aunque
  # encuentre el texto (grep sale en la primera coincidencia y llvm-strings muere por SIGPIPE), y
  # la comprobación de rutas pasaba **en falso** justo cuando había rutas. Lo cazó la primera
  # ejecución de este script, que rechazó un AAR con el commit dentro.
  "$LLVM_BIN/llvm-strings" "$so" > "$CHECK/strings.txt"
  grep -qF -- "$COMMIT" "$CHECK/strings.txt" || falla "$abi: el commit no está dentro de la librería"
  if grep -qF -e "$HOME" -e "$MOD_DIR" -e "/var/folders/" "$CHECK/strings.txt"; then
    falla "$abi: quedan rutas locales dentro de la librería"
  fi
  echo "$abi: páginas de 16 KB, commit dentro, sin rutas locales"
done
echo "proguard.txt del AAR:"
unzip -p "$OUT" proguard.txt | sed 's/^/    /'
rm -rf "$CHECK"

mv "$OUT" "$LIBS_DIR/krypta-p2p.aar"
[ ! -f "$BUILD_DIR/krypta-p2p-sources.jar" ] || mv "$BUILD_DIR/krypta-p2p-sources.jar" "$LIBS_DIR/"
rm -rf "$BUILD_DIR"

echo "== AAR regenerado en native-bridge/libs/krypta-p2p.aar"
echo "commit: $COMMIT"
sha256 "$LIBS_DIR/krypta-p2p.aar"
