#!/usr/bin/env bash
#
# Rasterize an SVG into every icon file the Studio needs.
#
#   scripts/generate-icons.sh [path/to/icon.svg]
#
# Defaults to src/main/resources/icons/icon.svg. Outputs land next to the SVG:
#   icon-{16,32,64,128,256,512}.png  JavaFX window/taskbar icons (BotMakerStudio.applyAppIcons)
#   icon.png                         Linux jpackage installer (installer.icon default)
#   icon.ico                         Windows jpackage installer (Windows profile)
#
# jpackage can't read SVG, so these rasters must exist before building installers.
# Prefers rsvg-convert or inkscape for rendering; falls back to ImageMagick.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
svg="${1:-$repo_root/src/main/resources/icons/icon.svg}"

[[ -f "$svg" ]] || { echo "error: SVG not found: $svg" >&2; exit 1; }

dir="$(cd "$(dirname "$svg")" && pwd)"
sizes=(16 32 64 128 256 512)

# Pick the best available renderer.
if command -v rsvg-convert >/dev/null 2>&1; then
    render() { rsvg-convert -w "$1" -h "$1" "$svg" -o "$2"; }
elif command -v inkscape >/dev/null 2>&1; then
    render() { inkscape "$svg" -w "$1" -h "$1" -o "$2" >/dev/null 2>&1; }
elif command -v magick >/dev/null 2>&1; then
    echo "note: using ImageMagick (install librsvg2-tools or inkscape for higher-fidelity SVG rendering)" >&2
    render() { magick -background none "$svg" -resize "${1}x${1}" "$2"; }
else
    echo "error: need one of rsvg-convert, inkscape, or magick on PATH" >&2
    exit 1
fi

echo "Rendering PNGs from $svg"
for s in "${sizes[@]}"; do
    out="$dir/icon-${s}.png"
    render "$s" "$out"
    echo "  $out"
done

# Linux installer icon: a single PNG. Reuse the largest raster.
cp "$dir/icon-512.png" "$dir/icon.png"
echo "  $dir/icon.png (Linux installer)"

# Windows installer icon: multi-resolution .ico.
if command -v magick >/dev/null 2>&1; then
    magick "$dir/icon-256.png" "$dir/icon-128.png" "$dir/icon-64.png" \
           "$dir/icon-32.png" "$dir/icon-16.png" "$dir/icon.ico"
elif command -v icotool >/dev/null 2>&1; then
    icotool -c -o "$dir/icon.ico" \
        "$dir/icon-256.png" "$dir/icon-128.png" "$dir/icon-64.png" \
        "$dir/icon-32.png" "$dir/icon-16.png"
else
    echo "warning: no magick/icotool found; skipped icon.ico (Windows installer)" >&2
fi
[[ -f "$dir/icon.ico" ]] && echo "  $dir/icon.ico (Windows installer)"

echo "Done."
