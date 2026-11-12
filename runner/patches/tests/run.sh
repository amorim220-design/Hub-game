#!/usr/bin/env bash
# Roda o harness local do runnerpatch (Fase 2/3).
#
#   sh runner/patches/tests/run.sh
#
# Nao precisa de Android SDK: o harness reimplementa o parser de atalho do upstream
# (com.winlator.container.Shortcut) em Java puro e confere a ida e volta.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../src/com/winlator/hubgame"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

command -v javac >/dev/null || { echo "javac nao encontrado (instale openjdk-17)" >&2; exit 1; }

# So o nucleo puro entra no harness: HubGameImporter depende de Container/FileUtils/
# WineUtils, que vivem no upstream e so existem no build do app.
javac -d "$OUT" \
    "$SRC/HubGameProfiles.java" \
    "$HERE/HubGameTest.java" \
    "$HERE/HubGameResolutionTest.java"

java -cp "$OUT" com.winlator.hubgame.HubGameTest
java -cp "$OUT" com.winlator.hubgame.HubGameResolutionTest
