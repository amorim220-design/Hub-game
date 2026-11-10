#!/usr/bin/env bash
# Builds the "runner" APK: the Winlator base (LGPL-2.1) with our patches.
#
#   ./runner/build.sh                 # build in .runner-work/ and sign the APK
#   WORKDIR=/tmp/w ./runner/build.sh  # build somewhere else
#
# Requirements:
#   * JDK 17
#   * Android SDK: platforms;android-35, build-tools;35.0.0, cmake;3.22.1,
#     ndk;24.0.8215888
#   * git (the upstream and its submodules are cloned from GitHub)
#
# The output lands in runner/dist/.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"

# shellcheck disable=SC1091
set -a; . "$HERE/versions.env"; set +a

WORKDIR="${WORKDIR:-$REPO_ROOT/.runner-work}"
DIST="$REPO_ROOT/runner/dist"
KEYSTORE="${KEYSTORE_PATH:-runner/keystore/runner.keystore}"
case "$KEYSTORE" in
    /*) ;;
    *) KEYSTORE="$REPO_ROOT/$KEYSTORE" ;;
esac

log() { printf '\033[1;36m[runner]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[runner] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# --- 0. environment ---------------------------------------------------------
[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ] || die "ANDROID_HOME/ANDROID_SDK_ROOT nao definido"
command -v git >/dev/null || die "git nao encontrado"
command -v java >/dev/null || die "java nao encontrado"

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
[ -x "$SDKMANAGER" ] || SDKMANAGER="$(command -v sdkmanager || true)"
[ -n "$SDKMANAGER" ] || die "sdkmanager nao encontrado em $ANDROID_HOME"

log "instalando os pacotes do SDK (platform $ANDROID_COMPILE_SDK, build-tools $ANDROID_BUILD_TOOLS, ndk $ANDROID_NDK)"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --install \
    "platform-tools" \
    "platforms;android-$ANDROID_COMPILE_SDK" \
    "build-tools;$ANDROID_BUILD_TOOLS" \
    "cmake;$CMAKE_VERSION" \
    "ndk;$ANDROID_NDK" >/dev/null

# --- 1. fetch the upstream base --------------------------------------------
if [ ! -d "$WORKDIR/.git" ]; then
    log "clonando $WINLATOR_REPO @ $WINLATOR_REF"
    rm -rf "$WORKDIR"
    mkdir -p "$WORKDIR"
    git -C "$WORKDIR" init -q
    git -C "$WORKDIR" remote add origin "$WINLATOR_REPO"
    # Busca pelo SHA completo. Se o servidor recusar (objeto não anunciado),
    # cai para a branch e o checkout do commit.
    if ! git -C "$WORKDIR" fetch -q --depth 1 origin "$WINLATOR_REF"; then
        log "fetch por SHA recusado; buscando a branch e fazendo checkout do commit"
        git -C "$WORKDIR" fetch -q --depth 50 origin main || \
            git -C "$WORKDIR" fetch -q origin
        git -C "$WORKDIR" checkout -q "$WINLATOR_REF"
    else
        git -C "$WORKDIR" checkout -q FETCH_HEAD
    fi
    git -C "$WORKDIR" submodule update -q --init --recursive --depth 1
fi
APP_DIR="$WORKDIR/app"
[ -f "$APP_DIR/build.gradle" ] || die "o submodule 'app' nao foi inicializado (rode: git submodule update --init --recursive)"

# --- 2. our patches on top --------------------------------------------------
log "aplicando os patches do runner"
sh "$HERE/patches/apply.sh" "$APP_DIR"

# --- 3. build ---------------------------------------------------------------
log "buildando o APK (abi $ANDROID_ABI)"
cd "$APP_DIR"
chmod +x ./gradlew 2>/dev/null || true
./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx4g" \
    -Pandroid.injected.build.abi="$ANDROID_ABI" \
    assembleRelease

APK_UNSIGNED="$(find "$APP_DIR/build/outputs/apk/release" -name '*.apk' | head -n 1)"
[ -n "$APK_UNSIGNED" ] || die "APK nao encontrado em build/outputs/apk/release"

# --- 4. sign ----------------------------------------------------------------
mkdir -p "$DIST" "$(dirname "$KEYSTORE")"
if [ ! -f "$KEYSTORE" ]; then
    log "gerando o keystore em $KEYSTORE"
    keytool -genkeypair -v -keystore "$KEYSTORE" \
        -storepass "$KEYSTORE_PASSWORD" -keypass "$KEY_PASSWORD" \
        -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Hub Game Runner,O=Hub-game,C=BR" >/dev/null
fi

APKSIGNER="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/apksigner"
[ -x "$APKSIGNER" ] || APKSIGNER="$(ls "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | tail -n 1)"
[ -x "$APKSIGNER" ] || die "apksigner nao encontrado"

OUT_APK="$DIST/hubgame-runner-arm64.apk"
log "assinando -> $OUT_APK"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEYSTORE_PASSWORD" \
    --key-pass "pass:$KEY_PASSWORD" \
    --out "$OUT_APK" \
    "$APK_UNSIGNED"

"$APKSIGNER" verify --print-certs "$OUT_APK" >/dev/null || log "aviso: nao foi possivel verificar o APK"

log "pronto: $OUT_APK"
ls -la "$OUT_APK"
