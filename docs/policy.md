# Changing what is blocked

Everything both apps enforce comes from one signed JSON document, published at a
fixed HTTPS URL. Updating policy is: edit the file, sign it, push it. No backend,
no account, no app rebuild.

Devices poll daily. drawbridge also re-checks whenever its filter service starts,
and either app can be told to check now from its settings screen.

## Editing

`dist/policy.json` is the source. **Increment `version` on every change** —
devices reject any document whose version is lower than the one they already
hold, which is what makes replaying an old, permissive policy fail.

```jsonc
{
  "version": 2,
  "dns": {
    "encrypted_upstream": "tls://all.dns.mullvad.net",
    "upstreams": ["94.140.14.15", "94.140.15.16"],
    "enforce_safe_search": true,
    "strip_https_records": true,
    "block_encrypted_dns": true
  },
  "blocklists": [
    { "id": "adult", "url": "https://...", "format": "hosts", "category": "adult" }
  ],
  "blocked_domains": ["extra.example"],
  "allowed_domains": ["school.example"],
  "blocked_packages": ["com.instagram.android"],
  "allowed_browser_package": "app.drawbridge.herald",
  "exempt_packages": [],
  "browser": {
    "default_search_engine": "duckduckgo",
    "blocked_url_patterns": ["reddit\\.com/r/(gonewild|nsfw)"]
  }
}
```

| Field | Effect |
|---|---|
| `dns.encrypted_upstream` | Where queries actually go, over DNS-over-TLS. Encrypts the hop, so the local network cannot read the lookups or forge answers. Mullvad's `all` profile also blocks adult, gambling, social, ads and trackers at their end. |
| `dns.upstreams` | Plain-DNS fallback. Bootstraps the encrypted upstream's hostname and takes over if it is unreachable — keep it a *filtering* resolver so the failure mode is a narrower filter, not an open one. |
| `blocklists` | Domain lists downloaded and compiled on device. `format` is `hosts` or `domains`; Adblock-style `\|\|domain^` lines are tolerated in either. |
| `blocked_domains` | Extra domains on top of the lists. Suffix matching: `example.com` covers `www.example.com`. |
| `allowed_domains` | Wins over everything else, in the DNS filter and in herald alike. Use it to carve an exception out of a bulk list — and see the note below, because it is also what keeps the filter able to update itself. |
| `blocked_packages` | Apps drawbridge removes on sight. |
| `allowed_browser_package` | The browser tapped links are handed to, and the first one installed. Always a member of the allowed set. |
| `allowed_browser_packages` | Every browser allowed to exist. Anything else that registers a browser intent filter is removed or hidden. **Must agree with `required_apps`** — a browser named in one and not the other is installed and removed on a loop. Empty means "just `allowed_browser_package`". |
| `exempt_packages` | Escape valve for a device-specific app that would otherwise be caught. |
| `curfew` | A nightly window with no internet at all. **Drafted, not enforced** — see below. |
| `app_update` | drawbridge's own APK. Same shape as a `required_apps` entry. Installed only when its `version_code` is **higher** than what is running, so pinning the current version is a deliberate no-op. |
| `required_apps` | APKs drawbridge installs if missing — herald above all. Pinned by SHA-256, and filtered by `abi` so one policy serves all of herald's per-ABI splits. |
| `browser.blocked_url_patterns` | Regexes matched against the full URL — path-level rules DNS cannot express. herald only. |
| `browser.default_search_engine` | Selected until someone picks another in herald's settings, after which the choice is theirs. Matched loosely, so `duckduckgo` finds `ddg`. |
| `browser.search_engines` | The engines herald offers at all. Anything not named is hidden, including whatever the phone's locale would otherwise add; anything named that Mozilla's catalogue lacks is added by herald. |

### A filter that blocks its own updates stops being a filter

The blocklists are third-party and change daily, and nothing stops one of them
adding a host the *filter itself* needs. If that happens there is no error
anywhere: uBlock Origin keeps working on whatever lists shipped in the APK,
drawbridge keeps enforcing whatever policy it last fetched, and both quietly
stop getting newer ones.

`allowed_domains` therefore carries the update paths, and they are worth
recognising as load-bearing rather than as ordinary exceptions:

