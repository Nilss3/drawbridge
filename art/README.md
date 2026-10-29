# The masters, and where each one came from

`tools/make-artwork.sh` derives every image in the two apps and on the website
from the files here. This note records **how each master was made**, which
nothing else in the repository did until Google asked.

It is not bookkeeping for its own sake. Play Console's *AI asset declaration*
asks, for every image uploaded to a store listing, whether it was created or
edited using AI — and the answer has to be given again at each listing update,
for herald standard as well as mono. Getting it from a file rather than from
memory is the difference between an answer and a guess.

| Master | Origin |
|---|---|
| `drawbridge-icon.webp` | AI-generated |
| `herald-icon.webp` | AI-generated |
| `herald-mono-icon.webp` | AI-generated |
| `scene-day.webp` | **Not AI.** Photo by Girish Nayyar (Tower Bridge, London), from Pexels, free to use; cropped to 16:9. Herald's block page in light mode, credited under the photo |
| `scene-night.webp` | **Not AI.** Photo by Max Avans (the Palace Bridge, St Petersburg, name taken from the file name `pexels-maxavans-5146463`), from Pexels, free to use; cropped to 16:9. Herald's block page in dark mode, credited under the photo |
| `scene-spring.webp` | AI-generated. Not used by anything |
| `scene-dusk.webp` | AI-generated. Retired: it was drawbridge's hero until the Van Gogh replaced it, and is kept in `art/` and feeds nothing |
| `website-howitworks.webp` | AI-generated, on the evidence of its own style — correct this line if that is wrong |
| `hero-langlois.webp` | **Not AI.** Vincent van Gogh, *The Langlois Bridge at Arles*, 1888. Public domain, and credited as such on the website in all three languages |

**The Van Gogh is the one to be careful with.** It replaced an AI-generated
image, the site states its authorship as fact, and that is a public, checkable
claim. Anything that changes which file feeds the hero has to change the credit
in `tools/build-site.py` in the same breath, or the site will attribute a
generated picture to a painter.

## What this means for a Play listing

Everything drawn from an AI-generated master carries that provenance with it,
however it is processed afterwards — resizing, greyscaling and compositing are
not what the declaration is about, and neither is the code, which is about the
listing's images only.

For herald mono, as submitted: the **app icon** and the **feature graphic** both
come from `herald-mono-icon.webp`, and the **refused-page screenshot** shows a
block-page scene. Those three are declared; the other fourteen screenshots are
the app's own interface and photographs from Wikipedia.
