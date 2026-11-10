#!/bin/sh
# Monta um pacote de runtime de REFERÊNCIA (.zip) para o app.
#
# O pacote usa o guest de exemplo (tools/guest/pcshim_guest.c): ele não é um
# jogo, mas implementa o contrato completo (socket + framebuffer), o que
# permite validar o caminho todo num aparelho real sem Wine nem DXVK.
#
#   ./tools/guest/make-package.sh [caminho/do/ndk]
#
# Saída: tools/guest/build/pcshim-reference-runtime.zip
# Instale no app (Launcher > Pacotes de runtime > importar .zip).
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$HERE/build"
PKGDIR="$BUILD/pcshim-reference-runtime"

"$HERE/build.sh" "$@"

rm -rf "$PKGDIR"
mkdir -p "$PKGDIR/bin" "$PKGDIR/prefix/drive_c/jogo"

cp "$BUILD/pcshim_guest" "$PKGDIR/bin/pcshim_guest"
chmod 755 "$PKGDIR/bin/pcshim_guest"

# O "jogo" do prefixo: o guest recebe o caminho como último argumento e o ignora.
printf 'reference guest (sem jogo real)\n' > "$PKGDIR/prefix/drive_c/jogo/game.exe"

cat > "$PKGDIR/meta.json" <<'EOF'
{
  "type": "wine",
  "name": "Reference runtime (guest de teste)",
  "version": "1.0.0",
  "author": "Hub-game",
  "prefix": "prefix",
  "exe": "drive_c/jogo/game.exe",
  "launcher": "bin/pcshim_guest",
  "launcherArgs": [],
  "env": {},
  "displayWidth": 960,
  "displayHeight": 540
}
EOF

ZIP="$BUILD/pcshim-reference-runtime.zip"
rm -f "$ZIP"
if command -v zip >/dev/null 2>&1; then
    (cd "$BUILD" && zip -qr "$(basename "$ZIP")" "$(basename "$PKGDIR")")
elif command -v python3 >/dev/null 2>&1; then
    (cd "$BUILD" && python3 -m zipfile -c "$ZIP" "$(basename "$PKGDIR")")
else
    echo "Neither 'zip' nor 'python3' is available: install one of them to build the package." >&2
    exit 1
fi

echo "pacote gerado: $ZIP"
ls -la "$ZIP"
