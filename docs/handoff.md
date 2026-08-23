# Handoff — state as of 2026-08-19

Everything about *how the system works* lives in [README](../README.md),
[design-decisions](design-decisions.md), [policy](policy.md),
[app-ratings](app-ratings.md), [provisioning](provisioning.md) and
[removal](removal.md). This file covers only what those do not: **where the
project actually stands, what has never been watched working, what will bite you,
and what to do next.**

**Cut down from 5,161 lines on 2026-08-18.** It had become a day-by-day narrative
of forty investigations, most of them long settled, and at that length it hid the
five things somebody picking this up actually needs. What went: the chronology.
Where it went: `git log` has every word, the *reasoning* is in
[design-decisions](design-decisions.md) where it belongs as *why the code is like
this*, and anything that cost a day is in [Traps](#traps-that-cost-time-here),
which is kept whole on purpose.

---

## Where it stands

| | `main` (the alpha) | `dev` |
|---|---|---|
| drawbridge | 0.2.7, build 18 | **0.2.16, build 41** |
| herald | 0.1.9 | **0.1.14** |
| policy | **52** | **81** |
| install page | <https://drawbridge-project.pages.dev/install/usb/> | <https://dev.drawbridge-project.pages.dev/install/usb/> |
| phone | the owner's Nothing Phone (A059) | the Moto G15 |

**Both apps work end to end on real hardware.** A phone is provisioned over a
cable, filters DNS for every app, removes what the policy disallows, installs
herald by itself, locks behind a hundred-bit key, and can be handed back with no
data loss. What is *not* settled is everything in
[next steps](#reasonable-next-steps), and the alpha is one person's daily phone
rather than a released product.

### Read this first: two channels, and which is which

**`main` is deliberately behind, and that is not neglect.** The alpha is what a
tester installs and what the owner's own daily phone runs. Policy *content* is
kept roughly in step; the *builds* are not, because the dev work is still being
found wanting on hardware roughly once a day.

**Three things keep the two apart, and getting any of them wrong breaks the
alpha:**

1. **`required_apps` resolves through `/releases/latest/download/`.** Whichever
   GitHub release holds the **latest** flag is what every alpha phone installs.
   `v0.2.5` holds it, which is why herald 0.1.9 is what `main` delivers. The dev
   releases are pre-releases explicitly **not** flagged latest, and dev's policy
   pins their **versioned** URLs instead. A herald release that took `latest`
   would change the alpha without drawbridge moving at all.
2. **`policytool.py sign` rewrites blocklist URLs to the branch it runs on**, so
   signing on `main` produces `main` URLs and the merge trap cannot be set by
   hand. `app_update` and `required_apps` are **not** rewritten — they are
   deliberate values, and they are the two fields that must never travel between
   branches.
3. **Both apps read `drawbridgePolicyUrl`** from `gradle.properties`, which only
   `dev` sets.

**A dpc-only release does not touch GitHub releases at all.** The APK is served
from the channel's own Pages site (`site/assets/dpc-<digest16>.apk`, pinned by
`app_update`), which is how builds 28–37 shipped, and build 40 after them. Only a
herald change needs a GitHub release.

**Two releases went out on 2026-08-19**, and they are the two shapes:
`v0.2.8-dev.5` carried herald 0.1.14 and drawbridge build 39 and needed the whole
GitHub dance; build 40 changed only the DPC and needed none of it. Policies 75 to
80 went out between and around them, several of which reached the phone with no
build at all.

---

## What is enforced, and when

This changed twice on 2026-08-17 and is the thing most likely to be
misremembered.

| Moment | What happens |
|---|---|
| **Provisioning** (cable) | Device Owner granted. herald is fetched. Nothing is removed, no restriction is applied — that window is for adding a Google account, setting a screen lock, and moving data off. |
| **Installation onwards** | The DNS filter runs. **Apps no switch can bring back are removed**: the blocklist, browsers the policy never sanctioned, the store rule, allowlist mode. The store's catch-up scan runs within a minute on Wi-Fi. |
| **The lock** | The restrictions land, USB debugging goes, and the removals a switch *governs* happen — WhatsApp, Telegram, YouTube, streaming, browsers the chooser narrowed away. A key is minted and shown once. |
| **Unlocking** | The key is dropped. The filter, the VPN and the restrictions stay, because they are keyed on `protectedSince` rather than on the lock. Removal of what a switch governs pauses again, so a parent can install and migrate. |
| **The timer, if set** | 2 hours to 40 days, chosen before locking; or thirty days from `Forgot the code` on a locked phone. It ends the lock and nothing else. |

**Why removal no longer waits for the lock:** not everybody is going to lock. A
phone that filters the web and drops social media, undoable only by a factory
reset, is already most of the value, and the old design gave that person nothing
until they pressed a button they might never press. The consequence — apps
disappearing minutes after installation, before anybody has agreed to anything on
the phone — is why the warning moved ahead of the cable, in the install pages and
`docs/install*.md`.

---

## The way in is adb, and Play Protect is why

**`app.drawbridge.dpc` cannot be installed on a certified Android device by any
route Google controls.** This is the single largest constraint on the project and
it is not a bug in this code.

The controlled test, on a freshly reset G15 with **zero Google accounts**, no
device owner and no device admins, minutes apart over adb:

| Installed | Result |
|---|---|
| `app.drawbridge.dpc` | `INSTALL_FAILED_VERIFICATION_FAILURE`, Play Protect dialog |
| `app.drawbridge.probe` — same code, same signing key, different `applicationId` | **installs silently, and provisions by QR** |

`logcat` names the refuser: `com.android.vending/…finsky.protectdialogs.activity.PlayProtectDialogsActivity`.
That one table excludes everything else — not the code, not the key (the probe
shares it), not the device, not leftover state, not the account (there is none),
and not being a DPC (the probe is one). **Play Protect refuses this package by
name, at install, device-wide.**

Two consequences follow, and both are lived with rather than fixed:

- **drawbridge cannot update itself *unattended*.** The Update screen exists,
  explains why, and asks the parent to pause Play Protect for a minute — and
  that route works: the owner took the alpha from build 18 to build 41 with it
  on 2026-08-19, no cable involved. What is still missing is an update that
  needs nobody at the phone. See
  [next step 1](#1-get-drawbridge-able-to-update-itself-again).
- **QR provisioning is closed**, because the wizard cannot install the DPC. The
  cable is the route: `tools/provision-adb.sh`, or the WebUSB installer page,
  which is what a tester actually uses.

**The routes out**, neither taken: appeal through Google's Play Protect form
(<https://support.google.com/googleplay/android-developer/contact/protectappeals>),
or rename the package — mechanically cheap now (`dpcApplicationId` is a Gradle
property, the permission and provider authority are placeholders, herald needs no
rebuild) and a **one-way door**, because Device Owner binds permanently to the
package name. A rename might buy years or days and nothing here tells you which.

**The cable stays available**, which is the other half of why this is survivable:
USB debugging follows the *lock* rather than the protection, so a parent holding
the key can always unlock and put a build on the phone. See
`DeviceOwnerManager.restrictionsFor`.

---

## Devices

- **Moto G15** (Android 15, MediaTek) — the dev phone. Everything on `dev` is
  tested here first. It is also where every hardware surprise in
  [Traps](#traps-that-cost-time-here) came from.
- **Nothing Phone (3a)** (model A059) — the alpha phone, the owner's daily
  device, on `main`. Do not experiment on it. Running build 41 without
  trouble as of 2026-08-19, which is what the website's beta note now says.
- **Dumber Mini** (LineageOS 21, Android 14) — a third handset, and the first
  that is neither stock Android nor an OEM's version of it. drawbridge works on
  it, restrictions included: measured over adb on 2026-08-19, Private DNS is
  greyed in Settings and cannot be written even from a shell. See
  [12e](#12e-private-dns-on-lineageos--investigated-2026-08-19-nothing-wrong).
- **API 36 emulator** (`Medium_Phone_API_36.0`, and `herald_test`) — provisioned
  as Device Owner, and good for everything except what needs a real OEM: the
  keyguard's crowding, Doze, and Play Protect's behaviour, which it does
  reproduce but only after `-wipe-data`.

---

## What has been watched working, and what has not

**Watched working on hardware**, most recently on 2026-08-19 with build 40:

- **Android Auto connects wirelessly with the filter running.** Confirmed in the
  car on 2026-08-19, which closes the one thing this project had shipped and
  never seen work. It also settles the mechanism underneath it: Android Auto asks
  `ConnectivityManager` whether there is a VPN rather than looking for a `tun`
  interface, so leaving a package out of the VPN's UID ranges genuinely makes the
  VPN invisible to it. That is why split tunnelling is the fix, and it is now
  measured rather than argued — which matters for the next app that refuses to
  run beside a VPN.
- **The restriction set holds on LineageOS**, the first non-OEM Android any of
  this has run on. Private DNS is greyed in Settings, and `settings put` from an
  adb shell is silently ignored rather than refused, so enforcement is in the
  settings provider rather than the UI. Measured 2026-08-19; see
  [12e](#12e-private-dns-on-lineageos--investigated-2026-08-19-nothing-wrong).
- **A removal a switch governs hides the app instead of uninstalling it**, so
  herald survives the browser chooser going back and forth without a
  quarter-gigabyte download each way, and WhatsApp comes back with its chats. The
  owner confirmed it on the G15 the day it shipped. What that turned on is in
  [design-decisions](design-decisions.md#a-removal-a-switch-can-undo-hides-the-app-instead-of-uninstalling-it);
  the thing to re-check after any change near it is that a package **no** switch
  governs still goes for good.
- A clean cable install removing Temu, TikTok Lite, Fruit Ninja, Amaze GO!, Evony
  and Color Switch **35 seconds after installation**, by store rating and
  category, on a phone that had never been locked — while WhatsApp, YouTube,
  Telegram and Netflix stayed, because a switch governs them.
- The lock timer: a real two-hour timer lifting on its own, the keyguard counting
  down in words, and drawbridge's own screen leaving the challenge by itself when
  the deadline passed.
- The filter, the app rules, herald's auto-install, the lock cycle with a key
  typed back in lower case without dashes, and removal with no data loss.

**Never watched working**, and worth knowing before trusting it:

- **A phone that only ever sees mobile data.** The catch-up scan is
  Wi-Fi-only, so preloaded games would survive indefinitely. Fixed on 2026-08-18 — see
  [decisions taken](#decisions-taken-2026-08-18-so-they-stop-being-questions) —
  and never watched working on such a phone.
- **`Forgot the code` running its full thirty days.** The mechanism is tested and
  the door works; nobody has watched a month elapse.
- **An inexact alarm surviving days of Doze** on an OEM build. The hourly worker
  is the backstop, and it has never been the thing that fired.
- **The install lock's closed set removing an app it was right about**, which has
  been on this list since build 30.
- **A second person installing any of this**, from the website, without the
  author in the room.
- **Mono's slower fling on a real phone.** It is measured and watched working on
  the emulator, where scrolling is a scripted swipe rather than a thumb — see
  [next step 12](#12-herald-mono-take-out-always-on-reader-view--done-2026-08-19).
  Whether 0.05 is friction or annoyance is a question only a day of use answers.

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
  custom tab. This cost two rounds on the reader-view back bug — whose code is
  gone, while the trap is not; pass `tab.id`.
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
- **The Gecko pref that slows scrolling is not the one it looks like.**
  `apz.fling_friction` is `GenericFlingAnimation`'s, and Android does not use
  that animation: the fling here is Chrome's physics, under
  `apz.android.chrome_fling_physics.friction`. Both prefs exist in this
  GeckoView and both are given values in its own `geckoview-prefs.js`, so the
  wrong one is set successfully, reads back correctly and does **nothing** —
  and so does `apz.max_velocity_inches_per_ms`, measured inert at a fourteenth
  of its shipped value. `strings libxul.so | grep '^apz\.'` on the AAR is what
  settled which prefs this build actually reads, and is the tool to reach for
  next time a pref appears to be ignored.
- **The phone sleeps mid-test**, which produces entirely black screenshots that
  look like a rendering bug. `adb shell svc power stayon usb` while testing, and
  set it back to `false` afterwards.
- **Lint will demand `REQUEST_DELETE_PACKAGES` back, and it is wrong.**
  `MissingPermission` fires on `PackageInstaller.uninstall` in `AppBlocker`,
  because the platform annotation does not model the Device Owner waiver — the
  same blind spot that would have kept `DELETE_PACKAGES` in the manifest. It is
  suppressed there with the evidence attached. Adding the permission back to
  silence a lint error would undo a deliberate change and reopen the Play
  Protect question; the emulator check is what settles it, not the annotation.
- **A failed QR provisioning factory-resets the phone.** It does not drop back
  into the setup wizard, so there is no chance to enable USB debugging and read
  the logs afterwards — the buffer goes with the wipe. Any diagnosis of a
  provisioning failure has to be built from what is on screen plus what can be
  tested separately over adb on a device that has finished setup. This is the
  main reason the 2026-08-07 failure took a day to find.
- **adb installs are *not* exempt from Play Protect, and the emulator lies about
  it.** The exemption people remember is from the unknown-sources consent prompt,
  not from the verifier: `adb install` is verified by default, which is why the
  `verifier_verify_adb_installs` global exists at all. Measured 2026-08-10 — the
  Moto G15 has it unset, so the platform default (verify) applies and
  `app.drawbridge.dpc` is refused; **the Play emulator image ships with it set to
  `0`**, so every adb install there sails through untouched.

  That is what made the emulator look inconsistent for a whole session: dozens of
  `adb install` runs of the refused package succeeded, while the one install the
  DPC started itself — a `PackageInstaller` session, not an adb install — was
  blocked with a Play Protect dialog. Nothing was contradicting anything; two
  different code paths were being compared.

  `adb shell settings put global verifier_verify_adb_installs 1` on the emulator
  turns verification on and should reproduce the refusal locally, on a device
  with no account and nothing to lose. That is the local rig this problem has
  wanted all along, and it costs no factory resets.

  **And the inverse of that trap is the way in.** The same global set to `0` on
  the *phone* is what makes `app.drawbridge.dpc` installable there, which is the
  whole of `tools/provision-adb.sh`. The fact that spent a session looking like
  the emulator contradicting itself turned out to be the mechanism worth having.
- **A generator that hardcodes the package name will bite whoever renames it.**
  `tools/qrpayload.py` had `ADMIN_COMPONENT` as a literal `app.drawbridge.dpc/...`,
  so a payload built for any other package silently named a component that did not
  exist, and provisioning failed for a reason unrelated to what was being tested —
  caught on 2026-08-10 while generating a probe QR, before it cost a factory
  reset. That tool is gone with the QR path, but the shape of the mistake is not:
  anything generated per-package should read the package from the APK.
- **A wrong package id is inert; a wrong *domain* is not.** `anima.ai` sat on
  `ai-companions.txt` from the beginning: it is a venture studio, so it blocked
  an unrelated business for months while never blocking the Anima app, which is
  `myanima.ai`. Nothing reports this. Resolve a domain and look at what answers
  before adding it — several `games.txt` candidates were dropped the same way
  (`frostpunkmobile.com` had lapsed to a gambling site, `nuverse.com` is a
  financial firm).
- **Upstream blocklist URLs rot silently.** See policy 23. A valid signature says
  nothing about whether the internet still agrees with the document. `sign`
  checks them — but only when you sign, and a policy that needs no edits can rot
  for months. `python3 tools/policytool.py verify --check-urls` runs the same
  check with no key and no signature, and is the thing to put on a monthly
  reminder. It is deliberately stricter than the signing check: in a published
  policy a 404 on a repo-hosted list or a release asset is fatal rather than a
  warning, because there is no "not pushed yet" left to excuse it.
- **A pushed list is not a served list for several minutes.**
  `raw.githubusercontent.com` caches, so after committing a policy and its
  pinned list together there is a window — measured at roughly three to five
  minutes on 2026-08-08 — where the new policy is served alongside the *old*
  list. A device polling inside it fails the checksum and drops that whole
  category until its next poll. Committing them together is necessary and not
  sufficient; verify the served file hashes to the pin before assuming a policy
  is live.
- **`site/` is generated.** Hand-edited HTML disappears at the next
  `build-site.py` run, with no error. It has happened once already.
- **`git checkout <other-branch> -- <file>` silently discards whatever that file
  held on this branch.** It is the obvious way to port a doc change from `dev` to
  `main`, and it takes the *whole* file — so an edit made only on `main` is gone
  without a conflict or a warning. That is how the Android Auto result, recorded
  on `main` alone, was reverted twice on 2026-08-19 by later ports of unrelated
  sections. Write shared documentation on `dev` first and let it travel one way,
  or check `git diff dev -- <file>` before the checkout.
- **`dist/release/dpc-release.apk` is shared between branches**, so building a
  release on one channel leaves the other channel's staged APK wrong, and
  `build-site.py` then refuses to run — correctly, since the installer page must
  hand out the build the policy names. The fix is to fetch that channel's
  published APK back into `dist/release/`, not to force the build. It happened
  twice on 2026-08-19 while moving between channels.
- **Editing `build-site.py`'s copy by string-matching corrupts it, in two
  different ways, and both were walked on 2026-08-19.** Scanning backwards from a
  match to find the opening quote stops at an apostrophe *inside* French text, so
  the replacement is spliced into the middle of the sentence. Using `ast` node
  positions instead is right, but `col_offset` is a **UTF-8 byte** offset while
  Python string indices are characters: on any line carrying an em-dash or an
  accent the span overshoots the closing quote, and the edit silently eats the
  next entry. Work in `src.encode()` and assert the span starts and ends on a
  quote before splicing. Both failures parse afterwards, which is what makes them
  worth this paragraph.
- **A hidden package is "not installed for this user", and that is the same bit.**
  `getPackageInfo` throws for a hidden app exactly as for an absent one, and
  `isApplicationHidden` returns true for a package the phone has never had. So
  anything asking "is this here" about an app drawbridge may have hidden needs
  `MATCH_UNINSTALLED_PACKAGES` — `AppInstaller` would otherwise re-download
  herald to replace the copy already on the disk, which is the whole thing the
  hiding change exists to prevent.
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

- **WorkManager cancels running work when the app is replaced**, and installing
  drawbridge *is* an app replacement. That is why the store rule's catch-up scan
  never ran on a freshly installed phone: it started, was cancelled before the
  first request, and reported `asked about 0 of 60 (stopped early)`. Work that
  must happen *because* the app was just installed does not belong in
  WorkManager. It runs in the filter service now — see `StoreScan`.
- **Two jobs carrying the same worker collide.** The one-off scan and the
  fortnightly rescan are separate unique names, so both can run at once, and the
  logs read as one job misbehaving rather than two.
- **`am force-stop` is refused for a Device Owner** — *"Ignoring request to force
  stop protected package"*. So a hand-edited preference file is not picked up: the
  running process keeps its cached copy. Reboot instead.
- **Cloudflare Pages answers a missing asset with 200 and an HTML page**, not a
  404. So `policytool.py sign`'s URL check passes a mistyped or not-yet-deployed
  `app_update` URL. Check the content type, not the status. No device is at risk —
  `AppInstaller` pins the checksum — but the mistake surfaces on a handset instead
  of at signing time.
- **`raw.githubusercontent.com` rate-limits a batch URL check.** The signer fires
  eighteen requests at once and gets 429s on a different subset each run, which
  reads exactly like dead blocklists. Fetch them serially with a few seconds
  between before believing any of it.
- **An adb server steals the USB interface from the WebUSB installer.** Kill it
  before a cable install, or the browser cannot claim the phone.
- **`store to scan` falling to zero is the only sign the store rule is armed.** An
  unscanned app is `unverified`, which means *keep*, so a rule that never ran and
  a rule that found nothing look identical from the outside.
## The alpha found what the dev phone could not

**A Nothing Phone on the alpha, 2026-08-18: TikTok Lite installed and played
video; Instagram Lite installed and showed nothing.** Both were installable
because neither package was listed and the alpha has no store rule. Only one of
them *worked*, and that difference is the finding.

It took two rounds, and the second is the more useful lesson.

**Round one.** All six tiktok-branded domains were blocked and the app did not
need any of them: it reaches `api.snssdk.com` for the feed and
`ibytedtos`/`byteimg`/`pstatp` for media — ByteDance's names, not TikTok's.
Eighteen domains added, on both channels. Instagram Lite is the control: Meta
serves its Lite build from `instagram.com` and `fbcdn.net` like everything else,
so the existing entries already covered it.

**Round two: it still played video.** The list had carried `tiktokcdn-us.com`
since long before any of this, and no `-eu` counterpart. TikTok serves European
users from European infrastructure, so a Belgian handset was reaching hosts
nobody had listed. Eleven more, including `tiktokcdn-eu.com` and `tiktokv.eu`.

**The checklist gained a step from each round** — find the parent company's
infrastructure, then look for regional variants of names already on the list. The
second one is the sharper: a `-us` suffix on an existing entry is a statement that
the service splits by region, and the other half does not announce itself. See
[policy.md](policy.md#adding-a-service-to-a-domain-list-the-checklist).

**Round three settled it, and the answer was not a domain.** Measured over adb on
the alpha phone, 2026-08-18, with policy 52 applied:

```
example.com                    resolves -> 172.66.147.243     (control)
instagram.com                  BLOCKED (no such host)
tiktok.com                     BLOCKED (no such host)
api.snssdk.com                 BLOCKED (no such host)
tiktokv.eu                     BLOCKED (no such host)
tiktokcdn-eu.com               BLOCKED (no such host)
ibyteimg.com                   BLOCKED (no such host)
ttlivecdn.com                  BLOCKED (no such host)
p16-sign-va.tiktokcdn.com      BLOCKED (no such host)
```

**The filter is doing its job.** Every name is refused, a real subdomain included,
and the control resolves. Then TikTok Lite was force-stopped, cold-started over
adb, and scrolled — and it **played video**, with these connections open:

```
71.18.73.249     no PTR   -> whois: Bytedance Inc. (BYTED)
71.18.129.228    no PTR   -> whois: Bytedance Inc. (BYTED)
2.17.196-198.x            -> deploy.static.akamaitechnologies.com
```

**It reaches ByteDance's own IP space without asking a resolver**, so there is no
lookup to refuse — hardcoded addresses, or its own HTTPDNS, which amounts to the
same thing from here. No further domain will fix this, and the two rounds of
domains are still correct: they stop the website and any app that does use DNS.

**This falsifies a claim the README carried from the beginning**: that no
mainstream app connects to hardcoded IPs. TikTok Lite does, and it is now written
down as measured rather than assumed.

**What actually stops it** is the app layer — `blocked_packages`, which uninstalls
it, and on `dev` the store rule, which catches it by rating without anybody naming
it. The package was deliberately taken *off* the alpha's list in policy 53 so the
app could serve as the probe for exactly this test; **it should go back now that
the test has answered.**

**What would stop it at the network layer** is route-level blocking of named IP
ranges. drawbridge routes only DNS into its tunnel by design, and the one place it
already does IP work is black-holing known DoH resolvers by address. Extending
that to a `blocked_ip_ranges` field is a real option and a real maintenance
burden: ByteDance's prefixes move, and a wrong range breaks something invisible.
Not built, and not obviously worth it while the package block exists.

---

## Decisions taken 2026-08-18, so they stop being questions

All three of the open items were answered by the owner on the same day, and the
answers are recorded here because two of them changed the code and the third
deliberately did not.

**The catch-up scan runs on any network.** It was Wi-Fi-only, which reads as
prudent and was a hole: a phone that never sees Wi-Fi never scanned, so its
preloaded games survived indefinitely — and once anybody notices, staying off
Wi-Fi stops being a delay and becomes a bypass. The trade is one-off: 50–100 MB
while a phone is being set up, against the rule that decides whether it has games
and companion apps on it at all. **The per-update re-ask still waits for Wi-Fi**,
which is the opposite trade — it recurs every time Play refreshes an app, the
verdict already exists, and what a re-check finds is rare.

**Google Play Games goes on the blocklist.** It was *Parental guidance* with no
switch, so it was already being removed by rating on any phone that had it;
naming it makes that deliberate rather than incidental. Policy 73.

**The clock stays pinned only when something needs it** — a curfew or a running
lock timer. The wider claim was considered and declined: it would take manual
clock setting away from every phone that has neither, to close an attack that
only matters where a wall-clock deadline exists. `CurfewController.apply` remains
the one place that decides, and
[design-decisions](design-decisions.md#the-clock-is-locked-for-a-curfew-and-for-a-lock-timer-and-this-section-used-to-claim-more)
now records this as settled rather than open.

---

## Reasonable next steps

The MVP is done and shipped. What follows is a feature roadmap, in the order the
owner set on 2026-08-08, not a defect list.

### 1. Get drawbridge able to update itself again

**No longer blocking provisioning** — `tools/provision-adb.sh` gets a certified
handset provisioned today, and that is what the top of this file is about. What
is still broken is delivery to a phone that is already locked *and stays locked*:
self-update is a `PackageInstaller` session rather than an adb install, so the
verifier global does not touch it, and `DISALLOW_DEBUGGING_FEATURES` has taken
the cable away for as long as the lock holds.

A phone that cannot receive a fix is a phone where every bug found from here on
is permanent. **The decision this raised has since been taken.** On 2026-08-10
`DISALLOW_DEBUGGING_FEATURES` became the one restriction keyed on the lock rather
than on `protectedSince`, so a parent with the key can unlock, re-enable USB
debugging, run `tools/provision-adb.sh --update` and lock again — and it gives
nothing away, since whoever holds the key can already remove drawbridge entirely
from the configuration screen. See
[the cable is now repeatable](#the-way-in-is-adb-and-play-protect-is-why).
**Corrected 2026-08-12**; this entry called the trade undecided for two days
after it was decided and shipped.

So what is left of this item is narrower than it reads: an *unaided* update — one
that reaches a phone with nobody at the cable — still needs the Play Protect
verdict on the package name lifted.

The finding, the evidence and what has already been ruled out are above. Two
rounds are done: `REQUEST_INSTALL_PACKAGES` is gone and did not help, and the
same-run herald control proved the problem is the drawbridge payload rather than
the installer.

**The rig is cheap now, so use it.** A round is: bump the version code, build,
publish a DPC-only pre-release, sign a policy pointing `app_update` at the
versioned URL, push, reboot the G15, read Diagnostics from the lock screen's
overflow menu. Roughly twenty minutes and a 3 MB upload, and the phone stays on
versionCode 11 as the constant.

Next, in order:

1. **Drop `REQUEST_DELETE_PACKAGES`**, having first confirmed on the provisioned
   emulator that the app blocker still uninstalls without it — the same check
   that cleared `REQUEST_INSTALL_PACKAGES`. Single variable.
2. **Set `setInstallReason(INSTALL_REASON_POLICY)` on the install session.** It
   costs one line and is true by construction. Remember it takes effect only
   from the *installed* build forward, so it is tested one release after the one
   that adds it — which means it can ride along with step 1 without confounding
   it, since the two are exercised at different moments.
3. **The different-package-name experiment**, which is the one that actually
   settles whether any of this is winnable by editing a manifest.

If none of it moves, the honest conclusion is that the verdict is attached to
drawbridge rather than to anything drawbridge does, and the remaining avenues
are an appeal specific to install-blocking (separate from the 2026-08-06
enrolment one) and **developer verification** — which stops being a September
deadline and becomes the actual fix. What is not an answer: asking a parent to
switch Play Protect off, which a locked device should not permit anyway.

**Do not ship a build that assumes this is solved.** Treat every release as
unable to reach a deployed phone, and weigh changes accordingly.

### 2. Put a Google account on the phone and find out whether FRP works

**Before any feature work, and second only to the update channel above.**
Everything the factory-reset decision rests on is currently taken from Google's
documentation rather than from a device, and today produced two separate cases
where that was not good enough.

The account is already on the G15 as of 2026-08-08 — which is what activated
Play Protect and produced step 1.

The G15 has never had a Google account on it — every provisioning run skipped
sign-in on purpose — so Factory Reset Protection has never been armed, never
triggered, and never observed. Yet FRP is now the whole backstop: `DISALLOW_FACTORY_RESET`
was removed so that a lost key cannot cost a handset, which means a child who
knows the screen lock *can* wipe the phone. What is supposed to make that
worthless to them is FRP demanding an account only the parent has.

The test, on a phone whose account password only the owner knows:

1. Add the account, lock drawbridge.
2. **Wipe from recovery.** Does setup then demand that account? *Expected: yes —
   an untrusted wipe leaves FRP armed.*
3. Re-provision, then **reset from Settings**. Does it demand it? *Expected: no —
   a trusted wipe clears FRP.*

Step 3 is the uncomfortable one. If a Settings reset clears FRP, a child who
knows the screen lock has a one-minute route to a clean phone and the only thing
left is the protected-since date telling the parent afterwards. That would be
worth knowing before deciding anything else, and might well argue for bringing
step 8 forward once the timer exists.

~~**Fix `lockAccounts()` at the same time.**~~ **Done on 2026-08-10, and reversed
the same day.** `DISALLOW_MODIFY_ACCOUNTS` was wired into `MANAGED_RESTRICTIONS`
keyed on the lock, then removed again: blocking every account to stop one is the
wrong trade, and it blocks *removing* accounts too. `lockAccounts()` is deleted
and accounts are deliberately unrestricted — which is what the closing note of 2a
below already says, and what `DeviceOwnerRestrictionsTest` asserts. **Corrected
2026-08-12**; this entry described the restriction as live for two days after it
came out.

### 2a. Decide whether "never the child's account" is still advice worth giving

**Raised by the owner on 2026-08-10, and it deserves a straight answer rather
than inertia.** Every install guide and both website pages say to sign in with
the parent's account and never the child's. That instruction was built on FRP,
and FRP turned out not to be armed — so the stated reason is void.

What is left of the argument is weak and, worse, partly points the other way:

- **Play Store access is symmetrical.** Whichever account is signed in, whoever
  holds the phone can install from Play. The child's account does not open a
  door the parent's account keeps shut.
- **The parent's account on a child's phone is its own exposure.** It syncs the
  parent's mail, photos, drive and contacts onto a handset the child carries, and
  puts the parent's saved payment method behind a Play Store the child can reach.
  Nothing in the docs has ever mentioned this, and it is a larger risk than the
  one the advice was written to prevent.

**The Family Link half is now answered, and the answer is no.** Tried on the
provisioned G15 on 2026-08-10: setting Family Link up fails partway through, with
Family Link itself reporting that it cannot be used together with this. That
matches the documented platform behaviour — a device with a Device Owner is
fully managed, and Family Link does not supervise managed devices — so it is
inherent rather than a drawbridge fault, and it applies to any tool of this kind.

**It matters less than it first looked, and the owner was right to say so.** The
first draft of this note called it "the question every parent will ask". It is
not: Family Link supervises a *child's* account, and drawbridge is for teenagers
and adults. The two barely address the same person.

The sharper version, which is now in the FAQ: at 13 — or whatever the local age
is — **the teenager can end supervision themselves**, and the parent is only
notified. So Family Link stops being something a parent can rely on at exactly
the age drawbridge is built for. That is a point in drawbridge's favour rather
than a limitation to apologise for, and the answer says so.

It is still worth an FAQ entry, because somebody tried it and hit a confusing
failure — that is what FAQ entries are for — with the honest consequence that it
is one or the other, and a parent who would rather keep Family Link should
install **herald on its own**. That is the standalone deliverable the README
already describes, and the browser is exactly the gap Family Link leaves open.

What that leaves of the child-account question: a *supervised* account is not
possible at all, so the advice can only ever be about an ordinary one — and
against an ordinary account the reasoning above still applies, symmetrical and
partly backwards. The wording is still worth rewriting; the likeliest honest
replacement is "use an account you do not mind the child having, or none at all"
rather than a flat prohibition. Not yet done.

The exact on-screen wording of the failure was not captured. If it is seen again,
write it down — it is the sentence a parent will search for.

Note `DISALLOW_MODIFY_ACCOUNTS` is still never applied, so accounts can be added
to a locked phone without unlocking it. That is the state this was tried in.

### 4. A setting for video streaming, with or without YouTube

Two separate questions a parent will ask differently: "may this phone stream
video at all", and "may it use YouTube". The policy already models options
(`whatsapp` is one), so the mechanism exists; what is needed is the option
definition, the wording in three languages, and the sweep behaviour when it is
turned off. Note that YouTube is currently blocked outright in `social.txt`, and
that the safe-search rewrite only takes effect if it stops being blocked — the
comment at the top of that list explains the interaction.

### 5. Install F-Droid by default

It is useful, it is how a managed phone gets software that is not on Play, and
it is already unblocked. Adding it to `required_apps` means hosting or pinning
its APK the same way herald is — by URL and SHA-256 — and deciding whether it is
required (reinstalled if removed) or merely allowed.

### 6. A setting for browsers: none, or herald only

Today the policy names `allowed_browser_packages` and the blocker removes
everything else. "No browser at all" is a stricter position some parents will
want, and it interacts with `required_apps`: a browser in `required_apps` and
absent from the allowed list is installed and removed on a loop, which is the
trap the two-list rule exists to prevent. Whatever ships must keep those two in
agreement.

### 9. Lock factory reset — last, and only with the timer

**The prerequisite exists as of 2026-08-17**, and is not quite what this entry
assumed: what was built is a delayed *unlock* rather than a delayed self-removal,
which answers the same problem because removal lives behind the lock. A real
two-hour timer was watched lifting on the Moto the same day. Everything else in
this entry stands, including the objection that matters — this escape runs
*inside* drawbridge, so a crash loop or a bad update takes it with it, where a
factory reset never involved drawbridge at all.

`DISALLOW_FACTORY_RESET` goes back **only** with that timer proven on hardware,
and deliberately after everything above — unless step 2 shows that FRP does not
hold, in which case this moves up, because the backstop it was removed in favour
of would not exist. While features are being built, a
mistake that bricks a handset costs a device; with a factory reset available it
costs ten minutes. See
[design-decisions](design-decisions.md#losing-the-key-a-delay-not-a-back-door).

Remember that reinstating it means reversing the `RETIRED_RESTRICTIONS`
migration too, and that today's escape works even when drawbridge is completely
broken while a timer-based one does not.

### 10. "This phone, these apps, nothing else" — and the apps still update

**Asked for on 2026-08-11, and it is mostly already built.** The request people
actually make is not a curated blocklist: it is *let me install the handful of
apps this person needs, then close the door*. Which apps differs per person, so
it cannot live in the signed policy.

**The mechanism exists.** `allowed_packages` already flips app control to
allowlist mode: any *user-installed* package outside the list is removed, system
and preinstalled apps left alone. What is missing is only that the list is a
static field in a document signed by this project's key, so it cannot be
per-person.

**The design that fits: snapshot at lock.** When the parent presses *Lock*,
record the set of user-installed packages into device-local state, next to the
selected profile and options, and treat it as an allowlist from then on. The
install-what-you-need-then-close-the-door flow is exactly the pre-lock window
that already exists for accounts, screen lock and the cable.

**The update question answers itself, and the reason is worth understanding.**
An update does not change a package name, so an updated app is still on the
list. Better than that, `PackageWatcher` never even asks:

```kotlin
if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
```

A replacement is not a new install and is ignored outright. Play Store keeps
updating everything on the phone, because Play is a system app and the apps it
updates keep their identity.

**The obvious alternative is the trap.** `DISALLOW_INSTALL_APPS` looks like the
right restriction and probably breaks exactly what is being asked for: it stops
the package installer, which is also how updates arrive. **Untested here** — and
worth testing before anyone reaches for it, because drawbridge's existing
remove-after-install design, which looks cruder than blocking the install,
is precisely what keeps updates working. It never blocks an install; it removes
afterwards, and an update is never an "afterwards".

**One property this must keep.** The local list may only ever *narrow*. An app
survives iff it is in the snapshot **and** the policy does not block it —
intersection, never union. Otherwise a device-local file, unsigned and editable
by anyone who reaches it, could re-admit something the signed policy forbids.

Consequences to state before building it, not after:

- **Adding an app later costs the key.** Unlock, install, lock again — and
  locking mints a new key, so "I need one more app" is a credential rotation.
  That may argue for a narrower door: an *add apps* mode that re-snapshots
  without re-minting, which is a change to `LockActivity`'s key handling and
  should be designed deliberately rather than discovered.
- **The removal window stays.** An app installs and is removed seconds later
  rather than being refused. That is already true of every blocked package.
- **The snapshot will catch whatever happened to be there**, including anything
  the parent installed to migrate data and no longer wants. Showing them the list
  before sealing it is probably not optional.

### 11. Update the website — the owner's, not a coding task

**Raised 2026-08-13 and assigned to the owner.** The site still describes the
project as it was on 2026-08-11 and is now wrong or thin in several places. It is
listed here because it is real work with a deadline attached to the alpha, not
because anyone should generate it:

- **The alpha warning is out of date in the good direction.** It says *"tested on
  exactly one device: a Motorola G15, by the people who built it"*. Two handsets
  now, on different OEMs and different Android versions.
- **The three toggles are not mentioned anywhere.** WhatsApp 14+, YouTube 16+ and
  Telegram 18+ are what a parent chooses between, and the site does not say they
  exist.
- **Nor is the disconnect philosophy**, which is the largest feature added since
  the site was written: always offline, always online, or a curfew.
- **Nor are the five browsers.** The site still reads as "herald only", which
  stopped being true in policy 41.
- **The Private Space step** is in the install pages already, added 2026-08-12.
  Worth checking it reads well, since it is the step that stopped a real
  provisioning.
- **What a blocked site looks like in Chrome**, which is a question the other
  four browsers create and nothing answers. In herald it is drawbridge's block
  page; everywhere else it is a DNS error that reads as "the internet is broken".

Remember `site/` is generated: edit `site-src/` and `tools/build-site.py`, run
`python3 tools/build-site.py`, and commit what it writes. Hand-edited HTML in
`site/` is overwritten without warning.

### 12. ~~herald mono: take out always-on reader view~~ — done 2026-08-19

**Asked for 2026-08-17, from use; removed on 2026-08-19.** `Edition.autoReaderView`
is gone, and with it the automatic entry, the `dismissedForPage` flag and the
two-step back press that only existed to prop the feature up. Reader view stays
in the menu, back is `ReaderViewFeature.onBackPressed` again — it leaves reader
view and stops on the article — and the readability re-check is kept, because it
is what makes the menu entry appear on pages that are articles in *both*
editions. See
[design-decisions](design-decisions.md#mono-asks-for-reader-view-rather-than-imposing-it)
and the rewritten status header on
[reader-view-back](reader-view-back.md).

**The owner's replacement idea was taken, and it works: a slower fling.** Mono's
thesis is friction rather than stripping — `loadDelayMillis` is the same idea —
and a page that is harder to throw is friction no page can fight, whereas reader
view depends on a Readability pass that either works or leaves the reader worse
off. One Gecko pref does it, and **it is not the one it looks like**; see the
trap above, and `EngineProvider.applySlowScrollingPrefs` for the measurements.

Measured on the API 36 emulator with one scripted flick on a 40,000 px page, in
CSS pixels scrolled — about 460 of which is the drag itself:

| `apz.android.chrome_fling_physics.friction` | scrolled | screenfuls |
|---|---|---|
| 0.015, GeckoView's own | 4,466 | 5.6 |
| **0.05, what mono ships** | 2,045 | 2.6 |
| 0.15 | 1,143 | 1.4 |

**What is left of this item** is a phone. Everything above was watched on the
emulator: no automatic reader view on an article, the menu entry still offered
and still working, back leaving reader view in one press and stopping on the
article, and the fling shortened on a first run with a fresh profile. How 0.05
*feels* in the hand, over a day, is the open question, and the number is one
constant to move.

### 12b. Two small things on the configuration screen, reported 2026-08-19

Both visual, from the owner running build 41. **The first is fixed; the second
is still open.**

- ~~**A band of empty space sits under the title bar**~~ — **fixed 2026-08-24,
  and the suspicion in this entry was right.** `applyScreenInsets` was adding
  `actionBarSize` to a `systemBars()` top inset that already contained it.
  AppCompat's `ActionBarOverlayLayout` folds the bar it draws into the insets it
  hands down to the content, so what arrived was 231px on the 420dpi emulator —
  24dp of status bar plus 64dp of action bar — and the old code read that as the
  status bar alone. Every screen in the app began 64dp lower than it had any
  reason to. Measured before and after with `uiautomator dump`: the first
  heading moved from y=420 to y=252, against an action bar ending at y=231, so
  the gap is now the 8dp the `LinearLayout` asks for and nothing else. Checked
  on the configuration screen and on diagnostics; the fix is in `Insets.kt` and
  applies to all five activities that call it. It was invisible below API 35,
  where the decor consumes the insets and this reports zero.
- **"Drawbridge Control" could be smaller.** *Still open.* It is the activity
  title in the default action bar, set at runtime from `main_title`; nothing
  sizes it today, so it takes whatever `textAppearanceTitleLarge` the Material
  theme gives it.

### 12c. Comet and Via, and what any new browser has to be checked against

**Both were cleared by the owner on 2026-08-19 and are now on both channels.**
Neither has a VPN option and neither has a DNS setting, which is the thing that
mattered: a browser that cannot be pointed at its own resolver cannot route
around a DNS-only filter. Comet is Perplexity's browser and Perplexity is already
allowed as an app, so allowing its browser changes nothing about what the phone
can reach.

The three checks below are what they were cleared against, and are worth keeping
for the next browser somebody asks for.

**Via is the one with a reason to be there beyond curiosity.** It is what
tech-minimalists put on a small light phone, which is exactly the household this
project is for, and a phone whose owner chose Via is a phone whose owner might
choose drawbridge. It is also the more likely of the two to pass: it is a
WebView wrapper rather than its own engine, so it resolves through the system
resolver and has no *Use secure DNS* of its own to turn on. Confirm that rather
than assume it — a wrapper can still ship a proxy.

Comet is Perplexity's browser. The rating rule has no opinion on it — the store
says PEGI 3 — so the only thing that decides is the browser list, which is why
this is a browser decision rather than a whitelist one.

**What to look at before it goes near the alpha**, in the order that would settle
it fastest:

1. **Does it speak its own DNS?** It is Chromium underneath, so it has *Use
   secure DNS*. Chrome has the same switch and is allowed anyway, because
   `block_encrypted_dns` blackholes the known DoH endpoints by address — so the
   question is whether Comet ships a resolver that is not on that list. Set it to
   a custom provider and watch whether the block page still appears.
2. **What does the assistant fetch, and from where?** An answer composed on
   Perplexity's servers and handed to the phone as text is content the DNS filter
   never sees, whatever the domain lists say. That is a different shape of hole
   from a browser reaching a blocked site, and it is the one the ordinary browser
   argument does not cover.
3. **Does it register as a browser at all?** If it does not answer an `https://`
   intent, `isBrowser` will not see it and the browser list is not what governs
   it — check with `pm query-activities`.

If either fails any of those, take it out of `allowed_browser_packages`; nothing
else in the policy depends on either of them.

**The third question is the one that matters most for Via** and is worth putting
first there: a browser that does not answer an `https://` intent is not seen by
`isBrowser` at all, so it would survive on any phone whatever this list says —
which would make allowing it here a formality and its *absence* from the list
meaningless. That is worth knowing before trusting the browser rule to be
complete.

### 12d. The browser cards need logos for browsers the phone does not have

**Reported 2026-08-19, once Comet and Via were allowed.** The browser choice
cards describe themselves with the launcher icons of the browsers *actually
installed*, which was a good idea while the allowed set was five and every phone
had most of them. With seven, a phone that has two of them shows two icons under
a card that claims to allow seven, and the claim reads as false rather than as
incomplete.

Two ways out, and the owner named both: ship the logos in the app so the card can
draw browsers the phone has never had, or draw a small **+** after the icons and
let the ⓘ description carry the list. The second is cheaper and keeps
`bindBrowserIcons` honest — it would still only draw what is really there — but
it costs a tap to answer "which browsers?".

Whichever, the description is the fallback either way, so it has to name them:
the default profile's text does, in three languages, as of policy 85.

### 12e. ~~Private DNS on LineageOS~~ — investigated 2026-08-19, nothing wrong

**Measured over adb on the Dumber Mini** (LineageOS 21, Android 14, drawbridge
Device Owner, protected but unlocked at the time). The restriction works there,
and both of the guesses this entry used to carry were wrong.

| | |
|---|---|
| Is `DISALLOW_CONFIG_PRIVATE_DNS` applied? | **Yes.** It is in the effective restriction set, spelled `disallow_config_private_dns` rather than `no_*` like its neighbours, which is the platform's own naming and not a bug. |
| Does the Settings UI honour it? | **Yes.** Private DNS is greyed out and reads *Controlled by admin*, exactly like VPN beside it. |
| Can adb override it? | **No**, and this is the part worth knowing. `settings put global private_dns_mode hostname` is accepted at the prompt and **silently does nothing** — the value reads back unchanged. Android's settings provider maps that key to this restriction, so a shell with `WRITE_SECURE_SETTINGS` cannot write it either. Enforcement is well below the UI. |
| Is the restriction keyed on the lock? | **No, on protection.** The phone was unlocked when measured — `no_debugging_features` absent, adb working — and the DNS restriction was applied anyway, so it survives an unlock like the filter does. |

**What the owner actually saw was the pre-lock window**, and that is correct
behaviour rather than a gap: *nothing* is applied until the first lock, which is
the whole point of that window — a parent adds an account, sets a screen lock and
moves data off. They checked before ever locking. After the first lock the
restriction lands and stays.

**Why choosing another resolver took the phone offline**, which was the other
half of the report and is also deliberate: `block_encrypted_dns` routes the
known DoH/DoT endpoints into the tunnel and drops them. The tunnel's route table
on this phone carries Google, Cloudflare, Quad9, AdGuard, OpenDNS, Control D and
NextDNS, v4 and v6 — `1.1.1.1` among them, and `one.one.one.one` is what was
typed in. Android's hostname mode is strict and has no fallback, so every lookup
failed. Fail-closed, by design, and confirmed here: `ping 1.1.1.1` is
unreachable while ordinary resolution works.

### 12f. ~~A Private DNS hostname set before the first lock is frozen in place~~ — fixed 2026-08-19

**Found while investigating 12e, and fixed the same day.** The restriction stops
the Private DNS value being changed; it does not change the value. So a phone
where somebody set Private DNS to a hostname *before* locking kept it, lost every
lookup once the tunnel came up — `block_encrypted_dns` drops the known DoT
endpoints and Android's hostname mode has no cleartext fallback — and had no way
back, because Settings greys the entry out, `settings put` from a shell is
silently ignored, and unlocking does not help since the restriction is keyed on
protection. The only exits were removing drawbridge or a factory reset, for a
setting chosen before drawbridge had any opinion about it.

`DeviceOwnerManager.normalisePrivateDns` now runs at the top of
`applyUserRestrictions`, before the restriction lands: if the mode is
`PRIVATE_DNS_MODE_PROVIDER_HOSTNAME` it is moved to opportunistic. Opportunistic
rather than off because there is no setter for off, and it is the right
destination anyway — it probes DoT against *the network's own* resolvers, which
on a filtered phone are the tunnel's fake addresses, and those answer port 53 and
nothing else, so the probe fails and the system falls back to cleartext, which is
the filter.

**Watched working on the API 36 emulator**, provisioned as Device Owner, with the
trap set up by hand:

```
before lock : private_dns_mode=hostname  specifier=one.one.one.one  restriction absent
at lock     : "Private DNS was pinned to one.one.one.one; moved to opportunistic
               so the filter stays reachable once the restriction lands"
after lock  : private_dns_mode=opportunistic  specifier=null  restriction applied
              example.com resolves; instagram.com blocked; putting the mode back
              from an adb shell is ignored
```

Diagnostics gained a `private DNS:` line, which reads `opportunistic` there. It
is the only way to see this state on a phone, and a phone showing `hostname` has
DNS going somewhere the filter cannot see.

**Not on any handset yet**: the fix is in the DPC, so it needs a build. It only
matters for a phone locked *after* somebody set a Private DNS host, which is a
first-install situation, so existing phones are unaffected — the alpha reads
`off`.

### 13. A copy pass over the app, then the website — the app half is done

**Asked for 2026-08-17; the app was rewritten by the owner on 2026-08-19.** The
strings had grown by accretion — three languages, several features added in a
week, each written in the moment — and what they wanted was not tidying but a
decision about what each control actually promises. What changed:

- **The screen is called `Drawbridge Control`**, set at runtime rather than as
  the activity's label, because MainActivity is the launcher activity and a
  label would rename the icon too.
- **Sections look like sections.** `TextAppearance.Drawbridge.SectionHeading` is
  bold, accent-coloured and carries a hairline above it; the first heading takes
  a variant without the rule, since it has nothing above to be separated from.
- **The policy note under the card** now says the policy is always on and the
  options are the part that waits for the lock — which is what the code has done
  since 2026-08-15 and what the old wording said the long way round.
- **Always-online is the first disconnect choice**, being the state the phone is
  already in, and says so.
- **`No other apps`** is the install lock's name; the section above it keeps the
  name `App installs`, or the screen says the same three words twice running.
- **The options are framed as permissions to withdraw** rather than to grant,
  which is the change that needs the policy document below.
- **The lock, reveal and forgotten-code screens** say what the key is and is not:
  a new one every time, written down or deliberately forgotten, and the reveal
  no longer carries the paragraph about settings not being sealed.

**Two of the owner's items are policy, not app, and are prepared but unsigned**
in `dist/policy.json` (version 75): the default profile's ⓘ text, and
`default_enabled: true` on every option. **Until that document is signed the app
contradicts itself** — the Options section says drawbridge allows all of the
following while every switch reads off. One command closes it:

```
python3 tools/policytool.py sign --in dist/policy.json --out dist/policy.signed.json
```

**A second pass followed on the same day, from the phone, and it split in two.**
Half of what wanted changing is in `values/`, which needs a build; the other half
is the option descriptions, which are the *document's* and reach a phone at the
next poll. That is worth knowing before promising somebody a text change: the
option and profile wording ships in a policy, everything else on that screen
ships in an APK, and only one of those two can reach a locked phone.

The curfew now says
*after lock* like the offline choice next to it; the browser and option
descriptions say what a switch actually does since build 40 — suspended and
hidden, back with bookmarks and chats intact, uninstall it yourself first if you
want it really gone — and that half of it lives in the signed policy rather than
in `values/`, because the option descriptions are the document's.

**The website half is done too, for the blocklist page.** It says at the top that
it is written by a language model and apologises for the em-dashes, counts seven
categories rather than six, and explains that what a list can never close is
closed from the other side by removing anything the store does not rate PEGI 3.
Three of its tables are generated from the signed policy and the ratings cache
rather than typed: the AI companion apps removed by name, the tools allowed above
PEGI 3, and the streaming services the one switch governs. **Regenerate them when
those lists change** — they are in `site-src/block-list.md` as plain markdown,
so nothing will tell you they have drifted.

What is left of item 11 is the rest of the site.

### Standing items, unchanged

- **The QR is blocked on certified hardware, and it is not the allowlist.** The
  old note here said to retry every couple of weeks because the allowlist "has
  never blocked this project". That is out of date twice over: QR provisioning is
  now blocked, and the mechanism is the Play Protect PHA classification on the
  package name rather than the DPC allowlist — the two show different warning
  text. See the Play Protect section.

  **adb provisioning is not blocked**, and is the supported route today; see the
  section at the top of this file. The QR path is still worth reclaiming — it
  yields a phone without the OEM's downloaded preloads, which adb does not — and
  it is expected to work untouched on a handset with no Play Protect at all.
  **That last part is untested**, and is the next thing to put on hardware.
- ~~**Keep both keys backed up.**~~ **Done, 2026-08-11.** All three — release
  keystore, policy key, emergency key — are offline. Keep any new key in the same
  place; the backup is only as good as the next person knowing it exists.
- **Drop unused ABIs.** `armeabi-v7a` and `x86_64` have never been downloaded by
  anything and cost ~650 MiB of every release. Removing an ABI means removing
  its `required_apps` entry in the same policy.
- ~~**Build the WebADB installer.**~~ **Built, and it is the alpha's only install
  route.** <https://drawbridge-project.pages.dev/install/usb/> provisions and
  updates a phone over WebUSB; `dev` serves the same page from its own build.
  **Corrected 2026-08-12**: this entry still described a disabled button, and
  justified itself with QR provisioning "working" and needing "no cable" — QR is
  retired, and the installer it was asking for is a cable path by design.
- **Localise herald.** drawbridge speaks three languages; the browser is
  English-only, ~45 strings. drawbridge cannot set it — a per-app locale cannot
  be set by another app — so herald needs its own picker.

---

## Secrets, and where they are

None of these is in git. **They are backed up offline as of 2026-08-11**, which
retires what this file called the single largest risk in the project for its
entire life. What follows is still what each one costs, because the backup is
only as good as the next person knowing it exists and where.

| What | Where | Consequence of losing it |
|---|---|---|
| Emergency unlock key | `emergencyKey` in `keystore.properties` | Development only. Losing it costs nothing a factory reset cannot fix; **leaking it unlocks every device running a build that carries it**. Remove before real deployment. |
| Release signing keystore | `keys/drawbridge-release.jks`, password in `keystore.properties` | Every provisioned device is stranded on its installed version forever. Android refuses updates signed with a different key. |
| Policy signing key | `keys/drawbridge-2026-07.pem` | No device can ever be given a new policy again without reinstalling both apps. |

Both directories are git-ignored, and both are now copied offline. Keep it that
way: after a phone is provisioned neither key can be replaced, and the emergency
key has joined them in mattering, because it is the way back into a *tester's*
stranded handset rather than just your own.

The QR code pins the *release certificate*, so it stays valid across every
future release signed with that keystore. Change the keystore and every
provisioned device is orphaned and the QR must be regenerated.

---

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
  anyone depends on gets wiped, and it has earned that: provisioned, removed and
  re-provisioned perhaps a dozen times across one session, by QR and by adb.

  **It is the reference device.** Every claim in this file about real hardware
  came from it.

  **Its current state, 2026-08-10, end of day**: **provisioned over adb, and not
  locked.** drawbridge **0.2.6 (versionCode 17)**, herald and herald mono are
  installed, all release-signed; drawbridge is Device Owner,
  `provisioningState: 3`. USB debugging on and adb authorised.
  `verifier_verify_adb_installs` is unset, exactly as found.

  **Zero Google accounts, and that is a state it was put into rather than reset
  into** — the owner's account was signed in, removed in Settings to allow
  `dpm set-device-owner`, and has not yet been signed back in. `user_setup_complete`
  is 1 and always has been through this; the phone has not been wiped since the
  FRP test. Signing back in is step 4 of [install.md](install.md).

  0.2.6 is a **local build, not published** — it carries the USB-debugging change
  and nothing else. There is no release and no policy pointing at it. The live
  policy's `app_update` still pins versionCode 16, which is lower, so nothing
  will try to move the phone.

  It lives at `dpc/build/outputs/apk/release/dpc-release.apk`, **not** in
  `dist/release/`, which deliberately still mirrors the published v0.2.5 and
  hashes to the tracked `SHA256SUMS`. Push it again with:

  ```bash
  tools/provision-adb.sh --update --no-herald --dir dpc/build/outputs/apk/release
  ```

  Nothing is enforced, because nothing has been locked — so the whole window
  described in [provisioning](provisioning.md) is still open on it: account,
  screen lock, anything to be installed by cable.

  **The test it is set up for**, which needs a person at the screen because its
  whole subject is adb going away:

  1. `dumpsys user` → *Device policy restrictions: none*, `adb_enabled` is 1.
     That is where it stands now.
  2. Add the parent's Google account and set a screen lock.
  3. Open drawbridge, **Lock drawbridge**, write the key down, tick the box,
     press *Done*. adb should drop **at that moment** — not when the button was
     pressed, since the restriction now lands after `ParentKey.commit`.
  4. Abandoned-reveal check, worth doing separately: lock again, and press home
     *before* ticking the box. The phone should stay unsealed and adb should
     stay up.
  5. Unlock with the key. Re-enable USB debugging in developer options, and adb
     should come back. `tools/provision-adb.sh --update` should then work.

  It is the only hardware this project has.

  `DISALLOW_DEBUGGING_FEATURES` takes adb away the moment a release build is
  locked, so install everything before locking.

  **How to tell what it is running without unlocking:** the lock screen's
  overflow menu → Diagnostics reports the version and the policy. (The menu
  itself was the marker for v0.2.0 against rc3, which has none.)

  **Rebooting forces both workers**, and is a reasonable mechanism rather than a
  workaround: `DnsFilterService` calls `PolicyWorker.refreshNow` and
  `UpdateWorker.runNow` when it starts, and those use `CONNECTED` rather than
  `UNMETERED`, so they run on mobile data too. That is how each Play Protect
  test round is triggered. Do not reboot it while an *unaided* poll is the thing
  being observed — the periodic jobs are every three hours as of the build after
  v0.2.0, but versionCode 11 still has the old daily schedule with its unmetered
  and battery-not-low constraints, which is why the reboot matters here.

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

---

## Two questions people ask, answered as far as they can be

Neither has been tested. Both are written down because they are asked often
enough that guessing twice is worse than reasoning once.

### Would drawbridge work on GrapheneOS?

**Most of it should, one part silently would not, and one problem disappears.**

*What should work.* Everything drawbridge enforces is `DevicePolicyManager` on
AOSP: Device Owner itself, `setApplicationHidden`, `setPackagesSuspended`,
`PackageInstaller.uninstall`, the user restrictions, the always-on VPN and the
clock lock. GrapheneOS is AOSP underneath and does not remove those. The cable
route is `dpm set-device-owner`, which is the same call on any AOSP build, so
`tools/provision-adb.sh` is the route to try first.

*What disappears, in a good way.* **The Play Protect problem is a Play problem.**
GrapheneOS has no Play services, so nothing refuses `app.drawbridge.dpc` at
install and nothing blocks a `PackageInstaller` self-update. The single largest
constraint on this project — see [the way in is adb](#the-way-in-is-adb-and-play-protect-is-why)
— is a Google Play behaviour, and it is absent there. Unattended updates would
work on GrapheneOS today.

*What would silently stop working, and this is the one to warn people about.*
**The store rule needs the Play Store.** `StoreCatalogue` fetches
`https://play.google.com/store/apps/details` per package to read its age rating.
That is an ordinary HTTPS request rather than a Play services API, so it works
wherever the network does — but on a phone with no Play *listing* for a
sideloaded app there is nothing to read, and an app the scan cannot answer for is
`unverified`, which means **keep**. So on a GrapheneOS phone carrying F-Droid
builds, the rating rule quietly allows everything it cannot look up, and
[store to scan falling to zero](#traps-that-cost-time-here) is the only sign it
ran at all. The blocklist, the domain filter and the browser rule are unaffected.

*What changes shape.* Factory Reset Protection is Google's, and the decision to
remove `DISALLOW_FACTORY_RESET` rests on FRP being the backstop. GrapheneOS has
its own scheme, so that reasoning has to be redone rather than assumed.

### Could herald be Chromium-based, using the system WebView?

**Yes, and it would be a different product with a different set of holes.** Worth
laying out because the size argument is genuinely strong: herald is about 230 MB
per ABI because GeckoView is inside it, and a WebView browser is a few megabytes.

*What gets better.* The engine is updated by the system rather than by us, so
security fixes arrive without a release. **And the DNS story improves**: WebView
uses the platform network stack, so lookups go to the system resolver where the
filter sees them — there is no in-browser DoH to disable, which is the thing
`EngineProvider` has to be careful about today and the thing every new browser
has to be checked for. `shouldInterceptRequest` is a cleaner hook for the
blocklist than `RequestInterceptor` is.

*What gets lost, and the first one is the reason not to do it lightly.*
**WebView has no extension support, so uBlock Origin goes.** Request-level
blocking survives — that is the blocklist, and it is ours — but cosmetic
filtering, element hiding and everything uBO does inside the page do not, unless
they are reimplemented as injected scripts. Reader view goes the same way:
Readability would have to be injected rather than being an extension Gecko
already ships. The block page and the filtering are portable; the two features
built on Gecko's extension support are not.

*What is unknown.* Whether `shouldInterceptRequest` sees everything worth
blocking — WebSocket upgrades and some subresource types are worth measuring
before promising the filter is equivalent.

**The honest summary:** a WebView herald would be smaller, safer at the DNS layer
and cheaper to keep current, at the cost of the ad blocker and reader view as
they exist now. That is a product decision rather than a technical blocker, and
Via is the existence proof that the shape works.

## Working notes for whoever picks this up

- `./gradlew test lint` is the fast check. `assembleDebug` now builds **both**
  browser editions, so it takes about twice as long as it used to.
- Release procedure is in [policy.md](policy.md#releases-are-cut-locally-not-in-ci),
  and the order matters: herald first, then hash and sign, then drawbridge.
  `tools/stage-release.sh` after the builds.
- `python3 tools/policytool.py sign --key-id drawbridge-2026-07` then `verify`
  after any `dist/policy.json` edit, and copy the result over
  `policy/src/main/assets/drawbridge/default-policy.json`. `sign` now fetches
  every URL the policy names and refuses to sign a dead third-party blocklist;
  `--skip-url-check` to sign offline.
- `python3 tools/build-site.py` after any website change, and commit what it
  writes. `site/` is generated; hand-edited HTML is silently overwritten.
- **Adding a service to a domain list has a checklist**, and it exists because
  blocking `tiktok.com` did not stop TikTok Lite playing video —
  [policy.md](policy.md#adding-a-service-to-a-domain-list-the-checklist). The
  short version: find the *parent company's* hosts, check each one resolves,
  ask what else shares the CDN, and remember a service ships more than one
  package.
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

---

## The record: what shipped, one line each

`git log` is the full story; this is the index. Every dpc-only release serves its
APK from the channel's Pages site and re-pins `app_update`.

| Build | Policy | What it was |
|---|---|---|
| 38 | 74 | The catch-up scan runs on mobile data too, because Wi-Fi-only is a bypass once anybody notices. Eleven more ByteDance domains: TikTok has an EU and the list only had the US. |
| 37 | 72 | The catch-up scan runs in the filter service — WorkManager was cancelling it on every install. Google TV joins the streaming option. |
| 36 | 71 | The APK bundles a current policy again (it was stuck at 37, which predates the store rule); the scan is queued at the first sweep, not only at the lock; cache TTL 30 → 180 days. |
| 35 | 70 | Removal starts at installation rather than at the lock. The keyguard becomes three short lines. The store rule reaches preloads with a launcher icon. |
| 34 | 69 | The lock timer: 2 hours to 40 days, plus the thirty-day `Forgot the code` door. |
| 33 | 66–68 | Deferral became a property of the *reason* rather than the package, after the install lock rescued apps that other rules should have removed. |
| 31–32 | 62–65 | The store rule ships: Play's rating and category decide, with a whitelist paying for *Parental guidance*. |
| 28–30 | 57–61 | The install lock — a closed set taken at the lock, not a date. `DISALLOW_INSTALL_APPS` retired for blocking Play Store *updates*. |
| 25–27 | 51–56 | The curfew as three disconnect philosophies; the browser chooser; herald mono. |
| 18 | 50 | **`v0.2.7`, the alpha `main` still runs.** |

**Anything older is in `git log` and in [design-decisions](design-decisions.md).**
The investigations that used to fill this file — Play Protect, FRP, the QR path,
the reveal screen that locked phones by accident, five rounds of browser bugs —
are settled, and their conclusions are either above, in the traps, or in the code
comment that exists because of them.
