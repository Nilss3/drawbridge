# Design decisions

Points where the implementation departs from the original design notes, or where
the reasoning is not obvious from the code.

## Always-on VPN runs *without* lockdown

The design called for
`setAlwaysOnVpnPackage(admin, package, lockdownEnabled = true)`. That turns out
to be incompatible with a DNS-only filter, and the combination was verified to
break the device completely.

Lockdown means "drop any traffic that does not go through the VPN interface".
This filter deliberately routes only the resolver addresses into its tunnel, so
with lockdown enabled every non-DNS packet on the device is dropped: names
resolve, then every `connect()` fails with `EPERM`, for every app. The phone has
no working internet at all.

The two are only compatible if the VPN carries *all* traffic, which requires the
userspace TCP/IP stack that v1 explicitly set out to avoid.

**What holds the line instead:** always-on itself (the platform restarts the
service), `START_STICKY`, and `DISALLOW_CONFIG_VPN` so no other VPN can be
configured or the filter swapped out. **What is lost:** the window between the
network coming up at boot and the tunnel being established, during which DNS
falls back to the system resolvers unfiltered.

If that window ever matters, closing it means phase 2 — full packet capture,
built on NetGuard or RethinkDNS rather than from scratch.

## The curfew is that same lockdown, used on purpose

**Enabled 2026-08-12**, as one of three *disconnect philosophies* the parent
chooses on the configuration screen, above the policy: always offline, always
online, or a curfew. The mechanism below is unchanged; what changed is that
something now calls it.

### The philosophy is the parent's, so it is not in the policy

The signed document says what the *web* may contain. Whether this phone reaches
the web at all, and between which hours, is a property of one household — a
document signed by this project's key cannot carry "offline at nine on
weeknights" for somebody else's teenager. So `DisconnectSettings` lives in the
device's own preferences, next to the chosen profile and options, and
`Policy.curfew` becomes a default a document may suggest rather than the thing
enforced.

The schedule is two windows, weekdays and weekend, because that is the split
households actually run and because a full seven-day editor is a lot of screen
for two answers. Each is stored as the evening it *starts*, which is what makes
Friday 21:00–08:00 cover Saturday morning without Saturday being named.

### Offline means offline, and unlocking is what lifts it

Two rules, and the first is what makes the second unnecessary.

**Nothing is exempt from the lockdown, including drawbridge.** The first version
of this kept the DPC's own package out of it so the phone could still poll,
reasoning that a phone with no internet cannot hear about the setting that would
give it some back. That reasoning belonged to a design where the schedule came
from the signed document — and it did not survive the schedule becoming
device-local, which happened in the same commit. The way back online is a parent
unlocking and changing the setting, and that needs no network at all. Keeping the
exemption would have been a hole justified by a problem that no longer existed.

So the promise on screen — *this device cannot connect to the internet* — is
literally true, which is worth more than a background poll.

**The policy goes stale on a permanently offline phone, and that is fine.** The
blocklists exist to filter traffic, and there is none. A phone on a curfew polls
during its online hours like any other, and the moment it comes back online — at
the morning boundary, or when a parent unlocks — drawbridge asks for a refresh
rather than waiting up to three hours for the next scheduled one.

**And the curfew follows the lock, not the protection.** An unlocked drawbridge
is a parent working on the phone, and everything they unlocked to do — install
something, move data off, try a browser — needs a network. This is the same rule
as the app blocker, for the same reason: unlocking costs the key, and whoever
holds the key can remove drawbridge outright. It goes dark again at the next
lock.

What stays keyed on protection is the clock lock, because a wall-clock window is
only as trustworthy as the clock and a child does not stop being able to wind it
forward because a parent is halfway through changing a setting.

### It covers Bluetooth tethering, and cannot spare USB ethernet

Lockdown is a rule about the *user*, not about a network: every packet from every
app must go through the tunnel. So it covers Wi-Fi, mobile data, Bluetooth
tethering from a second phone and USB ethernet alike, without naming any of them
— which is the right shape, since a list of transports would be a list to forget
something from.

**That is also why "always allow ethernet over USB" is not offered.** The
exemption `setAlwaysOnVpnPackage` accepts is a set of *package names*; there is
no per-transport carve-out anywhere in the Device Owner API. The nearest
expressible thing is exempting particular apps, which is not the same request and
would leak on every transport rather than one. Asked for on 2026-08-12 and not
built, because it cannot be.

### What survives it: calls and SMS, and that is the whole list

The lockdown is a rule about IP traffic leaving an app, so **what survives is
what never enters Android's IP stack at all.**

- **Voice calls survive.** Circuit-switched calls never touch it, and VoLTE runs
  in the modem's IMS stack on its own bearer rather than as an app socket.
- **SMS survives**, for the same reason: it rides the signalling channel, or IMS
  when the carrier does SMS-over-IMS. Either way the Android networking stack
  never sees it, so neither does the tunnel.
- **RCS does not.** It is SIP and HTTP over the ordinary data connection, from an
  ordinary app UID — nothing about it is carrier signalling. With no route out it
  cannot even register, so the phone reports RCS as unavailable and Google
  Messages falls back to SMS or MMS.
- **MMS does not either**, and this is the one that surprises people. MMS goes
  over a dedicated APN, which sounds like an exemption and is not: it is still IP
  through the Android stack from an app UID, and the only exemption
  `setAlwaysOnVpnPackage` accepts is a list of package names. So picture messages
  and **group messages** — which are MMS, not SMS — do not go through.

The last one is worth saying out loud because it compounds: the RCS fallback for
exactly those messages *is* MMS, so both halves are gone rather than one
degrading into the other. It is a smaller loss than it looks — MMS is being sunset
in market after market as RCS replaces it — but a parent should hear it before
choosing the mode rather than discover it from a photo that never arrives.

**Verified and not.** The first two are mechanism, not observation, and match
what every always-on VPN does. The MMS claim is reasoning from the same
mechanism: Android's DPC documentation names no telephony carve-out from
lockdown, and the package allowlist is the only documented bypass — but **it has
not been tried on a handset**, and the emulator has no carrier to try it with.
One photo sent during a curfew settles it.

**One thing may survive that arguably should not: Wi-Fi calling.** VoWiFi builds
its own IPsec tunnel to the carrier's ePDG, and it is reported to bypass Android
VPNs rather than ride inside them. If that holds here, a phone with poor cell
coverage can still make calls over Wi-Fi while offline — which supports the
promise on screen rather than undermining it, since the promise is about the
internet rather than about the telephone. Also unverified.

### The radios stay on, and the reason is asymmetry

**Raised and rejected on 2026-08-12.** The suggestion was to switch Wi-Fi and
mobile data *off* while offline, and lock those settings — on the good reasoning
that apps behave better seeing no network than seeing a network that fails every
connection.

What Android actually offers is lopsided:

| | Available to a Device Owner? |
|---|---|
| Turn Wi-Fi off | Probably. `setWifiEnabled` always fails for ordinary apps from API 29, and Device Owners are documented as exempt. **Unverified here** — this project has been wrong about four such carve-outs already. |
| Turn mobile data off | **No.** The only API is `@SystemApi` behind `MODIFY_PHONE_STATE`, which a sideloaded DPC cannot hold. |
| Stop either being *changed* | Yes — `DISALLOW_CONFIG_WIFI`, `DISALLOW_CHANGE_WIFI_STATE`, `DISALLOW_CONFIG_MOBILE_NETWORKS`. But that freezes the switch, it does not turn it off. |

So the buildable version is Wi-Fi dark and mobile data connected-but-failing,
which is half the benefit and a new inconsistency — **the owner's call was that a
half-working version reads as buggy, and a phone that looks broken in one place
and fine in another is worse than one that is honestly offline everywhere.** It
also costs a parent the ability to join a network without unlocking.

Worth knowing before anyone revisits this: the lockdown already fails Android's
connectivity check, so the network is marked unvalidated and shows *No internet*.
Apps that read `NET_CAPABILITY_VALIDATED` therefore do see the phone is offline,
which is some of what switching the radios off was meant to buy.

The bug above is the feature. "Every non-DNS packet dropped, every `connect()`
failing with EPERM, in every app" is a broken phone as a permanent state and an
exact description of what an evening internet curfew should do. So the curfew
does not introduce a mechanism — it calls
`setAlwaysOnVpnPackage(admin, package, lockdownEnabled = true)` at the start of
the window and passes `false` at the end.

The consequences worth knowing:

- **Calls and SMS keep working.** They are carrier-side, not IP, so lockdown
  does not touch them. That is what makes this safe to leave running overnight
  on a child's phone, and it is a property of the mechanism rather than
  something the implementation had to arrange.
- **The allowlist is API 29+.** `setAlwaysOnVpnPackage` has an overload taking
  packages that survive lockdown — a messaging app a parent wants reachable at
  night. On API 28 the overload does not exist, so the allowlist is dropped and
  the curfew is absolute. That is the stricter reading, which is the right way
  to degrade.
- **No new manifest permission.** `INTERNET` was already declared for the policy
  fetch and the VPN, lockdown is a Device Owner API rather than a permission,
  and the clock lock below is a user restriction. The declared permission set is
  unchanged — which matters more than it sounds, because that set is the main
  thing Play Protect's classifier weighs. A feature this invasive costing zero
  permissions is worth knowing about before designing an alternative.
- **Failing to leave a curfew is worse than failing to enter one**, so both
  outcomes are logged, and `releaseDeviceOwnership` clears the always-on package
  outright. A removal that left lockdown set would hand back a phone with no
  internet and nothing on it still privileged enough to undo that.

### A wall-clock window needs a clock that cannot be edited

Otherwise the curfew is advisory: changing the device clock walks straight out
of it. `DISALLOW_CONFIG_DATE_TIME` covers date, time and time zone.

