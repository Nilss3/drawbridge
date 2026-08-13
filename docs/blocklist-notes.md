# Blocklist notes

What the current policy blocks, and — more usefully — where a requested item
could not be blocked exactly as asked.

For the plain list of every blocked package, see
[blocked-apps](blocked-apps.md). This file is the reasoning; that one is the
inventory.

Package IDs were verified against the Play Store rather than written from
memory; a wrong package ID is invisible, because the app blocker simply never
matches it.

## Requested items that are not a one-to-one match

### YouTube Shorts

**Cannot be blocked separately.** Shorts is a surface inside YouTube, served from
the same app and the same domains. Blocking YouTube blocks Shorts. There is no
package or hostname that is Shorts and not YouTube.

**All of YouTube is blocked**, at the owner's explicit request: the main app,
Kids, TV, Music, Studio, the Android TV build, `youtu.be` links, embeds via
`youtube-nocookie.com`, and the `googlevideo.com` video CDN. Because matching is
suffix-based, `youtube.com` also covers `m.`, `music.`, `tv.`, `kids.` and
`studio.` without listing them.

The safe-search redirect in `DnsFilter` still maps `youtube.com` to
`restrictmoderate.youtube.com`, but the block check runs first, so it is dormant.
It was left in place deliberately, and **policy 43 collected on that**: the
*Allow YouTube (16+)* option puts the YouTube hosts in `allowed_domains`, allow
beats block, and the redirect wakes up by itself. Switching YouTube on switches
Restricted Mode on with it, enforced at the DNS layer where nothing inside the
app or the site can turn it off. No code was needed in either app.

**Shorts are still not separable**, and the option does not change that: it is a
surface inside YouTube on the same domains. What *could* hide it is a cosmetic
filter in herald — which already ships uBlock Origin and a bundled extension of
its own — and that only helps in herald, not in the YouTube app or in the other
four browsers.

**YouTube Music is deliberately not part of the option.** Its package stays
blocked and `music.youtube.com` is a distinct hostname, so it can be separated at
the entry point — but it streams from `googlevideo.com` and talks to
`youtubei.googleapis.com`, both shared with YouTube proper. Allowing Music alone
therefore opens the shared media backend, though not `youtube.com` itself. Worth
testing on a device before anyone promises it works.

### Snapchat My AI

**Cannot be blocked separately.** My AI is a bot inside Snapchat, reachable only
through the app and its own domains. Blocking Snapchat covers it. There is no way
to keep Snapchat while removing My AI from outside the app.

### Grok

**Blocked as a standalone app and site; inside X it was already covered.** Grok
lives in two places. The standalone app (`ai.x.grok`) and its own sites
(`grok.com`, `x.ai`) are now blocked directly. The version built into X needs
nothing new: `com.twitter.android` has always been on the package list and
`x.com` on the social list, so the whole surface goes with them.

It sits on the AI-companion list rather than being left to the "general-purpose
assistants are not blocked" rule, and that is a judgement rather than a
technicality — Grok ships a companion mode with deliberately unfiltered personas,
which is the thing that list exists for. ChatGPT, Claude and Gemini stay
unblocked; if you want them gone, `blocked_domains` is where they go.

### Minecraft: Bedrock servers

**Partially blocked, and this one deserves attention.** Minecraft itself is left
alone — the package `com.mojang.minecraftpe` is not blocked and `minecraft.net`
is not in any list — so single-player and LAN work normally.

`dist/lists/games.txt` blocks Realms and the large public Bedrock servers
(Hive, CubeCraft, Mineplex, Lifeboat, Galaxite, NetherGames, InPvP, Mineville).

It cannot block:

- **servers joined by raw IP address**, which bypass DNS entirely;
- **the long tail of small servers**, which cannot be enumerated.

So this is a speed bump, not a wall. If multiplayer needs to be genuinely off,
block the Minecraft package itself and accept losing single-player with it.

### GTA Online

**Not applicable on Android.** GTA Online is PC and console only; there is no
Android build to block. `rockstargames.com` and the Social Club domain are in
`games.txt`, which stops the web account and companion pages, but nothing on the
phone was ever going to run it.

### Every other search engine

**Blocked as far as a list can go, which is not all the way, and this entry
exists so nobody later reads `search.txt` as a wall.** Added in policy 37, after
herald dropped Brave Search, Startpage and Qwant on 2026-08-10 for having a safe
search that cannot be forced. Removing an engine from a browser's list does not
make it unreachable — it is still a website — so `dist/lists/search.txt` blocks
the engines a person can name: the three dropped, the majors never offered, the
independents, and the ones marketed on not filtering.

**The set is open, and no curation closes it.** Anyone can run a SearXNG instance
on any hostname in five minutes; ten public ones resolved in a sample taken on
2026-08-12 and searx.space lists dozens more that churn weekly. Treat a miss as
expected rather than as a bug.

What keeps that from mattering as much as it sounds is the second layer: the
adult and gambling lists block the *destinations*. An unfiltered engine returns a
result list and the click still fails. The engines that genuinely matter are the
ones rendering images inline **on their own domain**, which is why forcing
DuckDuckGo, Google and Bing is worth more than blocking Mojeek.

Left out deliberately, each for its own reason:

