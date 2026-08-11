# Handoff — state as of 2026-08-10

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
| Release | [v0.2.5](https://github.com/Nilss3/drawbridge/releases/tag/v0.2.5), 9 assets, **latest**; v0.2.1 to v0.2.4 are one-asset **pre-releases** on purpose |
| Live policy | version **33**, live at `dist/policy.signed.json` on `main` |
| Apps, published | drawbridge `0.2.5` (versionCode 16) and herald + herald mono `0.1.9`. Play Protect refuses `app.drawbridge.dpc` by name, so it cannot install itself and cannot be provisioned by QR — but it **can** be provisioned over adb; see below |
| Website | trilingual, generated into `site/`, on Cloudflare Pages |
| Tests | 372 unit tests across four build variants, lint clean |

The two apps are **no longer in lockstep**, and that is deliberate: herald has
not changed, and rebuilding it purely to move a version number would alter every
hash and force a policy re-sign for an identical binary.

## The way in is adb, and it costs one global setting

**Found 2026-08-10, on the reference G15, after everything below.** Read this
before the Play Protect narrative, because it changes what that narrative is
about: the verdict on `app.drawbridge.dpc` is unchanged and unappealed, and the
project is no longer stuck behind it.

Play Protect refuses the package at *install*. adb has a lever that the QR
wizard does not:

```bash
adb shell settings put global verifier_verify_adb_installs 0
```

That global decides whether adb installs are put to the verifier at all. It is
writable only from a shell — which is exactly why the setup wizard cannot do the
equivalent, and why this reopens adb and not QR. Same APK, same phone, minutes
apart, zero Google accounts, no device owner:

| `verifier_verify_adb_installs` | `adb install app.drawbridge.dpc` |
|---|---|
| unset (platform default) | `INSTALL_FAILED_VERIFICATION_FAILURE` |
| `0` | **installs** |
| `1` (restored) | `INSTALL_FAILED_VERIFICATION_FAILURE` |

One variable, flipped three times, the outcome changing each time. Then
`dpm set-device-owner` succeeded and drawbridge 0.2.5 launched clean.

**Restoring the setting leaves the installed copy alone.** Device Owner survives,
`provisioningState` stays 3, the app runs. The verdict is applied at install and
is not re-litigated against what is already on the device — the same asymmetry
seen on 2026-08-08, when Play Protect was switched back on and the copy that had
landed while it was off was left where it was.

**The parent never touches Play Protect.** This is the part worth defending. The
obvious alternative — tell them to switch Play Protect off in the Play Store — is
device-wide, indefinite, and asks somebody to disable a protection on a child's
phone. This window is two `adb install` calls long, on a cable, with the operator
present.

`tools/provision-adb.sh` is the whole path: preconditions, the verifier window,
both APKs, Device Owner. It treats putting the setting back as the thing it must
not get wrong — the original value is read first and `null` is restored by
*deleting* the row rather than writing one, the restore runs on success, on any
failed install and on Ctrl-C, it happens before Device Owner is granted, and a
restore it cannot confirm is a loud non-zero exit rather than a success message.
Exercised on all of those paths.

**What this does not fix**, and do not let it blur:

- **QR provisioning is still closed on certified hardware.** The wizard has no
  shell. Nothing here touches that.
- **drawbridge still cannot update itself.** Self-update is a `PackageInstaller`
  session, not an adb install, and that global does not govern it. 0.2.5's manual
  [UpdateActivity](../dpc/src/main/java/app/drawbridge/dpc/ui/UpdateActivity.kt)
  is still the route, and it still asks the parent to pause Play Protect.
- **QR provisioning is still closed on certified hardware.** The wizard has no
  shell. Nothing here touches that.

So: new devices can be provisioned today, on certified hardware, without an
appeal and without a rename.

### No factory reset is needed, and the account was never the install problem

**Tested on the G15 on 2026-08-10, after the owner asked whether two long-standing
assumptions were actually true. Neither was.**

There are **three gates**, and this project has been conflating them:

| Gate | Mechanism | Does a Google account matter? |
|---|---|---|
| Installing the APK | Play Protect verification | **No.** Refused with zero accounts and with one alike; the verifier lever works the same either way |
| Granting Device Owner | Android platform check | **Yes**, and this is the only one that cares |
| QR provisioning | Play Protect, at install | No — it fails at the first gate |

With one Google account signed in: the install failed as usual, then succeeded
the moment `verifier_verify_adb_installs` was set to 0 — identical to the
zero-account result. Then `dpm set-device-owner` threw *"Not allowed to set the
device owner because there are already some accounts on the device"*. So the
account has never been why anything failed to install. It blocks one platform
call.

**And that call's precondition is removable.** The account was removed in
Settings and `dpm set-device-owner` succeeded immediately, on a phone reporting
`user_setup_complete: 1` — fully set up, in use, **never reset for this**. Apps
intact, both herald editions still installed, nothing wiped but that account's
local data.

**So the headline claim in the parent-facing guide was false.** It opened with
*"This erases the phone. drawbridge can only be installed on a phone that has just
been reset, because Android only hands out this level of control before any
account exists."* The second clause is nearly right and the conclusion does not
follow: Android wants no account *at the moment you grant it*, which is a state
you can pass through. The flow is **remove accounts → provision → sign back in**.

`no_modify_accounts` is not applied and nothing is enforced until lock, so signing
back in afterwards is unobstructed.

**What this costs, and it must be said in the same breath as "no wipe needed":**
locking uninstalls every app in `blocked_packages`, straight away, and switching
an option back on does not reinstall them. On a wiped phone the app blocker has
nothing to remove; on a phone in daily use it takes things the owner had. That
caveat now leads [install.md](install.md) in all three languages and the website's
install page.

The reset path still buys the thing noted on 2026-08-07 — a QR-provisioned phone
never receives the OEM's *downloaded* preloads — and an in-use phone obviously
keeps everything it had.

### And the cable is now repeatable, because USB debugging follows the lock

**Decided by the owner on 2026-08-10, and implemented the same day.** The adb
channel used to be available exactly once — `DISALLOW_DEBUGGING_FEATURES` landed
at lock and stayed for the life of the device, so a phone in the field could
never be fixed at all. Since drawbridge cannot update itself either, that made
every bug found after deployment permanent.

USB debugging is now the one restriction keyed on the **lock** rather than on
`protectedSince`: applied when the key is committed, cleared when the key is
used. The rule lives in `DeviceOwnerManager.restrictionsFor` and nowhere else,
and `applyUserRestrictions` now *clears* whatever the current state leaves out —
without that half a restriction could go on and never come off.

So delivery to a deployed phone is: unlock drawbridge with the parent's key,
re-enable USB debugging, `tools/provision-adb.sh --update`, lock again.

**It gives nothing away.** An unlocked drawbridge is one whose configuration
screen is open, and that screen offers complete removal in its overflow menu.
Whoever holds the key can already undo everything; the restriction only ever
protected against somebody who does not have it, and that person cannot unlock
the phone to begin with.

**The ordering is the part to be careful with.** `MainActivity.lockDevice`
applies the policy *before* the parent has decided to keep the key, so this
restriction is applied later, from `LockActivity.sealWithKey`, after
`ParentKey.commit`. An abandoned reveal therefore leaves a phone that can still
be worked on — the same reasoning that already leaves it unsealed. Getting that
backwards would take the cable away from a parent who has not yet written the key
down.

Covered by `DeviceOwnerRestrictionsTest`, including that unlocking moves *only*
this entry: dropping `DISALLOW_SAFE_BOOT` or the multi-user restrictions on
unlock would leave an unlocked phone unfiltered rather than merely reachable, and
nothing else in the codebase would notice.

**Verified on the G15 by the owner on 2026-08-10**: developer options are greyed
out while drawbridge is locked and come back after unlocking. That is the half no
one can check over adb, since the restriction's whole effect is that adb goes
away.

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

**Why it appeared only then, as understood at the time:** Play Protect was
believed to need a Google account to be active, and the device had none until
2026-08-08. **That belief is wrong** — see the 2026-08-10 entry below, where Play
Protect refused an install on a phone with zero accounts. The timing was a
coincidence, or the verdict was formed around then for other reasons. The
account is not the trigger, and nothing about the FRP argument depends on it
either way.

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

**The name in the notification is noise, not signal.** Round one's notification
called the app `app.drawbridge.dpc.DrawbridgeApplication` — the package plus the
`<application android:name>` class, which is what a label lookup falls back to
when it cannot resolve a label — and that briefly looked like evidence about what
Play Protect was reading. It is not: 2026-08-08 said "drawbridge", round one said
the class name, round two said "drawbridge" again, for three payloads that differ
only in ways that have nothing to do with labels. The rendering is simply
inconsistent between attempts. Do not build an argument on it.

#### Round two: 0.2.2, published, and blocked as well

Two changes, chosen so they are exercised at **different moments** and therefore
do not confound each other:

- **`REQUEST_DELETE_PACKAGES` is gone**, the last install-adjacent permission
  drawbridge declared and herald does not. Verified unnecessary on the
  provisioned emulator first, to the same standard as the other one: with
  neither permission declared, the app blocker still uninstalled a disallowed
  browser. drawbridge's manifest now asks for nothing install-related at all.
  **Tested by versionCode 11 installing this build.**
- **`SessionParams.setInstallReason(INSTALL_REASON_POLICY)`** on the install
  session. Public API, one line, and true by construction for a Device Owner
  applying the policy it was provisioned with. **It can only be tested by an
  already-installed build** — the session is described by whatever is doing the
  installing — so it is exercised one release later, by 0.2.2 installing 0.2.3.
  `setPackageSource(PACKAGE_SOURCE_STORE)` is also available and is deliberately
  not used: it asserts a provenance the install does not have.

**The manifest half of round two failed too.** versionCode 13 was refused on the
same terms. So three payloads have now been offered to the same installer — one
declaring `REQUEST_INSTALL_PACKAGES`, one without it, one declaring nothing
install-related whatsoever — and all three were blocked, while herald installs
from that same installer without comment.

**Treat the manifest as ruled out.** Both permissions stay gone because they were
verified to do nothing, not because removing them helped. What is left that
drawbridge has and herald does not cannot be given up: `QUERY_ALL_PACKAGES`,
because noticing packages nobody declared is the app blocker's whole job; the
`DeviceAdminReceiver` and its metadata; the custom signature permission. Editing
the manifest further is not a plan.

#### Round three: the install session, and the end of what we control

`INSTALL_REASON_POLICY` had never actually run. A `PackageInstaller` session is
described by whatever does the installing, so the release that introduces a
session parameter can only ever be installed by a build that lacks it — and the
G15 sat on versionCode 11 through rounds one and two. Landing 0.2.3 by hand,
with Play Protect switched off, made the phone's installer a build that marks
its sessions; 0.2.4 was then published differing in **nothing but the version
number**, so the session was the only variable.

**Blocked, same message.** Four rounds:

| Payload | What was different | Result |
|---|---|---|
| 11 | `REQUEST_INSTALL_PACKAGES` + `REQUEST_DELETE_PACKAGES` | blocked |
| 12 | no `REQUEST_INSTALL_PACKAGES` | blocked |
| 13 | no install-related permissions at all | blocked |
| 15 | installed by a build marking `INSTALL_REASON_POLICY` | blocked |
| herald 8 | — | installs, from the same installer, in the same run |

**Everything inside the APK that we control has now been tried.** The manifest
over three rounds and the session parameters over one. Do not spend another
round on either; the next person's instinct will be to try one more permission
combination, and that instinct has been wrong four times.

`INSTALL_REASON_POLICY` stays in the code. It is true, it costs nothing, and
"it did not help" is not a reason to describe a session dishonestly.

#### What the documentation says, which nobody had read until 2026-08-10

Four rounds of experiment were run before anyone looked this up. It should have
been first.

**Google enforces an allowlist of approved Android Enterprise device policy
controllers, and Play Protect flags unapproved ones as "Harmful app blocked"** —
the exact wording seen on the G15. The trigger is reported as the app *being a
DPC*, not as any permission it declares. Google's own help page puts it as: only
DPCs verified and approved by Android Enterprise may install apps during
enterprise enrolment.

That fits every observation at once. herald installs because it is not a DPC.
drawbridge is refused whatever its manifest says. The message never changed
across four rounds because it was never about the thing being changed.

It does **not** reverse the earlier finding that the allowlist did not break QR
provisioning on 2026-08-07 — that phone had no Google account, so Play Protect
was not active. Both are true, and the difference between them is the account.

**There is no supported way to switch verification off.** The Android Enterprise
security guidance only shows an admin how to *strengthen* it —
`ENSURE_VERIFY_APPS`, `DISALLOW_INSTALL_UNKNOWN_SOURCES`, and a managed
configuration on `com.android.vending` — and `setGlobalSetting` is largely
deprecated with no key that helps. That avenue is closed rather than untried.

- https://support.google.com/work/android/answer/16694822 — the allowlist
- https://developer.android.com/work/dpc/security — verification, enforce-only
- https://bayton.org/android/android-enterprise-faq/play-protect-blocked-my-dpc-why/
  — the same wording, and appeal advice: align with the unwanted-software
  guidance *before* appealing, being transparent about function and justifying
  sensitive permissions. Review times of days to weeks, no published SLA.

**The lesson worth keeping** is not about Play Protect. Four rounds of build,
publish, sign, reboot were spent on hypotheses that ten minutes of reading would
have reordered. The project's own habit — verify on hardware rather than trust
the documentation — was right about `DISALLOW_FACTORY_RESET` and the DPC
allowlist and quietly became a reason not to read the documentation at all.

#### 2026-08-10, and this is the answer: the package name is refused

**QR provisioning stopped working entirely, and the cause turned out to be the
same one.** After the FRP test wiped the G15, provisioning it again failed: QR
scanned, APK downloaded, *"this device belongs to your organization"*, then
**"Something went wrong"** — the 2026-08-07 symptom, with no Play Protect message
on screen.

It is not a regression. **v0.2.0 — the byte-identical build that provisioned this
same phone on 2026-08-07 — now fails in exactly the same way.** The handoff
activities are present in the published APK (`aapt2 dump xmltree`), and the only
manifest differences from v0.2.0 are two removed `uses-permission` lines and one
non-exported activity.

**The controlled test.** On the freshly reset G15 — **zero Google accounts**, no
device owner, no device admins, `provisioningState: 0` — over adb, minutes apart:

| Installed | Result |
|---|---|
| `app.drawbridge.dpc` 0.2.5 | `INSTALL_FAILED_VERIFICATION_FAILURE`, Play Protect dialog |
| `app.drawbridge.probe` — same code, same key, different `applicationId` | **installs silently** |

`logcat` names the refuser:
`com.android.vending/…finsky.protectdialogs.activity.PlayProtectDialogsActivity`.
The probe also **provisions by QR** where `app.drawbridge.dpc` cannot.

**So Play Protect refuses this package by name, at install, device-wide, with no
account signed in.** Provisioning fails downstream of that — the wizard cannot
install the DPC, so the handoff never happens and the wizard reports a generic
error. Two symptoms, one cause.

Everything else is excluded by that one table: not the code, not the signing key
(the probe shares it), not the device (the probe works on it), not leftover DPC
state (wiped, and the package is not even installed), not the account (there is
none), and not being a DPC (the probe is one).

**The consequence is the worst state this project has been in.** The name is not
degraded, it is unusable: `app.drawbridge.dpc` cannot be installed on a certified
Android device, so it cannot be updated *and cannot be provisioned*. The QR path
— the entire reason provisioning needs no cable — is closed for that package.

#### What to do about it

**Appeal, and note the route exists for apps that are not on Play.** Google's
developer guidance for Play Protect warnings carries a dedicated form, separate
from the Play Store removal appeal:
<https://support.google.com/googleplay/android-developer/contact/protectappeals>.
Their wording is that classifications are corrected "in appropriate
circumstances, including if an error was made". This is the only route that
reclaims the name.

**A rename works today and is a one-way door.** The probe proves a different
`applicationId` installs and provisions. Renaming is also mechanically cheap now:
`dpcApplicationId` is already a Gradle property, the custom permission and the
provider authority are already manifest placeholders, and keeping their literal
defaults means **herald needs no rebuild** and `required_apps` needs no re-pin.
Against it: Device Owner binds permanently to the package name, so every future
device is committed; and the probe name is *hours* old to Google, so "clean" and
"not yet scanned" are indistinguishable. A rename might buy years or days, and
nothing here tells you which. Do not make renaming a habit — repeatedly changing
identity to escape a classification is itself the pattern these systems look for,
and it would make the appeal harder to win.

**There are no deployed devices.** The G15 is wiped and there is no user base, so
this is the cheapest moment a rename will ever cost. That is an argument about
timing, not about whether it is the right call.

#### What the research came back with, 2026-08-10

Most of it was our own evidence handed back. Four things were not.

**There are two separate Google mechanisms here, and they show different text.**
The Android Enterprise *approved-DPC allowlist* blocks an unapproved DPC at
enrolment with *"This app can request access to sensitive data. This can increase
the risk of identity theft or financial fraud."* What this device shows is *"this
app can install potentially harmful apps without your permission"* — a different
classification entirely, the general PHA/sideload one. **So the block on
`app.drawbridge.dpc` is not the DPC allowlist**, and the appeal filed on
2026-08-06 was aimed at the wrong gate. The right one is the Play Protect appeal
form, below.

**A second auto-block rule exists and does not apply to us.** Play Protect
auto-blocks sideloaded apps declaring `RECEIVE_SMS`, `READ_SMS`,
`NOTIFICATION_LISTENER` or `ACCESSIBILITY`, and extends that block during
enterprise enrolment. Checked against the published APK on 2026-08-10:
drawbridge declares none of the four. Ruled out.

**The appeal is a worse instrument than it looked.** The form
(<https://support.google.com/googleplay/android-developer/contact/protectappeals>)
wants developer name, package name, the APK's SHA-256 and a justification, and
states that decisions are final and **no response is returned**. One Android
Enterprise community thread reports a custom DPC still blocked after four appeals
across two months. Treat it as a lottery ticket with no receipt, not as a plan.

**And the direction of travel is against custom DPCs entirely.** The allowlist
was introduced during 2025 as the gate on them; the legacy Play EMM API closed to
new integrations on 30 September 2025, leaving the Android Management API as the
only open path; and the advice given in Google's own support forum to a developer
in this exact position was not "appeal" but "distribute through Managed Google
Play instead". That route means an AMAPI deployment with a Google-hosted
enterprise binding — which is the opposite of this project's no-account,
no-backend constraint, and would not deliver the DPC at enrolment anyway.

So the honest framing is not "a false positive awaiting correction". It is a
category being closed, on certified devices, with an allowlist as the mechanism.
The fork that actually matters:

- **Get on the approved-DPC allowlist** as a legitimate custom DPC, which is a
  submission-and-review process with no published timescale; or
- **accept that certified Android is closing this path** and decide what
  drawbridge is on devices where it is not enforced. Non-GMS builds
  (LineageOS, /e/OS, GrapheneOS) have no allowlist and no Play Protect at all.

A rename remains available and unresolved: it works today, it is a one-way door,
and nobody can say whether the new name holds.

#### The one test that cannot be run

The research proposed capturing `logcat`/`bugreport` across a live QR
provisioning failure, filtered for `Finsky`, `ManagedProvisioning` and
`SetupWizard`, to prove whether the provisioning failure is the same Play Protect
refusal or an unrelated ManagedProvisioning fault. It is the only open question
left, and **it cannot be done on this hardware**: when provisioning fails the
device does not fall back into setup, it **factory resets itself**, taking the
log buffer with it. There is no window in which to enable USB debugging and pull
anything.

That is also why the original 2026-08-07 failure took so long to diagnose, and
why the inference has to stand on its own: the wizard must install
`app.drawbridge.dpc`, that package will not install on this device by any route
tested, so provisioning cannot succeed. Strong, and still an inference.

#### What was actually left, and what the probes settled

#### The probes ran on 2026-08-10, and the answer is the package name

Both probes were the drawbridge APK with the same signing key, the same code and
the same manifest, delivered through `required_apps`. On one reboot, with Play
Protect on:

| Label | Package | Difference | Result |
|---|---|---|---|
| — | `app.drawbridge.dpc` | the real thing | **blocked** |
| probe A dpc | `app.drawbridge.probe` | renamed, still a DPC | **installed** |
| probe B plain | `app.drawbridge.probeb` | renamed, `DeviceAdminReceiver` removed | **installed** |

One reboot, three payloads, one variable between the first two. What that rules
out:

- **Not the code**, and **not the certificate** — probe A is byte-for-byte the
  same build under a different name, signed with the same release key.
- **Not being a DPC.** Probe A is a full device policy controller and installed
  anyway. So the approved-DPC allowlist is *not* what is blocking ordinary
  installs — consistent with Google's own wording, which scopes it to
  provisioning, and with the fact that this device provisioned fine before it had
  an account.
- **Not the manifest**, which four earlier rounds had already established.

**What is left is the package name.** `app.drawbridge.dpc` carries a verdict that
the identical APK under another name does not.

**The one reading this does not exclude**, and it matters enormously: the probes
are *new packages Google has never seen*. drawbridge has been scanned since
2026-08-08. So "the name is poisoned, permanently" and "an unknown package is
allowed until the backend catches up with it" both fit today's data. The
difference decides whether renaming is a fix or a few days' grace.

**The test that separates them is cheap and needs only time.** Leave both probes
installed, wait a week, publish a probe A with a higher version code, and see
whether it still installs. Still installing → the verdict is bound to the name
and a rename is a real fix. Newly blocked → content-based scanning caught up, a
rename only resets a clock, and nothing we ship will ever hold.

Until that is known, treat the rename as *unproven*, not as the plan.

**Building a probe cost two confounds that the emulator caught and the G15 could
not have.** Both would have failed the install *before* Play Protect was
consulted, and with no adb on the phone they would have read as blocks:

- `INSTALL_FAILED_DUPLICATE_PERMISSION` — two packages cannot declare
  `app.drawbridge.permission.READ_SELECTION`, even sharing a signing key; the
  installed owner wins.
- `INSTALL_FAILED_CONFLICTING_PROVIDER` — a `ContentProvider` authority is unique
  device-wide, and both used `app.drawbridge.dpc.selection`.

Both are now manifest placeholders in `dpc/build.gradle.kts`, defaulting to the
current literals so the shipped build is unchanged — verified with `aapt2`. The
label is a placeholder too, because two apps called "drawbridge" produce a
notification that cannot be attributed, and an app wearing another's name is its
own PHA signal. **Always install a probe on the emulator first.**

**Cleanup is deliberately deferred.** The policy after the test was meant to drop
both from `required_apps` and put both packages in `blocked_packages` so the app
blocker removes them. That still happens — but not until the week-long re-test
above is done, because the probes sitting on the device are the experiment. They
are inert while they sit there: neither is Device Owner, so `reapplyIfProtected`
does nothing, `installMissingRequiredApps` is gated on a `protectedSince` they do
not have, and `checkAndInstallSelf` returns `UpToDate` because `app_update` names
a package that is not theirs. They poll the policy every three hours and
otherwise cost two launcher icons.

#### So what now

**Nothing in this list is blocking any more** — see the adb section at the top of
this file, which reopens provisioning on certified hardware without needing any
of it. What remains below is about reclaiming the *name*, which still governs
QR provisioning and drawbridge's own self-update.

In rough order of value:

1. **Appeal the verdict on `app.drawbridge.dpc`.** This is a *different* appeal
   from the 2026-08-06 DPC-allowlist one — that governs provisioning, and
   provisioning is not what is broken. The relevant route is the Play Protect
   warning appeal for an app flagged as harmful:
   https://developers.google.com/android/play-protect/warning-dev-guidance
   Nothing else restores the update channel for the one phone that exists, since
   a rename cannot move an already-provisioned device: Device Owner is bound to
   the package name.
2. **Run the week-later re-test** before believing in a rename.
3. **Only then decide about renaming**, which buys new devices a working update
   channel at the cost of a new QR, a new provisioning payload, and orphaning
   every device provisioned as `app.drawbridge.dpc`. Today that is one Moto G15.

Worth noticing for its own sake: the earlier conclusion that Play Protect
objected to *drawbridge the app* was wrong in a way four experiments could not
show, because every one of them changed the app and none changed its name.

Worth researching alongside it: whether a Device Owner has any *supported* way
to exempt its own installs from the package verifier — `setGlobalSetting` and
`setSecureSetting` take only a short allowlist of keys, and whether anything
relevant is still on it needs checking rather than assuming. This would be a
real trade-off rather than a free win: it weakens verification device-wide on a
phone belonging to a child.

And after that the levers are outside the APK entirely: an appeal specific to
install-blocking, separate from the 2026-08-06 enrolment one, and **developer
verification**, which stops being a September deadline and becomes the plan.

Note this is unfolding **while the 2026-08-06 allowlist appeal is still live**.
Whether Play Protect's treatment of drawbridge is settled is unknown, so a
result today may not hold in a fortnight — in either direction.

**No release can reach a deployed phone unaided.** 0.2.1 through 0.2.4 are
published and the one real device installed 0.2.3 only because Play Protect was
switched off by hand. Weigh every change accordingly, and do not ship anything
that assumes this is solved.

### 0.2.5: drawbridge stops updating itself, and says why

**The update channel is now manual, by decision rather than by defeat.** Five
rounds established that Play Protect refuses drawbridge's own install and that
nothing in the APK changes it; the sixth option — renaming the package — was
rejected on the grounds that a new name would simply acquire the same verdict,
and that most of what a deployed phone needs is policy rather than code. Policy
updates are untouched and still arrive by themselves every three hours.

So `UpdateWorker` no longer installs drawbridge. It still installs **herald**,
which was never refused, because a phone with no browser is a different order of
problem. What replaced the self-install:

- `AppInstaller.availableSelfUpdate()` — a pure query against the signed policy,
  no side effects, used by both screens to decide whether to say anything.
- `AppInstaller.installSelfUpdate()` — the same install as before, started by a
  person.
- **[UpdateActivity](../dpc/src/main/java/app/drawbridge/dpc/ui/UpdateActivity.kt)**,
  which explains the one thing the parent has to do first: pause Play Protect.
  The wording is deliberately not defensive — drawbridge really can install apps,
  that really is what Play Protect exists to be wary of, and on most phones its
  suspicion would be right. It tells them to switch it back on afterwards.
- **`InstallOutcome`**, because `PackageInstaller` answers through a broadcast
  that arrives after the caller is gone. Without somewhere to put that answer the
  screen could only ever say "started", and a refusal and a success look
  identical from there. One slot, overwritten, read by the screen.

**The update is reachable from the lock screen**, which is the part worth not
undoing. A locked phone is the normal state, and unlocking to reach a
configuration screen discards the parent's key and mints a new one to write
down — a credential rotation as the price of a maintenance task is a good way to
guarantee the maintenance never happens. Nothing is given away: the APK is named
by the signed policy and pinned by checksum.

### The configuration screen lost its status block, and the lock screen gained a menu

- **"Device status" is gone** — ownership, filter state, policy version, the live
  restriction list. All of it is in Diagnostics, which is where someone
  troubleshooting looks, and none of it was something a parent acts on. Eleven
  string resources went with it, in three languages.
- **The protected-since date went with it too**, for a better reason than tidying:
  it is a fact about the *lock*, so it belongs on the screen that lock produces,
  which is where it already was.
- **"Check for policy updates" moved into the overflow** on the configuration
  screen, and — the part that matters — **was added to the lock screen's
  overflow**. That is the screen a managed phone spends its life on, so it is the
  only one most parents will ever see; leaving the manual check behind the key
  would mean the person most likely to need it has to spend their key to press it.

### 0.2.3: the reveal screen locked phones by accident, and a back door now exists

Three fixes, all found by using the phone rather than by reading the code, and
one of them is a deliberate compromise that must not survive development.

**The lock screen sealed the device before anyone had the key.** Reported from
the G15 on 2026-08-09: the parent reached the reveal, pressed home by accident
before writing the key down, and reopening drawbridge landed on the challenge —
a phone locked with a key that had existed only on a screen nobody had read. The
only way back was a factory reset.

The cause was deliberate and is worth understanding before touching it again.
`LockActivity` minted the key *on the way in*, and the comment explaining why was
sound: it meant the screen could never display a key different from the stored
one, and that backing out could not leave a device unlocked after the parent had
been told it was locked. Both true. The unconsidered case was the parent leaving
by any route that is not a button — home, recents, a phone call, the process
being killed.

Now the key is generated in memory and **committed only by an explicit act**:
`ParentKey.commit` is called from *Done* with the checkbox ticked, or from the
"close without keeping the key" dialog, and from nowhere else. Anything else
forgets the key and leaves the phone unsealed. The identity between what was
shown and what is stored is kept by passing the same string rather than by
writing it early.

Note what deliberately does **not** unwind: the restrictions and the filter are
applied by `MainActivity.lockDevice` before this screen opens, and they stay on
through an abandoned reveal. That leaves a filtered phone whose settings are
still reachable, which is recoverable — the alternative would be a phone that
un-filters itself because somebody took a call.

**A handwritten key could not be typed back.** The alphabet is Crockford base32
and has no O, I or L, precisely so a key cannot be misread — but `normalise` then
*dropped* any character outside the alphabet, so a reader who wrote a 0 and typed
an O got a key one character short and a flat "that is not the key". The decoder
now folds Crockford's aliases: O reads as 0, I and L read as 1. U stays dropped,
having no digit to be confused with. Both screens now say so on screen as well.

**And there is now a second unlock key, which is a back door.** Requested for
development on 2026-08-09, after the lock bug above stranded the reference
device. It is real and it is dangerous, so:

- It exists only when `emergencyKey` is set in the untracked
  `keystore.properties`, or `DRAWBRIDGE_EMERGENCY_KEY` in the environment. A
  build without it has no second key at all — the check compiles down to a
  comparison against an empty constant.
- **The APK carries only the SHA-256**, so taking the build apart does not yield
  the key. It is a full twenty-character key with the same hundred bits as any
  other, so the hash is not guessable either.
- **Diagnostics reports `emergency key: true`** when a build has one. A back door
  that a build will not admit to is worse than one it announces.
- It opens *any* device running such a build, including one locked long before
  by a different key — verified on the provisioned emulator, which was sealed by
  an older build and opened with it.

What that costs: it is the same key on every device, it never rotates, and it
survives every lock. **Do not ship a build carrying one to anybody else, and take
it out before the first real deployment.** The sanctioned answer to a lost key is
the delayed self-removal on the roadmap, not this. The key itself lives in
`keystore.properties` on the build machine, which is git-ignored and **not backed
up** — the same risk as the two signing keys, and it belongs in the same offline
copy.

### The emulator reproduces the Play Protect block, which nobody had noticed

**Found by accident on 2026-08-10**, while testing the new update screen. The
provisioned emulator refused drawbridge's own update with the *identical* dialog
the G15 shows — "Google Play Protect / Harmful app blocked / drawbridge / This
app can install potentially harmful apps without your permission / Got it" — and
`INSTALL_FAILED_VERIFICATION_FAILURE`, status 3.

This matters out of proportion to how it was found. Five rounds of build,
publish, sign, push, reboot were spent asking questions that a local rig can
answer in a minute, on the assumption — written into this file — that Play
Protect needs a Google account and the emulator therefore could not show it. The
emulator has no account. **Confirmed on hardware on 2026-08-10**: the G15 refused
an install with no account signed in at all. Play Protect does not need one.

**One confound, and it is worth ten minutes to remove:** the payload was a
release-signed APK replacing a debug-signed install, so a signature-related
refusal cannot be excluded from this single observation. Against that: the error
is a *verification* failure rather than a signature mismatch, and the dialog is
Play Protect's, quoting a permission drawbridge no longer declares — exactly as
on the phone.

**Do this before any further Play Protect work:** build two debug-signed DPCs a
version apart, serve the newer one to the emulator, and see whether the block
still happens. If it does, every remaining question — whether the verdict
follows the package name, whether an unknown package is merely unscanned,
whether a re-test a week later behaves differently — becomes a local experiment
instead of a release cycle.

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

### FRP does not protect this phone, and never did

**Tested on 2026-08-10. The G15 was factory reset and setup never asked for the
Google account.** So the reasoning that removed `DISALLOW_FACTORY_RESET` — that
a child who knows the screen lock can wipe the phone, but Factory Reset
Protection makes the result worthless to them — rests on a backstop that was not
there.

**It is not a fault in the handset, and it is not the trusted-versus-untrusted
wipe distinction this file assumed.** On a *fully managed* device FRP is simply
not on by default, and a reset from Settings does not trigger it whatever
accounts are present. Google provides the switch instead:
`DevicePolicyManager.setFactoryResetProtectionPolicy()`, API 30, which names the
Google accounts allowed to reactivate a wiped device — **enterprise** FRP, EFRP.
`grep FactoryResetProtection dpc/src` returns nothing. drawbridge has never
called it, so this device had no factory reset protection of any kind.

Two related traps, both from the same reading:

- **OEM unlocking in Developer Options disables FRP** on Android 14 and below.
  From Android 15 that stops being true for managed devices, where EFRP is
  enforced regardless — but the G15 is Android 15 and had no EFRP configured, so
  it made no difference here.
- **EFRP has to be configured before the wipe.** It cannot be applied to a phone
  that has already been reset, and it cannot rescue this one.

**What this changes, and what the owner decided on 2026-08-10.**

**EFRP will not be armed.** `setFactoryResetProtectionPolicy` is the documented
fix and it is deliberately not being used: it would put a Google account
identifier at the centre of the recovery story, and the project's constraint is a
phone that needs no account and no backend. Do not "fix" this by adding it
without asking.

**`DISALLOW_FACTORY_RESET` goes back instead — but later**, once the rest of the
system is dependable, exactly as [next steps](#reasonable-next-steps) item 9
describes. Its removal was justified almost entirely by FRP covering the gap, and
that justification is gone; what has not changed is that reinstating it while
things are still breaking means a mistake costs a handset rather than ten
minutes.

**So until then, a factory reset is the escape hatch, and it is unprotected.** A
child who knows the screen lock can wipe the phone and end up with a clean one.
That is a known, accepted gap with a date on it, not an oversight.

What stands in for prevention meanwhile is **detection**, and as of 0.2.5
drawbridge writes that message itself. Left alone, Android's keyguard says *"This
device belongs to your organization"* — verified on the emulator, and wrong on a
child's handset in a way that matters: a repair shop, a school or the child reads
corporate IT rather than a parent's decision.

`DeviceOwnerManager.updateLockScreenInfo` now sets it through
`setDeviceOwnerLockScreenInfo`, in three states:

| State | Keyguard |
|---|---|
| Never locked | nothing set; Android's default returns |
| Locked | "Drawbridge is guarding this device and its owner - Locked since ‹date›" |
| Unlocked, but locked before | the same without the date |

The middle state is the tamper indicator: a wiped phone stops naming a date the
parent recognises, which is a better thing to miss than a generic notice nobody
read. The third exists because unlocking removes the key and **not** the
restrictions or the filter — the phone really is still guarded, so it still says
so, and only the claim about the lock comes off.

Verified on the emulator on 2026-08-10: the string appears on the keyguard and
*replaces* the organization disclosure rather than sitting beside it. Two things
that cost time and are worth knowing:

- **It is not in `dumpsys device_policy`, nor in `settings get secure
  device_owner_info`** — both come back empty on API 36. Read it back through
  `getDeviceOwnerLockScreenInfo`, which Diagnostics now prints as
  `lock screen says:`.
- **The bouncer is not the lock screen.** The PIN-entry screen still shows the
  organization disclosure; ours is on the keyguard proper. Do not conclude from a
  screenshot of the bouncer that it failed.

**Verified on the G15 on 2026-08-10, and it works — but it does not stand out.**

It was first reported missing on a phone that had been provisioned and not yet
locked, which is the designed behaviour rather than a fault:
`updateLockScreenInfo` deliberately writes **nothing** in the never-locked state.
After locking, the line appears.

**What the emulator got wrong is the prominence.** On the emulator the string
*replaced* the organization disclosure and was the only such line. On the Moto
G15 it is, in the owner's words, "one of the many things it says" — the keyguard
already carries several strings and drawbridge's is merely one more. That is OEM
territory, and it weakens the tamper check this line exists for: a parent who has
to pick their date out of a crowded keyguard is less likely to notice the day it
stops being there. Worth knowing before leaning on it, and worth re-checking on
any new handset rather than assuming the emulator's rendering.

If it is ever blank after a lock, read it back through Diagnostics'
`lock screen says:` — the value does *not* appear in `dumpsys device_policy` or
`settings get secure device_owner_info`, both of which come back empty on API 36 —
and remember the bouncer is not the keyguard, which cost time once already. And note it only
works as a check if the parent knows what the phone is supposed to say, which
belongs in [provisioning](provisioning.md) rather than only in the code.

`setOrganizationName`, `setShortSupportMessage` and `setLongSupportMessage`
remain unused and are the obvious next places to say something true.

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
  being the only one on the device — and `lockAccounts()` was dead code, so
  nothing stops a child adding their own. See the unverified list.
- **The protected-since date**, which is why the clock is now locked on every
  device: winding it back a year, locking, and winding it forward forges a year
  of protection that never happened.

Putting the restriction back is on the roadmap, but **only behind a working
delayed self-removal** — the two ship together or not at all.

### The browser installer exists, and the USB half is untested

**Built 2026-08-10.** `/install/usb/` provisions a phone from Chrome or Edge over
WebUSB — the same sequence as `tools/provision-adb.sh`, with the same promise
about `verifier_verify_adb_installs` being restored on every exit path.

Two decisions worth not re-litigating:

- **The APK is served from the site**, at `/assets/dpc-release.apk`, and is
  committed. GitHub's release downloads carry **no `Access-Control-Allow-Origin`
  header** — measured through the redirect to `release-assets.githubusercontent.com`
  — so a browser `fetch` of them is blocked outright. Hosting it here is also the
  only version that keeps the page's no-third-party-requests property.
  `.gitignore` only excludes `dist/release/*.apk`, and that rule is about
  herald's 230 MB rather than about APKs in principle.
- **The DPC only.** herald is 233 MB down a USB cable; drawbridge fetches it
  itself from `required_apps` after locking, exactly as the QR path does.

`build-site.py` now **fails the build** if the staged APK does not hash to the
`app_update` pin in the signed policy. Those are the same claim in two places —
the policy tells provisioned devices which build is current, this page hands that
build to a phone that has none — and a build where they disagree is one where
something is lying. Verified by staging the unpublished 0.2.6 and watching the
build refuse it.

**This page is the only JavaScript on the site**, which is a real cost against the
posture in the section below. WebUSB cannot be done without it. Everything it
loads is same-origin, so "nothing leaves the device" survives; "no client-side
JS" does not.

`site-src/installer/adb.js` is a small ADB client written out rather than
vendored, because a bundler and an npm tree would cost the no-build-step
property that makes Cloudflare Pages work here at all.

**What is verified, and what is not.** The protocol layer was cross-checked
against ground truth on the build machine: `encodeAndroidPublicKey` reproduces
the *byte-identical* base64 that adb itself wrote in `~/.android/adbkey.pub` for
the same private key, which is the part — modulus word order, `n0inv`, `rr` — that
fails silently on a device. The token signature verifies as a well-formed PKCS#1
v1.5 block carrying the SHA-1 DigestInfo prefix, and packet framing round-trips.
In a real browser: key generation, persistence, and the same-origin APK fetch
verifying against its pin.

**The commands it runs are verified on the G15**, over ordinary adb, on
2026-08-10 — the point being that the *service semantics* can be checked without
the browser even though the transport cannot:

- Every preflight command was run and its real output fed back through the
  page's parsing: `Accounts: 0`, an absent `Device Owner:`, `getprop`, and an
  unset verifier reading as the string `null`.
- **`cmd package install -S <size>` is governed by
  `verifier_verify_adb_installs`**, which had been assumed and is now measured.
  The APK was streamed to it at the default setting and refused with
  `INSTALL_FAILED_VERIFICATION_FAILURE`; with the setting at 0 the identical
  stream returned `Success`. That matters because it is a *different code path*
  from `adb install`, and the whole page depends on the lever reaching it.

**The transport works, first run, from the deployed site — 2026-08-10.** Chrome
against a real phone: device selection, `claimInterface`, the CNXN handshake,
**AUTH**, a `shell:` stream, command execution and output parsing all succeeded,
and preflight then stopped the run because it found a Google account on the
phone. That last part is the guard doing its job rather than a failure.

The half worth calling out is AUTH. The phone accepted a key it had never seen,
which means the `RSAPublicKey` blob is right on hardware and not merely
byte-identical to what adb wrote locally — the two could have agreed and both
been unusable.

**And then the whole path ran, on 2026-08-10.** The G15 was factory reset with
sign-in skipped, and provisioned from the deployed site end to end: drawbridge
installed over WebUSB, Device Owner granted, and after the parent pressed *Lock*
the app blocker removed Facebook and the rest, developer options closed, and the
keyguard carried drawbridge's line.

So every layer is now exercised on hardware — transport, `exec:cmd package
install -S`, `dpm set-device-owner` — and a phone can be provisioned from a
website with no software installed on the computer and no factory-reset
requirement beyond whatever the owner wants.

Worth keeping in view: **this is one run, on one handset, by the person who wrote
it.** Nothing here has been through an unfamiliar phone, an unfamiliar OEM's USB
stack, or somebody who does not already know what the page is supposed to do.

**One trap, hit immediately, and it will hit everyone.** WebUSB fails with
`Unable to claim interface` while adb holds the phone — and `adb kill-server`
alone does **not** fix it, because a running emulator re-registers with the
server and respawns it within seconds. Close the emulators first. The installer
now says so instead of surfacing the raw DOMException.

### There is a website now, and it is generated

Trilingual (EN/NL/FR), static, on Cloudflare Pages, deploying from `main` on
every push. No framework, no client-side JS, no webfonts, no third-party
requests — the same "nothing leaves the device" posture as the app.

**The install page was reordered on 2026-08-10** to match what actually works.
USB now leads and the QR follows, under the heading *"On a phone without Google
services"*, with a loud callout saying not to scan it on a phone with Google Play
— a parent who does gets the handset wiped, which is a bad way to learn this. The
alpha notice now says the true thing rather than the old one: USB provisioning
*is* confirmed on real hardware, the QR is not. The homepage's "how it works"
paragraph promised a QR scan "or a button on this website"; the button still does
not exist, so it now describes the cable.

Checked at 375px in all three languages, no horizontal overflow, and the QR
warning renders in the same register as the alpha one because it is the same
class. No CSS changed — every class the new layout uses was already there.

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
| Emergency unlock key | `emergencyKey` in `keystore.properties` | Development only. Losing it costs nothing a factory reset cannot fix; **leaking it unlocks every device running a build that carries it**. Remove before real deployment. |
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

- ~~**`lockAccounts()` is dead code, and a doc promises otherwise.**~~ **Fixed
  2026-08-10.** `DISALLOW_MODIFY_ACCOUNTS` had never been applied on any device:
  the function existed, `unlockAccounts()` ran during removal, and nothing ever
  called `lockAccounts()` — while all three install guides told parents that
  account changes close at lock. Confirmed on hardware on 2026-08-08, when an
  account was added to a *locked* G15 and nothing objected.

  It is now the second restriction keyed on the **lock** rather than on
  `protectedSince`, alongside USB debugging, and `lockAccounts()` is gone —
  applying it is `restrictionsFor`'s job. The pre-lock window is when the parent
  chooses what account the phone carries, and unlocking is how they change their
  mind; keying it on protection would have sealed that choice at the first lock
  and put it out of reach without a factory reset.

  **Not yet seen on hardware.** The unit tests cover the rule, but no phone has
  been watched refusing an account after a lock, or accepting one after an
  unlock. That is the check to run on the next provision.

  Two consequences to know rather than discover. It blocks *removing* accounts as
  well as adding them, so an app that signs in through `AccountManager` cannot be
  set up on a locked phone — the parent unlocks for that, which costs them the
  key. And it makes the documented order mandatory rather than advisory: any
  account the phone is to carry goes on before the lock.

- ~~**Whether FRP behaves as assumed at all.**~~ **Tested on 2026-08-10, and it
  does not.** See the section below: the phone was factory reset and never asked
  for the Google account. The backstop that `DISALLOW_FACTORY_RESET` was removed
  in favour of does not exist and never did.

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

### 0. ~~Provision a non-Googled handset by QR~~ — retired

**Decided by the owner on 2026-08-10: the QR path is removed altogether.** It had
been the standing focus item, on the reasoning that LineageOS, /e/OS and
GrapheneOS have no Play Protect and would therefore take a QR provision exactly
as written. Nobody ever ran it.

The reasoning that retired it: the QR path was already dead on every
Google-certified handset, so the only audience left was people running
open-source Android — and those people will have no difficulty with a USB
install. Keeping a second provisioning route alive for them cost a documented
flow in three languages, a payload generator, a printed code, and a release asset,
against an audience that did not need it.

So `tools/qrpayload.py`, `dist/release/provisioning-qr.json`, both QR images and
every QR section in the docs and on the website are gone. The website's install
page is now one method.

**What was deliberately *not* removed**, and should stay: the DPC's
`GET_PROVISIONING_MODE` and `ADMIN_POLICY_COMPLIANCE` activities. They are inert
on a phone nobody provisions by QR, they cost nothing, and taking them out would
re-create the exact fault that silently broke QR provisioning for the life of the
project up to 2026-08-07. If the path ever comes back, it comes back working.

### 1. Get drawbridge able to update itself again

**No longer blocking provisioning** — `tools/provision-adb.sh` gets a certified
handset provisioned today, and that is what the top of this file is about. What
is still broken is delivery to a phone that is already locked: self-update is a
`PackageInstaller` session rather than an adb install, so the verifier global
does not touch it, and `DISALLOW_DEBUGGING_FEATURES` has taken the cable away by
then anyway.

A phone that cannot receive a fix is a phone where every bug found from here on is
permanent. The decision that has appeared alongside this, and which nobody has
made yet: **whether `DISALLOW_DEBUGGING_FEATURES` should still be applied at
lock.** It closes the adb removal route, which is why it is there. It also closes
the only delivery channel that currently works. Both halves are true and the
trade is now a real one.

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

~~**Fix `lockAccounts()` at the same time.**~~ **Done on 2026-08-10**, and not
via `lockDevice()`: `DISALLOW_MODIFY_ACCOUNTS` is now in `MANAGED_RESTRICTIONS`
and withheld while the phone is unlocked, so it lands from
`LockActivity.sealWithKey` after the key is committed and lifts again on unlock.
`lockAccounts()` is deleted. See the unverified list above for what still has to
be watched on a device.

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
- **Keep both keys backed up.** Every published release now depends on it.
- **Drop unused ABIs.** `armeabi-v7a` and `x86_64` have never been downloaded by
  anything and cost ~650 MiB of every release. Removing an ABI means removing
  its `required_apps` entry in the same policy.
- **Build the WebADB installer.** The `/install/` page still has a disabled
  "Install over USB" button. Less urgent now that QR provisioning works, and
  still the only path that needs no cable and no allowlist.
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