It is now applied on **every locked device**, curfew or not. It began here, but
a movable clock defeats two things that have nothing to do with curfews: it lets
the protected-since date be forged (wind back a year, lock, wind forward, and the
phone reports a year of protection it never had), and it is the standard way
round screen-time limits in Family Link and similar tools a parent may have
layered on top. drawbridge cannot enforce those, but it can stop the phone lying
to them. See [the clock is locked on every device](#the-clock-is-locked-for-a-curfew-and-for-a-lock-timer-and-this-section-used-to-claim-more).

Locking is not sufficient on its own, because forbidding edits only freezes
whatever the clock already said — a device whose clock was wrong when the
restriction landed would stay wrong. So `setAutoTimeEnabled` and
`setAutoTimeZoneEnabled` are set alongside it where the API exists (30+),
forcing network time rather than merely preventing changes.

Note that the device cannot reach an NTP server *during* a curfew, since
lockdown drops that traffic too. This does not matter: the RTC keeps running
across the window and across reboots, and the clock only needs to be right to
the minute.

### The window is evaluated, never remembered

Every run asks the policy what the window is, asks the clock where we are in it,
and sets the device to match. There is no "curfew is running" flag, because a
flag is a second source of truth that goes stale — after a reboot mid-window, a
policy that changed the hours, or an alarm that never fired.

That is also why a *null* curfew actively lifts lockdown rather than doing
nothing. Dropping the curfew from a policy has to release the last device that
applied it, and only an unconditional "set it to what the policy says now" does
that.

Boundaries are scheduled with `setAndAllowWhileIdle` rather than an exact alarm.
Exact alarms need `SCHEDULE_EXACT_ALARM` from API 31, which is a user-visible
grant and a Play-policy-restricted permission — a real cost for something that
does not need second accuracy. Doze may defer a boundary by a few minutes, and a
curfew starting at 21:03 instead of 21:00 is not a defect worth a permission
for. The alarm is a backstop rather than the mechanism: because state is
recomputed from the clock on every run, a dropped alarm is corrected at the next
boot or policy refresh instead of stranding the device.

### Named days are the days a window *starts* on

A curfew of 21:00–07:00 on `fri` runs from Friday evening into Saturday morning.
Saturday is not named and starts nothing, but Saturday's early hours still
belong to Friday's window. Naming `sat` as well would add a second window, not
extend the first.

This is the one piece of arithmetic with real edge cases, so
`nextChangeAfter` finds the next boundary by stepping a minute at a time over a
week rather than computing it directly. The direct version has to reason about
midnight crossings, unnamed days on either side, and the week wrapping — three
chances to be subtly wrong on exactly one day of the week. A week of minutes is
~10k comparisons of a pure function, at a once-per-boundary rate.

## The encrypted upstream uses DoT, not DoH

The upstream resolver is reached over DNS-over-TLS (`tls://all.dns.mullvad.net`),
not DNS-over-HTTPS, despite DoH being the more obvious choice.

Mullvad's DoH endpoint **only accepts HTTP/2** — verified: an HTTP/1.1 request is
closed without a response, an HTTP/2 request returns 200. Android's built-in
`HttpURLConnection` speaks HTTP/1.1 only, so a first implementation on top of it
failed on roughly every query and silently fell back to plain DNS. Making DoH
work would mean bundling a full HTTP/2 client into a service that stays resident
on the device permanently.

DoT gives exactly the same property — the local network can neither read the
lookups nor forge answers — over a protocol that is a TLS socket plus a two-byte
length prefix, using nothing beyond `javax.net.ssl`. Measured on device: 20
consecutive lookups, zero fallbacks, zero errors.

Two details that are easy to get wrong and are load-bearing:

- **`endpointIdentificationAlgorithm = "HTTPS"`** must be set explicitly on the
  socket. Without it the certificate is validated as a chain but never checked
  against the hostname, which leaves the connection open to exactly the
  interception DoT exists to prevent.
- **The upstream's own hostname is carved out of the blocklist** in
  `DnsFilter.decide`, before the block check. Every encrypted-DNS blocklist
  contains the public resolvers — that is what those lists are for — so without
  the carve-out the filter blocks its own upstream. It is also resolved over
  plain DNS rather than over the encrypted channel, because resolving the
  endpoint through itself is a deadlock.

The plain `upstreams` are still required: they bootstrap that hostname and take
over if DoT is unreachable. They are set to a *filtering* resolver so that losing
the encrypted hop degrades to a narrower filter rather than an open one.

## There is no release workflow

Releases are built and signed on a workstation, not in CI. The policy signing key
is meant to live offline, and `required_apps` pins herald's APKs by checksum —
so CI-built APKs, which are not byte-reproducible, could never match a policy
signed anywhere else. See [policy.md](policy.md) for the procedure.

## Policy is signed with ECDSA P-256, not Ed25519

`Signature.getInstance("Ed25519")` only exists from API 33, and these apps target
API 28. P-256 with SHA-256 is available through the platform provider on every
supported release, so signature verification needs no third-party crypto library
inside an app that runs an always-on network service. The envelope format,
rollback protection and key rotation are unchanged.

## HTTPS/SVCB queries are answered empty, not stripped from responses

The design called for stripping HTTPS/SVCB records (which carry ECH keys) out of
forwarded DNS responses. Doing that means rewriting a message whose answer
section is full of compression pointers into earlier offsets — every removal
shifts those offsets, and getting it subtly wrong corrupts answers in ways that
are very hard to diagnose.

Refusing the query instead — NOERROR with no records — achieves the same result:
no ECH configuration is ever delivered, so ECH is not negotiated. Clients fall
back to A/AAAA, which costs nothing. The parser in this codebase therefore reads
questions and *builds* answers, but never rewrites one.

## Every screen insets itself for the system bars

Both apps target API 36, and from API 35 the platform lays every app out edge to
edge with no way to opt out. `android:statusBarColor` is ignored, the bars are
transparent, and whatever the app draws at the top of its window is drawn
*underneath* the status bar.

The two apps were hit differently, and neither failure is visible below API 35:

- **herald** put its toolbar under the status bar, because a `NoActionBar` theme
  gives the layout the whole window.
- **drawbridge** kept its action bar in the right place — AppCompat still insets
  that — but the content view now starts at the top of the window and was drawn
  behind both bars, hiding the first two lines of every screen.

Each app therefore pads its own root: `View.applySystemBarInsets` in herald,
`View.applyScreenInsets` in drawbridge, which also adds `actionBarSize`. Padding
rather than margin, so the strip under each bar keeps the view's background —
which is why herald's roots carry the toolbar colour and its content views carry
the theme background. Both helpers no-op below API 35, where the decor view
consumes the insets first and reports zero.

Two consequences worth knowing:

- **The bar icon colours are pinned in the themes.** With transparent bars the
  platform picks icon colour from the theme, which is wrong for both apps:
  herald's strips are always the dark toolbar colour, drawbridge's always follow
  day/night. `windowLightStatusBar` and `windowLightNavigationBar` are set
  explicitly rather than inherited.
- **Fullscreen video needed one special case after all, and the camera hole is
  why.** A hidden bar reports no inset, so that half of the padding does collapse
  on its own. A **display cutout is not a bar**: it is a hole in the panel, it is
  still there when everything is hidden, and its inset is reported the whole
  time. `enterImmersiveMode` compounds it rather than helping — it sets
  `FLAG_LAYOUT_NO_LIMITS` and cutout mode `SHORT_EDGES`, stretching the window
  underneath the hole on purpose. So a fullscreen video on a phone with a
  punch-hole camera kept a strip of toolbar colour across the top of the screen,
  which is the one place a video is meant to be.

  herald asks for the cutout only while the status bar is showing. The cutout is
  in that list to keep the *toolbar* clear of the hole — in landscape it sits
  beside the toolbar rather than inside the status bar, so the bar's own inset
  does not cover it — and once the bars are hidden there is no toolbar to keep
  clear. Keyed on bar visibility rather than on a fullscreen flag of herald's
  own, so the padding follows the window it is being computed for instead of
  what the browser believes about itself.

  **A phone found it, but an emulator could have.** The insets work was verified
  on the API 36 emulator in both orientations, and an emulator has no cutout by
  default — so the inset the bug turns on was zero every time anybody looked. It
  will grow one on request, which is worth knowing before the next change to this
  code:

  ```bash
  adb shell cmd overlay enable com.android.internal.display.cutout.emulation.hole
  ```

  That is a punch-hole camera, the shape the bug was reported on; `corner`,
  `double`, `tall` and `waterfall` are the other four. The display then reports
  `cutout DisplayCutout{insets=Rect(0, 136 - 0, 0)}` and the white strip appears
  exactly as described. Disable it again the same way. `InsetsExtTest` covers the
  same state without a device: insets carrying a cutout with the bars hidden,
  asserting the padding is nothing at all.

## herald's chrome is four colours, and some of them belong to Mozilla

herald follows the phone's day/night setting. Everything it draws over comes from
four colours in `values/colors.xml` and their night twins — `toolbar_background`
for the toolbar and for the strips the system bars sit over, `toolbar_text`,
`toolbar_hint`, `menu_icon` — so changing the chrome means editing two files and
nothing else. The bar icon polarity is pinned per mode in the matching
`themes.xml`, because with transparent system bars the platform picks it from the
theme and gets it wrong for whichever mode does not match.

Android Components does not inherit any of that. Its defaults assume a light
toolbar, and anything left unset is invisible on a dark one — which is how the
address bar came to be unreadable while being edited, and the tab counter came to
be a dark box on a dark toolbar. Three of them have to be set by hand:

- **`toolbar.display.colors` and `toolbar.edit.colors`.** Display *and* edit —
  they are separate colour sets, and only setting the first leaves the text
  unreadable exactly while it is being typed.
- **`TabsAdapter(styling = TabsTrayStyling(...))`**, or the tray rows stay a
  white card with a bright blue selection.
- **The tab counter**, which has no setter at all: `TabCounterView.setColor` is
  internal, so `mozac_ui_tabcounter_default_tint` is overridden as a resource
  instead.

Content follows the same setting through `preferredColorScheme`, which is read
from the configuration rather than left at `PreferredColorScheme.System`: the
engine resolves "system" once and keeps it, so a phone switching to dark while
herald is running would go on rendering pages — and the block page — light until
the process restarted. `HeraldApplication.onConfigurationChanged` pushes the new
value in, because the activity is recreated on a mode change but the engine is
process-wide and outlives it.

## herald dispatches its own search region

`SearchMiddleware` loads the bundled search engine catalogue on exactly one
trigger — `SearchAction.SetRegionAction` — and Android Components leaves
dispatching it to `RegionMiddleware`, which resolves the region over the network
through Mozilla's location service. That endpoint is retired. It resolves
nothing, the action is never dispatched, and herald shipped with **no search
engines at all**: no default, an empty picker in settings, and a URL bar that
could open addresses but not search.

herald drops `RegionMiddleware` and dispatches `SetRegionAction` itself, with the
region taken from the phone's locale. The catalogue is bundled inside
`feature-search`, so this needs no network — fitting an app whose only remote
dependency is meant to be its own policy URL.

The policy's `default_search_engine` is applied once the catalogue arrives (it
loads asynchronously, so there is nothing to select at startup) and re-applied on
policy changes, until someone picks an engine in settings — after which the
choice is theirs and the policy stops overriding it.

## The policy names the engines; the user picks among them

`browser.search_engines` decides which engines exist, and herald hides everything
else — including whatever the phone's locale brought in. An engine Mozilla does
not bundle — Kagi is the only one left on the list — is added by herald as a
custom engine with its own URL template, so the list does not change when the
phone travels.

There is deliberately no in-app "add a search engine" button. It would be a way
to reach an unfiltered engine from inside the browser, which is the same reason
there is no `about:config` and no secure-DNS toggle.

