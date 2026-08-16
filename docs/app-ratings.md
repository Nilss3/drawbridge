# Spec: app admission by store metadata

**Status: specified 2026-08-16, not built.** This is the design for deciding
whether an app may stay on a managed phone using Google Play's own content
rating and category, instead of only a hand-written list of package names.

It exists because of the finding in
[handoff](handoff.md#policy-59-the-ai-companion-category-was-open-on-the-play-store-side):
policy 59 added twenty-two AI companion apps by hand, and the category grows
weekly. A curated list is a filter for a phone whose app store is wide open, and
the list will always trail. The fix has to be a *rule*, with the list carrying
only what the rule misses.

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
| 1 | `isProtected` — drawbridge, allowed browsers, `exempt_packages`, `NEVER_TOUCH` | **keep** | no |
| 2 | Local **allowlist** | **keep** | no |
| 3 | Preinstalled (`isSystemPackage`) | **keep** | no |
| 4 | Local **blocklist** (`blocked_packages`) | **remove** | no |
| 5 | Browser rule | existing behaviour | no |
| 6 | Store **category** in `GAME_*` or `DATING` | **remove** | yes |
| 7 | Store **rating** not in `allowed_ratings` and not in `neutral_ratings` | **remove** | yes |
| 8 | Install lock: outside the closed set | **remove** | no |
| 9 | otherwise | **keep** | — |

Branches 6 and 7 are the new ones. Everything else is today's behaviour, in
today's order.

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

### Three rating outcomes, not two

- **`allowed_ratings`** → keep. `PEGI 3` and its equivalents.
- **`neutral_ratings`** → *fall through to branch 8*, i.e. the rating says
  nothing and the rest of drawbridge decides. This is `Parental guidance`, and it
  is a third state rather than a lenient second one: 15 of 35 ordinary apps carry
  it, so treating it as blocked takes half the phone, and treating it as allowed
  would silently exempt an entire band from the rule.
- **anything else** → remove.

Both lists live in the **signed policy**, not in the app:

```json
"app_ratings": {
  "store_region": "BE",
  "allowed_ratings": ["pegi 3", "everyone", "usk: all ages", "rated 3+"],
  "neutral_ratings": ["parental guidance"],
  "blocked_category_prefixes": ["GAME_"],
  "blocked_categories": ["DATING"]
}
```

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

## The chatbot toggle

Chatbots are **blocked by default and restorable by one option**, in the shape
the streaming option already has.

- `dist/lists/chatbots.txt` — the domains, blocked in the DNS filter and
  therefore in every browser including herald.
- `blocked_packages` — the app ids.
- An option whose `allowed_domains` **mirror that list exactly** and whose
  `exempt_packages` name the same apps. `policytool.py sign` already refuses to
  sign when a list and its option's `allowed_domains` disagree, so the mirror is
  enforced rather than remembered.

**Grok is never in the option.** It is blocked by package and by domain, and it
is deliberately absent from both `allowed_domains` and `exempt_packages`, so the
toggle cannot restore it.

**That creates a signing constraint worth stating before it bites.** The mirror
check compares a list against its option, so Grok's domains cannot sit in
`chatbots.txt` — the check would demand they be restorable, which is the one
thing they must not be. Grok's domains go in a separate list with no option
behind it; `ai-companions.txt` already has that shape.

Blocked in the web filter *as well as* by package is the point, and the reason is
the one policy 55 established for Ecosia: an app id stops the app, and only a
domain rule stops the same service reached through a browser. Both, or neither
works.

---

## The "what's blocked" page

`site/why-blocked/` exists and is generated by `tools/convert_blocklist.py` from
`site-src/block-list.md`. Today it lists domains, which was honest when the list
*was* the policy and stops being honest here.

The page becomes an explanation of the **rule**, in this order:

1. What is allowed: apps rated for everyone, that are not games and not dating.
2. What is blocked and why: everything rated above PEGI 3, all games, all dating,
   and the curated list.
3. What a parent can switch back on: the options, chatbots among them.
4. The curated list, last — as the exceptions the rule misses rather than as the
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

The list stops growing weekly, which is the whole gain: a new companion app now
has to be mis-rated PEGI 3 *and* filed outside Entertainment-adjacent categories
to get through, and most are not.

**Allowlist.** New, and needed because of branch 7: Strava, Audible, Spotify,
Netflix, Zoom, eBay and RTBF Auvio are *Parental guidance*, so they fall through
to the rest of policy — but a household that wants one of them kept regardless of
any future rule needs somewhere to say so. It is device-local, like the browser
choice, because "this family cycles" is not something a signed document can know.

It sits at branch 2, above the blocklist, and can only *keep* an app. It cannot
remove one, and it cannot override `isProtected`.

---

## What is not decided

- **Where the chatbot toggle's app ids come from.** ChatGPT, Claude, Gemini and
  Copilot are general-purpose tools rather than companions, and their ratings have
  not been checked. If they are PEGI 3 they pass branch 7 anyway and the toggle is
  only about whether the household wants them; if they are *Parental guidance*
  they fall through to branch 8 and the toggle is doing real work. **Measure
  before writing the option.**
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

1. `tools/` curation script — fetch, parse, cache, and print what the rule would
   do over a large app list. No phone involved. This is what validates the
   thresholds.
2. Policy fields and the two lists, signed.
3. `StoreMetadata` + cache in the DPC, with Diagnostics reporting the unverified
   count.
4. Branches 6 and 7 in `AppBlocker`, in the order above.
5. The chatbot option and its domain list.
6. The website page.

Nothing here reaches a phone before step 1 has been run against a few hundred
apps and the result looked at by a human.
