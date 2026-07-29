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
| `allowed_browser_package` | The one browser allowed to exist. Every other browser is removed or hidden. |
| `exempt_packages` | Escape valve for a device-specific app that would otherwise be caught. |
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

A policy can offer named variants — "Everyday", "Schoolwork only" — chosen on the
device behind the parent's PIN, the same authority that removes drawbridge. Each
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
      "description": "The usual rules."
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

herald is not profile-aware: it keeps filtering on the base policy. On a managed
device that costs nothing, because a profile's stricter lists are enforced at the
DNS layer, which the browser's traffic goes through anyway.

### Search engines are a filtering decision

Safe search is forced by rewriting the engine's hostname at the DNS layer, and
only **Google, Bing and DuckDuckGo** publish a hostname to rewrite to. Every
other engine serves image results from its own CDN, which no domain blocklist
covers — its safe-search setting is a cookie the user controls, not something
this system can enforce.

That is why the default is DuckDuckGo, why the shipped list is Google, Bing,
DuckDuckGo, Brave Search, Qwant, Ecosia, Startpage and Kagi, and why **Yandex and
Baidu are absent rather than merely unselected**. Narrowing `search_engines` to
the first three is the strict setting; adding an engine to that list is a
decision to trust its own safe search.

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

## Signing and publishing

```bash
python3 tools/policytool.py sign --key-id drawbridge-2026-07
python3 tools/policytool.py verify
git add dist/ && git commit -m "policy: block ..." && git push
```

Devices pick it up within a day.

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

Release URLs point at `/releases/latest/download/`, so the QR and the policy do
not change from one release to the next.

> **Do not mark the newest release as a pre-release or leave it a draft.**
> GitHub's `/releases/latest` skips both, so `/latest/download/...` would fall
> back to an older release — or 404 if every release is flagged. That silently
> breaks QR provisioning and herald's auto-install. Older releases may be flagged
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
gh release create vX.Y.Z dist/release/*.apk dist/release/SHA256SUMS \
    dist/release/provisioning-qr.json
```

`assembleRelease` builds **both editions** — six herald APKs, since the mono
flavour was added. `tools/stage-release.sh` is what keeps the standard edition's
published filenames stable, because `required_apps` pins them by URL and a
flavour dimension would otherwise rename them; it fails loudly if any APK the
signed policy names is missing or stale. Run it after each build step.

CI still runs tests and lint on every push, which is what it is good for.

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
