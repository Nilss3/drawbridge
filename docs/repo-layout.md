# Where things are

The map of the repository: which folder holds what, and which of them you edit
by hand. It deliberately does not explain *why* anything is the way it is —
that is [design-decisions](design-decisions.md), and this file links to it
rather than repeating it.

Start with [handoff](handoff.md) if you are new: it says where the project
stands. This one says where the files are.

---

## The three Gradle modules

| Module | What it is |
|---|---|
| `policy/` | A library. The signed document, its verification, and the domain matcher. Both apps depend on it and neither owns it. |
| `dpc/` | **drawbridge** — the Device Policy Controller. The DNS filter, the app removal, the lock. |
| `herald/` | **herald** and **herald mono** — the browser, two editions from one source tree. |

### `policy/` — the part both apps share

```
policy/src/main/
  assets/drawbridge/
    default-policy.json      the signed document compiled into every APK
    default-blocklist.txt    263-domain seed list, for first boot
    trusted-keys.json        the public half of the signing key
  java/app/drawbridge/policy/
    PolicyManager.kt         the front door: isHostBlocked, isUrlBlocked
    PolicyStore.kt           everything on disk — envelope, list cache, compiled list
    PolicyConfig.kt          per-app configuration (which URL, which assets)
    ContentFilter.kt         a compiled policy: blocked ∪ allowed ∪ browser rules
    SelectionSource.kt       reads the parent's profile and option choices
    blocklist/               BlocklistFile, DomainSet, DomainHash
    crypto/                  PolicyVerifier, SignedEnvelope
    model/                   Policy, Curfew, AppRatings — the document schema
    net/Downloader.kt        list and APK fetching
    work/PolicyWorker.kt     the periodic poll
```