- **Public SearXNG instances and front ends** (`searx.be`, `4get.ca`,
  `librex.me`, Whoogle). Vendoring a snapshot of searx.space the way
  `vendor-ublock.sh` pins uBlock Origin was considered and rejected for now: it
  is new tooling plus a list that is stale between refreshes, and it still cannot
  catch a self-hosted one.
- **AI search** — `perplexity.ai`, `you.com`, `phind.com`. These are search
  engines with no safe search at all, and blocking them would reverse the
  standing decision above that general-purpose assistants stay unblocked. Left
  as a known and chosen hole rather than an oversight.
- **Archive and cache sites** — `web.archive.org`, `archive.ph`, `12ft.io`. They
  serve copies of pages the filter blocks, which is a real route, but the
  Internet Archive has ordinary homework uses and this is a different problem
  from search.
- **`translate.google.com`**, which proxies whole pages and is a genuine
  circumvention route. Blocking it costs translation, which is an ordinary need.
- **`brave.com`** and **`qwantjunior.com`**. The first is the vendor's site
  rather than the engine; the second is the filtered edition, and blocking a
  child-safe engine to enforce child safety would be perverse.
- **`bing.net`**, which is Bing's CDN rather than a front end. Blocking it would
  break the engine that is allowed.

`swisscows.com` **is** blocked despite advertising itself as family-safe, on the
rule that only the five engines herald offers should work. That is consistency
rather than a judgement about its content.

## Deliberate omissions

- **Minecraft** — see above.
- **WhatsApp** is blocked, and can be allowed again with the "Allow WhatsApp
  (14+)" option on drawbridge's configuration screen.
- **YouTube** is blocked, and can be allowed again with "Allow YouTube (16+)",
  which brings Restricted Mode with it. Kids, TV, Creator Studio and Music are
  not part of it.

  These two are what a parent can switch back on without editing the policy.
- **Signal** is deliberately left alone, and now explicitly so: it is in
  `exempt_packages` and `signal.org` is in `allowed_domains`, so no upstream
  blocklist can quietly start blocking it.

## Packages not on the Play Store

Four entries do not resolve to a Play listing, and are included deliberately —
a package that is not on Play can still be sideloaded:

| Package | Why |
|---|---|
| `com.epicgames.portal` | The Epic Games launcher has never been on Play. Blocked so it cannot sideload anything. |
| `com.AgainstGravity.RecRoom` | Rec Room's Play listing does not resolve from here; the ID is the one its store URL uses. Worth re-checking if Rec Room ever appears on the device. |
| `com.vkontakte.android` | VK, delisted from Play in the EU. The package is still valid for a sideloaded build. |
| `ai.nomi.twa` | Nomi's original TWA build, since delisted. Its replacement, `nomi.ai.friend.chat`, is blocked alongside it. |

**Two entries this table used to claim were unlisted now resolve**, checked
2026-08-03: `com.epicgames.fortnite` is back on Play following the Epic v.
Google ruling, and `com.ss.android.ugc.trill` — a second TikTok build used in
some regions, not a duplicate of `com.zhiliaoapp.musically` — resolves normally.
Both stay blocked; only the note about them was wrong.

A package ID that turns out to be wrong costs nothing except that the app is not
blocked — the blocker matches on exact package name and ignores everything else.
The reverse is not free, though.

## A wrong domain is not free, unlike a wrong package

`anima.ai` was on `ai-companions.txt` from the beginning and was **removed in
policy 21**. It is a venture studio for nature-inspired innovation — no
connection to the companion app at all. The Anima in question is `myanima.ai`,
which is what replaced it.

The failure is worth naming because it is the mirror image of the one above. A
wrong *package* is inert: nothing matches it and no app is affected. A wrong
*domain* is not — it blocks a real, unrelated site for as long as nobody
notices, and nothing on the device reports it. The app itself was never blocked
at the domain layer during that whole time, so the entry managed to do only
harm.

Resolve a domain and look at what answers before adding it. Several candidates
were dropped from `games.txt` on exactly this basis: `frostpunkmobile.com` has
lapsed to a gambling site, `wwemayhem.com` is a fan forum, `nuverse.com` is a
financial advisory firm rather than the games publisher.

## The European Parent Safety Catalogue

Policy 21 added 109 packages from the catalogue — 104 Android games and 15 AI
companions, of which 10 were already blocked — plus the matching publisher and
game domains. [blocked-apps](blocked-apps.md) is the resulting inventory.

Two judgement calls in that import are worth recording:

- **Minecraft stayed unblocked**, though the catalogue lists it. The catalogue's
  own risk note — that the content is fine and third-party servers with
  unmoderated chat are the problem — is an argument for the arrangement already
  in place, not against it. See above.
- **The three baseline entries are blocked anyway.** Subway Surfers, Toca Boca
  World and Slay the Spire appear in the catalogue as examples of what a
  low-risk game looks like, Slay the Spire explicitly as "the profile to look
  for". They are blocked at the owner's explicit request. Anyone reading the
  catalogue later and wondering why the control group is on the blocklist: that
  is why, and removing them is a one-line change each.

## Two layers, and what each one catches

Blocking a service well means covering both:

- **`blocked_packages`** stops the *app* — removed within about a second of
  finishing installation, silently, with no prompt.
- **The domain lists** stop the *website*, in herald and in any other app's
  embedded WebView, via DNS.

An app blocked by package can still be reached at its website unless the domain
is listed too, which is why the social, AI-companion and games lists mirror the
package list rather than duplicating it by accident.
