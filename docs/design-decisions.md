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
- **Fullscreen video needs no special case.** The insets go to zero while the
  bars are hidden, so the padding collapses on its own.

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
else — including whatever the phone's locale brought in. Engines Mozilla does not
bundle (Brave, Startpage, Kagi) or bundles only for some locales (Ecosia, Qwant)
are added by herald as custom engines with their own URL templates, so the list
does not change when the phone travels.

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

## Debug builds skip `DISALLOW_DEBUGGING_FEATURES`

Applying it switches off USB debugging immediately, dropping the adb connection
and leaving no way to reinstall or inspect the app. Every other restriction still
applies in debug builds, and release builds always enforce it —
`BuildConfig.RETAIN_ADB_ACCESS`.

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