| Host | Needed by |
|---|---|
| `raw.githubusercontent.com` | the signed policy, the curated lists, hagezi's lists |
| `blocklistproject.github.io` | the adult and gambling lists |
| `ublockorigin.github.io`, `ublockorigin.pages.dev` | uBO's own filter lists |
| `pgl.yoyo.org` | Peter Lowe's list, enabled in uBO by default |
| `publicsuffix.org` | uBO's public suffix list |
| `malware-filter.gitlab.io`, `malware-filter.pages.dev`, `curbengh.github.io` | uBO's URLhaus list |

They are exact hostnames, deliberately. `allowed_domains` matches suffixes, so
an entry for `pages.dev` would exempt every site anyone can host on Cloudflare
Pages. For the same reason `cdn.jsdelivr.net` is *not* on the list: it is a
general-purpose CDN that will serve any file in any npm package or GitHub repo,
and for every list uBO enables by default it is only ever a mirror — dropping it
costs a fallback, not an update.

If you enable a filter list in uBO's dashboard that is off by default —
EasyList's own servers, AdGuard, and so on — add its host here too, or it will
be the one list that never updates.

## Profiles: named variants of one policy

> **The document says "profile"; drawbridge's screen says "policy".** From
> inside, a profile is a variant of the one signed document. From outside, to
> whoever is holding the phone, "the policy this phone runs" is the only phrase
> that means anything — nobody sets up a child's first phone thinking about
> variant selection. The field names below are what you write in the JSON.

A policy can offer named variants — "Everyday", "Schoolwork only" — chosen on the
device behind the parent's key, the same authority that removes drawbridge. Each
profile overrides the fields it names and inherits the rest, so it reads as a
diff rather than a second policy that can drift out of step:

```jsonc
{
  "version": 12,
  "default_profile": "everyday",
  "profiles": [
    {
      "id": "everyday",
      "name": "Everyday",
      "subtitle": "For a first phone, roughly 10 to 14",
      "description": "The usual rules.",
      "name_i18n": { "nl": "Alledaags", "fr": "Au quotidien" },
      "subtitle_i18n": { "nl": "Voor een eerste telefoon" },
      "description_i18n": { "nl": "De gewone regels." }
    },
    {
      "id": "schoolwork",
      "name": "Schoolwork only",
      "description": "Only approved apps stay installed.",
      "allowed_packages": ["app.drawbridge.herald", "com.google.android.calculator"],
      "dns": { "upstreams": ["94.140.14.15"], "encrypted_upstream": "tls://all.dns.mullvad.net" }
    }
  ]
}
```

`name`, `subtitle` and `description` are the three lines the picker shows, in
that order: which profile this is, who it is for, and what it actually does.

### Words in the policy carry their own translations

drawbridge's own screens are translated in the APK, but a profile's name and
description come from *this document*, so switching the app to Dutch would
otherwise leave half the screen in English. Every human-readable field therefore
has an optional `_i18n` sibling keyed by two-letter language code, as above. A
missing entry falls back to the untranslated field rather than blanking, so a
policy can add a language without every profile being retranslated in the same
edit — and an older install reading a newer policy sees English rather than
nothing.

A profile may override `dns`, `blocklists`, `blocked_domains`, `allowed_domains`,
`blocked_packages`, `allowed_packages` and `exempt_packages`. An empty list is an
override, not an absence — `"blocked_packages": []` means "block nothing", which
is what makes a genuinely looser profile expressible.

**`allowed_packages` flips app control to allowlist mode**: any *user-installed*
app outside the list is removed. Preinstalled and system apps are deliberately
left alone — no allowlist a parent writes will name the hundred packages an
Android build needs, and a list that forgets the dialer or the keyboard would
take the phone apart. Preinstalls stay reachable through `blocked_packages`,
which hides rather than uninstalls and is therefore reversible.

**Switching is one-way for apps.** Choosing a stricter profile removes what it
does not allow, immediately. Choosing the looser one again does not reinstall
anything — that is a property of removal, not of profiles. The device asks for
confirmation and says so before applying.

