# Play Store listing copy

What goes in Play Console for **herald mono** (`app.drawbridge.heraldmono`),
kept here rather than only in the console so the wording is reviewable, is
translated in one place, and can be checked against what the app actually does.

**The rule this file exists to enforce:** every claim here has to be true of the
build being published. "Filtering is on by default" is checkable; "safe" is not,
and is deliberately absent — it would also pull the listing towards Play's
Families policy, which this app is not in (target audience 13+). Nothing here
claims an endorsement the repository does not document.

Limits Play enforces: **title 30 characters, short description 80, full
description 4000.** Counts below are of the text as written.

---

## Title (30)

    herald mono

Deliberately just the app's own name, matching `app_name` in
`herald/src/mono/res/values/strings.xml`. The descriptor lives in the short
description, where there is room to say something true instead of something
keyword-shaped.

---

## English (en-US — the default listing language)

### Short description (54)

    Monochrome slow browser which blocks addictive content

### Full description

    Herald mono is a minimalist, slow browser with a built-in content blocker,
    for people who find they browse too much. It can be your one and only
    browser: it adds friction to browsing without taking away core functionality
    you may need in daily life.

    It provides a slower browsing experience by introducing a breathing pause
    before every page, slower scrolling, no tabs (single-tasking), and pages
    rendered by default in black and white (monochrome, hence the name).

    Just like the regular herald browser, herald mono blocks addictive content:
    adult sites, gambling, games, ads, dating platforms, social media, video
    streaming, general-purpose chatbots and AI companion and roleplay chat sites.

    It's free and open source, and built on Mozilla's Firefox engine. Herald and
    herald mono are companion browsers of the drawbridge project, FOSS software
    to turn your Android device into a minimalist phone or dumbphone. We make the
    browsers separately available.

    The herald browsers are privacy-conscious. There is no account. Blocking
    happens on the device itself. There is no telemetry, no analytics, no
    advertising, no crash reporting.
### What was changed from the owner's draft, and why

Three corrections, each checkable against `dist/policy.json`:

- **"video streaming" kept, because the build changed rather than the claim.**
  It was false until herald 0.1.23: the `streaming` and `youtube` options ship
  `default_enabled: true`, and a browser with nobody to ask followed those
  defaults, so Netflix and YouTube loaded. `optionsOffWithoutSelection` makes a
  standalone herald read every option as off, which was verified on an emulator
  against policy 139 — youtube.com and netflix.com now reach the block page, and
  wikipedia.org does not. A managed phone is unaffected: drawbridge's answer
  still wins.
- **"general-purpose chatbots" is true as of policy 140**, and was not before
  it. Checked on an emulator running the build that would be uploaded:
  chatgpt.com, claude.ai and gemini.google.com all reach the block page. `ai-companions.txt` deliberately leaves chatgpt.com, claude.ai and
  gemini.google.com off — it covers companion and roleplay chat, plus Grok. The
  chatbots list and its 18+ switch were ported from dev in policy 140, and the
  standalone rule does the rest: a herald with no drawbridge reads the switch as
  off, so the chatbots are blocked. On a managed phone they stay allowed until a
  parent turns the switch off, which is the same document behaving differently
  in the two places on purpose.
- **"based on Firefox" → "built on Mozilla's Firefox engine".** herald embeds
  GeckoView, the engine, not the browser. The looser phrasing reads as a
  relationship with Mozilla that does not exist, which is the kind of claim a
  store listing should not make.

Two smaller ones: "after every click" became "before every page", since the
pause is on navigation rather than on any tap; and the privacy and source links
are kept, because Play's reviewers follow them and the data-safety answers have
to agree with that page.

---

## The AI asset declaration

Play asks, per listing, whether the uploaded images were created or edited with
AI. **It asks about images only** — not about the app's source, however much of
it was written with a model, and not about editing in the ordinary sense:
resizing, greyscaling and compositing a generated master do not change the
answer, and do not create one either.

For herald mono the answer is **label**, and three assets carry it:

| Asset | Declared | Why |
|---|---|---|
| App icon | yes | `art/herald-mono-icon.webp` is AI-generated |
| Feature graphic | yes | composed from that same master |
| `mono-*-3-refused.png` | yes | shows a block-page scene, also AI-generated |
| The other 14 screenshots | no | the app's own interface, and photographs from Wikipedia |

`art/README.md` records where every master came from, including the one that is
**not** generated — the Van Gogh the website credits by name.

---

## Dutch (nl-NL)

### Short description (57)

    Monochrome trage browser die verslavende inhoud blokkeert

### Full description

    Herald mono is een minimalistische, trage browser met een ingebouwde
    inhoudsblokkering, voor mensen die merken dat ze te veel surfen. Het kan je
    enige browser zijn: het voegt wrijving toe aan het surfen zonder de
    kernfunctionaliteit weg te nemen die je in het dagelijks leven nodig hebt.

    Het maakt surfen trager met een adempauze vóór elke pagina, trager scrollen,
    geen tabbladen (één ding tegelijk), en pagina's die standaard in zwart-wit
    verschijnen (monochroom, vandaar de naam).

    Net als de gewone herald-browser blokkeert herald mono verslavende inhoud:
    pornografie, gokken, games, reclame, datingplatformen, social media,
    videostreaming, algemene chatbots en AI-gezelschaps- en rollenspelchatsites.

    Het is gratis en open source, en gebouwd op de Firefox-motor van Mozilla.
    Herald en herald mono zijn de begeleidende browsers van het
    drawbridge-project, FOSS-software om van je Android-toestel een
    minimalistische telefoon of dumbphone te maken. We maken de browsers
    afzonderlijk beschikbaar.

    De herald-browsers zijn privacybewust. Er is geen account. Het blokkeren
    gebeurt op het toestel zelf. Er is geen telemetrie, geen analytics, geen
    reclame, geen crashrapportage.
