# drawbridge

Content filtering for an Android device you manage on someone else's behalf.
Blocks adult content, gambling, social media, AI companion chats and a chosen
set of games — without an account, a backend, or a subscription.

Two apps in one repo:

| | | |
|---|---|---|
| **herald** | `herald/` | A real browser (GeckoView) that enforces the blocklist on every page and subresource it loads. Tabs, bookmarks, history, saved passwords, reader view, day/night. |
| **drawbridge** | `dpc/` | The device policy controller: a device-wide DNS filter, app blocking, and the restrictions that stop the whole thing being switched off. |
| *policy* | `policy/` | Shared library: the signed policy document, blocklist compilation, and the update poller both apps use. |

They ship as two deliverables:

1. **Full package** — drawbridge + herald on a device drawbridge fully manages.
   Filtering happens at the DNS layer for *every* app, and again inside herald.
2. **Standalone browser** — herald on its own, for a phone that already has app
   blocking (Family Link or similar) and only needs the browser gap closed.

## How it filters

Three layers, deliberately overlapping, because each one has a gap the others cover:

**DNS.** An always-on `VpnService` routes only DNS into a local resolver. Blocked
names get NXDOMAIN, Google/Bing/YouTube get rewritten to their safe-search
hostnames, HTTPS/SVCB queries are answered empty so ECH is never negotiated, and
known DoH endpoints are black-holed by both name and IP. Whatever survives is
forwarded over DNS-over-TLS to a filtering resolver, so the local network can
neither read the lookups nor forge answers. Because it is DNS-only
there is no userspace TCP/IP stack to maintain — and because *every* app has to
resolve a name, it covers content rendered inside another app's embedded WebView,
which no browser can see.

**Browser.** herald checks every document load against the same blocklist and
shows a block page; a bundled web extension covers subresources. It exposes no
"secure DNS" setting and no `about:config`, so it cannot resolve names behind an
encrypted channel the DNS layer can't see.

**Apps.** drawbridge removes blocked packages the moment they finish installing,
and removes *any* browser other than herald — detected by intent filter, not by a
list of package names that would be out of date next month. Preinstalled browsers
that cannot be uninstalled are hidden instead. This is what makes DNS-only
filtering sound: with no other browser on the device, nothing is left that can
run its own encrypted DNS.

### Known gaps

- **Connections to hardcoded IPs with no DNS lookup** bypass a DNS-level filter.
  No mainstream app or site works this way; the well-known encrypted-DNS
  resolvers that do are black-holed explicitly.
- **A hardware recovery-mode wipe** removes everything. On a Google-certified
  device, Factory Reset Protection then demands an account that was previously on
  the device — which is why setup locks account changes with only the parent's
  account present. De-Googled ROMs have no FRP equivalent, so there is no
  backstop there at all.
- **Between boot and the filter starting**, DNS is briefly unfiltered. See
  [always-on VPN without lockdown](docs/design-decisions.md#always-on-vpn-runs-without-lockdown).
- **Ad blocking is domain-level.** Empty placeholder boxes remain, and YouTube
  ads are served from the same domains as the videos, so they are not blocked.

## Getting started

Requirements: JDK 21 (the Gradle daemon picks it up automatically via
`gradle/gradle-daemon-jvm.properties`), and the Android SDK with platform 36.

```bash
./gradlew :herald:assembleDebug :dpc:assembleDebug
```

Then:

- **[docs/install.md](docs/install.md) — installing it on a phone with a QR code**
  ([Nederlands](docs/install.nl.md) · [Français](docs/install.fr.md))
- [docs/provisioning.md](docs/provisioning.md) — the same thing for developers, plus adb
- [docs/policy.md](docs/policy.md) — changing what is blocked
- [docs/blocklist-notes.md](docs/blocklist-notes.md) — what is on the list, and what could not be blocked as asked
- [docs/removal.md](docs/removal.md) — taking it off again
- [docs/design-decisions.md](docs/design-decisions.md) — why it works the way it does
- [docs/handoff.md](docs/handoff.md) — current state, what is untested, what to do next

## Repository layout

```
herald/       the browser
dpc/          the device policy controller
policy/       shared: signed policy, blocklists, update poller
dist/         the published policy document and the lists it references
tools/        policytool.py (sign policy), qrpayload.py (provisioning QR)
keys/         signing keys — never committed
```

## Licence

MIT. The DNS filter is written from scratch rather than adapted from DNS66 or
personalDNSfilter, both of which are GPLv3 — they were read as references, not
copied. GeckoView and Mozilla Android Components are MPL-2.0, which imposes no
constraint on this project's own licence.