The list is a filtering decision rather than a preference: safe search is forced
by rewriting the engine's hostname in `DnsFilter`, and only Google, Bing and
DuckDuckGo publish a hostname to rewrite to. Everything else serves image results
from its own CDN, which no domain blocklist covers — so DuckDuckGo is the
default, and Yandex and Baidu are absent rather than merely unselected. See
[policy.md](policy.md#search-engines-are-a-filtering-decision).

## Reader view is Gecko's own, and cannot be a way around the filter

Reader view is `feature-readerview`: the same Readability pass Firefox uses,
shipped as a built-in web extension. It is worth stating why that is safe in a
filtering browser — it runs over the DOM of the page already loaded and fetches
nothing, so it can only ever show content the filter has already let through. The
article it renders lives at a `moz-extension://` URL, which is why that scheme has
to stay reachable.

The menu entry only appears where Gecko reports the page as readerable, and the
font and colour controls only once it is on, so neither shows on a page that has
no article in it.

### `ReaderViewMiddleware` is load-bearing, and its absence is silent

`ReaderViewFeature` registers the content-script ports only when
`readerState.connectRequired` is set, and re-runs the readability check only when
`readerState.checkRequired` is. Both flags are set *exclusively* by
`ReaderViewMiddleware`, on `SelectTabAction`, `LinkEngineSessionAction` and
`UpdateUrlAction` — so a store without it has a reader view that works by
accident when the first port happens to connect and never re-checks afterwards.

Nothing warns about this: no exception, no log line, just a menu entry that
appears on some pages and not others. It was found by reading the decompiled
feature after a De Morgen article that Firefox offered reader mode for and herald
did not — that URL serves a consent interstitial first and only then redirects to
the article, so the article itself was never checked.

## A blocked iframe is denied, not given a block page

`RequestInterceptor.onLoadRequest` fires for every document load, top-level and
iframe alike, but Android Components loads `InterceptionResponse.Content` into
the *session* rather than the frame that asked for it. Returning a block page for
a blocked iframe therefore replaced the whole tab: a page the filter was happy
with vanished because one tracker frame on it was not, and the block page named
that tracker rather than the site the reader had asked for. On an ad-supported
site that is most pages.

Subframes get `InterceptionResponse.Deny` instead, which cancels that frame and
leaves the page alone. Only top-level documents are worth a block page, because
only there is the blocked host the thing the reader asked for.

A subframe that merely *fails* — a DNS-filtered tracker on a managed device —
does not take the page down; that path is frame-scoped already.

## The block page carries its picture inside itself

The block page is built as a string and handed to the engine as a `data:` URL, so
it renders with no network and no assets — which matters, because the one page a
blocked device is guaranteed to see should not depend on the connection working.
The illustration keeps that property by being read out of `res/raw` and inlined
as a base64 `data:` URI, which means it is base64-encoded twice: once into the
page, and again by GeckoView's loader when it takes the whole document. That is
why the file is 1000px of WebP at ~42 KB rather than the 1.9 MB master, and why
it is cached for the life of the process — a child who has hit a wall tends to
hit it repeatedly.

If the resource cannot be read the page still renders, without the picture. An
exception thrown while building the block page would leave the engine showing the
site that was supposed to be blocked.

**Both times of day travel with the page.** herald ships a daylit scene and a
night one, and a `<picture>` element lets the browser's own
`prefers-color-scheme` choose — the same switch the card's colours already
follow. Picking one in Kotlin would have been half the bytes and wrong the moment
the phone crossed into dark mode with the block page still open: everything
around the picture turns, and it would be the only bright thing left on screen.
This is also why they are two resources with distinct names rather than one name
with a `-night` qualifier, which would give the page only whichever one matched
at the time it was built.

drawbridge does not do this. Its screen is read once, by a parent, deciding
something, and a picture that changed with the hour would be decoration for its
own sake.

## The language picker changes drawbridge, and only drawbridge

drawbridge's screens are offered in English, Dutch and French, through
`AppCompatDelegate.setApplicationLocales` — a *per-app* locale. Device Owner
privilege does not extend to setting the system language; there is no such API,
and there is no way to set herald's locale from another app either. So the picker
changes what the parent reads on the configuration screen, and nothing else.

Below API 33 AppCompat only persists the choice if
`AppLocalesMetadataHolderService` is declared with `autoStoreLocales`; without it
the picker works until the process dies and then silently forgets.

Half of the configuration screen is not string resources at all — the profile's
name and description come from the signed policy document. Those carry their own
translations; see [policy.md](policy.md#words-in-the-policy-carry-their-own-translations).

## uBlock Origin ships inside the APK

A domain blocklist cannot block advertising properly, and no amount of list
curation fixes that: an ad served from the same host as the article is
indistinguishable from the article, and blocking a host outright leaves the empty
box behind rather than removing it. uBO does the part the DNS layer and the
shared blocklist structurally cannot — request rules with URL and type context,
plus cosmetic filtering.

It is installed with `installBuiltInWebExtension` from
`resource://android/assets/extensions/ublock/` rather than fetched from AMO.
Three reasons, in order of weight:

- **A built-in cannot be disabled or removed from inside the browser**, and
  herald exposes no add-on manager, so the only extension surface is uBO's own.
  An AMO install brings the whole add-on machinery with it.
- **No network on first run.** Installing over the wire would leave the browser
  without ad blocking until a download completed, on a device whose own DNS
  filter would first have to be told to allow `addons.mozilla.org`.
- **No permission prompt.** GeckoView prompts before installing a signed add-on.
  A permissions dialog at first launch, on a phone set up for someone else, is a
  dialog that gets answered without context.

The cost is that uBO only updates when herald does. Its *filter lists* still
update themselves over the network as usual.

`tools/vendor-ublock.sh` downloads a pinned build, checks it against a SHA-256 in
the script and unpacks it, so what is committed is reproducible rather than a
blob. The AMO signature block is dropped: it covers the packed XPI and means
nothing once unpacked, since a built-in extension is trusted for having come out
of the APK.

### aapt silently drops `_locales`

The first install failed with `Extension is invalid` and one line about
`_locales/en/messages.json` not being found. aapt's default ignore list contains
`<dir>_*`, which excludes *any* asset directory whose name starts with an
underscore — so all 72 of uBO's translation folders were left out of the APK.
`herald/build.gradle.kts` therefore sets `ignoreAssetsPatterns` to aapt's own
default with that one pattern removed. Setting the list at all replaces the
default wholesale, which is why the rest is repeated verbatim.

### The popup needs a host, the dashboard does not

`WebExtensionToolbarFeature` renders uBO's browser action into the toolbar, but
tapping it only parks an `EngineSession` on `WebExtensionState.popupSession` —
Android Components renders it nowhere. Without
`extensions/ExtensionPopupFragment` the button appears to do nothing.

The popup is a sheet rather than a tab so it stays out of the tab list and the
back stack. The dashboard is the opposite case: it is an ordinary
`moz-extension:` page, so the menu entry just opens it as a tab.
`HeraldRequestInterceptor` only inspects `http`/`https`, so that scheme passes
through untouched.

The dashboard does contain a switch that turns uBO off globally, and a
trusted-sites list. That is a deliberate trade — see the known gaps in the
README — and it is worth being clear that it opens up advertising, not blocked
content: the DNS layer and the shared blocklist sit underneath uBO and are not
reachable from it.

## Bookmarks are exchanged as Netscape HTML

`bookmarks.html` — the format Firefox, Chrome, Safari and Edge all read and
write — rather than anything herald-shaped, so a phone can be seeded from a
parent's own browser export and an export is readable somewhere other than here.

The format is nominally HTML and has never been valid HTML: `<DL>` is frequently
never closed, `<DT>` never is, and Chrome emits a stray `<p>` after every list.
Parsing it means being deliberately forgiving rather than correct, which is why
`BookmarkHtml` is a hand-rolled scanner over the four tags that matter.

Two decisions inside it are about safety rather than fidelity:

- **Only `http` and `https` are imported.** Bookmark files can carry
  `javascript:` bookmarklets and `file:` and `data:` URLs, and a filtering
  browser should not be talked into storing any of them as a one-tap entry point
  by being handed a file.
- **Input is capped** at 8 MiB, 50 000 nodes and 32 levels, and a file that hits
  a cap is reported as a truncated import rather than read whole. The file comes
  from a picker, so it is untrusted.

Imports land in a new `Imported <date>` folder rather than merging into the
tree, so an import can be undone by deleting one folder and can never overwrite
what is already there.

## The bookmarks new tab page is an overlay, not a URL

Showing bookmarks on a blank tab could have been a page at some `herald:` URL
served by the request interceptor, the way the block page is. It is a view drawn
over the engine instead, because the address bar of a new tab should be empty and
ready to type in — a real URL would sit in it — and because a new internal scheme
is one more thing that has to keep being carried past the interceptor, the
filter and the app-links feature correctly.

It is off by default. The setting exists because a curated bookmark list is the
right home page for the device this is built for, and a blank page is the right
one for a browser that is only closing the browser gap on someone else's phone.

## herald mono is a flavour, not a fork

Mono is the same browser minus three things, so it shares one source tree and
one Gradle module. What differs is named in `Edition` and switched on a
`BuildConfig` flag, which R8 resolves at compile time — each edition's release
build contains only its own branch.

It sets `applicationId` outright. The ban on `applicationIdSuffix` still holds
and is a different thing: a suffix makes a *variant of the same app* that Device
Owner and the policy do not recognise, which is what got a `.debug` herald
uninstalled seconds after provisioning. A flavour with its own id is a
deliberately separate app. A managed device still runs exactly one browser —
whichever `allowed_browser_package` names — because drawbridge removes every
other one, so herald and mono are alternatives rather than companions.

The cost is packaging: a release now builds six herald APKs instead of three.

### Adding a flavour renames the APKs, and that is not cosmetic

`required_apps` pins each APK by URL under `/releases/latest/download/`, so the
published filenames are load-bearing — a drifted name means every provisioned
device fetches a 404 and quietly stops updating. A flavour dimension renames
Gradle's outputs to `herald-standard-<abi>-release.apk` by default, which would
do exactly that.

AGP 8 does not expose `outputFileName` on the public variant API, and the legacy
variant API is incompatible with this project's configuration cache. The names
are therefore fixed at staging time by `tools/stage-release.sh`, which then
checks that every APK the signed policy pins exists under exactly that name and
matches its hash. A loud failure in place of a silent one.

## Mono renders without colour at the surface, not in the page

The greyscale filter goes on the engine's own view, not on the document. That
reaches everything Gecko paints — text, CSS, images, canvas, WebGL and playing
video — and costs the page no layout.

The obvious alternative is a content script injecting `filter: grayscale(1)`.
It was rejected before being built: `filter` on the root element makes it a
containing block for `position: fixed` descendants, so sticky headers and fixed
navigation start scrolling with the page on a great many sites. A page can also
see and remove an injected stylesheet.

The price is moving GeckoView off its default `SurfaceView`, whose contents the
system composites separately and which a view-level colour filter cannot touch.
`GeckoView.setViewBackend(BACKEND_TEXTURE_VIEW)` puts the engine inside the
app's own view hierarchy, where a `ColorMatrixColorFilter` applies. This was
spiked before it was designed: a Wikipedia article and a playing video both
render monochrome, and the same video frame is vivid red with the filter off.
Toggling costs under a millisecond, so "show this page in colour" is a layer
change and not a reload.

TextureView is the less-travelled GeckoView path and copies a frame more than
SurfaceView does. It also logs `TextureView doesn't support background color`,
which is a no-op warning but means the engine view's background will not paint —
watch for a flash on slow loads. Neither has been measured on real hardware.

The toolbar is filtered too, because uBlock Origin's browser-action icon is a
bitmap the extension supplies and no palette can reach it.

## The address bar is wrapped so the library cannot empty it

`ToolbarPresenter` re-renders on **every** `BrowserState` update, and one thing
it does unconditionally is `toolbar.setSearchTerms(tab.searchTerms)`.
`BrowserToolbar` applies that only while the toolbar is in edit mode — only while
someone is typing — and applying it means `EditToolbar.editSuggestion`, which
calls `updateUrl` and *replaces the field's text*.

For an ordinary page the search terms are empty, so the field empties. That is
the whole of "the URL bar sometimes clears while I type": intermittent only
because it needs a state update to land mid-keystroke — a page still loading,
uBlock Origin's badge counter ticking, the periodic session autosave, the
reader-view swap. It affected both editions and was in every release up to and
including v0.1.7.

`EditSafeToolbar` holds those updates back while editing rather than dropping
them, because `BrowserToolbar` keeps the last search terms it was given and uses
them to prefill the field the next time edit mode is opened. Suppressing them
outright would also fix the bug and would quietly lose that prefill; suppressing
them only while editing keeps both, since a page that loads while nobody is
typing still updates them.

It is a wrapper and not a subclass because `BrowserToolbar` is final.
`ToolbarFeature` takes the `Toolbar` *interface*, which is the seam. One
consequence worth knowing: the wrapper owns the toolbar's single edit listener,
so anything else that wants `setOnEditListener` has to go through it.

## Leaving reader view by the back button counts as dismissing it

`ReaderViewFeature.onBackPressed` hides reader view and reports that it handled
the press. In mono that left a readerable page with reader view off — which is
exactly the condition the automatic entry turns it back *on* for. The article
returned immediately and the back button looked dead.

The fix is not to special-case back but to treat it as what it is. The
`dismissedForPage` flag that already stops automatic re-entry after an explicit
toggle is now also set when back leaves reader view, so a dismissal is a
dismissal however it is made. A second back press then goes back in history, and
navigating anywhere clears the flag with the page it belonged to.

## The pause in mono is felt, not enforced

The two-and-a-half-second wait is presentation. The page loads underneath it the whole
time, so nothing is slower and the wait does not vary with the network.

Delaying the load itself is possible and worse. `RequestInterceptor.onLoadRequest`
is synchronous, so waiting inside it blocks the thread Gecko calls it on.
Returning `Deny` and reissuing the load works for typed URLs and bookmarks but
loses POST bodies on form submissions. Wrapping GeckoView's own navigation
delegate — which *does* return a deferrable `GeckoResult` — would work, and takes
herald off the Android Components abstraction it otherwise sits cleanly on top
of.

**There is no pause on the way to a wall.** A blocked page used to get the full
two and a half seconds and then say no, which reads as the browser being slow
rather than deliberate, and made the block page feel like a punishment. The
interceptor already knows it is about to block, so it cancels the pause there.
Both a running hold and one about to start have to be handled, because the
interceptor and the store's `loading` flag do not arrive in a fixed order —
hence a deadline *and* a hide, and a `@Volatile` field, since the interceptor
runs on Gecko's thread.

The pause begins when `loading` turns true, not when the URL changes. The first
attempt used the URL and was wrong in a way only the device showed: the URL
commits after the round trip, so the old page flashed up and the hold read as
the new page stalling rather than as the browser being deliberate.

**Nothing ends a running hold except its own timer.** That rule sounds obvious
and was arrived at the hard way, after the pause turned out to be cut short
exactly when moving between two reader-view pages — the case mono makes most
common. Reader view engages *during* the pause for the page being entered, and
it trips two separate cancels: the suppression that stops it being paused for
twice, and the moment its URL is a `moz-extension:` one, before
`ReaderViewMiddleware` rewrites it back to the article's own. Both used to call
`hide()`. Each was individually reasonable and together they meant the hold
survived on ordinary pages and collapsed on articles.

Timed on the phone afterwards, tapping a link from one reader page to another:
the overlay holds from 157 ms to 2,490 ms and the next article appears at
2,897 ms.

## Mono keeps one tab by enforcing it, not by finding every path to it

Removing the tab counter, the tray, "New tab" and the long-press entries removes
the *sight* of tabs, not the ability to make them. `target="_blank"` and
`window.open` are handled a layer down, by setting Gecko's own
`browser.link.open_newwindow` to "current tab" with the restriction that covers
`window.open` calls asking for window features. Then, whatever still manages to
create a tab — an intent from another app, a restored session — the selected one
survives and the rest are closed.

Two mechanisms because the first is best-effort: `GeckoPreferenceController` is
marked experimental, and if it changes or stops working the invariant still
holds. `TabIntentProcessor`'s third parameter is `isPrivate`, not `openNewTab`;
it has no option to load in place, which is what made the invariant necessary
rather than merely tidy.

## The PIN is gone, and the key is the whole credential

drawbridge used to have two secrets: a six-digit parent PIN, and a twenty-
character recovery code sitting behind it for when the PIN was forgotten. The
PIN is now gone and the key does everything.

The PIN was never carrying its weight. Six digits is ten thousand possibilities —
an afternoon of tapping — so it needed lockout throttling, four free attempts and
a delay doubling to thirty minutes. It needed the recovery code underneath it
anyway, because a forgotten PIN could not be allowed to strand the parent. And
the recovery code, being the real backstop, had to be as strong as if the PIN
were not there. So the PIN bought convenience, and paid for it with a second
secret, a throttling mechanism, a change-PIN path, and a lockout that could shut
the parent out of their own phone for half an hour.

The key is twenty Crockford base-32 characters: a hundred bits. Guessing is not a
threat model, which is why there is no throttling any more — and removing it
removed the failure mode where the only way in was temporarily unavailable.

Two consequences worth stating:

- **A fresh key is minted at every lock.** The alternative — one key for the life
  of the install — meant a key photographed once was a key forever. Minting on
  lock also guarantees the reveal screen always has something to show, and that
  nothing is stored at all while the device is unlocked.
- **Removal no longer asks for anything.** It used to be the one door with a lock
  on it, back when the configuration screen was open to whoever picked the phone
  up. Now that whole screen is behind the key, so asking again at the removal
  screen would be asking the same question twice.

There is still no reset, by design. **What answers a lost key since 2026-08-17 is
a clock rather than a credential** — a timer chosen before the lock, or the
thirty-day code-forgotten door on the lock screen; see
[Losing the key](#losing-the-key-a-delay-not-a-back-door). Neither is a second
secret, which is the property this section is about: there is still exactly one
thing that opens a phone on demand, and it is the key.

A parent who keeps no copy and sets no timer still freezes the settings as they
stand. The reveal screen says so, and lets them close it without keeping the key —
deliberately making the configuration permanent is a legitimate thing to want, and
the checkbox is there so it cannot happen by accident.

## Both apps must read the same document, not merely the same shape of one

**herald and drawbridge poll the policy independently**, each with its own store,
its own cache and its own version. That is deliberate — herald ships standalone,
with no drawbridge behind it, and has to work anyway.

The cost is that they can disagree, and on 2026-08-13 they did. herald read
`PolicyConfig`'s default URL, which is `main`'s, while drawbridge read whichever
channel it was built for. A dev phone therefore ran herald against the alpha's
policy and drawbridge against dev's: one app unblocking what the other still
blocked, with nothing on the device to say so. It was found because herald
reported policy 50 on a phone whose drawbridge was on 49.

Both now take the same `drawbridgePolicyUrl` build property and fall back to
`main` when it is absent, so a released build is unchanged by construction.

**The general rule worth keeping:** anything that decides *which* document an app
obeys has to be set for both apps or neither. Anything that decides what to do
*with* that document can differ, and does — herald acts on the browser fields,
drawbridge on the package ones.

## herald reads drawbridge's selection, or it enforces a different policy

Both apps fetch the same signed document, and for a long time that was assumed to
be enough. It is not: the document is only half the answer. Which profile is
running and which options are on is a *selection*, it lives on the device, and
only drawbridge holds it.

So the browser filtered on the document's defaults while the DNS layer filtered
on what the parent had actually switched on. Mostly invisible, because on a
managed device DNS blocks first and the browser never gets the chance to
disagree — until an option *allowed* something, at which point the two layers
wanted opposite things and the stricter one won. "Allow WhatsApp" on, and
WhatsApp Web still refused to load in the browser.

drawbridge now publishes the selection through a read-only `ContentProvider`
guarded by a `signature` permission it declares itself. Both apps are signed with
the same release key, so herald and herald mono are the only packages on the
phone that can hold it — no manifest declaration and no user prompt can grant it
to anything else. There is deliberately no write path: a browser that could
change the selection would be a way around the lock.

Three properties are load-bearing:

- **A missing provider means "no selection", not "no rules".** The standalone
  browser has no drawbridge to ask, and neither does one whose drawbridge is
  mid-upgrade. Both fall back to the document's own defaults, which are the
  *strict* reading — so the failure mode is a browser that blocks more than it
  needs to, never less.
- **An empty answer is not a missing one.** `option_ids=""` means the parent
  turned everything off; a missing column means nobody answered. Conflating them
  would silently re-enable every default-on option.
- **herald needs `<queries>` for the authority.** Without it, API 30+ package
  visibility makes the provider invisible and the query returns nothing — which
  looks exactly like the standalone case, on a managed device, with no error
  anywhere.

Changes are pushed rather than polled: drawbridge calls `notifyChange` and herald
holds a `ContentObserver`. Without that the browser would keep the old answer
until its next daily poll, and a parent who flips a switch and hands the phone
back would be handing back a browser that disagrees with the switch.

## Both herald editions may exist, and the two lists have to agree

`allowed_browser_package` was a single package, on the reasoning that a device
runs one browser or the other and never both. Shipping both means
`allowed_browser_packages` — a list — and it comes with a trap worth naming.

`required_apps` installs what is missing, and the app blocker removes browsers
the policy does not name. If those two disagree, drawbridge installs a browser
and then removes it as a rogue one, and reinstalls it at the next poll, forever.
That is precisely why herald mono was left out of `required_apps` until now. Name
a browser in one list and not the other and you get the loop.

`Policy.browserPackages` folds `allowed_browser_package` into the list so the
default link handler cannot be uninstalled by an incomplete list, which is the
likeliest way to write the field wrong.

## Locking is the only button, because protecting was never a separate decision

drawbridge briefly had two buttons: *Turn on protection*, which applied the
restrictions and started the filter, and *Lock drawbridge*, which sealed the
configuration screen. Nobody wants one without the other, and a phone that had
been given the first and not the second sat configured, unlocked and unfiltered
while looking finished. Now *Lock* does both, in that order, and the order
matters: everything that needs the configuration screen happens before the
screen is sealed.

The one step that can come back later is the VPN-consent dialog, which only
appears when drawbridge is *not* device owner. If the parent declines it, the
phone is deliberately **not** locked — sealing the screen would turn "locked
means filtered" into a promise they could no longer check.

## A browser is not one more blocked app

**Decided 2026-08-12, and it is the one exception to the rule below.** Browsers
are removed whether the phone is locked or not. Everything else waits for the
lock.

The filter is DNS-only. That is a deliberate trade — no TLS interception, no
certificate on a child's phone — and its price is that anything which tunnels
over 443 to a host the blocklists do not name walks straight past it. Several
browsers now ship exactly that under the name *VPN*: Opera's is an in-browser
proxy, not an Android `VpnService`, so `DISALLOW_CONFIG_VPN` does not touch it and
being second in line behind drawbridge's tunnel does not either.

So an unapproved browser surviving an unlock is not one more app the parent
chose to keep. It is the filter switched off, on a phone that still says it is
protected. That asymmetry is why the two rules differ: **migrating data does not
require a browser**, so keeping the unlock window open for everything else costs
nothing here.

### What is allowed, and why Firefox is not

`allowed_browser_packages` names herald, herald mono, **Chrome** and **Firefox
Focus**. One browser was never going to be enough — some sites do not render on
Gecko at all, which is the reason a Chromium engine is on the list rather than a
preference about it.

Firefox is deliberately absent: it offers a VPN. Focus does not. Chrome has no
extensions on Android and no proxy of its own.

**"Secure DNS" is a smaller problem than it looks, and not for the reason it is
usually given.** Three separate things stop browser-level DoH here, and only two
of them are ours: the `encrypted-dns` blocklist, so those hostnames do not
resolve; `DnsFilterService.ENCRYPTED_DNS_BLACKHOLE`, twelve well-known resolver
IPs routed into the tunnel and dropped; and Chromium's own choice to disable DoH
when it detects enterprise management. That last one is a heuristic inside code
this project does not control, re-decided at every browser update — so *"secure
DNS does not work on a managed device"* should be read as "not today, on the
browsers we checked, for the endpoints we know".

### The class is wider than browsers, and the perimeter knows it

`AppBlocker.isBrowser` asks the package manager which apps answer an `https://`
VIEW intent. An app that proxies without declaring one — Orbot, Psiphon in
browser-only mode, Outline — is not a browser by that test and is caught only by
name. Policy 41 therefore names thirty proxy, Tor, VPN and DNS-changer packages,
every id checked against the Play Store rather than written from memory, because
a wrong package id is invisible: the blocker simply never matches it.

**Consumer VPN apps do not work on a managed device, and that is tested rather
than reasoned** — the owner tried them on 2026-08-12: they cannot change the VPN,
because `DISALLOW_CONFIG_VPN` stops a second one being configured and Android
runs one at a time regardless. So their entries on the blocklist are defence in
depth, not the mechanism. **The ones that do the work are the apps that proxy
inside themselves**, which no restriction covers, and those are the reason the
list exists at all.

### herald is the default, and the default cannot be changed

With five browsers allowed, which one a tapped link opens in stopped being a
detail. `setDefaultBrowser` makes herald the persistent handler through
`addPersistentPreferredActivity` — a Device Owner API, so **Settings cannot
override it**. The others remain installed and open normally when somebody
launches them; they simply do not inherit links.

That is the shape asked for: herald recommended by default, the alternatives
available for the sites it cannot render. It is worth being clear that
"recommended" here means "chosen for you and not changeable", which is stronger
than the word suggests.

**The package now comes from the policy.** `allowed_browser_package` has been in
the document since the beginning and nothing read it — the DPC used a
`BuildConfig` constant with the same value, so editing the document changed
nothing and said otherwise. It is read now, with the constant as the fallback for
a device that has not fetched a document yet.

### Hiding has to be reversible, and now is

A preinstalled browser cannot be uninstalled, so it is hidden instead — and until
2026-08-12 nothing ever unhid it except complete removal. Adding Chrome to the
allowed list would have left every phone that had already hidden it hidden
forever. `AppBlocker.restoreNowAllowed` now brings back what the policy names
explicitly: the allowed browsers and the exempt packages, minus anything still
blocked.

It is restricted to those lists on purpose. The general rule — unhide whatever
would no longer be removed — cannot be written safely, because a hidden app
answers no intent queries, so asking whether it is a browser requires unhiding
it, which would hide it again on the next sweep. Every fifteen minutes, forever.

## What waits for the lock, and what stopped waiting

**This heading has moved twice, and each move gave something up.** It read
*"nothing is enforced until the phone is locked"* until 2026-08-12, when herald
began installing at provisioning and the filter began running from it. It then
read *"nothing is **taken away** until the phone is locked"* until 2026-08-17,
when app removal stopped waiting too.

What waits now is **the restrictions**, and **the removals a switch on the
configuration screen still governs** — WhatsApp, Telegram, YouTube, streaming, a
browser the *chooser* narrowed away. Everything else acts from installation.

### Why removal stopped waiting for the lock

**The owner's call, 2026-08-17, and the reasoning is about who this is for:
not everybody is going to lock.** A phone that filters the web and drops social
media, undoable only by a factory reset, is already most of what drawbridge
offers — and a version of that which quietly does nothing until somebody presses a
button is a version that fails the person who never presses it. The lock is for
sealing the *choices*; it should not have been the thing that switched the
product on.

The Moto is what made it concrete, twice in one day: a preloaded game that no
rule had ever been asked about, and a phone whose owner had every reason to
believe it was already protecting them.

**What it costs is paid before anybody has agreed to anything**, and that is not
a small thing. On a phone already in use, apps start disappearing minutes after
the install, and whatever lived only inside them goes too. The old design bought
that consent with the lock button. The new one has to buy it earlier, which is why
the warning moved to **before the install** — the website's install pages, the
USB installer, and `docs/install*.md` all say it ahead of the cable now, rather
than beside a button the person has not reached yet.

**One rule had to be hardened for this to be safe.** The browser rule removes what
is *not* named, and `Policy(version = 0)` — what `PolicyManager` holds before it
has read anything — names herald alone. A sweep racing the load would have
answered *"this phone allows no browser but herald"* and uninstalled Chrome,
Firefox and Vivaldi on the strength of a document nobody had opened. That race was
survivable while nothing was removed before the first lock; it is not survivable
when the first sweep runs minutes after installation.

**The fix is to wait, not to skip**, and the difference is not academic.
`PackageWatcher` does one *full* sweep on start and thereafter asks the platform
only which packages **changed** — so an app passed over in that first sweep has
not changed, is never revisited, and sits there until the process next starts.
Declining for want of a document would therefore have traded one bug for a
quieter one. Both the initial sweep and the install broadcast now await
`ensureLoaded()`, which reads what is on disk or the copy bundled in the APK,
costs milliseconds once, and is free afterwards. `StoreScanWorker` already did
this; the sweep was the odd one out.

`AppBlocker.browserRuleApplies` stays as the last resort, for the case where even
that yields nothing readable — `ensureLoaded` logs *"No usable policy"* and
carries on with version 0 rather than throwing. It is pure and tested. Every
other branch fails safe on an empty document.

### The filter does not wait, and the old rule was inconsistent

The owner's observation, from a Nothing Phone that sat provisioned and unfiltered
until somebody pressed Lock: a web filter is protection rather than confiscation,
and the deliberate act is *installing drawbridge*.

The argument that settled it is about consistency rather than urgency. If "not
locked" meant "not filtered", then **unlocking would have to un-filter the phone**
— it is the same state. It never did. The filter was keyed on `protectedSince`
and survived an unlock, while the pre-lock window had no filter at all, so the
same visible state behaved two different ways depending on history nobody could
see. Starting the filter at provisioning removes the seam instead of documenting
it.

Three of the four reasons for the old behaviour had expired anyway. The wizard
failure was the QR path, which is retired. The first-launch failure was about
removals and restrictions. And starting `DnsFilterService` also starts
`PackageWatcher`, so the filter used to drag app removal with it — which stopped
being true when removal became keyed on the lock, days earlier, without anyone
noticing the consequence.

What is kept: the filter still never starts while the setup wizard is running.
That guard costs nothing and encodes the one failure that actually happened.

**A phone that should have no web filter belongs in a policy that says so** — a
profile choice somebody makes, rather than a state reached by leaving a button
unpressed. Not built; noted as the right shape.

### And the restrictions still wait

Before the lock, a provisioned phone has no restrictions — at provisioning, at
first launch, and on the daily poll alike. `DeviceOwnerManager.reapplyIfProtected`
is what every automatic caller goes through, and it is a no-op until then; the
lock button is the only place the lockdown is applied unconditionally.

**App removal used to be in this paragraph and is not any more** — see above. The
reasoning below is about the *restrictions*, and it survives the change intact:
what the pre-lock window is for is adding a Google account and setting a screen
lock, neither of which app removal touches.

The rule was learned twice, from opposite directions.

**Enforcing during provisioning breaks provisioning.** Applying the restrictions
from `onProfileProvisioningComplete` — which fires while the setup wizard is
still on screen — left a QR-provisioned Moto G15 unable to finish setup at all.
Device Owner was granted, the policy compiled, and `USER_SETUP_COMPLETE` stayed
`0`: no notification shade, a Settings that closed itself on launch, and a reboot
that offered a factory reset because the wizard could not account for what it
found. The platform hands a DPC an `is_setup_wizard` flag in its provisioning
handoffs precisely because it expects the DPC to hold back.

**Enforcing at first launch is still too early.** Deferring only until setup
finished meant opening the app once uninstalled Facebook, pulled down ~470 MiB of
browsers and switched USB debugging off. The phone was managed before anyone had
agreed to anything.

What the window is for is concrete. QR provisioning never prompts for a Google
account or a screen lock, and the account is what arms Factory Reset Protection —
the backstop that makes a recovery-mode wipe useless to a child. Both must be
done by hand, and both are impossible once the restrictions land, as is enabling
USB debugging on an unfamiliar handset.

`ParentKey.protectedSince` is the signal for the *restrictions and the filter*,
not `isLocked`. It survives unlocking, so a parent who unlocks to change a
setting has not withdrawn their protection and the phone stays filtered while
they do it. Only removal clears it, which is right: removal is the off switch.

### Except the removals a switch governs, which follow the lock

**Changed 2026-08-12, after the owner found it on a real phone, and narrowed on
2026-08-17.** The rule below was written when *all* app removal followed the lock.
Only half of it does now: what a control on the configuration screen still governs
waits, and what nothing can bring back does not. The argument is unchanged for the
half it still covers, and it is the argument that decides which half is which — an
unlock has to be a window somebody can work in.

USB debugging is keyed on the lock for the same reason. Everything else — the DNS
filter, the multi-user restrictions, safe boot — stays on through an unlock,
because dropping those would leave an unlocked phone unfiltered rather than merely
open.

The bug was that removal followed *nothing at all*. `AppBlocker.evaluate` had no
gate; `PackageWatcher` lives inside the always-on filter service, which keeps
running after an unlock by design, so apps went on being uninstalled from an
unlocked phone. The one gate that existed was on the configuration screen and
asked `protectedSince != 0` — which reads as *has ever been locked* and stays
true forever, so unlocking never reopened anything.

**What that cost is the point of unlocking.** A parent unlocks to move data off
the phone, to migrate an account, to try a second browser before deciding — and
each of those needs an app to survive longer than the fifteen-minute sweep. An
unlock that reopens Settings but still deletes what you install is not a window,
it is a taunt.

It gives nothing away, for exactly the reason USB debugging is keyed there:
unlocking costs the parent's key, and an unlocked drawbridge already offers
complete removal from its own overflow menu. Whoever reaches this state can undo
everything anyway.

**The cost, said plainly.** Every other browser is removed because a browser with
its own encrypted DNS routes around a DNS-only filter. While the phone is
unlocked that protection is off, so an unlocked phone carrying a second browser
is filtered less than it looks. `LockActivity.sealWithKey` runs a full sweep the
moment the key is committed, which is what makes the window close properly rather
than fifteen minutes later — and it cannot be left to the watcher, whose own
startup sweep runs from `lockDevice`, on a phone that is not locked yet.

## The browser choice narrows the policy, and can only narrow it

**Built 2026-08-15, having been promised on the website first.** Three choices:
every browser the policy sanctions, herald mono alone, or none at all.

**Device-local, like the disconnect philosophy and for the same reason.** The
signed document says which browsers are *safe* — the ones carrying no in-browser
proxy and no secure DNS of their own — and this says how many of the safe ones a
household wants. A document signed by this project's key cannot know that
somebody is struggling with browsing itself.

`BrowserSettings.allowedBrowsers` **intersects** with the document rather than
naming packages outright, so the two compose instead of competing: if a policy
ever stopped sanctioning herald mono, *only herald mono* would allow nothing
rather than quietly out-ranking the document. Narrowing is the only direction
available.

### The icons are the description, and they are the policy's list rather than the phone's

Each choice shows the icons of the browsers it allows. That is not decoration
standing in for a sentence — it *is* the sentence, and a better one: "all the
allowed browsers" is a claim to take on trust, five icons somebody recognises is
the same claim, checkable at a glance.

**They come from the policy's list, not from what happens to be installed**,
which was the first version and was wrong. The row answers *what does this choice
allow* — a question about the document — and a phone without Vivaldi on it does
not make Vivaldi any less allowed. Reading only installed apps made the same
choice look different on two phones, and look *smaller* than it is on a phone
whose browsers the choice above had just removed.

So each icon resolves in descending order of how true it is: the installed app's
own launcher icon, then a bundled copy, then a globe. The third rung matters
because the map is keyed by package name while the allowed list is a signed
document that changes without an app update — a browser added tomorrow gets the
globe rather than leaving a hole in a row whose whole job is being complete.

**On the bundled third-party marks.** They identify the products they belong to,
which is what any browser picker does, and it is a different act from the rating
shields — those are drawn in-house precisely *because* borrowing PEGI's mark
would have asserted that PEGI graded something. Naming Chrome with Chrome's icon
asserts only that this is Chrome. Provenance is recorded beside the map in
`MainActivity`: Chrome, Firefox Focus and Vivaldi logos from Wikimedia Commons,
CC BY where the file carries a licence, each a trademark of its owner; herald's
two are this project's own, downscaled from `site/assets/img/`. Five files,
about 15 KB in total.

### It waits for the lock, and a browser the policy never allowed does not

The rule is not "browsers wait too". A browser the **policy** never sanctioned —
Opera, with its in-browser proxy over 443 — is a hole in a DNS-only filter and
goes whether the phone is locked or not. A browser the **parent** narrowed away
with this chooser is a reversible preference, and it lands at the lock like an
option's apps do, for the same reason: they may be mid-decision, and for a
user-installed browser an uninstall is permanent.

Coming back works the way it does for options. A preinstalled browser is
*hidden*, so widening the choice unhides it — immediately, since restoring only
ever adds. herald is user-installed and therefore *uninstalled*, so it comes back
the way it arrived: `required_apps` fetches it at the next lock.

**That last part needed a guard, and without it the phone would loop.**
`required_apps` names herald; *no browser* uninstalls it at the lock; the next
poll finds it missing and pulls 230 MB to put it back, for the following lock to
remove again. `AppInstaller.installMissingRequiredApps` now skips a required app
that is a browser the current choice excludes. The choice has to be honoured at
both ends or at neither.

### drawbridge does not decide the default browser at all

The first build of this pinned herald as the web-link handler with
`addPersistentPreferredActivity`, and grew its own default-browser picker to let
a parent change it. Both are gone.

**The API is the wrong tool for a recommendation.** It is documented to keep its
activity as the default *"even if the intent preferences are reset"* — built to
be un-overridable, which is right for a kiosk and wrong here. The intention was
only ever "herald is what we suggest"; what it produced was a phone whose link
handler could not be changed from Settings, and then a second question-asking
control inside drawbridge to work around drawbridge.

So the platform behaves normally: the first tapped link brings up Android's own
chooser with every allowed browser in it, *always* makes one the default, and
Settings → Default apps changes it later. `releaseDefaultBrowser` runs on every
policy application and clears any claim — which matters for phones updating from
a build that pinned, since nothing else would ever release it.

**The cost, stated plainly:** herald is not pre-selected. It is one entry in the
chooser rather than the answer. Under *herald mono only* and *no browser* this
is moot, because there is nothing to choose between.

**One bug found and fixed on the way, then made moot.** `resolveBrowserActivity`
used a plain `queryIntentActivities`, and a persistent preferred activity makes
the platform answer that filter with the preferred activity alone — so the
method could only ever resolve the browser that was *already* the default, and
changing it failed silently with "Browser … is not installed" in the log. It had
been that way since it was written and was never exercised, because herald was
the only answer anything asked for. The method is deleted along with the pinning;
the shape of the mistake is the part worth keeping.

### Narrowed on 2026-08-15: only what a switch could change waits

**The rule above said "everything except browsers waits for the lock", and that
was too much.** Its reasoning — an unlock is a window, and a window that deletes
what you put in it is not a window — is right about the things a parent is still
deciding on. It is wrong about the rest, and the cost showed as a phone that
slowly refilled, during every unlock, with precisely the apps drawbridge had been
installed to remove. Somebody installs this *because* they want social media off
the phone. There is no second question there to leave open.

So removal now splits three ways rather than two:

| | Removed while unlocked? |
|---|---|
| Browsers | **Yes** — a browser is a way around a DNS-only filter, not one more app |
| Anything an **option** covers — WhatsApp, Telegram, YouTube, the streaming catalogue | **No.** Waits for the lock |
| Everything else the policy blocks | **Yes**, as of 2026-08-15 |

**Why an option is the line, and not, say, "user-installed".** An option is a
question the parent answers with a switch, and they may not have answered it
yet — the phone arrives with every option off. Taking an app away from somebody
halfway through deciding to allow it is the same taunt the window exists to
prevent, and it is worse than it sounds because it is not symmetrical:
`restoreNowAllowed` can unhide a *preinstalled* app when the option comes on, but
nothing reinstalls one that was uninstalled. The policy's own list carries no
such question, so it needs no such grace.

`AppBlocker.optionGoverned` reads the packages from **every** option rather than
the enabled ones, which looks wrong and is not: an option that is *on* never
reaches the rule, because its packages are exempt and `isProtected` declines them
first. The set is only ever consulted for options that are off — the ones a
parent might still switch on.

**The window before the first lock is untouched.** That is a separate gate, and
it is what lets a parent move bookmarks and data across; it is the reason
drawbridge does not need a factory reset. Nothing is removed before the first
lock, browsers included.

The configuration screen no longer follows from the rule for free. It only exists
while the phone is unlocked, so it used to be able to say one thing about
everything on it — that changes land at the lock. Now a policy change removes
apps as it is made and an option change does not, so the line above the controls
says which is which, and the two toasts differ.

**The confirmation in front of switching an *option* off is gone**, deleted
rather than reworded, because rewording left it with nothing to say. It warned
that the option's apps went straight away and that switching back on would not
return them; removal follows the lock, so the first half describes something
this screen cannot do, and `restoreNowAllowed` unhides what the policy names, so
the second half is wrong in the other direction. A dialog whose whole content
has become untrue is not a safeguard, and a parent who reads it and believes it
is worse off than one who never saw it. Both directions of an option now apply
as the switch moves, and the toast says where the change lands. (The dialog in
front of *policy* selection carries the same two stale sentences and is still
there: swapping a profile changes the resolver and every blocklist with it, so
it has something to confirm and wants rewording rather than deleting.)

One wrinkle worth knowing. `lockDevice` triggers the policy fetch and the
required-app install *before* it mints the key, because everything needing the
configuration screen has to happen before that screen is sealed. So
`UpdateWorker` cannot simply read `protectedSince` — it would race the very
install locking is meant to trigger. The explicit trigger carries a flag saying
"the parent asked for this"; the timestamp says "this phone is already
protected"; either is sufficient.

### herald is the exception, and it is not enforcement

Installing what the policy *requires* used to wait for the lock along with
everything else. It no longer does, and the distinction is worth keeping
straight: the deferral exists to stop drawbridge **taking things away** before
anybody asked — restrictions, removed apps, a filter. Adding a browser takes
nothing away.

What made the wait look necessary was the QR path, where this ran inside the
setup wizard and a ~470 MiB download competed with it for the network at the
moment it was trying to finish. That path is retired, and provisioning now
happens over a cable on a phone that has already completed setup.

The reason to want it early is bookmarks. The window before the lock is the only
time the parent has both their old browser and herald in front of them, so it is
the only time anything can be moved across. herald arriving after the lock is
herald arriving after the browser it should have inherited from has been removed.

It still requires Device Owner — these are silent `PackageInstaller` sessions —
and an unrequested run still waits for an unmetered network, because 235 MiB per
browser on somebody's mobile data is its own kind of surprise.

## The install lock is a closed set, not a date and not a flag

**Built 2026-08-16, and it is the answer to a problem the blocklist cannot
solve.** Policy 59 added twenty-two AI companion apps by hand, every id fetched
from its Play listing and checked; the category had been carried almost entirely
by a domain list, and a domain list cannot hold it — nobody reaches these by
typing a hostname, they are found by name in the Play Store and installed with
one tap. New ones appear weekly with fresh package ids. A signed document updated
by hand will always trail them.

So the fix is upstream of the list: **after the lock, this phone installs nothing
new, and the apps already on it go on updating.**

### Why a set, and why that makes updates free

That sentence has three plausible encodings and only one of them works.

- **A date.** "Anything installed after the lock." It reads well and it is wrong
  the first time an app updates: `lastUpdateTime` moves, and the phone removes an
  app nobody installed. It is also exactly the trap the Moto's YouTube laid on
  2026-08-14, where `firstInstallTime` had been rewritten by an OEM preload
  service.
- **`EXTRA_REPLACING`.** The platform hands over a boolean saying *new package*
  or *update of one already here*, which is genuinely the question. It is
  unavailable to the half of the enforcement that matters most: the
  fifteen-minute sweep has no broadcast to read a flag from, and the receiver
  only fires while the filter service is alive, so an app installed during a
  restart would never be seen.
- **A set** — the packages the phone carried at the last lock. This is what
  drawbridge does, and updates then need **no special case at all**: an update
  never adds a package name that was not already there, so it is in the set by
  construction and the rule cannot fire on it. Nothing in the app reads
  `EXTRA_REPLACING`, compares versions, or looks at a timestamp.

`PackageWatcher` still evaluates replacing broadcasts, as it has since
2026-08-15, and it can go on doing so. The set answers the question, not the flag.

### An absent snapshot is not an empty one

`InstallLockSettings.snapshot` is **nullable**, and that is load-bearing rather
than fastidious. An empty set means *this phone carries nothing*, which would
make every package on the device a newcomer and hand the blocker a rule that
takes the phone apart. A snapshot that has never been taken has to answer "I
cannot say". This is the same shape of mistake as keying enforcement on
`protectedSince`, which reads as *is protected* and means *has ever been locked*
— the bug that cost 2026-08-12.

### It is limited to user-installed apps, like allowlist mode

This is the second rule in `AppBlocker` that removes what is *not* named rather
than what is, and that shape is the one that can take a phone apart. A snapshot
is the worse of the two: it is generated rather than written, so nobody ever
reads it, and it cannot know about a package that does not exist yet. An Android
version upgrade legitimately adds system apps — a snapshot taken on 15 has never
heard of what 16 ships — and hiding those would be an OTA quietly subtracting
from the phone, with nothing able to restore them because `restoreNowAllowed`
only brings back what the *policy* names.

Nothing is lost by the limit. This setting exists because of the Play Store, and
a preinstalled app was on the phone when the parent locked it.

### There is no prevention layer, and the platform is the reason

It shipped with one for exactly one build. `DISALLOW_INSTALL_APPS` in
`DeviceOwnerManager.restrictionsFor`, keyed on the lock like
`DISALLOW_DEBUGGING_FEATURES`, was to stop a new app arriving at all — with the
closed set behind it, deliberately built to give the promised semantics whatever
the restriction turned out to do.

**It turned out to block Play Store updates as well, and that killed it.**
Measured on the owner's Moto G15 on 2026-08-16: with the restriction in force, an
attempt to update Bitwarden was refused, Play's install activity opening and
closing again in 57 milliseconds. The restriction is checked in
`PackageInstaller.createSession`, and an update is an ordinary session there — the
platform draws no distinction at that point between a new package and a
replacement. **No AOSP user restriction expresses *no new apps, updates fine*.**

Blocking updates is worse than the prevention was good, which is what made this a
same-day fix rather than a parameter to tune: it freezes security patches for
every app on the phone, and the app that surfaced it was a password manager.

So the closed set carries the feature alone. A Play install succeeds, then
`PackageWatcher` uninstalls anything outside the snapshot within seconds, with the
fifteen-minute sweep as the backstop. What is lost is only the difference between
*refused* and *removed a moment later*.

**`DISALLOW_INSTALL_UNKNOWN_SOURCES` was considered and declined.** The snapshot
catches a sideloaded package exactly as it catches a Play install, so it would
change *when* rather than *whether* — and adding a second restriction on an
untested assumption, immediately after the neighbouring one behaved differently
from the assumption made about it, is the mistake this section exists to record.

The gap that leaves, stated rather than closed: Android requires a matching
signature to *update* an installed app, but nothing stops uninstalling one and
sideloading a differently-signed build under the same package name, which the
snapshot allows because the name is in the set. That is an adversary with an
APK-patching toolchain rather than a child.

**The retirement mattered as much as the removal.** `applyUserRestrictions`
computes what to *clear* from `MANAGED_RESTRICTIONS`, so dropping an entry from
that list stops it being set on new devices and never takes it off one that
already carries it. The restriction moved to `RETIRED_RESTRICTIONS` instead —
the same mechanism that un-did `DISALLOW_FACTORY_RESET` — or every phone that
locked once under build 29 would have been left unable to update anything, for
good, with nothing on the device able to reach it.

### drawbridge's own installs are the half that can strand a phone

`required_apps` names herald. herald is user-installed, so a browser-policy change
*uninstalls* it rather than hiding it, and the next poll fetches 230 MB to put it
back. Three separate things had to be right or the phone would loop, or lose its
browser for good:

1. **It joins the set before the session is committed**, not on success —
   `ACTION_PACKAGE_ADDED` can beat the install-result broadcast, and
   `PackageWatcher` would evaluate a package that was not yet in the set.
2. **A package still downloading counts as present.** herald takes minutes, and
   *choose the allowed browsers, then lock* puts the lock in the middle of that:
   the lock re-takes the snapshot from the packages actually on the phone, does
   not find herald, and writes the name straight back out. `closeTheInstalledSet`
   unions the installed packages with whatever drawbridge has in flight.
3. ~~**The restriction stands down for the length of the install.**~~ Gone with
   the restriction itself, one build later. It existed because a Device Owner was
   only *expected* to be exempt from `DISALLOW_INSTALL_APPS`, and the thing
   depending on that was a browser that could not otherwise come back. The
   in-flight set it was built on survives, because point 2 above needs it.

Belt and braces on top of all three: herald is an *allowed browser*, so
`isProtected` declines it before the install-lock branch is reached at all. The
machinery above is what makes the rule right for **any** required app, including
ones no policy names yet.

### The switch defaults off, and the wording carries the cost

Unlike the four policy options, this changes what the phone *is* rather than what
it filters, so nobody should get it by leaving a button unpressed. The cost is
not obvious until it bites and a parent should meet the sentence before the
situation: **an app you have not installed yet, you cannot install** — no new
bank app, no train ticket app, no app a school asks for in March — without
unlocking with the key.

Which is also the whole way in, and it is the one drawbridge already uses for
everything else: unlock, install, lock again. The lock re-takes the snapshot, so
whatever is on the phone at that moment is what it keeps.

## drawbridge does not prevent a factory reset

An earlier version set `DISALLOW_FACTORY_RESET`. Its documentation says it stops
a user factory resetting *from Settings*, and [removal](removal.md) said for
months that the recovery-mode path could not be blocked by any app.

Both were wrong, measured on a Moto G15 on 2026-08-07. With the restriction in
force the hardware recovery menu offered no "Wipe data/factory reset" and no
"Wipe cache partition" at all — the entries were simply absent — and they
reappeared the moment drawbridge gave up Device Owner. A phone whose key had been
lost was reclaimable only by sideloading firmware from a PC.

That is not a price a parent should pay for mislaying a piece of paper, so the
restriction is gone. It is also listed in `RETIRED_RESTRICTIONS` and actively
cleared on every apply, because dropping an entry from the applied set does
nothing for the devices that already carry it — and those are exactly the devices
that must not stay stuck.

**What replaces it is not prevention but detection and consequence:**

- **Factory Reset Protection.** A reset from recovery is "untrusted" and leaves
  the phone demanding a Google account that was on it beforehand. This is why
  [provisioning](provisioning.md) asks for the parent's account and only the
  parent's: a child's own account, even as a secondary, lets them satisfy FRP
  themselves and walk away with a clean phone.
- **The protected-since date.** It cannot survive a wipe, so a phone that was
  protected for a year and now reports a date from Tuesday has been reset,
  whatever else it looks like. See below.

The honest trade is that a determined child who knows the screen lock can now
factory reset the phone from Settings. They end up with a wiped device, an FRP
challenge only the parent can answer, and a date that gives them away. That is a
worse outcome for them than it was, and a far better one for a parent who simply
lost the key.

## Losing the key: a delay, not a back door

**Built 2026-08-17, and as an automatic *unlock* rather than the self-removal
proposed here.** The note below is what was written before it existed and is
preserved for its reasoning; ["What was built, and where it departs from the note
above"](#what-was-built-and-where-it-departs-from-the-note-above) at the end of
this section says what the code actually does.

The key is shown once and stored only as a hash, so a parent who loses it cannot
get back into the settings. Since drawbridge stopped preventing factory reset
(above) that is no longer catastrophic — the phone can always be wiped, from
Settings or from recovery — but the wipe costs everything else on the device.

The idea worth building is a **delayed self-removal**: from the lock screen, ask
to remove drawbridge, wait a fixed period, and it deactivates itself with the
data intact. The delay is the entire security mechanism. A child can start the
countdown, but not finish it unnoticed, and cancelling needs the key — so a
parent can abort and a child cannot.

Three things decide whether it works, and all three are easy to get wrong:

- **The countdown has to be impossible to miss** — an ongoing notification and a
  line on the lock screen. The design assumes a parent notices; if it can run
  quietly, it is a bypass rather than a safety net.
- **The clock is the attack.** A child who can move the date forward skips the
  wait, and `SystemClock.elapsedRealtime` resets at boot so it cannot carry the
  count alone. The curfew work already has the machinery: `DISALLOW_CONFIG_DATE_TIME`
  with `setAutoTimeEnabled`, applied for as long as a countdown is running.
- **Ambiguity has to fail locked.** Clock moved backwards, state unreadable,
  anything unexpected — the timer does not fire.

The residual risk cannot be designed away: a child starts it, nobody looks at the
phone for two weeks, and it lifts. The delay length is the only dial.

**The timer is what buys back `DISALLOW_FACTORY_RESET`.** That restriction was
removed because losing the key meant a phone reclaimable only by reflashing
firmware. With a delayed removal in place that stops being true — the escape is
to ask and wait — so the restriction can come back, and a child loses the
half-minute Settings reset again.

The two therefore ship **together or not at all**. Reinstating the restriction
without a working timer reproduces exactly the brick this replaced, and the
migration in `RETIRED_RESTRICTIONS` would have to be reversed with it.

One property is lost in that trade and should be stated: today's escape works
even if drawbridge is comprehensively broken, because a factory reset does not
involve drawbridge at all. A timer-based escape runs *inside* the app. A crash
loop, a corrupted state file or a bad update would take the escape with it. That
argues for the timer's state being trivially simple and independently readable —
and for treating a build that reinstates the restriction as the most
safety-critical release the project has cut.

### Why there is no code you can email for

The recurring alternative is a code the author hands out on request. It is
rejected, and worth writing down so it stops coming back.

The obvious objection is that drawbridge is open source, so a fixed secret is
extractable from the APK. That one is actually solvable: a challenge–response
would do it — the device shows a challenge, the author signs it with a private
key, the device verifies against a public key in the build — and reading the
source would not help.

The reason to refuse is different. It would make the author a **permanent
dependency**. drawbridge's central claim is that it needs no account and no
backend, and that the only thing to trust is a signed document at a static URL.
An escape hatch held by one person means every stranded phone depends on that
person still reading email, still holding a key, and still being around. It also
means adjudicating who is entitled to unlock a device, with no way to tell a
parent from a determined teenager with a convincing story.

That converts a tool into a service, which is the one thing this project set out
not to be.

### What was built, and where it departs from the note above

**An unlock, not a removal**, and that is the largest departure. `LockTimer` ends
the *lock*: the key is dropped, the configuration screen opens again, the phone
comes back online, USB debugging returns. Everything keyed on
`protectedSince` — the filter, the always-on VPN, the restriction set — stays
exactly as it was, which is precisely what a parent unlocking to change a setting
already gets.

It answers the same problem for one reason: **removal lives behind the lock.**
`RemoveActivity` is in the unlocked screen's overflow menu, so a phone that
unlocks itself is a phone whose owner can then remove drawbridge, keep it and
re-lock, or hand it on. The timer therefore did not need to implement a teardown
of its own — the teardown was already written, and pointing a clock at the *lock*
rather than at the app is both smaller and reversible. A timer that removed
drawbridge outright would also be strictly worse for the ordinary case, which is
not a lost key at all: it is a weekend offline, and nobody wants their filter
uninstalled on Sunday night.

**Two doors, one deadline.** A period chosen on the configuration screen before
locking (2 hours to 40 days, armed in `LockActivity.sealWithKey`), and a
thirty-day one that `Forgot the code` starts from a phone that is already locked.
Both write the same three numbers and both are read by the same controller, so
there is one mechanism to get right rather than two. The picker's longest entry is
forty days; the code-forgotten door is thirty and is not configurable, because a
dial on that would be a dial on how long a bypass takes.

**Of the note's three requirements, two are met as written and one is met
differently:**

- *The clock is the attack* — `DISALLOW_CONFIG_DATE_TIME` with network time, for
  as long as a deadline is armed. The decision lives in `CurfewController.apply`
  and nowhere else, because two writers of one restriction means whichever runs
  last wins; see the section below.
- *Ambiguity fails locked* — `LockTimer.hasExpired` is a pure function of three
  numbers and refuses to fire on any state it cannot make sense of: a
  half-written deadline, a deadline at or before its own arming, or a clock now
  reading earlier than the arming. Nine unit tests cover it, because the
  alternative is checking a fortnight-long rule by holding a handset.
- *Impossible to miss* — **the keyguard and the lock screen, not an ongoing
  notification.** The keyguard counts down in words — *"drawbridge unlocks in 3
  days"* — which is the surface a parent is already told to check and the one
  nobody can swipe away. A duration rather than a date, because it is read
  against nothing; the trade is that a stored string goes stale, so
  `LockTimerController.apply` rewrites it on every run, hourly at worst. The lock
  date stays on drawbridge's own screen, which is where somebody comparing it
  against a memory is already looking. A notification would mostly be seen by whoever started the
  countdown, and its visibility is not something this build can promise:
  `POST_NOTIFICATIONS` is declared in the manifest but nothing asks for it and
  nothing grants it, so on Android 13+ it may simply not appear. The
  code-forgotten line is coloured as an error and says in words that somebody on
  the phone declared the code lost, since that is the one countdown a parent may
  not have started.

**It does not buy back `DISALLOW_FACTORY_RESET`, and no build should assume it
did.** The argument above holds — an escape that is "ask and wait" makes the
restriction survivable again — but the property that note flags as lost is now
real: this escape runs *inside* drawbridge. A crash loop or a bad update takes it
with it, where a factory reset never involved drawbridge at all. Reinstating the
restriction is still [step 9 in the handoff](handoff.md#9-lock-factory-reset--last-and-only-with-the-timer),
still after everything above it, and still the most safety-critical release this
project would cut. What has changed is only that the prerequisite exists.

**The residual risk is unchanged and cannot be designed away**: somebody starts
the thirty days, nobody picks the phone up, and it lifts. The length is the only
dial, and it is set where a child cannot reach it.

## The clock is locked for a curfew and for a lock timer, and this section used to claim more

**Corrected 2026-08-17, while the lock timer was being built.** The heading here
used to read *"on every device, not only for curfews"* and the paragraph under it
said the clock pin was part of the standard lockdown. That is what
`DeviceOwnerManager.applyClockLock`'s own comment still says, and it is not what
the phone does: `applyManagedDevicePolicy` does apply the pin, and then
`CurfewController.apply` — which runs immediately afterwards on every process
start, every boot, both ends of a lock and every fifteen minutes — clears it again
unless something needs it. Two writers, and the second one wins.

So the rule as built is: **the clock is pinned while a curfew is in force, and
while a lock timer is counting down.** The timer half is new and is not optional —
a deadline is a wall-clock instant, and `SystemClock.elapsedRealtime` resets at
boot, so a phone that can be wound forward forty days is a phone whose forty-day
lock ends this afternoon. The decision is computed in one place, in
`CurfewController.apply`, from both inputs at once, because that is the component
that already runs on every trigger either answer depends on.

**Whether to make the wider claim true was asked and declined, 2026-08-18.** The
two reasons below are real and have nothing to do with curfews, which is an
argument for pinning the clock on any locked phone and deleting the condition.
The owner's answer was no: pin it only where something needs it. Taking manual
clock setting away from every phone that has neither a curfew nor a timer is a
real cost, paid to close an attack that only exists where a wall-clock deadline
does. The condition stays.

`DISALLOW_CONFIG_DATE_TIME`, with `setAutoTimeEnabled` and
`setAutoTimeZoneEnabled` where the API exists, was written for the curfew, where a
wall-clock window is advisory if the clock can be edited. But the same edit
defeats two things that exist without one:

- **The protected-since date.** Wind the clock back a year, lock, wind it
  forward: the phone now reports a year of continuous protection it never had.
  A tamper check that can be made to lie is worse than no tamper check, because
  it is trusted.
- **Whatever the parent layered on top.** Moving the device clock is the standard
  way round screen-time limits in Family Link and comparable tools. drawbridge
  does not implement those and cannot enforce them, but it can stop the phone
  lying to them.

The cost is that nobody can set the clock by hand on a phone with a curfew or a
running timer. Network time and network time zone are forced on instead, which is
right on a phone that is on a network by definition, and travel is handled by the
time zone following the network rather than the user.

## The protected-since date is the cheap tamper check

The lock screen says how long this phone has been protected, before it asks for
anything. Two timestamps sit behind it: one written at the *first* lock and never
touched again, one rewritten at every lock.

The first is the one that matters, and it is deliberately not re-stamped — if it
moved every time a parent changed a setting on a Tuesday evening, it would say
nothing about the Saturday the phone was wiped. Nothing clears it but removing
drawbridge from inside the app or a factory reset, which is exactly the event
worth catching: a child who finds a recovery-mode wipe and sets the phone up
again cannot make that date old.

It is not authenticated and does not try to be. A child holding the key can
unlock and re-lock, which moves the second date and leaves the first alone — so
what the pair actually distinguishes is "someone opened this" from "this is not
the same installation any more". Both are worth knowing and neither was visible
before.

## An option widens; only a profile can narrow

Profiles and options are both "the parent chooses", but they are not two flavours
of the same mechanism. A profile replaces fields. An option may only add to
`exempt_packages`, `allowed_domains` and an *already-enabled* allowlist.

That asymmetry buys three things. The order of application stops mattering
(profile first, then options, and no option can undo what a profile decided).
The effect of switching an option off is exactly "the base policy", with nothing
to reason about. And an option cannot turn allowlist mode *on* — which, if it
could, would mean a switch labelled as a relaxation quietly uninstalling every
app not on a list.

The cost is that an option only means something if the base policy blocks what it
allows: `blocked_packages` has to name `com.whatsapp` for "Allow WhatsApp" to be
more than decoration. That is a policy-authoring trap rather than a code one, and
it is written down in [policy.md](policy.md#options-one-switch-each).

## The launcher icons are paintings; the themed icons are still vectors

All three apps now use a painted illustration as their adaptive-icon foreground.
A themed (monochrome) icon cannot be one: the system reads only the alpha channel
and fills it with the wallpaper's colour, so a painting comes out as a solid
blob. The vectors that used to be the icons were kept and demoted to the
`<monochrome>` layer, which is why the themed variants are still a trumpet and a
line-drawn drawbridge.

An adaptive icon is also not the square the illustrations were drawn as. The
launcher draws the 108dp layer and masks it with a shape inscribed in the middle
**72dp**, so stretching the art across the layer would throw a third of it away.
`tools/make-artwork.sh` draws each picture at 72dp in the middle, where the mask
is, and fills the 18dp of bleed around it with a blurred, blown-up copy of the
same picture — so a squircle mask finds matching colour where a circle finds
nothing. Two of the masters arrive as finished rounded-square icons, meaning
white in the corners; those corners are flood-filled to transparent first, or
they would show through a squircle as four white slivers.

## Blocklists are stored as hashes, not strings

A merged adult + gambling + ad list is a few hundred thousand domains. As a
`HashSet<String>` that is tens of megabytes of heap in a process that has to stay
resident. Compiled to a sorted array of 64-bit FNV-1a hashes it is 8 bytes per
domain, memory-mapped rather than heap-resident, and lookups are a binary search.

The trade-off is that the set cannot be enumerated and collisions cause false
positives. At 10⁶ entries the chance a given lookup collides is about 10⁻¹³ —
orders of magnitude below the rate at which the upstream lists contain mistakes.

## Browsers are detected, not listed

A package-name list of browsers is out of date the moment someone publishes a new
one. drawbridge instead asks `PackageManager` which packages can handle
`ACTION_VIEW` + `https` + `CATEGORY_BROWSABLE`, and removes any that is not the
one allowed browser. Zero list maintenance, and it catches browsers nobody
thought of.

A hardcoded protected list (`AppBlocker.NEVER_TOUCH`) guards the packages that
must survive regardless — system UI, Settings, the package installer, launchers,
Play Services. Some of those *do* register `https` intent filters, and removing
one would leave an unusable phone with no way to fix it, since the removal UI
lives on that same device.

## Neither app uses an `applicationIdSuffix` in debug builds

Both package names are load-bearing. Device Owner is bound to the exact package
used at provisioning time, and it appears in the adb command and the QR payload.
herald's package is named by the policy as the one allowed browser.

This was found the hard way: a `.debug` herald was silently uninstalled by
drawbridge's app blocker seconds after the device was provisioned, because a
browser whose package name is not the allowed one is exactly what that code
exists to remove.

## USB debugging follows the lock, not the protection

Most restrictions are keyed on `ParentKey.protectedSince`, which survives
unlocking on purpose: a parent changing a setting has not withdrawn their
protection, and the phone should stay filtered while they do it.
`DISALLOW_DEBUGGING_FEATURES` is deliberately the exception. It goes on when the
key is committed and comes off when the key is used, and
`DeviceOwnerManager.restrictionsFor` is the only place that rule lives.

### Accounts are deliberately not restricted

`DISALLOW_MODIFY_ACCOUNTS` was wired into this rule on 2026-08-10 and taken back
out the same day, on the owner's decision. It is recorded because the reasoning
is worth not repeating.

The case for it was that a phone left with **no account** would stay that way, so
the Play Store could install nothing. The case against, which won: people carry
several online accounts legitimately, and blocking all of them to prevent one is
the wrong trade. It also blocks *removing* accounts, so any app signing in
through `AccountManager` becomes unusable on a locked phone — a cost paid
constantly for a benefit that only applies to a phone whose owner chose to have
no account at all.

**`DISALLOW_ADD_USER` is the restriction that actually matters here**, and it is
unconditional. Always-on VPN is per-user, so a second profile would get
unfiltered network — which is a hole, where a second Google account is just a
second mailbox.

The install guides said "once drawbridge is locked, account changes are closed
off" for as long as the project existed, and it was never true: the restriction
sat outside `MANAGED_RESTRICTIONS` and the `lockAccounts()` that applied it was
never called from anywhere. That sentence is now gone rather than made true.

### USB debugging

**The reason is that a cable is the only delivery channel this project has.**
Play Protect refuses to install `app.drawbridge.dpc`, so drawbridge cannot update
itself and cannot be provisioned by QR; what works is `tools/provision-adb.sh`.
Applying this restriction for the life of the device would mean the cable is
available exactly once, before the first lock — and a phone in the field could
then never be fixed at all. Every bug found after deployment would be permanent.

**It costs nothing that was not already given away.** An unlocked drawbridge is a
drawbridge whose configuration screen is open, and that screen offers complete
removal in its overflow menu. Somebody holding the key can already undo
everything, with or without adb. The restriction only ever protected against
somebody who does *not* have the key, and that person cannot unlock the phone in
the first place.

Two things it does not do. It does not switch USB debugging *on* — it stops the
platform refusing it, and the developer-options toggle is still a deliberate act.
And it does not weaken the locked state at all: a locked phone has no adb, which
is the whole point.

The ordering matters and is easy to get backwards. `MainActivity.lockDevice`
applies the policy *before* the parent has decided to keep the key, so this one
restriction is applied later, from `LockActivity.sealWithKey`, after
`ParentKey.commit`. An abandoned reveal — the parent presses home before writing
the key down — therefore leaves a phone that can still be worked on, which is the
same reasoning that keeps the device unsealed in that case.

`applyUserRestrictions` clears whatever the current state leaves out, rather than
only adding. Without that half the restriction could go on and never come off.

### Debug builds skip it entirely

Applying it switches off USB debugging immediately, dropping the adb connection
and leaving no way to reinstall or inspect the app. Every other restriction still
applies in debug builds — `BuildConfig.RETAIN_ADB_ACCESS`. A release build
enforces it whenever the phone is locked.

## herald has no search-suggestion dropdown

`browser-awesomebar` no longer exists in Android Components 153; the awesomebar
is Compose-only now. Rather than pull Jetpack Compose into an otherwise
View-based app, herald uses inline autocomplete from local history and a shipped
list of popular domains.

It is also a better fit for the product: search suggestions stream unfiltered
text straight from the search provider, which is exactly the content this browser
exists to keep off the screen.

## Subresource filtering goes through a web extension

`RequestInterceptor` only sees document loads — top-level pages and iframes. It
never sees images, scripts, XHR or media. A bundled MV2 extension holds a
blocking `webRequest` listener and asks the app about each new hostname over
native messaging, caching the answer for the life of the extension process.

The blocklist itself never crosses into JavaScript: it is hundreds of thousands
of entries in a memory-mapped file, and copying it into the extension's heap on
every policy update would be slow and pointless. Firefox allows a blocking
listener to return a Promise, so one native round trip per hostname is enough.

## herald is ~218 MiB per ABI, and that cannot be reduced here

GeckoView is a whole browser engine: `libxul.so` alone is 145 MiB. Per-ABI splits
are already enabled, without which a universal APK would be the sum of all three.

An earlier version of these notes claimed the size was unstripped debug symbols
and that building with an NDK would cut it by more than half. That is wrong, and
was measured to be wrong: `libxul.so` contains no `.symtab` and no `.debug_*`
sections, and `llvm-strip --strip-all` leaves it byte-for-byte identical. Mozilla
already ships stripped release libraries. Installing an NDK changes nothing.

What is actually available, if the download size ever becomes a problem:

- Publish only the ABIs you deploy to. `x86_64` exists for emulators; dropping it
  from a release removes a third of the upload, though it does not change what
  any real device downloads.
- Android App Bundles would let the store slice further, but that means Play
  distribution, which this project deliberately avoids.

Each provisioned device downloads its own ABI once, at provisioning time, and
then only on updates.
