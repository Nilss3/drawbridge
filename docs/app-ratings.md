# Spec: app admission by store metadata

**Status: specified 2026-08-16, revised 2026-08-17, not built.** The tooling in
`tools/app-ratings.py` is built and is where every number below comes from. This is the design for deciding
whether an app may stay on a managed phone using Google Play's own content
rating and category, instead of only a hand-written list of package names.

It exists because of the finding in
[handoff](handoff.md#policy-59-the-ai-companion-category-was-open-on-the-play-store-side):
policy 59 added twenty-two AI companion apps by hand, and the category grows
weekly. A curated list is a filter for a phone whose app store is wide open, and
the list will always trail.

**Read the measurement under *Curated lists stay* before believing the headline.**
The rule catches about a third of the apps a Play search turns up in this
category, not most of them — publishers self-rate, and a great many apps named
*AI Girlfriend* are rated PEGI 3. The curated list is not replaced by any of
this; it is fed faster and triaged better.

---

## What was measured before any of this was designed

Every number here came from the public Play listing pages, fetched on
2026-08-16. They are the reason the design has the shape it does, and a future
session that wants to change the thresholds should re-measure rather than
re-reason.

**The rule that works, in Europe.** Across the 22 AI companion apps from policy
59 and 48 apps a real phone needs (banking, transport, office, tools, hobbies,
media, shopping):

| PEGI | AI companions (22) | Useful apps (48) |
|---|---|---|
| 18 | 7 | 0 |
| 16 | 7 | 0 |
| 12 | 1 | 0 |
| 7 | 0 | 0 |
| 3 | 5 | 40 |
| Parental guidance | 2 | 8 |

**Useful apps are never PEGI 7 or above.** Banking and identity were 8/8 PEGI 3,
including Belfius, KBC, Argenta, itsme and BNP; transport 3/3; tools 8/8. The
only eight exceptions in 48 were all *Parental guidance* — Zoom, Strava, Audible,
eBay, Netflix, Spotify, TuneIn, RTBF Auvio — and not one was a PEGI number.

**"Parental guidance" is not a signal.** 15 of 35 ordinary control apps carry it
(WhatsApp, Instagram, Spotify, Telegram, Discord, Reddit, TikTok, Snapchat,
Roblox…) against 2 of 22 companions. Blocking it takes half the phone. It is
IARC's catch-all for user-generated and interactive content, and it maps almost
exactly onto the set drawbridge already governs with options — which is why the
design lets it fall through rather than deciding anything.

**The US labels do not work and Europe is not optional.** On `gl=US` the same 22
apps come back Everyone ×5, Teen ×2, Mature 17+ ×15, and *Everyone* is also
Google Maps, Uber and Firefox Focus. There is no US threshold with Maps on one
side and Crushie AI: Romance AI Chat on the other. The rule is PEGI-shaped
because the European scale grades content where the American one does not.

**The rating cannot see addictive design, and the category can.** Of the eighteen
most engagement-engineered mobile games, eight are PEGI 3 — Candy Crush, Royal
Match, Gardenscapes, FIFA Mobile (with loot boxes), 8 Ball Pool, My Talking Tom,
My Little Pony, Fruit Ninja — while Minecraft, Pokémon GO, Clash of Clans and
Subway Surfers are PEGI 7. A rating gate alone therefore produces a phone with
Candy Crush on it but not Minecraft, which is the opposite of the point. Only
Coin Master (PEGI 18) is caught, and only because its mechanics are literally
gambling. **Games have to be blocked as a category, not as a rating.**

**Categories, from `applicationCategory`:** 21 of the 22 companions are
`ENTERTAINMENT` and one is `SOCIAL`, against 1 of 39 controls (Netflix). Tinder
and Bumble are `DATING`. Grindr is `SOCIAL`.

**The lookup is reliable if it is anchored.** Across 43 apps × 2 locales = 86
pages, the schema.org microdata field `itemprop="contentRating"` and the JSON-LD
`"contentRating"` were **both present on every page and agreed 86/86**. But 75 of
those 86 pages contain 2–4 *different* rating strings — carousels, "similar
apps" — so a bare text search for `PEGI` picks the wrong answer often. Two
extractions were written and thrown away during this investigation for exactly
that reason. **Never text-search the page; always anchor on one of the two
structured fields, and use their agreement as a free integrity check.**

---

## The rule

Evaluated in this order. The first branch that answers, wins.

| # | Branch | Outcome | Needs the store? |
|---|---|---|---|
| 1 | `isProtected` — drawbridge, allowed browsers, `exempt_packages`, `NEVER_TOUCH`, **and the whitelist** | **keep** | no |
| 2 | Local **blocklist** (`blocked_packages`) | **remove** | no |
| 3 | Browser rule | existing behaviour | no |
| 4 | Allowlist mode (`allowed_packages` on a profile) | **remove** | no |
| 5 | Store **category** in `GAME_*` or `DATING` | **remove** | yes |
| 6 | Store **rating** not in `allowed_ratings` — *Parental guidance included* | **remove** | yes |
| 7 | Install lock: outside the closed set | **remove** | no |
| 8 | otherwise | **keep** | — |

Branches 5 and 6 are the new ones. Everything else is today's behaviour, in
today's order.

**An earlier draft of this table had two mistakes, and the second was
dangerous.** It listed the whitelist as a branch of its own; it is folded into
`isProtected`, which is the existing "never touch this" gate and already runs
first. And it listed *preinstalled → keep* as a global branch, which would have
stopped `blocked_packages` hiding YouTube — the single most-exercised removal
this project has. Preinstalled packages are exempt from **the store rule only**,
which is what the prose below always said and what the code does. Corrected
2026-08-17 while building it.

### Why the local lists come first

**A package on the blocklist does not need verifying** — the decision was already
made by a signed document, and asking Play about it would be a network round trip
to reach the same answer more slowly and less reliably. Same for the allowlist.
This is the owner's call of 2026-08-16 and it also happens to be what keeps the
store lookup affordable: on a 294-package handset, branches 1–5 answer for
almost all of them.

### Why preinstalled apps are exempt

**This is the third rule in `AppBlocker` that removes what is *not* named**, after
allowlist mode and the install lock, and both of those already learned it. A
store verdict cannot know about a package that does not exist yet, an Android
version upgrade legitimately adds system apps, and hiding the OEM's dialer or
keyboard because Play has no listing for it would leave an unusable phone with no
sanctioned way to fix it. Nothing is lost: this rule exists because of the Play
Store, and a preinstalled app did not come from there.

### Games and dating are categories, not ratings

**No toggle for games** — owner's call, 2026-08-16. They are blocked outright.
The measurement above is the argument: rating cannot see addictive design, and a
casual game engineered around a variable-ratio reward schedule is exactly what
this phone is for not having.

**Dating is blocked for the same reason it is easy to miss** — several AI
companion apps file themselves there rather than under Entertainment, so the
category catches a slice of the very problem this spec is about, and it catches
Tinder and Bumble on the way. Note that it does **not** catch everything in that
space: Grindr is filed `SOCIAL`. The curated list still does the last mile.

The check must be prefix-based on `GAME_` rather than an enumeration —
`GAME_CASUAL`, `GAME_ARCADE`, `GAME_STRATEGY`, `GAME_ADVENTURE` and the rest,
plus whatever Google adds next.

### Two rating outcomes: PEGI 3, or blocked

- **`allowed_ratings`** → keep. `PEGI 3` and its equivalents.
- **anything else** → remove, *Parental guidance included*.

**Blocking `Parental guidance` is the owner's call of 2026-08-17, and it reverses
an earlier draft of this file** which let that band fall through to the rest of
policy. The reasoning is what changed, not the data: IARC assigns *Parental
guidance* where it grades nothing, and the apps that carry it are the ones with
no moderation — TikTok, Roblox, Bigo, Discord. "Watch your child every second" is
not a rating a managed phone can act on, and drawbridge is built for adults
locking their own device as much as for parents, where the same absence of
moderation is the same problem.

**It is the expensive half of the rule and the cost is measured**, below. Roughly
one useful app in eleven is *Parental guidance*, and the whitelist is what pays
for it — which is why the whitelist stops being a convenience and becomes load
bearing. See *The whitelist is now core*.

Both lists live in the **signed policy**, not in the app:

```json
"app_ratings": {
  "store_region": "BE",
  "allowed_ratings": ["pegi 3", "everyone", "usk: all ages", "rated 3+"],
  "blocked_category_prefixes": ["GAME_"],
  "blocked_categories": ["DATING"],
  "allowed_packages": ["ch.threema.app", "network.loki.messenger", "..."]
}
```

`allowed_packages` is the **default whitelist**, and it belongs in the signed
document rather than only on the device — see below.

Keeping them in the document means a threshold can move with a policy re-sign
rather than an APK update — which matters, because a build cannot be pushed to a
phone unaided (Play Protect refuses the DPC). It also lets the region be set per
deployment: the rule is PEGI-shaped, and a phone whose store answers in ESRB
labels needs different ids rather than different code.

**Ids are compared lowercased and trimmed.** The store returns a display string;
`"PEGI 3"` and `"pegi 3"` are the same verdict.

---

## The lookup

```
GET https://play.google.com/store/apps/details?id=<package>&hl=en&gl=<store_region>
```

Unauthenticated, no account, no protobuf, no GPlayApi. This is deliberate and it
is what makes the whole thing viable on drawbridge's terms: *no account, no
backend* is the project's founding constraint, and the private `/fdfe/details`
endpoint the Murena architecture uses would need Google credentials or an
anonymous token pool. See
[design-decisions](design-decisions.md) — the public page carries the same
structured field.

**Extraction, in order:**

1. `itemprop="contentRating"><span>…</span>` — schema.org microdata
2. `"contentRating":"…"` — JSON-LD
3. `"applicationCategory":"…"` — JSON-LD

If (1) and (2) are both present and **disagree**, treat the lookup as failed and
log it loudly. They agreed 86/86 in measurement, so a disagreement means the page
shape has changed and the parser is no longer reading what it thinks it is.

`hl=en` is pinned so that ids are stable — the rating title is localised, and a
policy table cannot be written against three translations of it. `gl` comes from
the policy, because the *rating itself* is regional.

### Caching, because a sweep must not fetch 300 pages

Persist per package: rating id, category, descriptors, the fetched-at time and
the `versionCode` it was fetched for.

- **Only user-installed, otherwise-undecided packages are ever fetched** —
  branches 1–5 answer first, which on a real handset leaves a few dozen.
- **Invalidate on `versionCode` change.** An app that updated may have been
  re-rated, and this is the only signal the device gets.
- **Otherwise a long TTL** — 30 days. Ratings change rarely.
- The sweep uses the cache and never blocks on the network.

### When the lookup fails

**Fail open, and make it visible.** A package with no verdict is kept, and
Diagnostics reports how many are in that state and which.

This is the opposite of the Murena design, which requires parent approval — and
the reason is that drawbridge has no approval channel. On a locked phone there is
no prompt, no PIN, no UI; the only two outcomes available are *keep* and
*remove*. Removing on a failed lookup would mean a network outage silently
uninstalling the phone's apps, and would remove any legitimately sideloaded or
F-Droid app on principle.

What makes fail-open acceptable is the owner's observation that the risk is
concentrated where the metadata is: **an addictive app wants users, so it is on
the Play Store.** F-Droid carries almost nothing of this kind, and what it does
carry goes on the curated blocklist by hand. The install lock, when on, removes
anything outside the closed set regardless of what any store says.

Visible rather than silent is the condition. A count that quietly grows is the
same failure as the pre-2026-08-15 log nobody could read.

---

---

## The whitelist is now core, and here is what it has to carry

Blocking *Parental guidance* moves the whitelist from insurance to load-bearing:
without it the phone loses its messengers. **`tools/corpora/useful-wide.txt`** is
323 packages harvested from Play search on 2026-08-17 across banking, government,
health, school, transport, utilities, office, tools, hobbies and messaging —
harvested rather than hand-written, so the ids are real and the population is
weighted by what people actually install. The rule over that corpus:

**280 keep, 26 *Parental guidance*, 17 removed by rating or category.**

Per bucket, the share the rule would block:

| bucket | blocked | note |
|---|---|---|
| banking | **0 / 25** | the category where a false positive is worst is clean |
| transport | **0 / 35** | likewise |
| tools | 1 / 36 | a Xiaomi file manager |
| school | 1 / 32 | LinDuo, filed `GAME_EDUCATIONAL` |
| government / ID | 3 / 36 | three digital-identity apps, all PG |
| office / work | 4 / 36 | Zoom, Webex, Viber (PG); Tango correctly at PEGI 18 |
| health | 4 / 36 | **see below — these are the surprising ones** |
| hobbies / sport | 8 / 36 | Strava, Sports Tracker, FITAPP, Cookpad, ReciMe, Samsung Food, and two birdwatching apps |
| utilities / home | 12 / 36 | **11 are supermarket *simulator games*** the search term dragged in, correctly blocked |
| messaging | **10 / 15** | every private messenger is PG |

Discounting the supermarket games, **about 30 of 323 genuinely useful apps — 9% —
need whitelisting**, and they are not spread evenly. They are concentrated in
three places:

1. **Messengers, 66%.** WhatsApp, Telegram, Threema, Session, SimpleX, Keet,
   Zangi, xPal, Messenger, WeChat. WhatsApp and Telegram already have options;
   the private ones need the whitelist, exactly as the owner predicted. A
   messenger is *Parental guidance* because it carries ungraded conversation,
   which is true of every messenger including the ones chosen for privacy.
2. **Hobbies, 22%.** Strava, two recipe apps, two birdwatching apps. These are
   the entries that would surprise a household, because nothing about *Birda –
   Birding Made Better* suggests a content rating problem. It has a community
   feed, so it is ungraded, so it is PG.
3. **Health, and this one is not PG at all.** Newpharma, i-Pharmacy and Aetna
   Health come back **PEGI 18**, so they are blocked by the base rule rather than
   by the new decision, and a household needing its pharmacy app is stuck without
   a whitelist entry.

   **It is not systematic, which is worse rather than better.** Multipharma and
   Farmaline — the same business in the same country — are **PEGI 3**. So a
   pharmacy's rating says nothing about pharmacies and everything about which
   questionnaire that publisher filled in, and there is no category rule to be
   written here. It is a whitelist entry per app, discovered the hard way, and it
   is the clearest single illustration in this file that IARC ratings are
   self-declared.

### What blocking Parental guidance actually buys

The obvious objection is that the band is expensive and mostly benign, so allow
it and let games and dating carry the load. **Measured 2026-08-17, and the
objection does not survive.**

Ten search terms for harm-adjacent apps, deliberately excluding games, dating and
companions because those are already caught by category, produced **103 packages
not on the blocklist**:

| | count |
|---|---|
| caught today, by PEGI 7+ or category | 40 |
| **caught only by also blocking Parental guidance** | **42** |
| through regardless, at PEGI 3 | 21 |

Blocking the band takes automatic coverage of that surface from **39% to 80%**.
What sits in the 42 is the argument: **TikTok Lite**
(`com.zhiliaoapp.musically.go`, a variant the blocklist does not name), **NGL**
and **Tellonym** and four anonymous-confession apps, five stranger-livestream
apps — Likee, Poppo Live, GOGO LIVE, BuzzCast, YouNow — and ten short-drama reels
apps, which are the addictive-feed format under a different name.

**About 17 of those 42 are benign** — 500px, Vimeo, Meetup, FamilyAlbum and a
cluster of photo-sharing apps — and they join the whitelist. So the honest trade
is roughly **25 harmful apps caught for 17 more whitelist entries.**

**The ratio is not what settles it; the shapes are.** The whitelist is finite and
knowable — every app a household needs can be written down in an afternoon, and
it changes slowly. The blocklist is neither: a new confession app appears faster
than a policy can be re-signed, which is the complaint this whole document exists
to answer. Blocking the band converts an unbounded curation problem into a
bounded one, which is the same move the install lock makes.

Two smaller points in the same direction. For an app **already** on the
blocklist, blocking the band adds nothing — the list catches it by name — so the
entire value lands on the uncurated tail, which is exactly where the measurement
says it pays. And the cost is front-loaded: the default whitelist is written once
and shipped, while the harm it forecloses arrives weekly.

**The band means *ungraded*, not *risky*, and the design says so out loud.** It
contains TikTok Lite and a birdwatching app for the same reason: neither has been
graded. drawbridge accepts a wider whitelist in order to get a narrower blocklist,
deliberately.

### It has to ship in the policy, not only on the device

If the whitelist were purely device-local, every household would discover that
Zoom is gone by finding Zoom gone. So it is two lists that union:

- **`app_ratings.allowed_packages` in the signed document** — the known-good set
  above, curated the same way `blocked_packages` is, updated by a policy re-sign
  rather than an app update. **A first draft of 23 packages is in
  `tools/corpora/whitelist-draft.txt`**, every entry verified to be one the rule
  would otherwise remove.

  Two invariants that want enforcing in `policytool.py sign`, both of the same
  shape as the streaming mirror check:

  1. **No entry may appear in `blocked_packages`.** The whitelist is evaluated
     *before* the blocklist, so an overlap silently unblocks a blocked app.
  2. **No entry may be governed by an option.** WhatsApp and Telegram are
     deliberately absent from the draft: whitelisting them would override the
     parent's switch and reduce the option to a decoration.
- **A device-local list**, like the browser choice, for what a signed document
  cannot know: this family cycles, that one needs a specific school app.

The default list can only *keep*. It cannot remove anything, and it cannot
override `isProtected` — narrowing stays the only direction a household can move,
which is the same rule the browser choice follows.

---

## Chatbots are not blocked, and the reason is architectural

**An earlier draft of this file specified a chatbot toggle. It is withdrawn.**

The question that settled it: can drawbridge suppress the AI assistant built into
the search engines it already allows? Measured 2026-08-17:

```
forcesafesearch.google.com  ->  216.239.38.120          its own IP
strict.bing.com             ->  150.171.28.16 ...       its own IP
noai.duckduckgo.com         ->  CNAME duckduckgo.com -> 52.142.124.215   the same IP
```

SafeSearch is deliverable over DNS because Google and Bing run **separate
enforcement IPs**: point the name at that address and the server enforces it
whatever the browser sends. DuckDuckGo's `noai` is a CNAME to the ordinary
address, so its behaviour is selected by the `Host` header, and a DNS rewrite
cannot deliver it. Google and Bing publish no AI-suppression hostname at all —
Google's mechanism is `&udm=14`, a URL parameter.

A URL parameter reaches **only herald**, because herald is the only browser
drawbridge can rewrite in. That is precisely the test Ecosia failed on
2026-08-15: *an engine is only as forced as its weakest browser.* On a phone that
allows Chrome, Focus or Vivaldi, Gemini answers in the search box whether or not
the ChatGPT app is installed.

So blocking the apps would be a claim the phone cannot keep, and this project
deletes those rather than shipping them. **General-purpose assistants go on the
whitelist** — ChatGPT, Claude, Gemini, Copilot, Perplexity, DeepSeek, Le Chat,
Pi, Meta AI — six of which are *Parental guidance* and would otherwise be caught
by the rating rule as a side effect.

**The line is companion versus assistant, not AI versus not-AI.** Grok,
Character.AI, Chai and Talkie stay blocked as AI companions: that is a decision
about content, it is reachable by the rating rule (all four are PEGI 18), and it
is unaffected by what a search box does. Grok's domains live in
`ai-companions.txt`, which has no option behind it, so the mirror check that
`policytool.py sign` enforces has nothing to complain about.

The website should say plainly that AI chat is not blocked and why. The
documentation already takes that position; this is the measurement behind it.

---

## Dating needs a web list

`dist/lists/dating.txt`, always-on, no option, beside `games.txt`. The app side is
covered twice — `blocked_packages` names Tinder and Bumble, and the `DATING`
category catches them again — but **there is no domain list today**, so a browser
reaches Tinder's web app unimpeded. The popular set is small; the long tail is
regional and low-traffic, and will stay leaky in the way `games.txt` is leaky.

Note the category does not catch everything in the space: **Grindr is filed
`SOCIAL`**, so the curated list still does the last mile here as everywhere else.

---

## Scanning: everything once at the first lock, then per install

**Every user-installed package is checked at the lock, at least once.** After that
the install receiver covers arrivals one at a time and the fifteen-minute sweep
reads the cache. Without the first pass the rule would only ever apply to apps
installed *after* drawbridge was set up, which is the wrong half — the phone
arrives with the problem already on it.

**The traffic is the cost, and it is not small.** A listing page is ~1.2 MB and
the fields sit **85–91% of the way into it**, so a Range request saves nothing —
measured, and recorded here so nobody tries it again. Forty to eighty
user-installed apps is therefore **50–100 MB, once**.

That wants the treatment herald's own download already gets: **defer the first
full scan to an unmetered network.** Until it completes the unscanned apps are
`unverified`, which is fail-open, and Diagnostics reports the count so the state
is visible rather than silent. Enforcement arrives on wifi; nothing is removed on
mobile data because a parent happened to press Lock in a car.

### On robots and whether this is scraping

**`play.google.com/robots.txt` does not disallow `/store/apps/details`.** It
disallows a long list of neighbouring paths — `/store/apps/datasafety*`,
`/store/people/details`, `/store/purchase`, `/store/xhr`, `/apps` — and pointedly
not the app listing, which Google wants indexed so that listings appear in search
results. The machine-readable crawl policy permits exactly the URL this design
uses and forbids the ones it does not touch.

That answers robots.txt and not Google's terms of service, which are a different
instrument. What makes the position defensible rather than merely unpunished:

- **One request per app the user actually installs, on their own device, on their
  own behalf.** This is not a crawl of the catalogue; a phone will make a few
  dozen requests once and a handful a month thereafter.
- **Identify honestly.** The `User-Agent` says what this is rather than
  impersonating Chrome. A service that would rather not serve us should be able
  to tell that it is us.
- **Degrade, do not escalate.** A rate limit or a block produces `unverified`,
  which keeps the app and reports the fact. There is no retry storm and no
  fallback to a private API.

If that ever stops being acceptable, the fallback is the one the curated list
already provides, and the measurements in this file say what it costs: the list
alone catches the biggest offenders and misses about a third of new arrivals.

---

## The "what's blocked" page

`site/why-blocked/` exists and is generated by `tools/convert_blocklist.py` from
`site-src/block-list.md`. Today it lists domains, which was honest when the list
*was* the policy and stops being honest here.

The page becomes an explanation of the **rule**, in this order:

1. What is allowed: apps rated for everyone, that are not games and not dating.
2. What is blocked and why: everything rated above PEGI 3, all games, all dating,
   and the curated list.
3. What a parent can switch back on: the options — WhatsApp, YouTube, Telegram,
   streaming.
4. **What is deliberately *not* blocked, and why**: AI chat, because every
   allowed browser reaches an assistant through its search box. Saying so is
   better than a claim the phone cannot keep.
5. The curated list, last — as the exceptions the rule misses rather than as the
   policy itself.

Trilingual like the rest of the site, and it must say plainly that the ratings
come from the Play Store's own publisher-declared data and are not an independent
audit. The measurements at the top of this file are the evidence for saying so:
five apps whose names contain *Romance*, *Crush* and *Fantasy Roleplay* are rated
PEGI 3 by their own publishers.

---

## Curated lists stay, and they are the last mile

**Blocklist.** Seven of the twenty-two companions leak a PEGI-7 gate and need
naming by hand:

| | Package | BE rating |
|---|---|---|
| Crushie AI: Romance AI Chat | `ai.onspace.app` | PEGI 3 |
| AI Character: Roleplay Chat | `com.ai.chat.assistant.smart.bot` | PEGI 3 |
| FantasyX: AI Roleplay Chat | `com.fantasyx.chat` | PEGI 3 |
| Roleplay: AI Chat, Stories | `com.roleplay.android` | PEGI 3 |
| Dopple.AI | `mobile.dopple.ai` | PEGI 3 |
| CRUSH: AI Romance × Otome | `xyz.passion.crushai` | Parental guidance |
| zeta: AI Chat, Story, Play | `com.scatterlab.messenger` | Parental guidance |

**Checked against a second, independent sample, and it holds — but not as
stated.** `tools/app-ratings.py search "ai girlfriend" "ai companion chat"`
returned 35 candidate ids not on the blocklist. Splitting them by what they
actually are, because the search terms also surface general-purpose assistants
that branches 6–7 are not meant to catch:

| | AI companions | General assistants |
|---|---|---|
| remove | **13** | 0 |
| neutral | 4 | 6 |
| keep | 2 | 10 |
| total | **19** | 16 |

**13 of 19 companions = 68%**, which is the same as 15 of 22 above. Two
independent samples, the same number: that is a replication rather than a
coincidence, and it is much better evidence than either was alone.

**A first pass at this reported 37% and was wrong**, having divided 13 by all 35
and counted sixteen general-purpose assistants — ChatGPT, Gemini, Perplexity,
Copilot, Poe — as escapes. They are not escapes; the chatbot toggle names them,
and the rating rule leaving them alone is the design working. The wrong
denominator then invited an invented explanation about prominent apps rating
themselves higher, which no evidence supported. Recorded because the arithmetic
error was harder to see than the story built on top of it.

**What is true is narrower than "stops growing".** Six of nineteen leak, and two
of them are named *AI Girlfriend* while sitting at PEGI 3 — Ashley
(`com.ashley.ashley_ai`) and HerStory (`com.hyperitycorp.herstoryai`). Four more
are *Parental guidance* and so survive on any phone without the install lock. The
list keeps growing; it grows at roughly a third of the rate.

The half that actually attacks *"this cannot be kept up with"* turns out to be
**search, not rating**. Two query terms surfaced 35 unlisted candidates in a few
seconds, one of them (`AIKO: AI Girlfriend 3D Game`) caught only by the
`GAME_SIMULATION` category rule. The workflow the tooling supports is therefore
curation *accelerated*, not curation *replaced*:

1. `search` harvests candidate ids for a set of terms;
2. the rule triages them — the `remove` verdicts need no thought;
3. a human reads the survivors and accepts or rejects, under policy 59's standing
   rule that the listing title must be the app it claims to be;
4. what is accepted joins `blocked_packages`.

Which also means the `search` command is not a convenience wrapped around the
real feature. It is closer to being the feature.

**Allowlist.** New, and needed because of branch 7: Strava, Audible, Spotify,
Netflix, Zoom, eBay and RTBF Auvio are *Parental guidance*, so they fall through
to the rest of policy — but a household that wants one of them kept regardless of
any future rule needs somewhere to say so. It is device-local, like the browser
choice, because "this family cycles" is not something a signed document can know.

It sits at branch 2, above the blocklist, and can only *keep* an app. It cannot
remove one, and it cannot override `isProtected`.

---

## The audit against the existing blocklist

`tools/app-ratings.py audit --policy-blocklist` ran the rule over all 304
packages `dist/policy.json` blocks by name. **148 remove, 87 neutral, 57 keep, 12
unverified.**

Taken flat that reads as the rule catching half of what the list carries, and
that reading is wrong. Bucketing the 57 it would *keep* by **why the policy blocks
them**:

| | count | can a rating ever catch it? |
|---|---|---|
| Filter-bypass — VPN, proxy, Tor, DNS changers | 27 | **no, and it should not** |
| Browsers — Opera, Tor Browser, Aloha, Ecosia | 4 | answered earlier, at branch 5 |
| Content and option-governed | 26 | partly |

**The list has a permanent job, and this is it.** NordVPN is PEGI 3 and correctly
so — a VPN carries no age-inappropriate content. It is blocked because it defeats
a DNS-only filter, which is a fact about drawbridge's architecture rather than a
fact about the app, and no content rating from anybody will ever express it.
Twenty-nine of the 304 are in that class. They are not the rule failing; they are
work the rule was never able to do.

The 87 *neutral* break down the same way: 84 are content or option-governed —
9GAG, Apple TV, BeReal, Bluesky, BritBox, CANAL+, Crunchyroll, DAZN — sitting at
*Parental guidance* and correctly falling through to the streaming option and the
blocklist that already govern them.

**So the genuine rating-rule misses are the seven AI companions** rated PEGI 3 or
*Parental guidance*, which is the same finding as the search sample and the same
seven-ish apps. Everything else the list carries, it carries on purpose.

The 12 *unverified* are worth a separate look, because two of them are
`app.drawbridge.probe` and `app.drawbridge.probeb` — this project's own Play
Protect probe packages, which are on the blocklist and have no Play listing. The
rest are dead or region-locked services: `com.showmax.app` (superseded),
`com.orange.ocsgo`, `com.youku.phone`, `com.tencent.qqlive`,
`com.vkontakte.android`, `org.telegram.messenger.web`. A 404 is not proof of a
wrong id — four streaming listings answered 404 from outside their market during
policy 52 — so these want checking against APKMirror rather than deleting.

---

## What is not decided

- **The games threshold has one soft edge.** `GAME_*` blocks the lot, including
  the ones a small child might legitimately have. That is the owner's call as of
  2026-08-16 — no toggle, block them — and it is recorded here as a decision
  rather than an oversight so that a future session does not "fix" it.
- **False positives on the category rule have a 39-app control set behind them
  and no more.** Before shipping, run the rule over a few hundred ordinary apps
  from `tools/`, offline, and look at what it would have removed. That script is
  worth building before the phone-side code, because it is the only way to test
  the rule against a realistic population without a handset.
- **Whether the Play Store app on a phone shows tag chips the web page does
  not.** The owner saw a Dating tag somewhere; a literal search of the seven
  PEGI-3 pages found "dating" only in marketing prose, never as a category. If
  the handset's own store UI carries a field the web page lacks, it is worth
  knowing — it would be a signal this design cannot currently see.

---

## Build order

1. ~~`tools/` curation script.~~ **Built** — `tools/app-ratings.py`, with
   `check`, `audit` and `search`. It is what produced every number in this file,
   and running it corrected the spec three times.

**Steps 2 through 6 are built as of 2026-08-17**, and nothing carries them on a
handset yet — no build has been cut since drawbridge 30. The rule goes live the
moment one is, because policy 62 already carries `app_ratings` and there is no
separate switch. **Run the tools script against the target phone's own package
list before cutting that build**:

```
adb shell pm list packages -3 | sed 's/package://' > /tmp/phone.txt
python3 tools/app-ratings.py audit --corpus /tmp/phone.txt --expect keep
```

That prints exactly what would be removed, offline, before anything is. The
measurement says to expect roughly one app in eleven to want a whitelist entry,
and it is better to meet them in a terminal than by noticing Zoom has gone.
2. **The default whitelist**, from the measurement above: private messengers,
   conferencing, sport and recipe apps, pharmacies, digital identity, and the
   general-purpose assistants. This is the prerequisite for blocking *Parental
   guidance*, not a follow-up to it — shipping the rule without it takes the
   phone's messengers away.
3. Policy fields (`app_ratings`), `dist/lists/dating.txt`, and Grok into
   `ai-companions.txt`. Signed.
4. `StoreMetadata` + cache in the DPC, with Diagnostics reporting the unverified
   count and the first-scan progress.
5. Branches 6 and 7 in `AppBlocker`, in the order above.
6. ~~The first-lock full scan, deferred to an unmetered network.~~ **Built** —
   `StoreScanWorker`. Queued at the lock and re-run weekly, both constrained to
   an unmetered network at the *request* rather than checked inside the job,
   which is the opposite of `UpdateWorker` and deliberate: that worker carries a
   3 MB self-update which must reach a phone that never sees Wi-Fi alongside a
   235 MB browser that must not, so it splits the decision internally. Here the
   whole job is the expensive half, so WorkManager can simply hold it.

   It resumes rather than restarts, because the cache *is* the progress: a
   package with a current answer is skipped. Bounded at 60 listings a run so one
   pass cannot approach WorkManager's ten-minute limit, and an interrupted run
   returns `retry` rather than `success` — a scan that stopped and a scan that
   finished must not be indistinguishable.

   The weekly repeat is not about staleness. An expired entry reads as
   `unverified`, which is *keep*, and an app that survived the first pass was
   allowed anyway. What it catches is a publisher **re-rating an app already on
   the phone**, which no other signal on the device would reveal.
7. The website page.

Step 2 before step 5, always. The rule is not safe to enable on a phone until the
list that pays for it exists.
