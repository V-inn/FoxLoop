#!/usr/bin/env bash
#
# Regenerates the launcher icon from artwork/foxloop_logo.png.
#
# Run this after changing the artwork; the files it writes are generated and
# should not be hand-edited. Needs ImageMagick.
#
#     ./tools/build-icons.sh
#
# WHY THE ART IS SCALED DOWN RATHER THAN USED FULL-BLEED. An adaptive icon
# layer is 108dp square, but only the middle 72dp is ever shown -- the outer
# 18dp on each side is margin the launcher crops into whatever mask shape it
# uses, and reveals only during parallax. Dropping this artwork straight in at
# full size put the fox's head under the crop: what survived was an orange
# blob with half a face, and none of the laptop or the tablet it is climbing
# between. So the whole picture is scaled into the 72dp safe area instead.
#
# That leaves the 18dp margin to fill, and it cannot be a flat colour: the art
# runs coral along the top and right, orange along the bottom, and near-black
# down the left where the laptop is, so any single colour shows a seam on three
# sides. The margin is therefore a blurred, slightly darkened blow-up of the
# art itself, which has the right colour in the right place on every edge with
# no seam to find.
#
# The Play Store icon is the *unmodified* art at 512x512, with no margin and no
# blur: Play applies its own rounding to the image you upload, and an icon that
# arrived pre-margined would be inset twice.
set -euo pipefail

cd "$(dirname "$0")/.."

src=artwork/foxloop_logo.png
res=app/src/main/res

if [[ ! -f $src ]]; then
    echo "missing $src" >&2
    exit 1
fi
if ! command -v convert >/dev/null; then
    echo "ImageMagick (convert) is not installed" >&2
    exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# 1500 is the master's size; the safe area is 72/108 of it, so 1000.
convert "$src" -resize 1500x1500^ -gravity center -extent 1500x1500 \
    -blur 0x40 -modulate 100,90 "$work/bleed.png"
convert "$src" -resize 1000x1000 "$work/sharp.png"
convert "$work/bleed.png" "$work/sharp.png" -gravity center -composite "$work/layer.png"

# 108dp at each density bucket. minSdk is 26, so adaptive icons are always
# available and there is no legacy square icon to generate alongside these.
for bucket in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
    dir=${bucket%%:*}
    px=${bucket##*:}
    mkdir -p "$res/mipmap-$dir"
    convert "$work/layer.png" -resize "${px}x${px}" -strip \
        "$res/mipmap-$dir/ic_launcher_background.png"
    echo "wrote $res/mipmap-$dir/ic_launcher_background.png (${px}px)"
done

# Play's listing icon: the art as drawn, 512x512, no alpha channel (Play
# rejects one).
convert "$src" -resize 512x512 -background white -alpha remove -alpha off -strip \
    artwork/ic_launcher-play-512.png
echo "wrote artwork/ic_launcher-play-512.png"
