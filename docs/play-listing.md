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

### The bundle: 0.1.26, and why it is not 0.1.25

`dist/release/herald-mono-0.1.26-release.aab` is the upload. The published
0.1.25 could not be, for a reason the emulator found rather than the code:

**A fresh install ran on policy 141.** `policy/.../default-policy.json`, the
signed copy compiled into every APK, was still the document from 23 October —
and of every policy in this repository's history, 141 is the one whose own
comment begins "the chatbots option and its blocklist are withdrawn". A phone
installing from Play would have run the single document that takes away the
thing the description promises, until its first poll replaced it. Settings said
`Policy version 141, last updated never`; one tap on *Check for updates* moved
it to 169 and every chatbot site blocked.

The window was not seconds. `PolicyWorker.schedule` enqueues periodic work at
`POLL_HOURS = 3` and nothing asked at startup, so WorkManager could defer the
first run by the whole interval.

Two changes, both in 0.1.26:

- the bundled copy is refreshed to the signed 169, which is the `cp` step
  `docs/policy.md` calls "not optional" and which 0.1.25 skipped;
- `HeraldApplication` asks once when `lastSuccessMillis` is still zero — one
  request per install, not one per launch, and the same field settings reads to
  say "last updated never".

**GitHub and Play carry the same binary.** `v0.2.29` publishes both editions of
0.1.26, and policy 170 pins them by hash; the APK in that release is byte for
byte the one the fresh-install test ran (`39d6e5f6…`), and the bundle here is
built from the same tree. The copy compiled into 0.1.26 is 169, one document
behind 170 by design — it must name yesterday's build, so `availableSelfUpdate`
never offers a phone something older than itself.

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

## Release notes ("What's new")

Play takes **500 characters** per language, shown on the listing and in the
updates list. This is a first release, so there is no changelog to write: a
user reading it has never had the app, and "fixed a stale bundled policy" would
mean nothing to them. It says what the app is instead, and every line of it is
a claim checked against the build — the same rule as the descriptions above.

### English (416)

    First release on Google Play.

    herald mono is a slow, monochrome browser with a built-in content blocker: a breathing pause before every page, slower scrolling, no tabs, and pages in black and white.

    Adult sites, gambling, games, ads, dating, social media, general-purpose chatbots, AI companion chat and video streaming are blocked by default.

    No account, no telemetry. The filtering happens on the device itself.

### Dutch (425)

    Eerste versie in Google Play.

    herald mono is een trage, monochrome browser met een ingebouwde inhoudsblokkering: een adempauze vóór elke pagina, trager scrollen, geen tabbladen, en pagina's in zwart-wit.

    Pornografie, gokken, games, reclame, dating, social media, algemene chatbots, AI-gezelschapschats en videostreaming worden standaard geblokkeerd.

    Geen account, geen telemetrie. Het filteren gebeurt op het toestel zelf.

### French (482)

    Première version sur Google Play.

    herald mono est un navigateur lent et monochrome doté d'un bloqueur de contenu intégré : une pause de respiration avant chaque page, un défilement plus lent, aucun onglet, et des pages en noir et blanc.

    Sites pornographiques, jeux d'argent, jeux, publicités, rencontres, réseaux sociaux, chatbots généralistes, compagnons IA et vidéo en streaming sont bloqués par défaut.

    Aucun compte, aucune télémétrie. Le filtrage se fait sur l'appareil même.

**At the next update this becomes a changelog**, and the first one has something
to say: 0.1.26 is the build where a fresh install stops running on the policy
compiled into it months earlier.

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

### The video the form asks for

Google does not take the justification's word for it: the form also wants a
video of the feature that needs the permission. Recorded 2026-10-30, and
reproducible with `tools/play-demos/record.sh query-all-packages` — 38 seconds, no narration, three
taps:

1. `tel:` → **"Open this link in Phone app?"** → the dialer opens on the number.
2. `mailto:` → **"Open this link in Gmail app?"** → Cancel, because the prompt is
   the evidence and the mail app is not.
3. `whatsapp:` → nothing happens. No installed app owns that scheme on the
   device, so no prompt appears and nothing is guessed at.

The page being tapped is `tools/play-demos/query-all-packages/index.html`, which names the package and
says what each link demonstrates, so the video explains itself without a
voice-over or a title card.

It is published from this project's own site rather than YouTube:

<https://drawbridge-project.pages.dev/assets/play/herald-mono-query-all-packages.mp4>

and that URL is what the form takes. Nothing on the site links to it. It sits in
`site/assets/`, the one directory `build-site.py` does not clear, and
`record.sh` writes straight into that directory — so re-recording replaces what
is published, rather than leaving a hand-copied file behind to drift out of step
with the build it claims to show.