---

## French (fr-FR)

### Short description (63)

    Navigateur lent et monochrome qui bloque les contenus addictifs

### Full description

    Herald mono est un navigateur minimaliste et lent, doté d'un bloqueur de
    contenu intégré, pour les personnes qui constatent qu'elles naviguent trop.
    Il peut être votre seul et unique navigateur : il ajoute de la friction à la
    navigation sans retirer les fonctions essentielles dont vous pouvez avoir
    besoin au quotidien.

    Il ralentit la navigation par une pause de respiration avant chaque page, un
    défilement plus lent, l'absence d'onglets (une seule chose à la fois), et des
    pages affichées par défaut en noir et blanc (monochrome, d'où le nom).

    Comme le navigateur herald ordinaire, herald mono bloque les contenus
    addictifs : sites pornographiques, jeux d'argent, jeux, publicités, sites de
    rencontre, réseaux sociaux, vidéo en streaming, chatbots généralistes et
    sites de compagnons IA et de jeu de rôle conversationnel.

    Il est gratuit et open source, et repose sur le moteur Firefox de Mozilla.
    Herald et herald mono sont les navigateurs compagnons du projet drawbridge,
    un logiciel libre qui transforme votre appareil Android en téléphone
    minimaliste. Nous proposons les navigateurs séparément.

    Les navigateurs herald respectent la vie privée. Il n'y a aucun compte. Le
    blocage se fait sur l'appareil même. Il n'y a ni télémétrie, ni analytique,
    ni publicité, ni rapport de plantage.
---

## Building the bundle

```bash
./gradlew :herald:bundleMonoRelease     # -> herald/build/outputs/bundle/monoRelease/
```

About 118 MB, signed with the release key, arm64 only. It is git-ignored and is
not a GitHub release asset: it goes to Play Console and nowhere else.

**A bundle ignores the `splits` block** — Play splits at delivery instead — so
the first one built carried all four of GeckoView's ABIs at 287 MB. The ABI list
now also appears as `ndk.abiFilters`, switched on for `bundle*` tasks only,
because AGP refuses to accept `abiFilters` and `splits` together.

At upload, choose **"use existing app signing key"** and export
`drawbridge-release.jks` with PEPK. Letting Google generate one would break the
`app.drawbridge.permission.READ_SELECTION` link between the two apps, which is
declared at `signature` level: the Play copy of herald would run fine alone and
silently fail to read the parent's settings on a managed phone.

## The feature graphic

`images/store/mono-feature-graphic-1024x500.png`. Play asks for one per listing
and shows it above the screenshots.

Greyscale throughout, which is the one design decision that makes itself: an app
whose premise is that there is no colour cannot advertise itself in colour.

**Play crops this image on some surfaces**, so nothing load-bearing sits near an
edge. If a crop ever eats the text, the fix is a centred variant rather than
smaller type.

## The app icon

`images/store/mono-play-icon-512.png` — 512x512, opaque PNG, under Play's 1 MB
cap. Play masks the corners itself, so the file is a full square.

It is **not** the launcher foreground layer scaled down, which would show the
lantern small inside a ring of blur. It is composed the way a launcher shows it:
the adaptive foreground rendered at 108dp (768px) and centre-cropped to the 72dp
the mask can reach (512px). What Play shows is then what a phone shows.

To regenerate it after `art/herald-mono-icon.webp` changes, the recipe is in the
commit that added it; it uses the same corner flood-fill as
`tools/make-artwork.sh`, because the master has white rounded-rect corners that
must become transparent before anything is composed over the blur.

## Screenshots

`tools/capture-store-shots.py` takes them. `images/store/` holds the result:
five shots per language, 1080x1920 PNG.

Play takes **2 to 8 phone screenshots** per language; **4 or more** at 1080px is
what makes a listing eligible to be promoted. PNG or JPEG — **not WebP**, which
is what `site/assets/img/` holds, so the store shots are their own set rather
than a reuse of the site's.

**A screenshot's long side may not be more than twice its short side**, and this
is the trap: every modern phone is past it. 1080x2400 is 2.22:1 and is refused.
That is the whole reason these are taken on an emulator launched with
`-skin 1080x1920` rather than on a handset.

Localised screenshots are optional — a language with none falls back to the
default listing's — but four of the five carry text the app translates, so they
are worth taking.

The set, numbered in the order Play should show them, chosen so the first two
tell the story in a thumbnail strip:

1. **The pause screen.** "Pause to think while loading" over a destination.
   Nothing else in the store looks like it, and it is the clearest single
   picture of what this app is.
2. **An article in black and white.** A real page, not a mock-up, because the
   point is that ordinary reading still works — and a photograph, because a
   screen of grey text proves nothing. Wikipedia's puffin does the work here:
   everyone knows what colour that beak is.
3. **A refused site.** What a block looks like to the person who hits one.
4. **The menu**, which is where mono's "show this page in colour" lives.
5. **Settings**, showing the search-engine choice and the filter's policy
   version — the shot that says this is a real browser and not a kiosk.

The two gotchas the script exists to remember: mono keeps herald's *class*
package under its own applicationId, so components are
`heraldmono/app.drawbridge.herald.X` and `am start -n heraldmono/.X` fails with
a misleading "does not exist"; and `SettingsActivity` is `exported="false"`, so
settings has to be reached by walking the menu rather than launched.