Domains are stored as hashes rather than strings — see
[design-decisions](design-decisions.md#blocklists-are-stored-as-hashes-not-strings).

### `dpc/` — drawbridge

```
dpc/src/main/java/app/drawbridge/dpc/
  vpn/DnsFilterService.kt    the always-on tunnel; reads the resolver, answers or forwards
  vpn/dns/                   DnsFilter (the decision), DnsMessage (the wire format),
                             EncryptedDnsClient (DNS-over-TLS)
  vpn/net/IpPacket.kt        just enough IP/UDP to read a query and write a reply
  apps/                      AppBlocker, PackageWatcher, BrowserSettings, InstallLockSettings
  apps/store/                the store rule: StoreCatalogue, StoreListing, StoreScan
  admin/                     Device Owner: provisioning, the admin receiver, the log
  curfew/                    CurfewController and its worker — the evening with no internet
  security/                  ParentKey and the lock timer
  update/                    AppInstaller — installs herald and drawbridge itself
  policy/SelectionProvider.kt  the content provider herald reads the selection from
  ui/                        MainActivity, LockActivity, RemoveActivity, DiagnosticsActivity,
                             UpdateActivity, Insets, Languages
```

### `herald/` — the browser

`src/main/` is the whole browser. `src/mono/` holds only what mono changes:
colours, themes and the app name. See
[design-decisions](design-decisions.md#herald-mono-is-a-flavour-not-a-fork).

```
herald/src/main/
  assets/extensions/blocklist/   the bundled web extension: subresource filtering, Shorts
  assets/extensions/ublock/      vendored uBlock Origin (tools/vendor-ublock.sh)
  java/app/drawbridge/herald/
    filter/                      HeraldRequestInterceptor, BlocklistExtension,
                                 BlockedPage, OfflinePage, Shorts, HeraldCard
    browser/                     BrowserFragment and the integrations hung off it
    components/                  Core, EngineProvider, UseCases — the Gecko wiring
    search/                      the engine catalogue and safe-search enforcement
    bookmarks/ history/ logins/ tabs/ settings/ downloads/ media/ ext/
```

---

## What ships

### `dist/` — the published artefacts

```
dist/
  policy.json           the document you edit by hand
  policy.signed.json    the same document, signed — this is what phones fetch
  lists/*.txt           the six blocklists this repo hosts
  release/              staged APKs (git-ignored) plus SHA256SUMS (tracked)
```

`dist/release/` holds **one channel's binaries at a time and git cannot tell you
which** — the APKs are ignored, so they do not change when the branch does. See
the rule in [handoff](handoff.md#where-it-stands).

### `site-src/` → `site/`

`tools/build-site.py` builds the second from the first. `site/` is what
Cloudflare Pages serves and is committed; do not edit it by hand.

- `site-src/block-list.md` — the human-readable account of what is blocked
- `site-src/app-names.json` — display names for the generated tables
- `site-src/installer/` — the WebUSB installer (`adb.js`, `installer.js`)
- `site-src/channel.txt` — which channel this branch builds
- `site/assets/dpc-<digest16>.apk` — the DPC binary the install page hands out,
  pinned by `app_update` in the policy

---

## The rest

| Folder | What it is |
|---|---|
| `tools/` | The workstation scripts. `policytool.py` (genkey/sign/verify), `stage-release.sh`, `build-site.py`, `provision-adb.sh`, `app-ratings.py`, `refresh-app-names.py`, `convert_blocklist.py`, `vendor-ublock.sh`, `make-artwork.sh`. `tools/corpora/` holds the drafting corpora for list work. |
| `docs/` | This folder. `handoff.md` first, then `design-decisions.md`, `policy.md`, `provisioning.md`, `removal.md`, `app-ratings.md`, `blocked-apps.md`, `blocklist-notes.md`, `reader-view-back.md`, `install.{md,fr,nl}`. |
| `keys/` | Both private keys — the policy signing PEM and the release keystore. Git-ignored, and losing either is unrecoverable. |
| `art/` | The eight masters every image is derived from, by `tools/make-artwork.sh`. |
| `images/` | Screenshots and device photos for the docs and the site. |
| `build/`, `*/build/` | Gradle output. Generated; ignore. |

---

## Every list, and which layer reads it

There are more of these than there look to be, and they are not all the same
kind of thing.

### Domains

| Where | What it is |
|---|---|
| `dist/policy.json` → `blocklists[]` | **Eleven URLs, not eleven lists of domains.** The document *names and pins* its sources; it does not contain them. Five are large upstream lists, six are this repo's own. |
| `dist/lists/*.txt` | The content of those six: `social`, `games`, `streaming`, `search`, `dating`, `ai-companions`. **These are the ones you edit by hand.** |
| `dist/policy.json` → `blocked_domains[]` | A handful of one-off domains, inline. For the case where a whole file is overkill. |
| `dist/policy.json` → `allowed_domains[]` | The domain allowlist. Wins over everything above. |
| `options[].allowed_domains` | Per-toggle allowlists: what comes *back* when a parent turns an option on. |
| `browser.blocked_hosts`, `browser.blocked_url_patterns` | herald-only extra blocks, applied at page load rather than at the resolver. Both empty today. |
| `policy/src/main/assets/drawbridge/default-blocklist.txt` | The seed compiled into every APK, so a phone filters before its first poll. |

**Why the document names lists instead of containing them:** the upstream lists
are hundreds of thousands of domains and rebuilt daily; `policy.json` is 35 KB
and signed. Inlining them would mean re-signing every day and shipping a
megabytes-large document through a signature check on every poll. Naming them
costs one extra fetch and lets the two move on their own schedules — which is
also why the repo's own lists are pinned by SHA-256 and the upstream ones are
not. See [policy](policy.md#pinned-and-unpinned-lists), and note that
`policytool.py sign` rewrites this repo's list URLs to the branch it runs on.

### Apps

| Where | What it is |
|---|---|
| `blocked_packages[]` | The 369 apps that are removed. |
| `exempt_packages[]` | Never touched, whatever else says (Signal). |
| `allowed_browser_packages[]` | The browser allowlist; `allowed_browser_package` names the default. |
| `app_ratings.allowed_packages[]` | Apps admitted past the PEGI/category rule. See [app-ratings](app-ratings.md). |
| `options[].exempt_packages` | What a toggle brings back. |

### And the page a reader sees

`site-src/block-list.md` is the human-facing account. `tools/convert_blocklist.py`
turns it into `site/why-blocked/`; the tables beside it are generated from
`dist/policy.json` at site-build time, which is what
`tools/refresh-app-names.py` keeps readable.

---

## Where a change lands

| To change… | Edit… | Then… |
|---|---|---|
| what is blocked | `dist/lists/*.txt` or `dist/policy.json` | `policytool.py sign`, commit, push |
| what the DNS filter does | `dpc/.../vpn/dns/DnsFilter.kt` | new drawbridge build |
| what the browser blocks at page load | `herald/.../filter/` | new herald build |
| the website | `site-src/`, then `tools/build-site.py` | commit `site/` |
| the seed list | `policy/src/main/assets/drawbridge/default-blocklist.txt` | new build of both apps |

A policy edit needs no build at all, which is the point of the split: the phone
picks it up on the next poll. Anything under a `src/` directory needs a release
— see [policy](policy.md#releases-are-cut-locally-not-in-ci).