One thing to know before a reviewer opens it: **it plays, but it does not
scrub.** Cloudflare Pages answers no byte-range request on this project — not
for this file and not for the DPC sitting next to it — so a browser never marks
the video seekable and dragging the scrubber returns to the start. The index was
moved to the front of the file anyway (`tools/play-demos/faststart.py`), which is
the half of the problem that was ours to fix; the other half is the host's and
would take a Worker in front of the asset to change. For 38 seconds that play on
the first press, that is not worth a Worker.

The three taps together are the justification restated as something watchable:
the permission is the question *who owns this scheme*, the prompt naming an app
is that answer shown to the user, and the third link is what the answer looks
like when it comes back empty.

It is recorded on the emulator rather than on a provisioned phone, deliberately.
A drawbridge phone has had most of these handoff targets removed, so the one
device where the feature is hardest to film is the managed one.

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

## The other two Play asks about: the foreground service types

herald declares two foreground service types, and targeting Android 14 means
declaring them again in Console, under App content. Play's help page describes four things per
type: a use case, a description of the feature, what happens to the user if the
system defers or interrupts the work, and a video. **The form asks for two.** A
checkbox, and a link to the video — no free text anywhere, not even behind the
*Other* boxes, as of 2026-10-30.

Which puts the whole justification inside the video, so each demo page states
the package and the permission on screen and narrates its own steps. The prose
below is kept anyway: the QUERY_ALL_PACKAGES form does take text, and a review
that comes back with questions will want sentences rather than a checkbox.

Both are real, and the system says so rather than just the manifest. With a
download running, `dumpsys activity services` reports the download service as
`isForeground=true types=0x00000001`, which is `FOREGROUND_SERVICE_TYPE_DATA_SYNC`;
with page audio playing it reports the media service as `types=0x00000002`,
`MEDIA_PLAYBACK`. Declared, requested, and actually used, which is the order
Play checks them in.

### FOREGROUND_SERVICE_DATA_SYNC — downloading a file

Use case: **Network processing → Other**. Play's help page calls this row
"Network transfer: Upload or download", but the form in Console offers only
*Backing up, restoring* and *Other* under Network processing, so a browser
download lands in Other — in that group, not under *Other tasks*, and nothing
under Local processing applies because herald transcodes, imports and exports
nothing. Video:
<https://drawbridge-project.pages.dev/assets/play/herald-mono-data-sync.mp4>

> herald mono is a web browser. When someone taps a link to a file, the browser
> downloads it and shows the progress in a notification. The service runs for
> the length of that transfer and no longer, and only ever because the user
> started it: nothing downloads on its own, and the app performs no background
> synchronisation of any kind.

Deferred: the file the user asked for does not arrive, with nothing to explain
why except a notification that never moves. Interrupted: the partly written file
is discarded and the transfer starts again from zero — on a metered connection,
the same data paid for twice.

The video shows the download being tapped, confirmed, and then **the browser
being sent off screen**, because surviving that is the whole reason the service
exists. It ends in herald's own Downloads list, with the file in it.

One honest note for the form: Google's own FAQ steers this case elsewhere — "we
recommend using the user-initiated data transfer job instead of the dataSync
foreground service type" for network transfers at explicit user request. herald
downloads through Mozilla's `AbstractFetchDownloadService`, which uses dataSync,
so dataSync is what is declared. Targeting SDK 36 also brings the Android 15 cap:
six hours of dataSync per 24, after which `Service.onTimeout` fires. A file
download measured in seconds is nowhere near that, and the service starts from a
tap while the app is in the foreground, which is the position the documentation
recommends anyway.

### FOREGROUND_SERVICE_MEDIA_PLAYBACK — audio from a page

Use case: **Media playback**, and that box only. The form also offers *Show
picture in picture*, which would be a claim about a feature herald does not
have: there is no `PictureInPictureFeature`, no `enterPictureInPicture` call and
no `supportsPictureInPicture` in the manifest. Video:
<https://drawbridge-project.pages.dev/assets/play/herald-mono-media-playback.mp4>

> herald mono is a web browser. When someone presses play on audio or video on a
> page, playback continues if they leave the browser, with play and pause
> controls in the notification. Playback only ever begins from a user action on
> the page — the browser starts no media by itself.

Deferred: sound cannot continue once the browser leaves the screen, so it stops
mid-sentence every time the user switches apps or locks the phone. Interrupted:
playback ends and the controls vanish, and the user has to find the page again
and their place in it.

Play's stated qualifier is the one worth reading twice: "Playback must be
user-initiated; any automatic soundtracks that play when you open an app do not
qualify." The demo page therefore plays nothing on load. The video shows a press,
then the browser leaving the screen, then the notification's pause button being
used — and then the page again, where the clock has advanced while the browser
was away and stopped exactly where the notification stopped it.

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
