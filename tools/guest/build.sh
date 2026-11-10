#!/bin/sh
# Compila o guest de referência (pcshim_guest) para ARM64 Android.
#
#   ./tools/guest/build.sh            # usa o NDK do Android SDK
#   ./tools/guest/build.sh <ndk-path>
#
# O binário resultante (build/pcshim_guest) é o "launcher" de um pacote de
# runtime do tipo "wine": ele é um executável ARM64 que o app executa como
# processo filho e com quem o app troca frames e eventos de entrada.
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OUT="$HERE/build"

NDK=${1:-${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.2.12479018}}
if [ ! -d "$NDK" ]; then
    echo "NDK nao encontrado: $NDK" >&2
    echo "informe o caminho: ./tools/guest/build.sh /caminho/do/ndk" >&2
    exit 1
fi

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -d "$TOOLCHAIN" ]; then
    echo "toolchain do NDK nao encontrado em $TOOLCHAIN" >&2
    exit 1
fi

CC="$TOOLCHAIN/aarch64-linux-android29-clang"
if [ ! -x "$CC" ]; then
    echo "compilador nao encontrado: $CC" >&2
    exit 1
fi

mkdir -p "$OUT"
"$CC" -O2 -Wall -o "$OUT/pcshim_guest" "$HERE/pcshim_guest.c"
echo "gerado: $OUT/pcshim_guest"
file "$OUT/pcshim_guest" 2>/dev/null || true
