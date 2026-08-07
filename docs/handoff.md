# Handoff — state as of 2026-08-07

Everything about *how the system works* lives in [README](../README.md),
[design-decisions](design-decisions.md), [policy](policy.md),
[provisioning](provisioning.md) and [removal](removal.md). This file covers only
what those do not: where the project actually stands, what is on the build
machine, what was and was not verified, and what to do next.

---

## Where things stand

| | |
|---|---|
| Repo | https://github.com/Nilss3/drawbridge — public, `main`, 60 commits |
| Release | [v0.1.8](https://github.com/Nilss3/drawbridge/releases/tag/v0.1.8), 9 assets, published and not flagged |
| Live policy | version **23**, live at `dist/policy.signed.json` on `main` |
| Apps, published | drawbridge, herald and herald mono, all `0.1.8` |
| Website | trilingual, generated into `site/`, on Cloudflare Pages |
| Tests | 372 unit tests across four build variants, lint clean |

### The QR path was broken by our own manifest, not by the DPC allowlist

**This section used to say the allowlist was the thing that could stop
everything. That was wrong**, and it was wrong for as long as QR provisioning
had never actually been tried on hardware.

Tried on the Moto G15 on **2026-08-07** (Android 15, API 35). The QR scanned,
the DPC downloaded, its pinned signing certificate verified, and the wizard
showed *"this Moto G15 belongs to your organization"* — then died with
*"Something went wrong"* and demanded a reset. **Play Protect never said a
word.** "App blocked to protect your device" is what the allowlist looks like,
and it blocks at *install*; the install succeeded.

The real cause was in `dpc/src/main/AndroidManifest.xml`. Since Android 11 a DPC
that the *platform* provisions — QR, NFC, cloud enrolment — must declare
activities handling `android.app.action.GET_PROVISIONING_MODE` and
`android.app.action.ADMIN_POLICY_COMPLIANCE`. drawbridge declared neither, at
`targetSdk = 36`. The wizard downloads the APK, verifies it, then tries to hand
off to the DPC to ask which mode to provision — and there was nothing to hand
off to.

**`dpm set-device-owner` never goes through that handoff.** It grants Device
Owner directly and asks nothing. That is exactly why 372 unit tests, every
emulator session and every adb provisioning passed while the one path that
exercises it had never once worked.

Fixed by [ProvisioningModeActivity](../dpc/src/main/java/app/drawbridge/dpc/admin/ProvisioningModeActivity.kt)
and [PolicyComplianceActivity](../dpc/src/main/java/app/drawbridge/dpc/admin/PolicyComplianceActivity.kt).
**Not yet retested** — the QR names `/releases/latest/download/dpc-release.apk`,
so proving it needs a published build. See [next steps](#reasonable-next-steps).

What is now unknown, rather than assumed:

- **Whether the allowlist applies to drawbridge at all.** It did not block this
  device on this date. That could be the 2026-08-06 appeal landing, or it could
  be that this Moto's GMS build does not enforce it. One data point.
- The appeal itself remains unanswerable by design: the form states decisions
  are final and no reply is sent.

Unchanged: publishing on Play would not help, since the gate is the Android
Enterprise allowlist rather than Play distribution; and non-GMS devices
(LineageOS, /e/OS, GrapheneOS) have no allowlist to enforce.

Separately, **Android developer verification does not affect drawbridge**: apps
installed by a DPC on managed devices are exempt indefinitely, as are ADB
installs.

### There is a website now, and it is generated

Trilingual (EN/NL/FR), static, on Cloudflare Pages, deploying from `main` on
every push. No framework, no client-side JS, no webfonts, no third-party
requests — the same "nothing leaves the device" posture as the app.

- `site/` is **generated output**. Edit `site-src/` and `tools/build-site.py`,
  rerun `python3 tools/build-site.py`, commit the result. Editing the HTML
  directly works for trying wording in a browser but vanishes at the next
  build; port it back. This has already happened once.
- Cloudflare needs **no build step**: framework preset None, build command
  empty, output directory `site`. Full setup in
  [site-src/DEPLOY.md](../site-src/DEPLOY.md).
- **Do not use the Workers flow.** Its form demands a required "Deploy command"
  and gets the output directory from a committed `wrangler.jsonc`; the Pages
  flow needs neither and no repo changes at all.
- The `.pages.dev` subdomain comes from the project name and **cannot be
  renamed** — delete and recreate the project to change it.
- `tools/convert_blocklist.py` mechanically converts `site-src/block-list.md`
  (~200 rows, several hundred citation links) into the English blocklist page.
  Never transcribe those by hand.
- The full cited page is **English only, permanently**. Dutch and French get a
  short summary at the same path that links to it. That is not a stub to fill
  in later.
- `build-site.py` fails the build on any internal link with no file behind it,
  added after `/nl/why-blocked/` and `/fr/why-blocked/` shipped as 404s.

### Policy 21–23: what changed

- **21** — the European Parent Safety Catalogue: 109 new packages (48 → 157)
  and 141 domains. Every package id was resolved against the Play Store rather
  than trusted from the source spreadsheet; all 119 rows named the app they
  claimed. Minecraft is still deliberately *not* blocked as a package. The
  catalogue's three baseline entries (Subway Surfers, Toca Boca World, Slay the
  Spire) *are* blocked, at the owner's explicit request, despite being listed
  there as examples of low-risk games. Also removed `anima.ai` from
  `ai-companions.txt`: it is a venture studio, not the companion app, and had
  been blocking an unrelated business since the beginning while never blocking
  Anima.
- **22** — armed `app_update`, which had never been set, pinned at the running
  `version_code` so it is inert. Drafted the curfew: schema and code exist,
  nothing reads it, no published policy carries one.
- **23** — repointed two HaGeZi blocklists that had been **404ing on every
  device**. See below.

### A signed policy is not necessarily a working one

HaGeZi moved `domains/` to `wildcard/`, and the ads/tracker/malware list and the
encrypted-DNS list 404'd on every device until someone happened to click one.
Nothing failed loudly: `PolicyStore` logs the download error, falls back to a
stale cache if it has one, and compiles whatever is left. Mullvad's `all`
upstream covers ads, trackers and malware at the resolver, which hid the gap
further.

`policytool.py sign` now fetches every URL the document names and refuses to
sign a dead third-party blocklist. Repo-hosted lists and
`required_apps`/`app_update` are warnings instead, because both legitimately
404 before the matching push. `--skip-url-check` signs offline.

### v0.1.8 is out, and was checked from the outside

Published 2026-08-02, in two commits so the release existed before the policy
named it: the code and `dist/policy.json` first, then `dist/policy.signed.json`
once the assets were up. That order is worth repeating — `required_apps` points
at `/releases/latest/download/`, and a policy that goes live first has every
device fetching a 404 for as long as the upload takes.

**Browser fixes only.** Policy 20 differs from 19 in `required_apps` and nothing
else: both editions of herald were rebuilt, so every hash moved. Nothing about
what is blocked changed, and no device loses an app over this one.

Verified against the *published* artefacts rather than the local ones:

- `/releases/latest` resolves to v0.1.8, which is neither a draft nor a
  pre-release;
- all six APKs in `required_apps` return 200, and their published sizes match
  what was staged; the two arm64 ones — the only ABI any real device here uses
  — were downloaded in full and hash to their pins;
- the QR's own `dpc-release.apk` URL returns 200 and matches;
- herald and herald mono bundle policy 19 and drawbridge bundles 20, checked by
  unzipping the built APKs.

The other four ABIs were checked by size and status rather than by hash. If one
of them ever matters, download and hash it before trusting it.

### WhatsApp is uninstalled, and has been since policy 19

This has now happened, or will at the next poll of any device that exists.

Policy 19 puts `com.whatsapp` and `com.whatsapp.w4b` in `blocked_packages` and
`whatsapp.com` / `whatsapp.net` / `wa.me` in `blocked_domains`, because the
"Allow WhatsApp (14+)" option can only mean something if the base policy blocks
what it allows. The option is `default_enabled: false`. So **any already
provisioned device that polls removes WhatsApp**, silently, within a day — and
switching the option on afterwards does not reinstall it. Intended, but not a
no-op the way policy 14 was: if a device had WhatsApp and should keep it, switch
the option on before it polls.

The same edit re-pinned `ai-companions.txt`, which is why the two files had to
be published in one commit: a device that gets the policy without the matching
list fails the checksum and drops the whole AI-companion category, not just
Grok. Remember that for any future edit to a list this repo hosts.

Signal goes the other way: `org.thoughtcrime.securesms` is now in
`exempt_packages` and `signal.org` in `allowed_domains`, because the WhatsApp
option's own text tells the parent Signal is always allowed on this device. It
was never blocked, but nothing stopped an upstream blocklist from starting to
block it, and a claim on screen ought to be enforced rather than merely true so
far.

**The document's prose is UI, and it goes stale like UI.** 17 started installing
both browsers, which made the profile description's "herald is the only browser
that can exist on the phone" false on every device reading it — and that
paragraph is the main thing a parent reads before locking. 19 is the fix, in all
three languages. Worth remembering when changing what a policy *does*: the
sentence describing it lives in the same file and does not update itself.

### Reader view is fixed, and was fixed by reading with it

**[reader-view-back.md](reader-view-back.md)** — three bugs, all the same shape:
state that cannot be believed yet. That file has the evidence, the two dead ends
not to walk again, and the adb traps that made three earlier rounds of testing
lie.

The short version of all three:

- **Back stopped at the plain article** because the second history step was
  `sessionUseCases.goBack.invoke(sessionId)`, and `sessionId` is null outside a
  custom tab. `GoBackUseCase.invoke` defaults its tab to the selected one but
  returns immediately on an explicit null, so the step was never taken. It also
  waited on `canGoForward`, which is ~400 ms early; it now waits for the load to
  end and names the tab.
- **Reader view often did not trigger** because the readability check is asked
  once, at the moment the URL changes — before the page exists to measure. Two
  Wikipedia articles that score well above Readability's threshold were being
  reported as not readerable, one of them every single time. herald now asks
  again after the page settles, up to four times at 700 ms.
- **And that fix caused a third bug**, found by using it on the phone: the
  readability answer names no page, so a late *yes* arriving during a navigation
  is an answer about the page being left — and entering reader view on it is a
  navigation, which loaded the old article over the one just asked for. Every
  click handed the previous page back. Reader view now waits for the browser to
  be idle before it comes on.

### Five browser bugs found by using v0.1.7 and v0.1.8; all fixed

None of this is in anything a device can download. Cutting v0.1.8 means the
ordinary release procedure — and, because herald changed, a policy re-pin and
re-sign with it.

| | Where | Cause |
|---|---|---|
| The address bar cleared itself while typing | both editions | `ToolbarPresenter` calls `setSearchTerms` on every state update, which in edit mode replaces the field's text. Guarded by `EditSafeToolbar`. |
| The pause ran on the way to a blocked page | mono | Nothing to think about on the way to a wall. The interceptor cancels it. |
| Back stopped at the plain article in reader view | mono | A `goBack` given a null tab id, which does nothing. See [reader-view-back.md](reader-view-back.md). |
| Reader view often did not trigger | both editions | The readability check is asked before the page can be measured, and never asked again. Same file. |
| Clicking a link put you back on the page you left | mono | A late readability answer, acted on during a navigation. Same file. |

The first two are verified on the emulator: the block page arrives with no pause
and a normal page still gets one, and a URL typed while a page loads survives
both the load and the reader-view swap that follows it. The block-page one is
verified on the phone as well.

All of it is on the phone, on **herald and herald mono 0.1.8**, release-signed
and installed in place so no bookmarks, history or session were lost — and the
third bug above was found there, by reading with it, after the first two were
called done on the emulator.

### drawbridge is one screen and one button now

The PIN is gone. So are `SetupActivity`, `ProfilePicker` and
`ParentCredentials` — replaced by a single configuration screen (language,
policy, options, status, lock) and a `LockActivity` that mints the key on the way
in and takes it back on the way out. `ParentKey` is what is left of the
credential code, and it is about a third of the size.

The reasoning is in
[design-decisions](design-decisions.md#the-pin-is-gone-and-the-key-is-the-whole-credential).
Four things will surprise someone reading the code cold:

- **A fresh key is minted at every lock**, and no secret is stored at all while
  the device is unlocked. `ParentKey.isLocked` is backed by the presence of the
  key rather than a flag of its own.
- **`ParentKey` also holds two timestamps**, and they outlive individual locks —
  `unlock()` removes the hash and salt but not them. Only `clear()` takes
  everything. See
  [design-decisions](design-decisions.md#the-protected-since-date-is-the-cheap-tamper-check).
- **Locking is the only button.** It applies the restrictions, starts the filter
  and *then* seals the screen. There is no separate "turn on protection".
- **`RemoveActivity` asks for nothing and lives in the overflow menu.** It is
  only reachable from a screen that is already behind the key, so asking again
  would be asking the same question twice.

### The screen says "policy" where the document says "profile"

They are the same thing. `Policy.profiles` is a list of variants of the one
signed document; drawbridge's screen calls the selected one "the policy", because
that is the phrase that means anything to a parent. The string resources are
named `policy_*` and the model is still `Profile`. Do not "fix" one to match the
other without deciding which audience the name is for.

### There is artwork now, and it is generated

Six painted illustrations live in `art/`: three icons and three scenes — the same
place by day, by night and at dusk.

**herald's block page carries the day and night pair** and lets its own
`prefers-color-scheme` query choose, so the picture turns with the card under it
— verified on device in both modes. They are two resources with distinct names
rather than one name with a `-night` qualifier because both have to be inlined
into the same document; a qualifier would give the page only one of them.

**drawbridge takes the dusk one**, added 2026-08-07. Its screen has no theme
query and no `-night` qualifier — one picture is shown whatever the theme is —
so the night scene it used to carry sat dark and heavy on a light-themed screen,
which is what most of them are. Dusk is warm enough for a light background and
dark enough for a dark one. Checked side by side on the emulator in both.

That master is **square**, unlike the 3:2 pair, and the hero shows it **whole** —
`wrap_content` with `adjustViewBounds` and `fitCenter`, rather than a fixed 200dp
band with `centerCrop`. The picture is composed as one: the reader on his bench
at the bottom left and the monsters at the bottom right are the point of it, and
a letterbox crop takes the spire tips off the top and the feet off the bottom. On
a phone screen the full square costs nothing but a little scrolling.

Nothing reads any of them at build time: `tools/make-artwork.sh` derives all
three launcher icons, both block-page scenes and drawbridge's hero image from
them. Run it after changing a master and commit what it writes.

The old icon vectors were not deleted — they are the `<monochrome>` layer now,
because a painting cannot be a themed icon. See
[design-decisions](design-decisions.md#the-launcher-icons-are-paintings-the-themed-icons-are-still-vectors).

### There are now two browsers

**herald** is unchanged in kind. **herald mono** is the same browser with three
things taken away — tabs, colour, and the immediacy of a page load — plus reader
view by default. It is a Gradle product flavour of the same module, package
`app.drawbridge.heraldmono`, and shares essentially all of its source; what
differs is named in `Edition` and switched on a `BuildConfig` flag.

**A managed device now gets both.** `allowed_browser_packages` names herald and
herald mono, and `required_apps` installs both — six entries, three ABIs each.
This used to be one browser or the other, and the reason was real: the app
blocker removes browsers the policy does not name, so a browser in
`required_apps` and not in the allowed list is installed and removed on a loop.
The two lists have to agree. `allowed_browser_package` (singular) still decides
which one tapped links open in.

**herald obeys drawbridge's switches, not just the document.** The browser asks
drawbridge which profile and which options are in force, over a read-only
provider guarded by a `signature` permission. That is what makes "Allow
WhatsApp" decide whether WhatsApp Web loads and not only whether the app
survives. Without drawbridge — the standalone deliverable — there is nothing to
ask and the browser follows the document's own defaults, which are the stricter
reading.

### Build-order note, as designed

`required_apps` pins the browsers by checksum and each browser embeds the policy,
so the two are circular; the documented resolution is that the browsers ship one
version behind. The bundled copy only applies until the first network poll.

In the staged v0.1.7 that offset came out right on its own, because the procedure
produces it: **herald and herald mono bundle 18, drawbridge bundles 19** —
verified by unzipping the built APKs, not assumed. Building the browsers before
re-pinning and re-signing is what makes it happen, which is why the order in
[policy.md](policy.md#releases-are-cut-locally-not-in-ci) is not a suggestion.

(During development all three bundle whatever was last signed, since the asset
lives in the shared `:policy` module. That is expected and not worth fixing
between releases.)

### Sizes

A release is now ~1.1 GiB of assets, up from ~650 MiB, because both editions
ship three ABIs each. GitHub imposes no limit that matters — the only hard one is
2 GiB per file and the largest asset is 242 MiB — so this is upload time, not
quota. Two of the three ABIs have never been downloaded by anything; see
[next steps](#reasonable-next-steps).

---

## Secrets, and where they are

Neither is in git. Both are on the build machine only, and **neither is backed
up**. This is the single largest risk in the project.

| What | Where | Consequence of losing it |
|---|---|---|
| Release signing keystore | `keys/drawbridge-release.jks`, password in `keystore.properties` | Every provisioned device is stranded on its installed version forever. Android refuses updates signed with a different key. |
| Policy signing key | `keys/drawbridge-2026-07.pem` | No device can ever be given a new policy again without reinstalling both apps. |

Both directories are git-ignored. Copy them somewhere offline **before**
provisioning any phone you care about — after that point neither can be
replaced.

The QR code pins the *release certificate*, so it stays valid across every
future release signed with that keystore. Change the keystore and every
provisioned device is orphaned and the QR must be regenerated.

---

## Build machine setup

Installed during this work, none of it in the repo:

```
brew install openjdk@21              # AGP needs 17–21; the system JDK is 24
brew install qrencode zbar           # QR generation and decode-verification
brew install gh                      # release upload; `gh auth login` done as Nilss3
brew install --cask android-commandlinetools
```

- **Gradle may not find JDK 21 from a non-interactive shell.** The toolchain is
  pinned to 21 and the only JDK on `PATH` is 24, so
  `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  may be needed. It works from an interactive shell.
- **`adb` is not on `PATH` either.** It is at
  `~/Library/Android/sdk/platform-tools/adb`.
- **The NDK is installed but useless here.** Installed to strip GeckoView's
  `libxul.so`; measurement showed the library is already stripped. Safe to
  delete. See design-decisions.md.

### Devices

- **A real phone**: Nothing A059, Android 16, arm64, serial `00146151P000419`.
  **Not managed** — no device owner. Both herald and herald mono are installed,
  on the **v0.1.7 release builds** as of 2026-08-01 (it had been on 0.1.5, not
  the 0.1.6 an earlier version of this file claimed — check rather than trust
  the note). This is still the only place rendering performance can be judged.

  It comes up `unauthorized` after a while and needs the *Allow USB debugging*
  prompt accepted on the phone before adb will talk to it. `adb kill-server`
  then re-plugging is what makes the prompt reappear.
- **Emulator `Medium_Phone_API_36.0`** — the *provisioned* one. drawbridge is
  device owner and the installed herald is a release build, so a debug herald
  cannot be installed over it.
- **Emulator `herald_test`** — created by hand in `~/.android/avd/` for browser
  work: same system image, 4 cores and 4 GB, no device owner. The Homebrew
  `avdmanager` has its own SDK root and cannot see the system images under
  `~/Library/Android/sdk`, which is why it was not created with the tool. Delete
  it if the disk is wanted back; nothing depends on it. **Wiped 2026-08-06**
  while attempting a QR provisioning test, so its browser state is gone.

  Both AVDs are `google_apis_playstore` images, so Play Protect is present —
  but see the trap above: that is still not enough to test QR provisioning.
- **A Moto G15** — arrived and used, 2026-08-07. Android **15, API 35**, arm64,
  serial `ZY32KV9J24`. Bought as a disposable provisioning target so no phone
  anyone depends on gets wiped, and it has earned that: it has been provisioned,
  removed and re-provisioned several times in a session.

  **Device Owner can be re-granted over adb on it**, because it has zero
  accounts — `dpm set-device-owner` succeeded again straight after a removal.
  Do not generalise that to a real deployment, where an account exists and
  `DISALLOW_DEBUGGING_FEATURES` has already taken adb away.

  It drops off USB after a reboot until the screen is unlocked. Set the screen
  timeout long; `svc power stayon usb` is cleared by every reboot.

---

### The first managed real device, 2026-08-07

Everything below was watched on the Moto G15 with a debug DPC (so adb survived),
and none of it had ever been seen on real hardware with a device owner.

- **The app blocker removing things.** YouTube, YouTube Music, Facebook, Opera,
  Chrome, Firefox, DuckDuckGo and TikTok, each with its reason logged. TikTok is
  one of the 109 packages added in policy 21, so that set is now confirmed as
  *actually removing apps* rather than merely holding correct package ids.
- **The first live policy update on a device** — `Compiled 7 blocklists into
  1517701 domains`, against the 255 in the bundled copy.
- **Both browsers auto-installing and surviving.** ~470 MiB fetched, hash-checked
  and installed by the DPC itself; the `ACTION_PACKAGE_ADDED` evaluation returned
  `Action.NONE` for both, and a full `sweep()` after a reboot left both alone.
  This is the loop the old one-browser rule existed to prevent, and it holds.
- **DNS filtering** — blocked names NXDOMAIN, `www.google.com` rewritten to
  `forcesafesearch.google.com`, normal names untouched.
- **Surviving a reboot and an OTA system update**, still managed, still
  filtering, `mAlwaysOnVpnPackage` intact.
- **Locking on a provisioned device**, with a working protected-since date, in
  Dutch.
- **`UpdateWorker` post-boot**: `SUCCESS`, nothing installed — browsers at 8
  against a pinned 8, DPC at 9 against a pinned 9. Armed and inert, as designed.

Four bugs came out of it, all fixed and all verified fixed:

| Bug | Cause |
|---|---|
| QR provisioning died after "belongs to your organization" | No `GET_PROVISIONING_MODE` handler; see above |
| Picking a language left only that language in the dropdown | `onRestoreInstanceState` re-applied the field's text through the *filtering* `setText`, narrowing the adapter. Labels are autonyms, so the restored text always matches exactly one entry |
| Removal left the DNS filter running | `requestStop` was called *before* always-on VPN was dropped, so Android restarted the service; then ownership went and nothing could stop it |
| Private DNS reachable despite `DISALLOW_CONFIG_VPN` | Android files it under network settings. Now covered by `DISALLOW_CONFIG_PRIVATE_DNS` |

**Private DNS is not a filter bypass**, measured rather than assumed: pointed at
`dns.google` in strict mode, resolution fails closed — blocked names stay
blocked and so does everything else, because the handshake cannot complete
through a tunnel routing only port 53. Opportunistic mode leaves the filter
intact. It was restricted anyway: failing closed is a one-tap self-inflicted
outage that looks exactly like drawbridge being broken, and the fail-closed
behaviour falls out of the tunnel's routing rather than anything anyone
designed.

### `blocked_packages` and the domain lists have drifted apart

Found by installing things on the G15. Several apps have their **domains blocked
but their packages missing**, so they install, survive the blocker, and sit
there broken — which reads as "drawbridge did not block this".

| App | Domain blocked | Package | Listed? |
|---|---|---|---|
| LinkedIn | `linkedin.com` | `com.linkedin.android` | no |
| Instants (Meta) | `instagram.com` | `com.instagram.moonshot` | no |
| TikTok Lite | `tiktok.com` | `com.tiktok.lite.go` | no |

Those three package ids came off a real device, which is stronger evidence than
policy 21's Play Store lookups. The fix worth doing is **systematic** — walk
every domain family in `social.txt`, `games.txt` and `ai-companions.txt` and
confirm the matching packages are listed — not these three by hand.

Related, and a documentation bug rather than a filtering one: the profile
description claims *"video platforms … blocked both as apps and as websites"*.
As packages that is the YouTube family and Twitch, nothing else; as domains it
adds Kick, Rumble and Bigo, and misses Vimeo, Dailymotion, Bilibili, Odysee and
BitChute. Netflix and friends are absent deliberately — the same paragraph says
streaming media stays untouched. Either broaden the lists or narrow the sentence.

## What was verified, and what was not

Verified on the API 36 emulator, end to end, over several sessions:

- Provisioning via `dpm set-device-owner`; all restrictions applied
- DNS blocking (NXDOMAIN), safe-search rewriting, DoT forwarding to Mullvad with
  zero fallbacks across 20 lookups
- Browser allowlisting — Chrome hidden, a rogue browser build uninstalled
- PIN setup, recovery-code display, wrong-PIN rejection, removal without data
  loss — *for the credential scheme that has since been replaced; see below*
- **The full auto-install path**: drawbridge fetched the policy from GitHub,
  downloaded the signed herald release, verified its SHA-256 and installed it
  silently in ~60 seconds

Verified on the unprovisioned `herald_test` emulator for the new drawbridge UI:

- **The whole lock cycle** — lock, key revealed once, *Done* disabled until the
  checkbox is ticked, reopening lands on the challenge, a wrong key rejected
  without clearing the real one, the real key accepted **in lower case and with
  the dashes left out**, and the configuration screen back afterwards
- **The protected-since date across a real `adb reboot`**: the phone came back
  locked, with the same timestamp, and the lock screen showing it
- **The option-toggle cancel path**, which used to re-fire the listener and read
  a cancelled "turn it off" as switching it on
- **`FLAG_SECURE` on the reveal**: `adb exec-out screencap` of that screen comes
  back entirely black. Which also means you cannot screenshot the key while
  testing — read it with `uiautomator dump`
- **The configuration screen** in all three languages, including the profile card
  and the option row, whose words come from the policy document rather than from
  string resources
- **The WhatsApp option** switching on and persisting to `state.json` as
  `"optionIds":["whatsapp"]`
- **The block page** rendering in GeckoView with the illustration inlined —
  worth having checked, because the picture is base64-encoded into a page that
  the loader then base64-encodes again — and doing so **in both light and dark
  mode**, showing the day scene and the night one respectively
- **All three launcher icons** in the Pixel launcher's circular mask
- **v0.1.7 on the Nothing A059**, installed from the published release APKs over
  the 0.1.5 that was on it: both editions report `0.1.7` / `versionCode 7`, both
  bundle policy 18 as designed, and the block page renders with the night scene —
  the phone is in dark mode, so that is the theme switch working on real
  hardware rather than on an emulator told what to think
- **herald following drawbridge's switch, live.** WhatsApp Web blocked with the
  option off, loading with it on, blocked again with it off — all three in the
  *same herald process* (checked by pid), so it was the `ContentObserver` doing
  it rather than a restart. Then drawbridge uninstalled entirely: herald blocks
  it again, which is the standalone fallback, and does not crash.
- **The signature permission**: `dumpsys package app.drawbridge.herald` shows
  `app.drawbridge.permission.READ_SELECTION: granted=true` on debug builds, where
  both apps share the debug key

Verified for the browser work:

- **Reader view** on the De Morgen article that Firefox offered it for and
  herald did not, past the consent gate; not offered on `about:blank`
- **uBlock Origin** installs, shows its counter, popup and dashboard
- **Bookmarks** — folders, moving, search, edit; export written and re-imported;
  a real Firefox `bookmarks.html` imported with its tree intact and its
  `javascript:` and `file:` entries rejected
- **History search** finds a page older than the paginated view
- **herald mono** — greyscale including playing video, the colour override and
  its expiry on navigation, no tabs (all three escape routes close, session
  holds one tab), reader view entering itself and staying off when dismissed,
  and the pause holding a measured 2.5 s across the reader-view swap
- **The standard edition is unchanged** by any of the mono work

Not verified, and worth doing:

- **QR provisioning end to end.** Attempted 2026-08-07 and it failed on a
  missing manifest handler, now fixed but **not retested** — proving it needs a
  published build, because the QR names `/releases/latest/download/`. This is
  still the largest untested path, but it is no longer an unknown *cause*.
- **`AppBlocker.sweep()` driven from the UI.** Applying a policy and turning the
  WhatsApp option **off** both call it, and neither has been watched removing
  anything. The blocker itself is now well proven on hardware; what is untested
  is these two entry points into it.
- **Locking on a provisioned device taking the silent VPN path.** Locking works
  there — that much was watched — but every earlier test ran without Device
  Owner and so took the consent-prompt path, and it has not been confirmed that
  the silent one is what actually happens now.
- **What locking does *after* a removal.** Reported as "locking doesn't work"
  on a device whose Device Owner had been given up. Without ownership it can
  still start the filter through the VPN consent prompt but can apply no
  restrictions, so it half-works — which is worse than failing outright. The
  exact symptom has not been pinned down, and the UI still offers the button.
- **The Dutch and French translations by someone who reads them properly.** They
  are complete and lint-clean, not reviewed.
- **Reader view over a slow connection.** Everything now waits for a page to
  settle before reader view comes on, and every measurement was made on a fast
  network. How long the plain article shows first, on a bad one, is unknown.
- **Whether the address bar still clears.** It had one definite cause, which is
  fixed and covered by a test, but it was reported as intermittent and only
  sustained use will say whether that was the whole of it.
- **herald mono under sustained real use.** It is installed on the phone but
  only briefly exercised. In particular **TextureView rendering performance** —
  mono moves GeckoView off its default SurfaceView, which copies a frame more.
  Scrolling a long image-heavy page and playing fullscreen video are the things
  to watch. Nothing an emulator says about this is meaningful.
- **A policy version *changing* under a running device.** The G15 fetched policy
  23 and built its tunnel from it, which is the first half; what has still never
  happened is a device sitting on version *n* and being handed *n+1*.
- **The curfew.** Drafted in policy 22 and never run on a device. Nothing
  reads it and no published policy carries one; wiring it up means calling
  `CurfewController.apply` from `BootReceiver` and after a policy refresh.
  The failure that matters is one that does not lift.
- **The self-update path.** `app_update` is set as of policy 22, but it pins the
  version already installed (`version_code` 9), so `checkAndInstallSelf` still
  returns `UpToDate` without downloading anything. The path is armed, not
  exercised. The first release that raises the version code is what will test
  it, and that is also the first chance to get the publish order wrong — see
  [policy.md](policy.md#drawbridge-updates-itself-the-way-it-updates-herald).
- **uBlock Origin on a managed device.** Its filter-list hosts were checked
  against the live blocklists and none are blocked, and policy 12 allowlisted
  them so an upstream list cannot start blocking them later — but no managed
  device has been watched updating a filter list.

---

## Traps that cost time here

Each of these looks like a bug and is not, or bites silently:

- **Release builds kill adb.** `DISALLOW_DEBUGGING_FEATURES` switches off USB
  debugging the instant it applies. Install everything *before* provisioning.
- **Neither app may take an `applicationIdSuffix`.** A `.debug` herald is
  uninstalled by drawbridge's own app blocker seconds after provisioning. A
  product *flavour* with its own `applicationId` is a different thing and is
  fine — that is what herald mono is.
- **Debug and release builds cannot replace one another** — different signing
  keys. Uninstall first, or build the variant that matches what is installed.
- **The app blocker hides Chrome**, so hidden packages vanish from
  `pm list packages`; use `pm list packages -a`.
- **Testing removal wipes the key**, and so does `pm clear`. Both leave the
  device unlocked, which is right — but it means a test run never exercises the
  challenge screen unless you lock again first.
- **The reveal screen cannot be screenshotted.** `FLAG_SECURE` is on it
  deliberately, so `screencap` returns black and there is no way to read the key
  back out of an image. Use `adb shell uiautomator dump`, which is not blocked.
- **aapt drops asset directories starting with an underscore.** Its default
  ignore list contains `<dir>_*`. This cost an afternoon when uBlock Origin's
  `_locales/` vanished from the APK and Gecko reported only
  `Extension is invalid`. `herald/build.gradle.kts` overrides the pattern list;
  do not "tidy" it away.
- **A product flavour renames Gradle's APK outputs**, and `required_apps` pins
  them by URL. `tools/stage-release.sh` fixes the names and refuses to stage a
  set the signed policy does not match. Use it; do not upload from
  `build/outputs` directly.
- **`ReaderViewMiddleware` is load-bearing and fails silently.** Without it the
  reader feature never re-checks a page after navigation. No exception, no log.
- **`SessionUseCases.goBack.invoke(null)` does nothing**, silently. The parameter
  *defaults* to the selected tab, but an explicit null returns before dispatching
  anything — and `sessionId` is null in every browser fragment that is not a
  custom tab. This cost two rounds on the reader-view back bug; pass `tab.id`.
- **The readability check is asked once and dropped if nothing answers.**
  `ReaderViewMiddleware` asks at the URL change, before the page exists;
  `checkReaderState` clears `checkRequired` whether or not a port was connected
  to hear it. Anything that wants a true answer has to ask again — see
  [reader-view-back.md](reader-view-back.md).
- **`ToolbarPresenter` writes into the field being typed in.** Every
  `BrowserState` update calls `setSearchTerms`, and in edit mode that *replaces*
  the text. `EditSafeToolbar` is the guard; anything that talks to the toolbar
  through the `Toolbar` interface has to go through it, and it owns the single
  `setOnEditListener` slot.
- **In mono, anything that turns reader view off has to say so.** The automatic
  entry re-reads `readerable && !active` and will put the article straight back,
  which is what made the back button look dead. Set `dismissedForPage`.
- **The phone sleeps mid-test**, which produces entirely black screenshots that
  look like a rendering bug. `adb shell svc power stayon usb` while testing, and
  set it back to `false` afterwards.
- **A wrong package id is inert; a wrong *domain* is not.** `anima.ai` sat on
  `ai-companions.txt` from the beginning: it is a venture studio, so it blocked
  an unrelated business for months while never blocking the Anima app, which is
  `myanima.ai`. Nothing reports this. Resolve a domain and look at what answers
  before adding it — several `games.txt` candidates were dropped the same way
  (`frostpunkmobile.com` had lapsed to a gambling site, `nuverse.com` is a
  financial firm).
- **Upstream blocklist URLs rot silently.** See policy 23. `sign` now checks
  them, but the deeper lesson is that a valid signature says nothing about
  whether the internet still agrees with the document.
- **`site/` is generated.** Hand-edited HTML disappears at the next
  `build-site.py` run, with no error. It has happened once already.
- **The Play-image emulator cannot test QR provisioning.** No consumer Setup
  Wizard, no `DISPATCH_PROVISIONING_MESSAGE` for adb shell, and `adb root` is
  refused. Rooting requires a non-Play image, which has no Play Protect —
  the thing under test. See next steps.
- **Cloudflare's Workers flow is not the Pages flow.** Workers demands a
  required "Deploy command" and a committed `wrangler.jsonc`; Pages needs
  neither. If the form asks for a deploy command, you are in the wrong one.
- **`dpm set-device-owner` proves nothing about QR provisioning.** It grants
  ownership directly and never launches `GET_PROVISIONING_MODE` or
  `ADMIN_POLICY_COMPLIANCE`. A DPC can pass every adb and emulator test and
  still be structurally incapable of being provisioned by a QR code. This cost
  the entire QR path, silently, for the life of the project so far.
- **Teardown order is as load-bearing as setup order.** `releaseDeviceOwnership`
  is what drops the always-on VPN, and while always-on is set Android *restarts*
  the filter the moment it stops. Stop the service before that and it comes
  straight back, then ownership goes and nothing can stop it again. The same
  hazard is why hidden browsers are un-hidden before ownership is released —
  it was understood for one teardown step and missed for the other.
- **Restoring view state can overwrite state you just derived.** The language
  picker binds itself from `Languages.current()` in `onCreate`, and
  `onRestoreInstanceState` then put the old text back through
  `AutoCompleteTextView`'s *filtering* `setText`, narrowing the adapter to its
  matches. Anything whose content is fully derived should carry
  `isSaveEnabled = false` rather than trust that the restore is harmless.
- **A restriction can sit in a Settings screen you did not think you owned.**
  `DISALLOW_CONFIG_VPN` does not cover Private DNS, because Android files that
  under network settings. Check where a setting actually lives before assuming
  a related restriction covers it.
- **A blocked domain is not a blocked app.** `blocked_packages` and the domain
  lists are curated separately and drift. An app whose domains are blocked but
  whose package is not still installs, survives the blocker and sits on the
  phone looking unblocked. See LinkedIn, Instants and TikTok Lite above.

---

## Reasonable next steps

1. **Cut a release carrying the provisioning fix, and retest the QR.** This is
   the one that unblocks everything else, and it cannot be shortcut: the QR
   payload names `/releases/latest/download/dpc-release.apk`, so the fix has to
   be *published* before it can be proven.

   The cheap way to prove it first, without a 1.1 GiB upload or a policy
   change: publish the release-signed DPC as a **pre-release** and generate a
   test QR pointing at that explicit tag URL. GitHub excludes pre-releases from
   `/latest`, so every browser URL in `required_apps` keeps resolving and no
   policy has to move. The QR pins the *certificate*, not the APK, so a test QR
   against the same keystore is valid. If it provisions, the same bytes become
   the real release.

   Then, for the real one: **it must carry all seven assets.** A release with
   only the DPC in it makes `/releases/latest/download/herald-*.apk` 404 on
   every device — the policy-23 failure, self-inflicted. The browsers need no
   rebuild, only re-upload. Raise the DPC's `version_code`, publish the assets,
   *then* publish the policy naming the new code and hash. That ordering is
   also the first genuine test of the self-update path.

   **The emulator cannot substitute for the QR test.** Its system image ships no
   consumer Setup Wizard, so there is no six-tap flow; the trusted-source
   provisioning intent needs `DISPATCH_PROVISIONING_MESSAGE`, which adb shell
   does not hold; and Play images refuse `adb root`. An image you *can* root is
   one without Play Protect. That is a dead end — do not spend an afternoon on
   it again.

2. **Reconcile `blocked_packages` against the domain lists** — policy 24. See
   the drift table above. Systematic pass, not the three known names, and
   settle the video-platforms sentence in the same edit.

3. **Keep both keys backed up.** They were backed up before v0.1.7 went out; from
   here on every published release depends on that staying true.
4. **Use herald mono properly and decide whether it is right.** Specifically
   whether the TextureView backend costs frame rate, whether 2.5 s is the right
   pause, and whether reader-view-by-default is too eager on news sites. If
   scrolling drags, the documented fallback is a CSS `filter: grayscale(1)`,
   which costs `position: fixed` breakage instead.
5. Then, in rough order of value:
   - **Drop unused ABIs.** `armeabi-v7a` and `x86_64` have never been downloaded
     by anything, and now cost double what they did. Dropping both would take a
     release from ~1.1 GiB to ~450 MiB. `x86_64` was kept deliberately for
     Chromebooks; `armeabi-v7a` is 32-bit ARM and `minSdk` is 28, so it is
     almost certainly dead weight. Removing an ABI means removing its
     `required_apps` entry at the same time.
   - **Decide whether the APK download path deserves an allowlist entry.**
     `required_apps` points at `github.com`, which redirects to a
     `*.githubusercontent.com` asset host that has not been pinned down; a
     blocked redirect target would stop auto-install silently.
   - **Build the WebADB installer.** The `/install/` page has a visibly
     disabled "Install over USB" button waiting for it. A static page using
     [ya-webadb](https://github.com/yume-chan/ya-webadb) can push the DPC APK
     and run `dpm set-device-owner` over WebUSB from Chrome or Edge, with no
     local software. **This is the hedge against the DPC allowlist**: nothing
     in that flow asks Play Services for permission, so a rejected appeal
     stops being a release blocker.

     The Windows driver problem that looked like a blocker **is not one**:
     Josh Gao added Microsoft OS Descriptors to the adb gadget in Android 10
     (API 29) with a CTS test, so every certified device since advertises
     `WINUSB` and Windows binds `winusb.sys` automatically. No Google USB
     driver, no Zadig. Zadig only matters pre-Android-10, or where an OEM
     driver already claimed the interface.

     Real constraints: Chromium and an HTTPS origin; no local `adb` server
     running (it claims the device exclusively); the device must have no
     account yet; and it is one-shot, since `DISALLOW_DEBUGGING_FEATURES`
     kills adb the moment provisioning applies.
   - **Localise herald.** drawbridge is now English, Dutch and French; the
     browser is still English-only, with ~45 strings. Note that drawbridge
     cannot set it — the picker is a *per-app* locale and no API lets one app
     set another's, so herald needs a picker of its own in its settings.
   - **Exercise the self-update path.** `app_update` is set as of policy 22, but
     pins the running version, so nothing has ever downloaded through it. The
     next release is the test: publish the APK, *then* the policy naming its
     version code and hash.
   - **Finish the curfew.** The model, window arithmetic and Device Owner calls
     are drafted and tested; nothing calls them. What is left is the UI, and
     three wiring points: `CurfewController.apply` from `BootReceiver` and after
     a policy refresh, and a `<receiver>` entry for `CurfewReceiver`. See
     [policy.md](policy.md#curfew-an-evening-with-no-internet) and
     [design-decisions](design-decisions.md#the-curfew-is-that-same-lockdown-used-on-purpose).
     **Nothing has ever been on a device**, and the one thing worth watching for
     first is a curfew that will not lift — a failure to *leave* lockdown is a
     phone with no internet and no way to fix it from the phone.

---

## Working notes for whoever picks this up

- `./gradlew test lint` is the fast check. `assembleDebug` now builds **both**
  browser editions, so it takes about twice as long as it used to.
- Release procedure is in [policy.md](policy.md#there-is-no-release-workflow),
  and the order matters: herald first, then hash and sign, then drawbridge.
  `tools/stage-release.sh` after the builds.
- `python3 tools/policytool.py sign --key-id drawbridge-2026-07` then `verify`
  after any `dist/policy.json` edit, and copy the result over
  `policy/src/main/assets/drawbridge/default-policy.json`. `sign` now fetches
  every URL the policy names and refuses to sign a dead third-party blocklist;
  `--skip-url-check` to sign offline.
- `python3 tools/build-site.py` after any website change, and commit what it
  writes. `site/` is generated; hand-edited HTML is silently overwritten.
- **Re-signing an unchanged policy still rewrites the file**, because ECDSA
  signatures are non-deterministic. If the base64 payload is identical, restore
  the published signature rather than committing a new one — there is no reason
  to churn a file every device fetches.
- `tools/vendor-ublock.sh` re-vendors uBlock Origin against a pinned hash. Do it
  as part of cutting a release; uBO's *code* is frozen until then, though its
  filter lists update themselves.
- `tools/make-artwork.sh` regenerates every icon and scene from `art/`. It needs
  ImageMagick and nothing else, and is never invoked by Gradle.
- The emulator needs `-wipe-data` to test provisioning again, since device owner
  cannot be granted twice.
- Watch the filter with
  `adb logcat -d | grep -F -e DnsFilterService -e AppInstaller -e EncryptedDns`,
  and the browser with
  `-e herald-greyscale -e herald-engine -e herald-content-blocker`.
