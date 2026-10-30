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

## Before this is submitted, 2026-10-30

The copy is final in three languages and all three now make the same claims.
What is left is mechanical, and neither item is a wording question.

### The bundle has to be rebuilt

`dist/release/herald-mono-0.1.23-release.aab` is three releases old. The
listing's claims are true of **herald 0.1.25** (`v0.2.28`) and of no build
before it, so that is the build that has to be uploaded.

### The screenshots, and the one number in them that is stale

The three refused shots were retaken on 2026-10-29 (`bd2e115`), so they show the
block page as it is now: Tower Bridge, photographed by Girish Nayyar, credited
under the picture. **That flips their row in the AI asset declaration below from
yes to no** — the scene they used to show was generated, this one is not.

The other four per language are from 0.1.23 and still show screens 0.1.25 has,
with one exception worth knowing about before upload: the settings shot reads
**"Policy version 137"**, and the current document is 169. Nothing rejects a
listing over it, but it is a checkable number in a public image, and retaking
that one shot in three languages is a few minutes once an emulator is up.

### Why the chatbots clause is now true, and was not before

All three descriptions say herald mono blocks general-purpose chatbots. Until
herald 0.1.25 that was false in exactly the place it mattered — a browser with
no drawbridge beside it, which is every copy installed from Play.

Policy 169 keeps the chatbots list, its packages and its switch in a
`conditional` fragment gated on `min_version_code: 63`, so that an older
drawbridge is never offered a switch it cannot honour. A standalone herald was
reading `0` for "nobody to ask", which made the browser with no parent the one
place the fragment never applied: the list it most needed never reached it.
`conditionalVersionCode` now answers `Int.MAX_VALUE` when a selection source
exists and nothing answers it, which lines the rule up with
`optionsOffWithoutSelection`: take everything the document offers, enable none
of it. A managed phone is untouched, because drawbridge's own version code still
decides there.

**The claim is checked on a device before submission**, which is the standard
this file holds claims to, and reading the code is not that standard. The check:
install the published 0.1.25 mono APK, hash-checked against the release's own
`SHA256SUMS`, on a device with no drawbridge present, and open chatgpt.com,
claude.ai and gemini.google.com. Two controls make the result unambiguous —
character.ai, from a list that was never gated, must block too, or the filter
has not loaded and the run proves nothing; and en.wikipedia.org must load. The
distinction to watch is the block page against GeckoView's "Address Not Found",
which is what *not* blocking looks like on a network that does not resolve those
hosts.

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

For herald mono the answer is **label**, and two assets carry it:

| Asset | Declared | Why |
|---|---|---|
| App icon | yes | `art/herald-mono-icon.webp` is AI-generated |
| Feature graphic | yes | composed from that same master |
| `mono-*-3-refused.png` | no | **Changed 2026-10-29.** The block page's generated scene was replaced by a Pexels photograph, credited on the page itself |
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
    enige browser zijn: het voegt frictie toe aan het surfen zonder de
    functionaliteit weg te nemen die men nodig heeft.

    Deze browser zorgt voor trager surfen: een adempauze vóór elke pagina, trager
    scrollen, geen tabbladen (één ding tegelijk), en pagina's die standaard in
    zwart-wit verschijnen (monochroom, vandaar de naam 'mono').

    Net als de gewone herald-browser blokkeert herald mono verslavende inhoud:
    pornografie, gokken, games, reclame, datingplatformen, social media, algemene
    chatbots, AI-gezelschaps- en rollenspelchatsites en videostreaming.

    Herald mono is gratis, open source, en gebouwd op de Firefox-engine van Mozilla.
    Herald en herald mono zijn de begeleidende browsers van het drawbridge-project,
    FOSS-software om van je Android-toestel een minimalistische telefoon of
    dumbphone te maken. We maken de browsers afzonderlijk beschikbaar.

    De herald-browsers respecteren de privacy. Er is geen account. Inhoudsfiltering
    gebeurt op het toestel zelf. Er is geen telemetrie, geen analytics, geen
    reclame, geen crashrapportage.

---

## French (fr-FR)

### Short description (63)

    Navigateur lent et monochrome qui bloque les contenus addictifs

### Full description

    Herald mono est un navigateur minimaliste et lent, doté d'un bloqueur de
    contenu intégré, pour les personnes qui constatent qu'elles naviguent trop. Il
    peut être votre seul et unique navigateur : il ajoute de la friction à la
    navigation sans retirer les fonctions dont on a besoin.

    Ce navigateur ralentit la navigation : une pause de respiration avant chaque
    page, un défilement plus lent, l'absence d'onglets (une seule chose à la fois),
    et des pages affichées par défaut en noir et blanc (monochrome, d'où le nom
    « mono »).

    Comme le navigateur herald ordinaire, herald mono bloque les contenus
    addictifs : sites pornographiques, jeux d'argent, jeux, publicités, sites de
    rencontre, réseaux sociaux, chatbots généralistes, sites de compagnons IA et de
    jeu de rôle conversationnel, et vidéo en streaming.

    Herald mono est gratuit, open source, et repose sur le moteur Firefox de
    Mozilla. Herald et herald mono sont les navigateurs compagnons du projet
    drawbridge, un logiciel libre qui transforme votre appareil Android en
    téléphone minimaliste ou dumbphone. Nous proposons les navigateurs séparément.

    Les navigateurs herald respectent la vie privée. Il n'y a aucun compte. Le
    filtrage du contenu se fait sur l'appareil même. Il n'y a ni télémétrie, ni
    analytique, ni publicité, ni rapport de plantage.

---

## The permission Play asks about: QUERY_ALL_PACKAGES

herald's manifest declares `android.permission.QUERY_ALL_PACKAGES`, added in
0.1.24 so that handing a `tel:`, `intent:`, `whatsapp:` or a bank's own scheme
to the app that owns it can first ask Android who that is. On API 30 and later
that answer is empty for any app not declared, and the schemes are not ours to
enumerate — every bank has its own.

**Play restricts it**, so the listing needs the **Permissions Declaration Form**
in Console. The policy
(<https://support.google.com/googleplay/android-developer/answer/10158779>) says
"Permitted uses include device search, antivirus apps, file managers, and
browsers", and herald is a browser. It also sets conditions a strict reading
would have us fail: a "core purpose to search for all apps on the device", a
justification for why "a less intrusive method of app visibility" will not do,
and core functionality "prominently documented and promoted in the app's
description".

What settles it is what shipping browsers do. **Firefox for Android declares the
same permission with the same lint suppression and no `<queries>` block at all**
(`mobile/android/fenix/app/src/main/AndroidManifest.xml`), and **DuckDuckGo does
the same**, its `<queries>` holding one unrelated intent for voice search.
Neither promotes app-searching in its store description. The word the category
turns on is *browsers*.

So the form is answered as a browser, and the justification is the handoff
above. The honest risk, written down rather than discovered later: Firefox and
DuckDuckGo are long-established listings, and a first submission from a new
developer account can draw more scrutiny than a precedent protects against.

The alternative, if Google ever refuses it: `<queries>` entries for the schemes
that can be named, and `startActivity` with a caught `ActivityNotFoundException`
for the rest — launching another app never needed this permission, only
*resolving* does. The cost is the prompt, which could no longer say "Open in
WhatsApp?" and would have to show the bare URL.

**The suppression is `tools:ignore` on the line itself, not a lint baseline.**
A baseline would have buried the other 37 warnings with it, and would keep
hiding this one after the day it stops being true. `tools:` attributes are
stripped from the merged manifest, so the suppression changes the lint result
and nothing about the shipped app.

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
