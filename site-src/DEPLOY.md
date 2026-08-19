# Publishing the site

`site/` is generated output — edit `site-src/` and `tools/build-site.py`,
never the files under `site/` directly. After any change:

```bash
python3 tools/build-site.py
git add site site-src tools/build-site.py tools/convert_blocklist.py
git commit -m "site: ..."
git push
```

## Cloudflare Pages, one-time setup

No build step runs on Cloudflare's side — `site/` is already static HTML, so
this is a "serve this folder" connection, not a build pipeline.

1. Cloudflare dashboard → **Workers & Pages** → **Create** → **Pages** →
   **Connect to Git** → select `Nilss3/drawbridge`.
2. Build settings:
   - Framework preset: **None**
   - Build command: *(leave empty)*
   - Build output directory: **`site`**
3. Save and deploy. Every future push to `main` that touches `site/`
   redeploys automatically — that's the entire CI story for this project's
   website, deliberately, to match [policy.md](../docs/policy.md#there-is-no-release-workflow)'s
   reasoning for the apps: nothing here needs a build server, so nothing runs
   one.

Cloudflare assigns a `*.pages.dev` subdomain immediately; a real domain can be
attached later from the same project settings once one exists.

**The site is at <https://drawbridge-project.pages.dev>.** Written down here
because it is not derivable from anything in this repository and it had already
been guessed wrong once, with the wrong host reaching three published install
guides. `drawbridge.pages.dev` and `drawbridge-site.pages.dev` are *other
people's projects*; the first is a single-page app whose catch-all returns 200
for any path, so probing a URL for a 200 does not tell you it is ours. Check the
`<title>`.

## Local preview

```bash
python3 tools/build-site.py
python3 -m http.server 8811 --directory site
```

Absolute paths like `/assets/css/style.css` only resolve under a real HTTP
server — opening the HTML files directly (`file://`) will look unstyled, and
that's expected, not a bug.

## What's deliberately not yet built

- **The USB/WebADB installer.** The button on `/install/` is a placeholder.
  Wiring it up means a small JS page using a library such as
  [ya-webadb](https://github.com/yume-chan/ya-webadb) to push the DPC APK and
  run the Device Owner provisioning step over WebUSB, from a Chromium-based
  browser, no local software required.
- **why-blocked/ in Dutch and French.** Deliberately English-only — see the
  comment above `WHY_TITLE` in `tools/build-site.py`. Around 200 rows of cited
  legal and clinical claims across jurisdictions is not something to
  machine-translate without review, and the content is expected to keep
  growing rather than settle into something worth maintaining in three
  languages.
- ~~**A night variant of the hero illustration.**~~ **Closed on 2026-08-19**, by
  removing the need for one. The hero is now `art/scene-dusk.webp` — the same
  master drawbridge's own welcome screen uses — and it is a sunset, warm enough
  to read on a light page and dark enough for a dark one. There is one hero
  image, served to both colour schemes, and the mismatched night copy is gone.
  It is written by `tools/make-artwork.sh` rather than copied by hand, because
  `site/assets/` is the one directory `build-site.py` does not clear and a
  hand-copied image drifts silently.

## A claim worth keeping an eye on

The homepage says the protection is "supported by neuroscience and parents'
organisations". That began as "made by pediatricians and neurologists", which
was not true of anything in this repo, then carried an asterisked "official
sources to come" for a while, and is now stated plainly.

What actually backs it today is [the blocklist page](https://github.com/Nilss3/drawbridge/blob/main/site-src/block-list.md):
around 200 entries citing AACAP, the APA, WHO, Ofcom, the European
Commission, eSafety, Mozilla, Common Sense Media, Internet Matters, NSPCC,
Child Focus and Mediawijs. That is a real evidence base for the *categories*
being blocked.

What it does not yet include is a named organisation that has formally
endorsed drawbridge itself. If one does, credit it here and in the README
before leaning on it in outward-facing copy — the same order that avoided the
problem the first time.