**herald follows the same selection.** On a managed device it asks drawbridge
which profile and which options are in force, so the browser and the DNS layer
enforce the same reading of this document. The standalone browser has nothing to
ask and follows `default_profile` and the options' own `default_enabled`. See
[design-decisions](design-decisions.md#herald-reads-drawbridges-selection-or-it-enforces-a-different-policy).

## Options: one switch each

The choice a parent actually wants to make is rarely "strict or relaxed" but
"everything as it is, except this one app my child's class group runs on".
Expressing that as a second profile means maintaining two near-identical copies
of the policy that drift. An option keeps one policy and adds a switch:

```jsonc
{
  "options": [
    {
      "id": "whatsapp",
      "name": "Allow WhatsApp",
      "recommended_age": 14,
      "description": "Lets the WhatsApp app run and reach its servers.",
      "default_enabled": false,
      "exempt_packages": ["com.whatsapp", "com.whatsapp.w4b"],
      "allowed_domains": ["whatsapp.com", "whatsapp.net", "wa.me"]
    }
  ]
}
```

**An option only ever widens what is permitted.** It can exempt packages from
removal, add to an allowlist a profile has already turned on, and name domains
that must resolve. It cannot block anything. That is what makes the order of
application irrelevant — profile first, then options — and makes the effect of
switching one off easy to state: the base policy, unchanged.

Which means **an option that allows something only means anything if the base
policy blocks it**. `blocked_packages` has to name `com.whatsapp`, and
`blocked_domains` has to name `whatsapp.com`, or the switch does nothing whether
it is on or off. Get one of the two and not the other and you have an option that
half works: the app installs but cannot reach its servers.

An allowed app whose servers are on a blocklist is an app that opens and does
nothing, so `allowed_domains` belongs with the package rather than in the base
policy — putting it in the base would allow those hosts whether the switch is on
or not.

| Field | Effect |
|---|---|
| `recommended_age` | Shown beside the name as "14+". Advice, not enforcement: nothing on the device knows how old its owner is. |
| `default_enabled` | Whether it is on before anyone has been asked. A device that has been asked stores its own answer, and an empty stored list means "the parent turned everything off", not "nobody has chosen". |
| `exempt_packages` | Spares those packages from the app blocker, including from `blocked_packages`. This is the field that does the work. |
| `allowed_packages` | Added to the allowed set, but **only** when the running profile is already in allowlist mode. An option cannot switch allowlisting on. |
| `allowed_domains` | Must resolve while the option is on. Wins over blocklists, as allow rules always do. |

Switching an option **on** applies immediately — nothing is downloaded, and
nothing can get stricter. Switching one **off** is asked about first, because
taking an app back means uninstalling it, and switching the option on again does
not reinstall it.

A stored option id the policy no longer offers is dropped, so a relaxation cannot
outlive the option that justified it.

### Search engines are a filtering decision

Safe search is forced by rewriting the engine's hostname at the DNS layer, and
only **Google, Bing and DuckDuckGo** publish a hostname to rewrite to. Every
other engine serves image results from its own CDN, which no domain blocklist
covers — its safe-search setting is a cookie the user controls, not something
this system can enforce.

That is why the default is DuckDuckGo and why the shipped list is **Google, Bing,
DuckDuckGo, Ecosia and Kagi**. Narrowing `search_engines` to the first three is
the strict setting; adding an engine to that list is a decision to trust its own
safe search.

**Brave Search, Qwant and Startpage were dropped on 2026-08-10**, having been
shipped until then. Brave can only be forced with a `safesearch` cookie, and the
vendor's own answer for filters rewrites the Cookie header behind TLS
interception, which this project will not do. Startpage searches by POST
deliberately, so there is no parameter to set. Qwant documents one that reference
implementations record as not heeded. See
[SearchEngineCatalogue](../herald/src/main/java/app/drawbridge/herald/search/SearchEngineCatalogue.kt).

**Dropping an engine from the browser is only half of it, which is what policy 37
is about.** An engine herald does not offer is still a website: its name typed
into the address bar reaches it, unfiltered, and Yandex being *absent* from the
list never stopped anyone from visiting Yandex. `dist/lists/search.txt` blocks
the engines a person can name — the three dropped, the majors never offered, the
independents, and the ones marketed on not filtering.

It also blocks the **unforced front doors of the engines that are allowed**, and
that half matters more: `html.duckduckgo.com`, `lite.duckduckgo.com`,
`cn.bing.com` and `encrypted.google.com` all resolve and match none of
`DnsFilter`'s rewrite rules, so each one was the allowed engine with its safe
hostname skipped. They are blocked in the policy rather than rewritten in
`DnsFilter` on purpose: a list entry reaches every phone within three hours, and
a code change needs a release that Play Protect will not let a deployed phone
install.

**None of this closes the set.** Anyone can run a SearXNG instance on any
hostname in minutes. The list stops the engines someone can name, and the
backstop for the rest is that the adult and gambling lists block the
destinations — an unfiltered engine returns a result list, and the click still
fails. [blocklist-notes](blocklist-notes.md) records what was left out.

**Kagi is the exception worth understanding.** Its settings live on the account,
server side, rather than in a cookie — so unlike every other engine here, what it
filters is not something the person searching can switch off. Its
[Family plan](https://help.kagi.com/kagi/plans/family-plan.html) (up to six
members) adds parental controls on top: a safe-search filter that excludes adult
material, lenses that can restrict a child's account to a whitelist of sites
rather than the open web, and content moderation on its AI features.

That makes Kagi the one engine in this list whose filtering drawbridge cannot
enforce *and* whose filtering cannot be undone from the phone, provided the
account is the parent's to manage. It is a paid service, and it returns nothing
useful until someone is signed in.

### Pinned and unpinned lists

A blocklist entry may carry a `sha256`. Lists hosted in this repo are pinned, and
`policytool.py sign` recomputes their hashes automatically so the two can never
drift apart.

The large upstream lists (HaGeZi, Block List Project) are deliberately **not**
pinned: they are rebuilt daily, and pinning them would mean re-signing the policy
every day. They are protected by HTTPS and by the signed policy that names them.
Unpinned lists are re-fetched once a day; pinned ones only when their hash
changes.

### A list's URL follows the channel it is signed on

The dev channel gives the *document* a staging path, but a document is a set of
URLs and the lists it names are fetched separately. A policy signed on `dev` that
still named `main`'s copy of a list could not test a change to that list at all:
the file does not exist on `main` yet, `PolicyStore` drops an unreachable source
and compiles the rest, and the device quietly filters less than the document
promises.

So `sign` **rewrites the URL of every list this repo hosts to the branch it is
run on**, and prints each one it moves. Signing on `main` produces `main` URLs;
signing on `dev` produces `dev` URLs. On any other branch it changes nothing,
since a policy signed there names a branch that may never be pushed.

**It is derived rather than typed, and that is the point.** Hand-editing the
branch into a URL is the obvious version and the dangerous one, because it
survives a merge — `dev`'s policy landing on `main` would point every alpha phone
at `dev`'s lists. Re-signing on `main` is now enough to undo that, and re-signing
is already the documented step after any edit.

### A signed policy is not necessarily a working one

The signature covers what the document *says*, not whether those URLs still
exist. HaGeZi moved `domains/` to `wildcard/` and two blocklists 404'd on every
device until someone happened to click one — silently, because `PolicyStore`
logs the failure, falls back to a stale cache if it has one, and compiles
whatever is left.

`sign` therefore fetches every URL the document names and **refuses to sign a
dead third-party blocklist**. Two things are a warning rather than an error,
because both legitimately 404 before the corresponding push:

- lists this repo hosts, which are pinned from the working tree and do not
  exist at their published URL until the commit lands;
- `required_apps` and `app_update`, which point at `/releases/latest/download/`
  and 404 until the assets are uploaded. Publishing the release first is the
  documented order, so a warning here means you have it backwards.

`--skip-url-check` signs without the network.

**That only helps on the day you sign**, which is the gap: a policy that needs no
edits can rot for months, and nothing on the device reports it. So the same check
runs from `verify`:

```bash
python3 tools/policytool.py verify --check-urls
```

No private key, no new signature, no file rewritten — safe to run on a schedule,
and **monthly is about right**, since what it catches is upstream restructuring
and that happens on nobody's timetable.

It applies a **stricter rule than signing does**, deliberately. The two warnings
above exist because the documented order writes the policy before pushing the
commit and uploading the assets. A *published* policy has no such excuse: every
URL in it is one devices are fetching right now, so a 404 anywhere is fatal.

## Signing and publishing

```bash
python3 tools/policytool.py sign --key-id drawbridge-2026-07
python3 tools/policytool.py verify
git add dist/ && git commit -m "policy: block ..." && git push
```

Devices pick it up within three hours, on the periodic poll.

**But not for the first five minutes, however hard you ask.**
`raw.githubusercontent.com` serves the policy with `cache-control: max-age=300`,
so for five minutes after a push an edge can still hand back the previous
document. A phone checked inside that window reports the *old* version and says
it succeeded, because it did — it fetched a document, and the document was stale.

This cost a debugging round on 2026-08-12, when policy 37 was pushed and a phone
went on reporting 36 in both apps. Nothing was wrong. Two things came out of it:
the manual check now sends `Cache-Control: no-cache` (the scheduled poll does
not, since it runs hourly-ish and cannot care), and Diagnostics now prints
`policy checked`, `policy succeeded`, `policy error` and the policy URL, so the
next person can tell a failure from a stale success without a cable.

Note the no-cache header is a request, not a guarantee: some CDNs deliberately
ignore request-side no-cache. If a phone still reports the old version inside
five minutes, wait rather than debug.

### Cutting a release without disturbing the alpha

**The rule that matters: `required_apps` resolves herald through
`/releases/latest/download/`.** Whichever GitHub release carries the **latest**
flag is what every phone on `main` installs. So a herald release can change the
alpha even when drawbridge does not move at all.

A dev-channel release therefore goes:

1. Build herald, then drawbridge. Order matters — see below.
2. `tools/stage-release.sh`.
3. `gh release create <tag> --prerelease --latest=false --target dev`, then
   **upload the assets one at a time** with `gh release upload`. Creating a
   release with seven assets attached failed mid-upload on 2026-08-13 with a
   404, and `gh` rolled the whole release back; uploading individually costs one
   file instead of all of them, and can be retried.
4. Pin the new APKs in `required_apps` at their **versioned** URLs, never
   `/latest/`, and re-sign.

`v0.2.5` holds `latest` and should keep it until a herald build is meant to reach
the alpha. That is the single flag standing between the dev channel and every
tester's phone.

**Two fields never travel between branches.** `app_update` and `required_apps`
name builds, and signing does not rewrite them the way it rewrites blocklist
URLs. Porting policy *content* from `dev` to `main` means copying everything else
and leaving those two alone.

## The signing key

Generated once:

```bash
python3 tools/policytool.py genkey --key-id drawbridge-2026-07
```

The private key lands in `keys/` (git-ignored). **Back it up offline.** Its
public half is written into `policy/src/main/assets/drawbridge/trusted-keys.json`
and compiled into both APKs, so losing the private key means no device can ever
be given a new policy again without reinstalling the apps.

To rotate: generate a new key id, ship a release containing both public keys,
wait for devices to update, then sign with the new key and drop the old one.

### Why ECDSA P-256 and not Ed25519

`java.security` only exposes Ed25519 from API 33; these apps support API 28. Using
P-256 keeps signature verification on the platform provider with no third-party
crypto library in an app that runs an always-on network service.

## Releases are cut locally, not in CI

There is deliberately no release workflow. CI cannot produce a correct release
here, for two reasons that compound:

- The **policy signing key lives offline**, so CI cannot sign a policy.
- `required_apps` **pins herald by checksum**, and Android builds are not
  byte-reproducible — so APKs built in CI would never match the hashes in the
  signed policy, and every device would refuse the download.

A workflow that publishes on a tag is worse than none: it silently replaces
correctly-signed assets with ones that cannot be installed. One did exist and was
removed after it fired on `v0.1.0` and had to be cancelled.

Cut a release like this, in this order:

Release URLs point at `/releases/latest/download/`, so the policy does not change
from one release to the next.

> **Do not mark the newest release as a pre-release or leave it a draft.**
> GitHub's `/releases/latest` skips both, so `/latest/download/...` would fall
> back to an older release — or 404 if every release is flagged. That silently
> breaks herald's auto-install. Older releases may be flagged
> freely; only the newest one matters. Only rebuild herald when herald has
actually changed — a rebuild alters its hash and forces a policy re-sign for an
otherwise identical binary.

```bash
./gradlew :herald:assembleRelease            # 1. herald first — only if it changed
tools/stage-release.sh                       #    names the APKs and checks the pins
# 2. hash the APKs into required_apps in dist/policy.json, bump version
python3 tools/policytool.py sign --key-id drawbridge-2026-07
cp dist/policy.signed.json policy/src/main/assets/drawbridge/default-policy.json
./gradlew :dpc:assembleRelease               # 3. drawbridge last, carrying that policy
tools/stage-release.sh                       #    again, now that dpc exists
gh release create vX.Y.Z dist/release/*.apk dist/release/SHA256SUMS
```

`assembleRelease` builds **both editions** — six herald APKs, since the mono
flavour was added. `tools/stage-release.sh` is what keeps the standard edition's
published filenames stable, because `required_apps` pins them by URL and a
flavour dimension would otherwise rename them; it fails loudly if any APK the
signed policy names is missing or stale. Run it after each build step.

CI still runs tests and lint on every push, which is what it is good for.

## Curfew: an evening with no internet

> **The curfew is enforced as of 2026-08-12, but not from here.** It is chosen on
> the phone, under *Disconnect philosophy* on the configuration screen, and
> stored in the device's own preferences — because the hours belong to one
> household and this document is signed for everybody. `Policy.curfew` still
> parses and is still a reasonable place to *suggest* a default; nothing reads it
> for enforcement. The schema below is therefore the shape of the setting, and
> the field remains available if a profile ever wants to propose one.

```jsonc
{
  "curfew": {
    "start": "21:00",
    "end": "07:00",
    "days": ["mon", "tue", "wed", "thu", "sun"],
    "allowed_packages": ["org.thoughtcrime.securesms"],
    "enabled": true
  }
}
```

| Field | Effect |
|---|---|
| `start`, `end` | Local wall-clock `HH:mm`, 24-hour. Start is inclusive, end exclusive. An `end` earlier than `start` means the window crosses midnight. |
| `days` | Days the window **starts** on — `mon`, `monday`, any case. Empty means every day. |
| `allowed_packages` | Packages that keep working during the window. Needs API 29; below that it is ignored and the curfew is absolute. |
| `enabled` | Lets a policy carry a curfew without it being in force. |

A profile may override `curfew`, so a stricter "school nights" variant is a
field rather than a second policy.

**What it actually does** is turn on the always-on VPN's lockdown flag, which
drops every packet not going through the tunnel — and since this filter routes
only DNS into its tunnel, that is every packet. **Calls and SMS keep working**,
being carrier-side rather than IP.

Two things follow from that, and both are easy to get wrong:

- **`days` names the evening, not the night.** `21:00`–`07:00` on `fri` covers
  Friday evening *and* Saturday morning. Saturday is not named and starts
  nothing of its own. Listing every day of the week a child is asleep will give
  you one extra window, not the one you meant.
- **A curfew pins the clock.** A wall-clock window is meaningless if the clock
  can be edited, so a configured curfew applies `DISALLOW_CONFIG_DATE_TIME` and
  forces network time. Removing the curfew from the policy lifts both.

Removing a `curfew` field is a real operation, not an absence: the next time a
device applies the policy it lifts the lockdown and unlocks the clock. That is
deliberate — see
[design-decisions](design-decisions.md#the-window-is-evaluated-never-remembered).

## drawbridge updates itself the way it updates herald

`app_update` names drawbridge's own APK, and `UpdateWorker` installs it in the
same daily pass that installs herald — silently, being Device Owner, after
checking the SHA-256 against the pin.

The gate is the version code, and it is checked **before anything is
downloaded**:

```kotlin
if (update.versionCode <= versionCodeOf(appContext.packageName)) return UpToDate
```

That is what makes the circularity survivable. drawbridge cannot ship a policy
naming its own hash — hashing the APK changes nothing about the APK, but
rebuilding it to carry the new policy changes the hash. So the resolution is the
same one `required_apps` uses: **the APK ships one policy version behind**, and
the policy that names it is published afterwards.

Which gives the release order:

1. Build drawbridge carrying the *current* signed policy.
2. Publish the release, so `/latest/download/dpc-release.apk` is the new APK.
3. Write its `version_code` and SHA-256 into `app_update`, bump `version`, sign,
   publish.

Between steps 2 and 3 the published APK is newer than the policy describes.
Nothing goes wrong: devices still read the old `app_update`, whose version code
is not greater than what they are running, so they never reach the download.
Getting this backwards — policy first — is what breaks, exactly as it does for
`required_apps`, and for the same reason.

A policy that pins the version already installed is therefore a valid, inert
state, and it is the right thing to leave in place between releases.

## Build order matters when `required_apps` changes

`required_apps` pins herald's APKs by checksum, and every APK embeds a copy of
the signed policy. That makes the two circular, so releases have to go in this
order:

1. Build **herald** first.
2. Hash the resulting APKs into `required_apps` and sign the policy.
3. Build **drawbridge** last, so it ships the policy naming those hashes.

Rebuilding herald after step 2 changes its APK and invalidates the checksums —
devices would then refuse the download. The consequence is that herald's own
bundled policy is one version behind drawbridge's; harmless, since the bundled
copy only applies until the first network poll.

## What ships inside the APK

Each build embeds a signed copy of the policy and a ~250-domain seed blocklist,
so a freshly provisioned device filters before it has ever reached the network.
The bundled copy is verified through exactly the same path as a downloaded one.
Regenerate it after changing the policy:

```bash
cp dist/policy.signed.json policy/src/main/assets/drawbridge/default-policy.json
```
