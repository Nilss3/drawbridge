# drawbridge

Content filtering for an Android device — someone else's to manage, or your own
to lock down. Blocks adult content, gambling, social media, AI companion chats
and a chosen set of games — without an account, a backend, or a subscription.

Two apps in one repo:

| | | |
|---|---|---|
| **herald** | `herald/` | A real browser (GeckoView) that enforces the blocklist on every page and subresource it loads, with uBlock Origin built in. Tabs, bookmarks with folders and import/export, searchable history, saved passwords, reader view, day/night. |
| **herald mono** | `herald/` | The same browser, built for single-tasking: no tabs, every page in black and white, articles opened straight into reader view, and a deliberate pause before a page appears. A product flavour of herald, not a fork. |
| **drawbridge** | `dpc/` | The device policy controller: a device-wide DNS filter, app blocking, and the restrictions that stop the whole thing being switched off. One configuration screen — language, profile, options — and a key that seals it. English, Dutch and French. |
| *policy* | `policy/` | Shared library: the signed policy document, blocklist compilation, and the update poller both apps use. |

## herald mono

Same filtering, same ad blocking, same bookmarks and history — and a browser
stripped back, for a phone that should be dull to pick up:

- **No tabs.** One page at a time. Links that ask for a new window open in the
  page you are on, and anything that manages to create a tab anyway is collapsed
  back to one.
- **No colour.** Pages, images and video all render in black and white. A menu
  entry restores colour for the page you are on — for the graph or map that
  cannot be read without it — and it lapses the moment you navigate away.
- **Reader view by default.** Any page Gecko can strip down to its article
  opens that way. Turning it off on a page is remembered until you leave.
- **A two-and-a-half-second pause** before a page appears, saying "Pause to
  think while loading" and naming where you are going. The page loads
  underneath, so nothing is actually slower; the friction is the point.

It is a separate app with its own package, and a managed device gets both
installed. The policy names both as allowed browsers and pulls both down, so
which one gets opened is the child's choice, made one app icon at a time.

## Setting it up

drawbridge has one screen and one button. The screen shows what this phone is
allowed to do — the **policy**, and the **options** you can switch on top of it,
each with the age it is usually reckoned suitable from. The button is **Lock**,
and it does everything: applies the policy, starts the filter, and seals the
screen behind a key. Protecting the phone and locking it were never two
decisions, and splitting them into two buttons let a phone sit configured,
unlocked and unfiltered while looking finished.

Locking mints a **key**: twenty characters, shown once, never stored anywhere
readable, and the only way back into that screen. A new one is minted every time
you lock, so a key photographed once stops working at the next lock. There is no
reset — not by email, not by anyone — which means writing it down matters, and
also means *not* writing it down is a legitimate way to make a decision
permanent on purpose. The screen says so before it mints anything.

There is no PIN. There used to be, with the key demoted to a recovery code
behind it; that was one secret too many, and the six digits a parent can
remember needed lockout throttling that the key does not. See
[design-decisions](docs/design-decisions.md#the-pin-is-gone-and-the-key-is-the-whole-credential).

The lock screen also says **how long this phone has been protected**. That date
survives reboots and survives unlocking, and the only things that clear it are
removing drawbridge from inside the app or wiping the phone — so a phone that
was reset and quietly set up again says so, at a glance, however innocent it
looks.

They ship as two deliverables:

1. **Full package** — drawbridge + both browsers on a device drawbridge fully
   manages. Filtering happens at the DNS layer for *every* app, and again inside
   the browser — and the browser follows drawbridge's own switches, so turning
   *Allow WhatsApp* on is what lets WhatsApp Web load and turning it off is what
   stops it.
2. **Standalone browser** — herald on its own, for a phone that already has app
   blocking (Family Link or similar) and only needs the browser gap closed. With
   no drawbridge to ask, it follows the policy document's own defaults, which are
   the stricter reading.

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

**Ads.** uBlock Origin ships inside herald as a built-in extension, doing the
part a domain blocklist structurally cannot: request rules with URL and type
context, and cosmetic filtering to remove the hole in the page rather than leave
an empty box. It cannot be disabled or removed from inside the browser, and
herald exposes no way to install any other extension — the whole extension
surface is uBO's own popup and dashboard.

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
- **A factory reset removes everything, and nothing stops one.** Recovery mode or
  Settings, either works. Factory Reset Protection does *not* cover this: it is
  not armed on a fully managed device by default, tested on hardware on
  2026-08-10. What stands in for prevention is detection — the protected-since
  date on the lock screen, which a reset cannot forge.
- **Between boot and the filter starting**, DNS is briefly unfiltered. See
  [always-on VPN without lockdown](docs/design-decisions.md#always-on-vpn-runs-without-lockdown).
- **YouTube ads are served from the same domains as the videos**, so neither the
  DNS layer nor uBlock Origin's network rules separate them.
- **uBlock Origin's own settings can switch it off** for a site or entirely.
  That is the cost of shipping the dashboard rather than only the popup; the DNS
  layer and the shared blocklist are underneath it either way, so what a child
  can reach through this is advertising, not blocked content.

## Getting started

Requirements: JDK 21 (the Gradle daemon picks it up automatically via
`gradle/gradle-daemon-jvm.properties`), and the Android SDK with platform 36.

```bash
./gradlew :herald:assembleDebug :dpc:assembleDebug
```

Then:

- **[docs/install.md](docs/install.md) — installing it on a phone**
  ([Nederlands](docs/install.nl.md) · [Français](docs/install.fr.md)). Over USB
  from a computer, on any Android phone, with no factory reset.
- [docs/provisioning.md](docs/provisioning.md) — the same thing for developers,
  and why `tools/provision-adb.sh` exists
- [docs/policy.md](docs/policy.md) — changing what is blocked
- [docs/blocked-apps.md](docs/blocked-apps.md) — every blocked app, as a quick-reference list
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
art/          the illustrations every icon and hero image is derived from
site/         the project website — generated, do not hand-edit
site-src/     the website's source: content drafts and the sourced blocklist
tools/        policytool.py (sign policy),
              provision-adb.sh (provision a device over USB),
              make-artwork.sh (icons and scenes from art/),
              build-site.py (generates site/ from site-src/)
keys/         signing keys — never committed
```

Nothing under `art/` is read at build time. `tools/make-artwork.sh` derives the
adaptive-icon layers, herald's block-page scene and drawbridge's hero image from
it and writes them into the two apps; run it after changing a master and commit
what it writes. The two scenes are the same place by day and by night: herald's
block page carries both and turns with the phone's light or dark mode,
drawbridge's welcome screen keeps the night one.

## Licence

MIT. The DNS filter is written from scratch rather than adapted from DNS66 or
personalDNSfilter, both of which are GPLv3 — they were read as references, not
copied. GeckoView and Mozilla Android Components are MPL-2.0, which imposes no
constraint on this project's own licence.

herald ships an unmodified copy of **uBlock Origin**, which is GPLv3, under
`herald/src/main/assets/extensions/ublock/` with its own `LICENSE.txt`. It is
included as a separate program rather than built into herald's own code, so this
is aggregation and herald stays MIT. Its source is
[gorhill/uBlock](https://github.com/gorhill/uBlock); `tools/vendor-ublock.sh`
records the exact build and its hash. See
[design-decisions](docs/design-decisions.md#ublock-origin-ships-inside-the-apk).
