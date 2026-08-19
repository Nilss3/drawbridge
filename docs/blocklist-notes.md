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

**YouTube Music is part of the option, and the attempt to separate it is worth
recording.** Policy 47 unblocked Music outright, on the reasoning that a music
service is not social media — which is true, and turned out not to be the
question.

**Separating Music from YouTube is not separable.** Music streams from
`googlevideo.com` and calls `youtubei.googleapis.com`, both shared with YouTube
proper, so unblocking Music meant leaving the **YouTube video CDN permanently
open**. `youtube.com` would still have been blocked, so neither the site nor the
app was reachable — but anything able to construct a `googlevideo.com` URL could
stream, and third-party front ends do exactly that: NewPipe, LibreTube, Tubular,
ReVanced and SkyTube talk to the API and the CDN and never touch `youtube.com`.
Those five can be named. The tail behind them cannot.

Reverted in policy 48, one policy later, by the owner's decision: an exception
that costs an always-open CDN plus a list nobody can finish is not an exception.
Music is blocked again and comes back with the toggle, so *Allow YouTube (16+)*
now restores all six apps — YouTube, Kids, TV, Creator Studio, YouTube TV and
Music.

**The third-party front ends are deliberately allowed**, as of policy 49, and
this reversed a decision made two policies earlier. NewPipe, PipePipe, LibreTube,
Tubular, ReVanced and SkyTube were blocked in policy 47 because they stream
straight from `googlevideo.com` and would bypass Restricted Mode.

The owner's argument for allowing them is the better one: they strip the ads,
drop the recommendation feed and allow background play. Blocking them was
defending the ad-supported, algorithm-driven version of YouTube against the quiet
one, which is the opposite of what this project is for. And the difference in
*content* between Restricted Mode and not is slight; the difference in how the
app behaves is not.

Nothing is named to allow them. They are simply absent from `blocked_packages`,
which is also why the unbounded tail of similar clients needs no decision at all
— an omission scales where an enumeration does not. They still need
`googlevideo.com` and `youtubei.googleapis.com`, which stay behind the toggle, so
they are inert while YouTube is off and work when it is on.

**The toggle's description says Restricted Mode covers the YouTube app and the
browser**, which is what the DNS redirect actually reaches, and does not
enumerate what else is on the phone. That is accurate rather than complete, and
deliberately so: a parent-facing sentence should say what is enforced where, not
inventory every way around it.

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
- **Telegram** is blocked, and can be allowed again with "Allow Telegram (18+)".
  The option exempts the three official clients — the Play build, the build from
  telegram.org, and Telegram X. **Plus Messenger (`org.telegram.plus`) stays
  blocked either way**, because allowing Telegram is not a decision to allow an
  unofficial fork of it.

  These three are what a parent can switch back on without editing the policy.
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
| `org.telegram.messenger.web` | The build Telegram distributes from telegram.org, which is why it is not on Play. Blocking only the Play package left an official Telegram one download away. Added 2026-08-13. |

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

### TikTok Lite, and why the brand names were not enough

*The procedure this produced is in
[policy.md](policy.md#adding-a-service-to-a-domain-list-the-checklist). What
follows is the case that produced it.*

**And the ending is worth knowing before you add a third round of domains: the
app does not need DNS at all.** Measured on the alpha with every name here
refused, TikTok Lite still played video, connected directly to `71.18.73.249` and
`71.18.129.228` — `whois`: Bytedance Inc. — plus Akamai edge addresses. The
domains below are still right, and they stop the website and anything that does
resolve; they will not stop the app. That is `blocked_packages`' job.

**Reported from the alpha on 2026-08-18: TikTok Lite served video on a phone
where `tiktok.com`, `tiktokv.com`, `tiktokcdn.com`, `tiktokcdn-us.com`,
`byteoversea.com` and `musical.ly` were all blocked.** Instagram Lite, installed
on the same phone at the same time, showed nothing.

That contrast is the finding. **Meta serves its Lite build from the same
`instagram.com` and `fbcdn.net` as the full app, so blocking the brand blocks
both. ByteDance does not.** The Lite build reaches the same backend under the
parent company's own names — `api.snssdk.com` for the feed, `ibytedtos.com`,
`byteimg.com` and `pstatp.com` for the media — so a list of tiktok-branded
domains blocks the website and leaves the app working.

So the section now carries the ByteDance infrastructure as well: the API and
telemetry hosts (`snssdk.com` and its regional twins, `amemv.com`,
`bytedance.com`, `bytedanceapi.com`, `byted.org`, `bytedns.net`) and the image
and video CDNs (`pstatp.com`, `ipstatp.com`, `sgpstatp.com`, `byteimg.com`,
`ibytedtos.com`, `byteicdn.com`, `bytetcdn.com`, `bytecdn.cn`, `muscdn.com`,
`worldfcdn.com`), plus `douyin.com` — the same product under its Chinese name.

**Every one was checked for an NS record before being added**, which is how
`musemuse.cn` was dropped: it appears in upstream lists and no longer resolves at
all. An apex with no A record is normal here and not a reason to leave one out —
these are CDN parents whose traffic rides on subdomains, and matching is
suffix-based.

**What was deliberately left out.** ByteDance's other products — Toutiao, Xigua,
TopBuzz, Ulike — and its ad-tech domains are a different argument from *this
teenager should not have TikTok*, and a social-media list that quietly becomes a
company blocklist is harder to defend and harder to review. **CapCut is the
interesting omission**: ByteDance's video editor, very popular with the same age
group and tightly wired into TikTok, but a video editor rather than a feed. It is
not blocked, and that is a decision waiting for somebody rather than an oversight.

**The package side needed the same fix.** `com.zhiliaoapp.musically.go` — TikTok
Lite — was not on `blocked_packages`, though `com.tiktok.lite.go` (a second Play
listing of the same app) was. `com.instagram.lite` was missing too. Both are now
named. `com.facebook.mlite`, `com.twitter.android.lite` and
`com.ss.android.ugc.aweme.lite` were checked at the same time and return 404 on
Play: they are delisted, and listing dead packages only makes the list harder to
read.
