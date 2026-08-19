#!/usr/bin/env bash
#
# Derives every image in the two apps, and the website's hero, from the masters
# in art/.
#
# The icon masters are square illustrations meant to be seen whole, which is not
# what an adaptive icon is: a launcher draws the 108dp layer and then masks it with a
# shape inscribed in the middle 72dp, so a third of the art would be thrown away
# if it were simply stretched across the layer. Each icon is therefore drawn at
# 72dp in the middle, where the mask is, and the 18dp of bleed around it is a
# blurred, blown-up copy of the same picture — so a squircle mask finds matching
# colour where a circle finds nothing.
#
# Two of the masters arrive as finished rounded-square icons, which means white
# in the corners. That white would show through a squircle, so those corners are
# made transparent and the bleed layer fills them in.
#
# Nothing here runs as part of the Gradle build. Run it after changing a master
# and commit what it writes.
#
# Requires ImageMagick (`brew install imagemagick`).

set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
art="$repo/art"

command -v magick >/dev/null || { echo "magick not found; brew install imagemagick" >&2; exit 1; }

# Adaptive icon layer size per density bucket, in pixels: 108dp at 1x, 1.5x, 2x,
# 3x and 4x.
densities=(mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432)

# Renders one master into an adaptive-icon foreground for every density.
#
#   $1  master image
#   $2  destination res/ directory
#   $3  "trim-corners" if the master has white rounded-rect corners to drop
icon() {
  local master="$1" res="$2" corners="${3:-}"
  local source="$master"

  if [[ "$corners" == "trim-corners" ]]; then
    source="$(mktemp -t drawbridge-icon).png"
    # Flood-fill inwards from each corner. The fuzz is deliberately tight: the
    # warm lantern's glow is nearly white too, and it is only the corners that
    # should disappear.
    magick "$master" -alpha set -fuzz 6% \
      -fill none \
      -draw 'alpha 0,0 floodfill' \
      -draw 'alpha %[fx:w-1],0 floodfill' \
      -draw 'alpha 0,%[fx:h-1] floodfill' \
      -draw 'alpha %[fx:w-1],%[fx:h-1] floodfill' \
      "$source"
  fi

  for entry in "${densities[@]}"; do
    local bucket="${entry%%:*}" layer="${entry##*:}"
    local inner=$(( layer * 2 / 3 ))   # the 72dp the mask can reach
    local blur=$(( layer / 12 ))
    mkdir -p "$res/mipmap-$bucket"
    magick \
      \( "$master" -resize "${layer}x${layer}!" -blur "0x$blur" \) \
      \( "$source" -resize "${inner}x${inner}!" \) \
      -gravity center -composite \
      -alpha remove -alpha off -strip -quality 92 -define webp:method=6 \
      "$res/mipmap-$bucket/ic_launcher_foreground.webp"
  done

  [[ "$corners" == "trim-corners" ]] && rm -f "$source"
  return 0
}

echo "launcher icons"
icon "$art/drawbridge-icon.webp"  "$repo/dpc/src/main/res"
icon "$art/herald-icon.webp"      "$repo/herald/src/main/res" trim-corners
icon "$art/herald-mono-icon.webp" "$repo/herald/src/mono/res" trim-corners

# The two scenes are the same place at two times of day.
#
# herald's block page carries **both** and lets the page's own
# prefers-color-scheme query choose, so the picture turns with the card under it
# rather than sitting bright white in a dark browser. That is why they are two
# resources with distinct names and not one name with a `-night` qualifier:
# both have to be inlined into the same document.
#
# drawbridge does not take either of them; it has its own picture, below.
echo "block-page scenes (day and night)"
mkdir -p "$repo/herald/src/main/res/raw"
for when in day night; do
  # Inlined into the block page as a data: URI so the page needs neither network
  # nor assets, and the whole page is then base64-encoded again by GeckoView's
  # loader — and now twice over, since both are in it. Hence 1000px and quality
  # 82: more than a phone in landscape can show, and no more.
  magick "$art/scene-$when.webp" -resize 1000x \
    -strip -quality 82 -define webp:method=6 \
    "$repo/herald/src/main/res/raw/blocked_scene_$when.webp"
done

# drawbridge's hero is decoded by the platform rather than embedded in a string,
# so it can afford to be sharper.
#
# It comes from a third master, `scene-dusk.webp`, and not from either of the
# two above. drawbridge's screen shows one picture whatever the theme is — there
# is no `-night` qualifier and no query to pick with — so the night scene sat
# dark and heavy on a light-themed screen, which is what most of them are. Dusk
# is warm enough for a light background and dark enough for a dark one, so one
# resource covers both and the question stops needing an answer.
#
# The hero shows it **whole**: the view is wrap_content with adjustViewBounds and
# fitCenter, not a fixed band with centerCrop. The picture is composed as one —
# the reader on his bench at the left and the monsters at the right are the point
# of it — and a letterbox crop takes the spire tips off the top and the feet off
# the bottom.
#
# The master was square until 2026-08-19 and is now 16:9, which is the whole
# reason for the change: shown whole, a square picture ate the top of a phone
# screen and pushed the thing the screen is actually for — the policy, the
# options and the button — below the fold. Same picture, same "shown whole"
# rule, about half the height.
echo "welcome scene (dusk)"
mkdir -p "$repo/dpc/src/main/res/drawable-nodpi"
magick "$art/scene-dusk.webp" -resize 1400x \
  -strip -quality 88 -define webp:method=6 \
  "$repo/dpc/src/main/res/drawable-nodpi/welcome_scene.webp"

# The website's hero is the same master again, and is written from here for the
# reason every generated thing is: `site/assets/` is the one directory
# `build-site.py` does *not* clear, so an image copied there by hand stays
# correct only until somebody edits a master and forgets. It carried its own
# `website-hero.webp` master until 2026-08-19, which had already drifted from
# the app's.
#
# One image, both colour schemes. The page used to swap in the night scene under
# a prefers-color-scheme query; dusk removes the need, and the title now sits
# *on* the picture, where a second version would have meant a second set of text
# colours to keep legible.
echo "website hero (dusk)"
mkdir -p "$repo/site/assets/img"
magick "$art/scene-dusk.webp" -resize 1600x \
  -strip -quality 86 -define webp:method=6 \
  "$repo/site/assets/img/hero.webp"
rm -f "$repo/site/assets/img/hero-night.webp"

echo
echo "written:"
find "$repo/dpc/src/main/res" "$repo/herald/src/main/res" "$repo/herald/src/mono/res" \
  "$repo/site/assets/img" \
  \( -name 'ic_launcher_foreground.webp' -o -name 'blocked_scene_*.webp' \
     -o -name 'welcome_scene.webp' -o -name 'hero.webp' \) \
  | sort | sed "s|$repo/|  |"
