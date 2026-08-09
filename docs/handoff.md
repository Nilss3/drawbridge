# Handoff — state as of 2026-08-09

Everything about *how the system works* lives in [README](../README.md),
[design-decisions](design-decisions.md), [policy](policy.md),
[provisioning](provisioning.md) and [removal](removal.md). This file covers only
what those do not: where the project actually stands, what is on the build
machine, what was and was not verified, and what to do next.

---

## drawbridge works end to end

**This is the MVP.** On 2026-08-07 a Moto G15 was taken out of its box, scanned
with the provisioning QR, and became a filtered, managed phone without a cable
ever being attached. Everything in the chain has now been watched working on
real hardware rather than on an emulator: QR provisioning, Device Owner, the
restriction set, the always-on DNS filter with safe-search rewriting, the app
blocker removing apps, both browsers installing themselves, the lock cycle, the
key, removal, and survival across a reboot and an OTA system update.

Nothing below takes that away. Read it as "what to build next", not "what is
still broken".

## Where things stand

| | |
|---|---|
| Repo | https://github.com/Nilss3/drawbridge — public, `main`, 96 commits |
| Release | [v0.2.0](https://github.com/Nilss3/drawbridge/releases/tag/v0.2.0), 9 assets, **latest**; [v0.2.1](https://github.com/Nilss3/drawbridge/releases/tag/v0.2.1) is a one-asset **pre-release** on purpose |
| Live policy | version **27**, live at `dist/policy.signed.json` on `main` |
| Apps, published | drawbridge `0.2.1` (versionCode 12), **which no deployed phone can install** — see below; herald and herald mono `0.1.8` |
| Website | trilingual, generated into `site/`, on Cloudflare Pages |
| Tests | 372 unit tests across four build variants, lint clean |

The two apps are **no longer in lockstep**, and that is deliberate: herald has
not changed, and rebuilding it purely to move a version number would alter every
hash and force a policy re-sign for an identical binary.

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
and [PolicyComplianceActivity](../dpc/src/main/java/app/drawbridge/dpc/admin/PolicyComplianceActivity.kt)
— but that alone was not enough, and the second half is below.

### And then it half-provisioned, which was our fault too

With the handoff activities in place the wizard got much further: Device Owner
granted, policy compiled, home screen reached. And then setup never finished.
`USER_SETUP_COMPLETE` stayed `0`, which is a state Android treats very
differently — no notification shade, and a Settings that closes itself the
instant it opens. Rebooting returned to the same half-provisioned state; only
after drawbridge gave up Device Owner did the wizard restart and show *"This
phone may be unsafe"*, offering a factory reset. Continuing setup from there
resolved its suspicion by tearing Device Owner out and running the normal OEM
preload flow.

The cause was drawbridge locking the phone down from inside the wizard —
`onProfileProvisioningComplete` applied every restriction, brought up the
always-on VPN, and started a ~470 MiB download while the wizard was still on
screen. See
[design-decisions](design-decisions.md#nothing-is-enforced-until-the-phone-is-locked).

**QR provisioning now works end to end**, verified on the G15 on 2026-08-07 with
a release build, and the on-device provisioning record shows the causal chain
rather than just the outcome:

```
21:33:49  setup=0  GET_PROVISIONING_MODE received
21:34:40  setup=0  applyManagedDevicePolicy DEFERRED (setup running)
21:34:56  setup=0  ADMIN_POLICY_COMPLIANCE -> RESULT_OK
21:37:37  setup=1  applyManagedDevicePolicy applying
```

`setup` flips after control is handed back and before anything is applied.

What is now unknown, rather than assumed:

- **Whether the allowlist applies to drawbridge at all.** It never blocked this
  device. That could be the 2026-08-06 appeal landing, or this Moto's GMS build
  not enforcing it. One handset, one date.
- The appeal itself remains unanswerable by design: the form states decisions
  are final and no reply is sent.

### Play Protect blocks drawbridge from updating itself — and the permission was not why

**Found 2026-08-08, tested on 2026-08-09, still open, still the most serious
problem in the project.** A phone can be provisioned, filtered and locked, and
then never receive a fix to drawbridge again — silently, months later, on
somebody else's device.

Read this section as two halves. The first is what was seen on 2026-08-08 and
the explanation it produced. **That explanation was wrong**, and the second half
is the experiment that showed it, which also narrowed the problem considerably.
Nothing here is fixed yet.

#### What was seen on 2026-08-08

What was observed, in order:

1. A Google account was added to the G15 (it had never had one).
2. Opening drawbridge triggered `UpdateWorker.runNow`, which downloaded v0.2.0
   and tried to install it.
3. Play Protect showed **"Harmful app blocked: drawbridge — this app can install
   potentially harmful apps without your permission"**, with a single *Got it*.
4. The policy updated normally, 23 → 26. drawbridge stayed on versionCode 10.
5. **Play Protect was switched off in the Play Store; drawbridge immediately
   updated itself to versionCode 11.** Switching it back on left the installed
   copy alone — a later scan reported no harmful apps.
6. herald was uninstalled by hand and **reinstalled itself with Play Protect
   active**, without complaint.

That is a controlled result rather than an impression: one variable, off and on,
with the outcome changing both times.

**Why it appeared only now:** Play Protect needs a Google account to be fully
active, and the device had none until 2026-08-08. Every earlier silent install
succeeded because nothing was watching. This means the account that
[provisioning](provisioning.md) requires — because FRP is the backstop for
having removed `DISALLOW_FACTORY_RESET` — is the same account that breaks the
update channel. Those two requirements currently point in opposite directions.

The explanation this produced was that the mechanism is **the payload's
permissions**: the notification's wording is the standard user-facing
description of `REQUEST_INSTALL_PACKAGES`, which drawbridge declared and herald
does not. So drop the permission and the problem goes away.

#### 2026-08-09: it is the payload, but it is not that permission

Two things were established, in this order, because the cost of being wrong
about the first was a build that could install nothing at all.

**A Device Owner does not need `REQUEST_INSTALL_PACKAGES`.** Verified on the
provisioned emulator rather than inferred: with the line deleted from the
manifest, the DPC still installed herald 6 → 8 in place and herald mono from
absent, both silently, both through `PackageInstaller`. The permission governs
the user-facing unknown-sources flow, which a Device Owner committing its own
session never enters — the same reasoning that already keeps `DELETE_PACKAGES`
out of the manifest. **This half held.** The permission is gone for good; it
bought nothing.

**And removing it unblocked nothing.** drawbridge 0.2.1 (versionCode 12) was
published with the permission absent, policy 27 pointed `app_update` at it, and
the G15 was rebooted. The phone took policy 27 and stayed on 0.2.0. Play Protect
fired again.

Then the control that makes this a result rather than an anecdote. herald was
uninstalled by hand and drawbridge locked, so the DPC attempted **both installs
in the same run, with Play Protect on**:

- **herald installed.** Silently, no complaint.
- **drawbridge was blocked**, with the same notification as the day before.

Same installer, same moment, two payloads, one verdict each. What that settles:

- **It is not the installer's identity or reputation.** The same v11 DPC
  installed herald successfully seconds either side of being refused.
- **It is not `REQUEST_INSTALL_PACKAGES`.** The refused payload does not declare
  it.
- **The notification's wording is not read off the incoming manifest.** It still
  says *"This app can install potentially harmful apps without your
  permission"* — the canned description of a permission the APK no longer asks
  for. Whatever Play Protect classifies on, it is not that line.

One detail recorded because it cost nothing and might save someone an afternoon:
the notification names the app **`app.drawbridge.dpc.DrawbridgeApplication`** —
the package plus the `<application android:name>` class, which is what a label
lookup falls back to when it cannot resolve an app label. So Play Protect does
appear to be parsing the staged APK rather than replaying a cached notification,
while still classifying it on something other than what it just read.

What is left, cheapest first:

- **`REQUEST_DELETE_PACKAGES`** — the last install-adjacent permission
  drawbridge declares and herald does not, and very likely unnecessary for the
  same reason its `DELETE_PACKAGES` sibling is. Untested either way.
- **The install session itself.** `SessionParams.setInstallReason(INSTALL_REASON_POLICY)`
  is public API and true by construction for a Device Owner applying its own
  policy; drawbridge sets no install reason today. **It can only be tested by an
  already-installed build**, so it is exercised one release later than the one
  that adds it — plan the two changes accordingly.
  `setPackageSource(PACKAGE_SOURCE_STORE)` is also available and is deliberately
  not used: it asserts a provenance the install does not have.
- **Everything else drawbridge has and herald does not** — `QUERY_ALL_PACKAGES`,
  which cannot be dropped because noticing packages nobody declared is the app
  blocker's whole job; the `DeviceAdminReceiver` and its metadata; the custom
  signature permission.

**The experiment that would settle it** is installing a drawbridge-shaped APK
under a *different package name* through `required_apps`. If that sails through,
the verdict is bound to `app.drawbridge.dpc` itself, no manifest change will
ever move it, and the remaining levers are an appeal and developer verification
rather than more builds. It is not first only because it puts a junk package
into the live policy.

Note this is unfolding **while the 2026-08-06 allowlist appeal is still live**.
Whether Play Protect's treatment of drawbridge is settled is unknown, so a
result today may not hold in a fortnight — in either direction.

**Every release is currently unable to reach a deployed phone.** 0.2.1 is
published and the one real device cannot install it. Weigh every change
accordingly, and do not ship anything that assumes this is solved.

### QR provisioning yields a cleaner phone than adb does

Not a side note — it is a reason to prefer it. The managed flow *replaces* the
consumer setup wizard, so the OEM's **downloaded** preloads never arrive. The
adb-provisioned G15 carried Temu, LinkedIn, Fitbit and several games; the
QR-provisioned one did not.

Checked rather than eyeballed, because the first version of this claim was wrong:
none of those packages are in `blocked_packages`, `allowed_packages` is null so
allowlist mode is off, and none of them are browsers — so no rule in the app
blocker could have removed them. They were never installed. Preloads baked into
the **system image** are a different matter and still arrive: Facebook was there,
and was removed as blocked.

Unchanged: publishing on Play would not help, since the gate is the Android
Enterprise allowlist rather than Play distribution; and non-GMS devices
(LineageOS, /e/OS, GrapheneOS) have no allowlist to enforce.

### Developer verification, September 2026 — and why the old note is now suspect

This file used to say flatly that **Android developer verification does not
affect drawbridge**, because apps installed by a DPC on managed devices are
exempt indefinitely, as are ADB installs. That is still what the documentation
says. It should now be treated as unverified rather than settled.

Google is reported to be tightening this from **September 2026** — next month at
the time of writing — potentially requiring every installed app, sideloaded ones
included, to come from a verified developer.

The reason for downgrading the claim is not new information about verification.
It is that this session produced three cases where a documentation-derived
belief about Google's behaviour was wrong:

- `DISALLOW_FACTORY_RESET` "only hides the option inside Settings" — it also
  strips the entry from the hardware recovery menu.
- The DPC allowlist was "the one thing that could stop everything" — it never
  blocked anything; our own manifest did.
- And Play Protect blocked a **DPC installing an app on a managed device**,
  which is exactly the category supposed to be exempt from that kind of
  interference.

Different mechanism each time, same shape: a carve-out was assumed and the
carve-out did not hold. A fourth instance would be no surprise.

**If the exemption does not hold, the consequence is total** — not slower
updates but no installs at all, herald included, on every certified device.

**The mitigation is probably compatible with the project.** Developer
verification concerns the *developer's* identity, not the user's. drawbridge's
constraint is that the phone needs no account and no backend; who signed the APK
is not part of that. So unlike publishing on Play — which would not help anyway,
since the gates here are Play Protect and the Enterprise allowlist rather than
distribution — registering as a verified developer is a dependency this project
could take without contradicting itself. Worth finding out what it costs before
September rather than after.

### v0.2.0, and policies 24 to 26

**[v0.2.0](https://github.com/Nilss3/drawbridge/releases/tag/v0.2.0)** — drawbridge
only. herald and herald mono are the v0.1.8 binaries, republished byte for byte
because `required_apps` pins them by URL under `/releases/latest/download/`: a
release that omitted them would 404 every browser URL on every device.

`versionCode` is **11**, not 10. The release candidates used 10, and a final
build sharing a version code with an rc would never reach a device provisioned
from one, since `app_update` only installs a strictly greater code.

- **24** — `app_update` from version_code 9 to 11 with the published DPC's hash.
  Signed *after* the assets were up, never before.
- **25** — the reconciliation: 21 apps whose domains were blocked while their
  packages were missing, so they installed and sat there broken. 157 → 178.
- **26** — Patreon allowed again, package and domain together. It is a payment
  page, not a feed, and grouping it with OnlyFans was a category error.
- **27** — `app_update` to versionCode 12, for the Play Protect test below.

**[v0.2.1](https://github.com/Nilss3/drawbridge/releases/tag/v0.2.1) is a
deliberate pre-release**, and the shape is worth reusing. It carries the DPC and
nothing else, so it costs a 3 MB upload instead of the 1.1 GiB a full release
costs — and policy 27 pins `app_update` at the **versioned** URL rather than
`/releases/latest/download/`, so v0.2.0 stays `latest` and all six
`required_apps` URLs and the QR keep resolving. That is the whole trick for any
drawbridge-only test build.

**Do not simply un-flag it to promote it.** v0.2.1 has no herald assets, so the
moment it became `latest` every `required_apps` URL would 404 on every device.
Promoting means uploading herald's six APKs into it first, or cutting the next
version the normal way.

One consequence to remember: while v0.2.0 is `latest`, **the QR still seeds
versionCode 11**. Harmless — a fresh phone has no Google account during
provisioning, so Play Protect is not yet active — but it does mean a newly
provisioned device lands on the old build and then has to survive the same
update channel as everything else.

**Three release candidates are published and flagged**, rc1 and rc2 with warning
banners: both carry `DISALLOW_FACTORY_RESET` and must not be used to provision.

### The factory-reset restriction was removed, and why that matters

`DISALLOW_FACTORY_RESET` does far more than its documentation says. It does not
merely hide the entry in Settings — **it strips "Wipe data/factory reset" out of
the hardware recovery menu too**, measured on the G15, with the entries
reappearing the instant drawbridge gives up Device Owner. A parent who lost the
key had a phone reclaimable only by reflashing firmware from a PC.

It is retired and actively cleared, so a device provisioned by rc1 or rc2 sheds
it on the next apply. See
[design-decisions](design-decisions.md#drawbridge-does-not-prevent-a-factory-reset).

Two things replaced it, and both are weaker than the restriction was:

- **Factory Reset Protection**, which depends on the parent's Google account
  being the only one on the device — and `lockAccounts()` is dead code, so
  nothing stops a child adding their own. See the unverified list.
- **The protected-since date**, which is why the clock is now locked on every
  device: winding it back a year, locking, and winding it forward forges a year
  of protection that never happened.

Putting the restriction back is on the roadmap, but **only behind a working
delayed self-removal** — the two ship together or not at all.

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
  anyone depends on gets wiped, and it has earned that: provisioned, removed and
  re-provisioned perhaps a dozen times across one session, by QR and by adb.

  **It is the reference device.** Every claim in this file about real hardware
  came from it.

  **Its current state, 2026-08-09**: drawbridge `0.2.0` versionCode **11**,
  policy **27**, locked, with a Google account and Play Protect **on**. It is
  the rig the Play Protect problem is being tested on, and it stays on 11 and on
  Play Protect until that resolves.

  It cannot take a sideload — `DISALLOW_DEBUGGING_FEATURES` is applied and
  unlocking does not lift it — so the only way to change what is installed on it
  is through the update channel, or by switching Play Protect off by hand.

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

- **`lockAccounts()` is dead code, and a doc promises otherwise.**
  `DISALLOW_MODIFY_ACCOUNTS` is never applied: the function exists,
  `unlockAccounts()` is called during removal, and nothing ever calls
  `lockAccounts()`. Meanwhile [provisioning.md](provisioning.md) step 3 tells
  parents that running setup "locks account changes".

  **Confirmed on hardware, 2026-08-08**: a Google account was added to the G15
  while drawbridge was locked, on a device that had none before. Nothing
  objected. This is no longer inferred from reading the code.

  Note the consequence for the fix: wiring `lockAccounts()` into `lockDevice()`
  makes the documented order — account first, then lock — *mandatory* rather
  than advisory. A parent who locks first and then needs their account on the
  phone has to unlock to add it, which mints a new key and invalidates the one
  they wrote down. Probably acceptable, but decide it rather than discover it.

  This matters more since `DISALLOW_FACTORY_RESET` was dropped, because FRP is
  now a load-bearing backstop rather than a second line. The FRP argument is that
  a child cannot answer the challenge, having never had an account on the
  device — but if account changes are not locked, they can add their own at any
  point afterwards and answer it themselves. Wiring `lockAccounts()` into
  `lockDevice()` is the fix and fits the enforcement rule exactly; it was left
  alone deliberately so it would not obstruct the FRP and Family Link testing.

- **Whether FRP behaves as assumed at all.** The trusted-versus-untrusted wipe
  distinction — a Settings reset clears FRP, a recovery reset does not — is taken
  from documentation, and this session has twice shown that to be a poor
  substitute for trying it. Everything the removal decision rests on assumes it.

- **Everything on `main` since rc2.** Three DPC changes are unverified on
  hardware: the enforcement gate (nothing applies until the phone is locked), the
  same gate on the daily `UpdateWorker`, and the uncropped hero. The half that
  most needs watching is not that provisioning stays clean but that **locking
  still applies everything afterwards** — deferring is only correct if the work
  actually happens later.
- **`AppBlocker.sweep()` driven from the UI.** Applying a policy and turning the
  WhatsApp option **off** both call it, and neither has been watched removing
  anything. The blocker itself is now well proven on hardware; what is untested
  is these two entry points into it — and both are now gated on the phone having
  been locked, which is also untested.
- **What locking does *after* a removal.** Reported as "locking doesn't work"
  on a device whose Device Owner had been given up. Without ownership it can
  still start the filter through the VPN consent prompt but can apply no
  restrictions, so it half-works — which is worse than failing outright. The
  exact symptom has not been pinned down, and the UI still offers the button.
- **Declining the battery-optimisation prompt.** The filter survives it — an
  always-on foreground service — but the daily policy and update poll can be
  deferred by Doze, so the phone filters against a stale list. Diagnostics now
  reports `battery exempt`, but nobody has watched a declined phone go stale.
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
- ~~**A policy version *changing* under a running device.**~~ **Done.** The G15
  went 23 → 26 unaided and 26 → 27 across a reboot on 2026-08-09, taking the new
  document each time while the old one was in force. This one can come off the
  list.
- **The curfew.** Drafted in policy 22 and never run on a device. Nothing
  reads it and no published policy carries one; wiring it up means calling
  `CurfewController.apply` from `BootReceiver` and after a policy refresh.
  The failure that matters is one that does not lift.
- **The self-update path**, which is now half-answered and worse than untested.
  `checkAndInstallSelf` has been exercised twice on the G15 with a genuinely
  newer version to fetch — policy 24 naming versionCode 11, then policy 27
  naming 12. Both times it found the update, downloaded it and committed the
  session; both times **Play Protect refused the install**. So the code path
  works and the channel does not. Nothing about drawbridge's own logic is known
  to be wrong here. See the Play Protect section.
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

The MVP is done and shipped. What follows is a feature roadmap, in the order the
owner set on 2026-08-08, not a defect list.

### 1. Get drawbridge able to update itself again

**Above the FRP test, because without an update channel nothing else can be
delivered.** A phone that cannot receive a fix is a phone where every bug found
from here on is permanent. It also shares a root cause with step 2: adding the
Google account is what activated Play Protect in the first place.

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

**Fix `lockAccounts()` at the same time.** `DISALLOW_MODIFY_ACCOUNTS` is never
applied — the function exists, `unlockAccounts()` runs during removal, nothing
calls `lockAccounts()` — so even with FRP armed a child can add their own
account afterwards and answer the challenge themselves. [provisioning](provisioning.md)
tells parents that setup "locks account changes", which is currently false.
Wiring it into `lockDevice()` fits the enforcement rule exactly: the parent adds
their account in the window before locking, and locking closes it. It was left
alone deliberately so it would not obstruct this very test — do the test first,
then wire it.

Also worth checking while an account is on the device: whether a **supervised
Family Link account** satisfies FRP the same way an ordinary one does, and what
Family Link does on a device that already has an owner. Neither is known, and
"can I use this alongside Family Link" is a question every parent will ask.

### 3. herald must force safe search everywhere, or refuse the engine

The policy already rewrites Google, Bing and YouTube at the DNS layer, and that
covers the engines it names and nothing else. A search engine drawbridge has
never heard of resolves normally and returns whatever it likes. herald is the
only browser on the device, so it is the right place to close this: force safe
search on every engine it offers, and for any engine where that cannot be forced,
do not offer it at all. Note that DNS rewriting cannot do this alone — the engine
list lives in the browser.

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

### 7. The curfew, with a floor of half an hour of internet a day

Drafted and never run on a device: the schema, the window arithmetic and the
Device Owner calls exist and are tested, nothing reads them, and no published
policy carries one. See
[policy](policy.md#curfew-an-evening-with-no-internet) and
[design-decisions](design-decisions.md#the-curfew-is-that-same-lockdown-used-on-purpose).

The new requirement is a **guaranteed daily window of at least thirty minutes**,
so a phone can always fetch its policy and its updates. That is not a nicety: a
curfew long enough to cover the whole day would otherwise be able to stop
drawbridge ever hearing about a policy that lifts it.

The failure that matters is a curfew that does not lift. Wire `CurfewController.apply`
into `BootReceiver` and the policy refresh, add the `<receiver>` entry for
`CurfewReceiver`, and treat "cannot leave lockdown" as the case to design
against — a phone with no internet and no way to fix it from the phone.

The clock lock this needs is already in place and applies to every locked
device, curfew or not.

### 8. A dev branch

Everything so far has gone straight to `main`, which is also what Cloudflare
deploys and what every device fetches its policy from. That was tolerable while
the only device was on the desk. It stops being tolerable now that a real phone
is provisioned: a policy pushed to `main` is live within minutes, and
`dist/policy.signed.json` has no staging path at all.

Worth deciding at the same time: whether the *policy* gets a channel of its own,
since a branch protects the code but the live document is a URL on `main`.

### 9. Lock factory reset — last, and only with the timer

`DISALLOW_FACTORY_RESET` goes back **only** once the delayed self-removal works,
and deliberately after everything above — unless step 2 shows that FRP does not
hold, in which case this moves up, because the backstop it was removed in favour
of would not exist. While features are being built, a
mistake that bricks a handset costs a device; with a factory reset available it
costs ten minutes. See
[design-decisions](design-decisions.md#losing-the-key-a-delay-not-a-back-door).

Remember that reinstating it means reversing the `RETIRED_RESTRICTIONS`
migration too, and that today's escape works even when drawbridge is completely
broken while a timer-based one does not.

### Standing items, unchanged

- **Retry the QR every couple of weeks** if the allowlist question ever matters
  again. It has never blocked this project, on one handset, on one date.
- **Keep both keys backed up.** Every published release now depends on it.
- **Drop unused ABIs.** `armeabi-v7a` and `x86_64` have never been downloaded by
  anything and cost ~650 MiB of every release. Removing an ABI means removing
  its `required_apps` entry in the same policy.
- **Build the WebADB installer.** The `/install/` page still has a disabled
  "Install over USB" button. Less urgent now that QR provisioning works, and
  still the only path that needs no cable and no allowlist.
- **Nothing in the UI forces an app update.** "Check for policy updates" calls
  `policy.refresh()` and nothing else, so a parent who knows a newer drawbridge
  exists has no button for it — the only routes are waiting a day or rebooting,
  and nobody would guess the second. Folding `UpdateWorker.runNow` into that
  button, and renaming it, would close it.
- **Localise herald.** drawbridge speaks three languages; the browser is
  English-only, ~45 strings. drawbridge cannot set it — a per-app locale cannot
  be set by another app — so herald needs its own picker.


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
