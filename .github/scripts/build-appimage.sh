#!/usr/bin/env bash
# Wraps the jpackage APP_IMAGE (target/dist/BotMaker Studio/) into a portable AppImage.
#
# AppImage is the recommended password-free Linux channel: it runs with no root, integrates with the
# launcher without touching the system desktop database (which is what left the .rpm launcher missing from
# GNOME/KDE search until a reinstall), and the in-app updater self-updates it by swapping the file.
#
# Usage: build-appimage.sh <output-file.AppImage>
# Requires: the app-image already built at "target/dist/BotMaker Studio", plus wget + appimagetool deps.
set -euo pipefail

OUT="${1:?usage: build-appimage.sh <output.AppImage>}"
APPIMAGE_SRC="target/dist/BotMaker Studio"
ICON="src/main/resources/icons/icon.png"

if [ ! -d "$APPIMAGE_SRC" ]; then
  echo "app-image not found at '$APPIMAGE_SRC' — run 'mvn -Pdist package' first" >&2
  exit 1
fi

APPDIR="$(pwd)/AppDir"
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr"
cp -a "$APPIMAGE_SRC/." "$APPDIR/usr/"

# The jpackage launcher keeps the app name (with a space); AppRun execs it directly.
cat > "$APPDIR/AppRun" <<'EOF'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/BotMaker Studio" "$@"
EOF
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/botmaker-studio.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=BotMaker Studio
Comment=Visual block-based bot builder
Exec=AppRun
Icon=botmaker-studio
Categories=Development;
Terminal=false
EOF

cp "$ICON" "$APPDIR/botmaker-studio.png"

# appimagetool (extracted, so it needs no FUSE on CI runners).
if [ ! -x ./appimagetool/AppRun ]; then
  wget -q https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage -O appimagetool.AppImage
  chmod +x appimagetool.AppImage
  ./appimagetool.AppImage --appimage-extract >/dev/null
  mv squashfs-root appimagetool
fi

# --sign uses the default gpg key when BOTMAKER_SIGN=1 (the workflow imports it first).
SIGN_ARGS=""
if [ "${BOTMAKER_SIGN:-0}" = "1" ]; then
  SIGN_ARGS="--sign"
fi

ARCH=x86_64 ./appimagetool/AppRun $SIGN_ARGS "$APPDIR" "$OUT"
echo "Built $OUT"
