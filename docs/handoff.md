# Handoff — state as of 2026-08-17

Everything about *how the system works* lives in [README](../README.md),
[design-decisions](design-decisions.md), [policy](policy.md),
[provisioning](provisioning.md) and [removal](removal.md). This file covers only
what those do not: where the project actually stands, what is on the build
machine, what was and was not verified, and what to do next.

---

## Removal starts at installation now — 2026-08-17, unreleased

**The owner's call, and the reasoning is about who drawbridge is for.** Not
everybody is going to lock. A phone that filters the web and drops social media,
undoable only by a factory reset, is already most of the value — and the old
design gave that person nothing until they pressed a button they may never press.

So `AppBlocker.evaluate`'s pre-lock gate is gone. What acts from installation:
the **blocklist**, **browsers the policy never sanctioned**, the **store rule**,
and **allowlist mode**. What still waits for the lock is everything a switch on
the configuration screen governs — WhatsApp, Telegram, YouTube, streaming, a
browser the *chooser* narrowed away — because the parent has not answered those
questions yet. `actsNow` already expressed exactly that split; the gate in front
of it was the only thing making a fresh phone inert.

**One rule had to be hardened first, and this is the part to remember.** The
browser rule removes what is *not* named, and `Policy(version = 0)` — what
`PolicyManager` holds before it reads anything — names herald alone. A sweep
racing the load would have answered *"this phone allows no browser but herald"*
and taken Chrome, Firefox and Vivaldi with it. Survivable while nothing was
removed before the first lock, since by then the document has been read many
times over; not survivable when the first sweep runs minutes after installation.

**And the sweep waits for a document rather than skipping past one**, which is
the owner's question and the better answer. `PackageWatcher` sweeps everything
once on start and thereafter asks only which packages *changed* — so anything
declined for want of a policy has not changed, is never looked at again, and sits
on the phone until the process restarts. Waiting costs milliseconds once
(`ensureLoaded` reads the disk copy, or the one bundled in the APK) and nothing
afterwards. `StoreScanWorker` already did this; the sweep and the install
broadcast were the odd ones out, and `sweepOnLock` now matches for consistency.

`AppBlocker.browserRuleApplies` stays as the last resort, for the case where even
that yields nothing readable — `ensureLoaded` logs *"No usable policy"* and
carries on with version 0 rather than failing. Pure and tested. Every other
branch fails safe on an empty document.

**The consent moved with the behaviour.** Apps now start disappearing before
anybody has agreed to anything on the phone, so the warning had to move ahead of
the cable rather than sit beside the Lock button. Rewritten in all three
languages, in four places: the website's install page, the USB installer page,
the FAQ's *how it works*, and `docs/install*.md`. Each one now says the same
thing — **move what you want to keep off the phone first**, because whatever
lived only inside a blocked app goes with it — and that what a switch can still
bring back waits for the lock. `lock_confirm_message` in the app changed for the
same reason: it used to promise removals that have already happened by then.

`AppBlockerLockGateTest` was rewritten around the new rule and now loads the
bundled policy rather than mocking one, so *"a blocked app goes before the phone
has ever been locked"* is pinned against the document the app actually ships.

### The lock screen did not notice being unlocked

**Reported from the Moto in the same session, and it is the first thing a real
timer found.** The two-hour unlock worked — the keyguard said *drawbridge
protecting* — but drawbridge itself, still open on the lock screen, went on
asking for a key that no longer existed until it was killed and reopened.

`MainActivity.onResume` has always forwarded to the lock screen when the phone is
locked. The way back was never written, because until the timer there was no way
for a phone to unlock without somebody standing in front of that screen doing it.
`LockActivity.onResume` now asks the controller first — idempotent, and it also
means a phone whose alarm was lost unlocks the moment somebody opens the app —
and leaves for the configuration screen if the lock is gone. A second check is
posted at the deadline itself while the screen is visible, so a phone being
watched at the moment it expires changes in place rather than looking stuck.

---

## The preloaded game the store rule never asked about — 2026-08-17, unreleased

**Reported from the Moto: a game called Amaze GO! survived a locked phone.** It is
`com.oakever.arrows`, and Play files it under `GAME_PUZZLE` — a category this
policy rejects outright. The rule had the right answer and was never asked the
question.

**Why:** it is *preloaded*, and preinstalled packages were exempt from the store
rule. Three separate gates enforced that, one line each — `storeReason`,
`packagesWantingStoreAnswer` and `ensureStoreAnswer` — with a documented reason
that reads well and proved too much: *hiding the OEM's dialer because Play has no
listing for it would leave an unusable phone*. True of the dialer, and the wrong
conclusion for a preloaded game, because on a handset whose junk arrives from the
factory the exemption covers exactly the apps somebody installs drawbridge to be
rid of.

**The fix is the narrower exemption: can a person open it.** Every user-installed
app is in reach, and a preinstalled one only if it has a launcher entry.
`AppBlocker.withinStoreReach(isPreinstalled, hasLauncherEntry)` is pure and
tested, like `actsNow` and `deferred`. Two rails were already there and are what
make it safe: an app Play has never heard of is `UNVERIFIED`, which is *keep*, so
the rule fails open on exactly the case the old comment worried about; and
`isProtected` still runs first, so the launcher, keyboard, Settings and Play are
not candidates at all. The icon test is about **scale** rather than safety — the
~294 packages on a handset are mostly services nobody can open, and asking Play
about each would multiply the scan to learn nothing.

**One subtlety that is easy to get wrong**: the launcher query uses
`MATCH_DISABLED_COMPONENTS or MATCH_UNINSTALLED_PACKAGES`, because a *hidden*
package answers no ordinary intent query. Without those flags the rule would
report "no icon" for precisely the apps it had just removed, flip to *keep* on the
next sweep, and drop them from Diagnostics' `still usable` line.

### Measured before shipping, which is the part worth keeping

`app-ratings.py audit` over a corpus of typical Moto and Google preloads:
**19 keep, 4 neutral, 1 remove, 5 unverified.**

- Every essential preload survives on its own PEGI 3 rating — Gmail, Photos,
  Maps, Drive, Messages, Phone, Contacts, Clock, Calculator, Moto Camera, FM
  Radio. The worry that widening the rule would hide Gmail is measured and
  unfounded.
- The five unverified are OEM services with no Play listing — `com.motorola.launcher3`,
  `com.motorola.notification`, `com.facebook.system` and friends — and they fail
  open, as designed.
- The four *Parental guidance* ones are decided elsewhere: Facebook and Google TV
  by the blocklist and the streaming option, YouTube Music by *Allow YouTube*.
  **Google Play Games is governed by nothing**, and is a decision waiting to be
  made: a games hub on a phone whose whole point is not having games.
- The one removal is Amaze GO!.

**Expect `store to scan` in Diagnostics to jump** the first time a phone runs
this: every launcher-visible preload is a new question, ~1.2 MB each, on Wi-Fi.

Code and tests are done; **not released**. It needs a build, so it should ride
with the next one rather than interrupt the timer that is running on the Moto.

---

## The lock timer — 2026-08-17, drawbridge 0.2.9 build 34, policy 69

**A lock can now end by itself.** This is the last feature asked for before the
alpha, and the thing the website has been describing since it was written. Two
doors, one mechanism:

| | |
|---|---|
| **A timer chosen before locking** | a switch and a period under the *Lock drawbridge* button: 2h, 4h, 8h, 12h, 1/2/3 days, 1/2/3 weeks, 30 days, 40 days. Off by default |
| **`Forgot the code`** | new item in the *locked* screen's overflow menu. Starts a **thirty-day** wait, from a phone nobody can open. Not configurable |
| **The key is untouched** | still minted, still shown once, still opens the phone on demand — and using it cancels the countdown |
| **What "unlock" means** | the key is dropped and the settings reopen. The filter, the VPN and the restrictions are keyed on `protectedSince` and stay. Removal is then available, because removal lives behind the lock |

**Where the state is:** `LockTimer`, four values in
`drawbridge_lock_timer.xml` — the draft (`enabled`, `length`) and the armed
deadline (`armed_at`, `expires_at`, plus the length and the reason for the
screens). All of it is printed in Diagnostics. Deliberately trivial: this is the
escape hatch for a lost key, it runs inside drawbridge, and a state file nobody
can read is a hatch nobody can trust.

**What drives it:** the alarm is primary (`LockTimerController`, inexact, no new
permission), and it is not trusted alone — every process start, every boot, and
`LockTimerWorker` hourly all call the same idempotent `apply()`. The failure that
matters is the timer *not* firing, which is a phone locked forever with a key that
may not exist.

**Two safety properties, both deliberate:**

- **The clock is pinned while a deadline is armed** — `DISALLOW_CONFIG_DATE_TIME`
  plus network time, decided in `CurfewController.apply` so that one place writes
  it. Without this, winding the phone forward forty days ends a forty-day lock
  this afternoon.
- **Ambiguity fails locked** — `LockTimer.hasExpired` refuses to fire on a
  half-written deadline, a deadline at or before its own arming, or a clock now
  reading earlier than the arming. Nine unit tests, because the alternative is
  checking a fortnight-long rule by holding a handset.

**Visibility, which is what makes the code-forgotten door survivable:** the
keyguard counts down — *"drawbridge unlocks in 3 days"* — and drawbridge's own
locked screen says it with a date, coloured as an error and naming the reason
when somebody on the phone declared the code lost rather than a parent choosing a
period. There is **no ongoing notification**, on purpose:
`POST_NOTIFICATIONS` is declared but nothing asks for it or grants it, so its
visibility is not something this build can promise, and the keyguard is the
surface parents are already told to check.

### What is verified

`./gradlew test lint` passes: 151 dpc unit tests, nine of them new, lint clean on
both modules.

**The whole flow was then driven on the API 36 emulator**, which was already
provisioned as Device Owner, and every step below was watched rather than
reasoned about:

- the switch and the twelve-entry picker under the Lock button, and the picker
  appearing and disappearing with the switch;
- the lock confirmation growing its extra sentence when a timer is set, and not
  growing it when there is none;
- the reveal screen saying *"unlocks itself 2 hours after you tap Done"*, and
  saying nothing when the switch is off;
- sealing writing `expires_at` exactly two hours past `armed_at`, and
  `dumpsys alarm` carrying an `RTC_WAKEUP` for
  `app.drawbridge.dpc/.security.LockTimerReceiver` at that instant;
- the locked screen line, *"Unlocks by itself on August 17, 2026 at 6:33 PM"*;
- `Forgot the code` in both states — arming thirty days as `FORGOTTEN` with the
  draft untouched, and reporting the running deadline when one already exists —
  and its line rendering in the error colour;
- **the key cancelling the timer**: the armed fields cleared, the draft kept, and
  `dumpsys alarm` recording `Reason=alarm_cancelled`;
- **and the unlock itself firing**, with a past deadline written into the
  preference file:

```
LockTimerController: Lock timer expired (FORGOTTEN); unlocking
DeviceOwnerManager:  Network lockdown off
```

  after which `key_hash` is gone, the timer state is back to the draft alone, no
  alarm is pending, and the app opens on the configuration screen instead of the
  lock.

**The keyguard was read back too**, through Diagnostics' `lock screen says:`
line — the value is not in `dumpsys device_policy`, as the older section on this
message already warns. All three states, on the device:

```
lock screen says:  drawbridge protecting
lock screen says:  drawbridge locked since August 17, 2026 at 5:33 PM
lock screen says:  drawbridge unlocks in 2 hours
```

**What the emulator could not show**: Settings refusing the clock, whether
drawbridge's line is one of many on a real OEM keyguard (on the Moto it is —
see the older keyguard section), and anything about the alarm surviving hours of
Doze. Those need the Moto.

### The keyguard message got shorter with it

On the owner's call, and it is a change to all three states rather than a new
sentence: the line used to open with *"Drawbridge is guarding this device and its
owner"* every single time, which spent a truncated keyguard line before reaching
anything that varies. It now says `drawbridge protecting`, `drawbridge locked
since …`, or `drawbridge unlocks in …` — one fact each.

The timer state says a **duration, not a date**, because a date on a keyguard has
to be compared against today before it means anything. The cost is that a stored
string goes stale — the platform does not re-resolve it — so
`LockTimerController.apply` rewrites it on every run, which is process start,
boot, and hourly. That is the second job that makes an hourly worker for a
day-scale countdown reasonable rather than wasteful. The lock date has not gone
anywhere: it is still on drawbridge's own screen, where somebody comparing it
against a memory is already looking.

### Released as build 34

`dpc` only. herald did not move, so its APKs and pins are untouched and no GitHub
release is involved — the phone takes this one from the dev channel's own site,
as builds 28–33 did.

- `versionCode` 34, `versionName` 0.2.9.
- Policy **69** re-pins `app_update` to `dpc-ba312d4df61967f5.apk` and changes
  nothing else. The `comment` field says so.
- `site/assets/` carries the new APK and the USB installer page is re-pinned to it.
- **R8 was checked rather than assumed**, since the emulator ran a debug build:
  `LockTimerReceiver` and `LockTimerWorker` keep their fully-qualified names in
  the release mapping (the manifest and WorkManager's consumer rules see to it),
  and the `Length`/`Reason` enum constants survive in the DEX — which is what
  keeps a running timer's stored state readable across an upgrade.
- **The policy was signed with `--skip-url-check`**, and that is worth knowing
  rather than hiding: `raw.githubusercontent.com` was answering 429 to this
  machine for the whole session, including for `StevenBlack/hosts` and
  `dibdot/DoH-IP-blocklists`. Checked individually, the 429 body is GitHub's
  rate-limit page rather than a 404. No blocklist URL changed between 68 and 69,
  and 68 was signed a few hours earlier with the check passing.

  **The check was then run separately, serially, once the limit cleared: all
  eighteen URLs resolve.** Two things came out of doing it by hand. The batch
  check is *itself* what trips the limiter — eighteen near-simultaneous requests,
  and a different subset is flagged on each run, which is the signature of rate
  limiting rather than a dead list. Four seconds between requests and everything
  answers 200.

### The URL check cannot see a wrong `app_update`, and that is new

Found while confirming the above, and it is a gap in the release tooling rather
than in this release. `check_urls_resolve` judges by status code, and the APK
URLs now live on **Cloudflare Pages, which answers a missing asset with 200 and
an HTML page** rather than a 404:

```
dpc-ba312d4df61967f5.apk   200  text/html                              8410 bytes
dpc-9be19a55f51e8727.apk   200  application/vnd.android.package-archive  3.39 MB
```

The first is this release's APK before the push — nothing is deployed yet, and it
still "passes". So a typo in `app_update`, or a policy signed and pushed before
the site deploys, sails through the one check meant to catch exactly that. The
project's own note about names drifting assumes a 404, and on Pages there is
none.

**No device is at risk from it**, which is why this is a tooling item rather than
an incident: `AppInstaller` pins the checksum, so a phone that downloads 8 KB of
HTML fails the hash and installs nothing. The cost is that the failure surfaces
on a handset instead of at signing time.

Worth fixing in `policytool.py` by checking the content type — or the first bytes
— for anything that is supposed to be an APK, rather than only the status.

### What to do on a handset

1. **Lock with a two-hour timer** and read the keyguard from the phone's own lock
   screen — the one surface the emulator could not confirm, and the one the
   code-forgotten door depends on. It should carry both dates.
2. **Confirm the clock cannot be moved** while it runs — Settings should refuse
   the date and time. That is the mechanism, so it failing is the feature failing
   quietly.
3. **Let one run out for real**, rather than forcing it. Two hours of Doze on an
   OEM build is the part no test covers, and `setAndAllowWhileIdle` being deferred
   or dropped is the likeliest way this disappoints somebody.
4. **If you do want to force it**, note what the emulator proved: on a
   Device-Owner phone `am force-stop app.drawbridge.dpc` is **refused** —
   *"Ignoring request to force stop protected package"* — so the running process
   keeps its cached preferences and a hand-edited deadline changes nothing. Write
   the past `expires_at` with `run-as`, then **reboot**; `BootReceiver` re-reads
   from disk and fires. (On the emulator the first cold boot after that was slow
   enough to ANR drawbridge before it reached the check, and the unlock happened
   at the next process start. Worth knowing before reading a slow phone as a
   broken timer.)

### What was deliberately not done

- **`DISALLOW_FACTORY_RESET` is unchanged.** The timer is the prerequisite
  [step 9](#9-lock-factory-reset--last-and-only-with-the-timer) was waiting for,
  and the objection in it still stands: this escape runs inside drawbridge, so a
  crash loop takes it with it. Still the owner's call, still after everything else.
- **The clock is still not pinned on every locked phone**, only for a curfew or a
  running timer. `design-decisions` claimed otherwise and has been corrected —
  making the wider claim true is a behaviour change for every non-curfew device,
  so it is written down rather than made.
- **The website copy changed with the code, not after it.** The FAQ now says
  2 hours to 40 days, and the code-forgotten wait is 30 days rather than 40 —
  the owner's decision. `site/` is regenerated in the same commit and still needs
  deploying.

---

## Handover — 2026-08-17, end of the store-rule work

**Where it stands: drawbridge build 33, policy 67, and build 33 is confirmed
working on the Moto.** herald did not move all day and its `required_apps` pins
are untouched. Read this section first; everything below it is the day's detail.

### What was built today

Apps are now admitted by the Play Store's own rating and category rather than
only by a hand-written list of names — the fix upstream of the blocklist that
policy 59 argued for. The design, every measurement behind it, and the parts
still unbuilt are in [app-ratings](app-ratings.md). In brief:

| | |
|---|---|
| **PEGI 3 passes**, everything else does not | *Parental guidance* included, which is the expensive half and was measured before it was chosen |
| **Games and dating go by category** | the rating cannot see addictive design: Candy Crush is PEGI 3, Minecraft is PEGI 7 |
| **A 32-package whitelist pays for it** | in the signed policy, because every private messenger is *Parental guidance* |
| **Chatbots are not blocked** | drawbridge cannot suppress the assistant inside the search engines it already allows |
| **`dating.txt` and 57 web-game domains** | the browser side, which the app rules cannot reach |

### The three bugs the Moto found, in order

All three were **precedence between rules**, not the rules themselves, and all
three were invisible to this project's pure-function tests because every
individual rule was returning the right answer the whole time. That is the
lesson worth carrying: `AppBlocker` now has six rules, and the interactions are
where the bugs live.

1. **Build 30** — `DISALLOW_INSTALL_APPS` blocked Play Store *updates*, not just
   new installs. Retired rather than dropped, so phones already carrying it get
   it cleared.
2. **Build 32** — the install lock was *too weak*. `isProtected` ran first, so
   whitelisted apps (Claude, DeepSeek, Session) and option-allowed apps
   (Telegram) bypassed the closed set entirely.
3. **Build 33** — the install lock was *too strong*. Deferral was asked about the
   **package**, and a newly installed package is outside the closed set by
   definition, so every arrival waited for the lock whatever else was wrong with
   it. TikTok, Firefox and Temu all survived on an unlocked phone. **Instagram is
   what exposed it, by working** — it was in the snapshot, so nothing deferred
   it. Deferral now travels with the *reason*.

### What has still never been watched working

**The install lock's closed set has never been seen removing an app it was right
about.** Build 30's sweeps logged zero because layer 1 was preventing anything
from reaching it; builds 32 and 33 were about the same mechanism failing in two
directions. With the lock on and the phone locked, install anything and watch:

```
adb logcat | grep "not among the apps"
```

**The store rule's first full scan.** `store to scan` in Diagnostics should fall
to 0 once the phone is on Wi-Fi. A number that never falls is a scan waiting for
a network it is not getting — a phone enforcing nothing that looks exactly like
one that has finished. `store unverified` staying high means it cannot reach
`play.google.com`.

**herald coming back**, still, from before any of this: *no browser*, lock,
unlock, *the allowed browsers*, lock again.

### The pre-flight that was never run

Build 31 turned the store rule on and the check the spec asks for has still not
happened, because the Moto was not connected when any build was cut:

```bash
adb shell pm list packages -3 | sed 's/package://' > /tmp/phone.txt
python3 tools/app-ratings.py audit --corpus /tmp/phone.txt --expect keep
```

It prints what the rule would remove from that handset, offline. The whitelist
has grown four times today by the other route — somebody noticing an app is
gone — and this finds the rest in one pass. Anything it turns up is a policy edit
and a re-sign, no build.

### Rolling back costs no build

Re-sign the policy with `app_ratings` removed and every phone stops enforcing the
store rule at its next poll. That is why the rule lives in the document rather
than the APK, and it is worth remembering before anything more drastic.

### The tooling is the durable part

`tools/app-ratings.py` — `check`, `audit`, `search` — produced every number in
`app-ratings.md` and **corrected the spec four times**, including one case where
a wrong denominator produced a confident three-paragraph explanation of a gap
that did not exist. Use it before believing anything about a package.

`search` is the half that matters most and was the last to be understood: a
rating answers *tell me about com.x.y*, and the actual problem is *what is the
newest companion app called*. Two query terms surfaced 35 unlisted candidates in
seconds.

### The judgement calls left in the files, so nobody re-litigates them blind

- **Four domains held back** from the games list — `vrt.be`, `bbc.co.uk`,
  `archive.org`, `ouders.ketnet.be` — commented out with the reasoning, and the
  hold **confirmed by the owner**. DNS cannot see a path, so blocking these
  blocks a broadcaster, a library and a page written for parents.
- **Chess is not blocked.** `chess.com` and `lichess.org` were on the list and
  came off it the same day. The case for blocking them was the engagement
  machinery — rating ladders, streaks, blitz timers — and the case against is
  that chess is not what the list is for. `lichess.org` came off alongside
  rather than being asked about: it is the free, ad-free, open-source one, so
  blocking it while allowing the commercial one would have been backwards.
- **Educational games are blocked**, and schools use several of them. Recorded in
  the list rather than left to be discovered — this is the judgement call most
  likely to surface next, since a child told to do homework on `topmarks.co.uk`
  will find it blocked.
- **YouTube Music is not whitelisted** although it is *Parental guidance*, because
  *Allow YouTube* governs it. `policytool.py sign` refuses the document if anyone
  tries — verified by constructing the mistake.

---

## Start here: the YouTube app the phone will not hide

**Diagnosed, fixed, released as build 28, and still unconfirmed on the handset
that found it.** Everything below happened on an emulator and in tests; the Moto
has not seen any of it. See *What to do on the Moto* at the end of this section.

**What is certain.** On the owner's Moto G15, switching *Allow YouTube* off and
locking leaves the app on the phone.
`setApplicationHidden(com.google.android.youtube, true)` **returns false** there,
while `com.google.android.apps.youtube.music` takes the identical branch and
succeeds. Both are plain system apps under `/product/app`, both are named the
same way by the policy, and the emulator hides both — so this is the platform
refusing one package on one handset, not a rule this app got wrong. Logged on
2026-08-14 at 20:14:52 with build 27, and corroborated by `hidden=false` for
YouTube and `hidden=true` for Music after the lock.

The platform gives no reason. `setApplicationHiddenSettingAsUser` returns false
and logs nothing, so there is no line to find. What the phone does show, offered
as a lead rather than a cause: YouTube's `firstInstallTime` and `lastUpdateTime`
for user 0 are both **2026-08-13 21:56**, its enabled state is explicitly set
(`enabled=1`) and its `lastDisabledCaller` is `com.google.android.partnersetup`,
while Music sits at `enabled=0` from 12 August. Something in Motorola's preload
machinery reinstalled it mid-testing. **drawbridge's own removal path has not
changed** — `git log` on `hide`, `remove` and `isSystemPackage` shows nothing but
build 27's logging — so if this used to work, what changed was on the phone.

**Suspension was checked against the refusing package on that phone by hand** —
`pm suspend com.google.android.youtube` succeeded where hiding failed, and was
undone immediately. That is what the fallback below is built on.

### What was built on 2026-08-15, and it is no longer only about YouTube

**The fix stopped being a YouTube fallback and became one ladder every removal
ends at.** Chasing the refused hide turned up the same shape of bug in three
other places, all of them silent, and the point of the day's work is that a
package policy disallows is now unopenable *whichever* branch it takes:

- **`remove` → `hideOrSuspend`, and every route reaches it.** Hide first; if the
  platform refuses, suspend. Both rungs are **checked against the phone's state
  afterwards** rather than believed: `setApplicationHidden` returning true and
  the app still being visible drops through to suspension anyway. This class had
  already been wrong twice about trusting what a platform call said it did.
- **A refused *uninstall* was logged and forgotten.** The mirror image of the
  YouTube bug, sitting in `PackageRemovalReceiver`: a user-installed app the
  platform would not uninstall stayed fully usable on a locked phone, for good,
  with a `Log.e` on a device that has no adb as the only witness. It now drops
  through to the same ladder. It calls `hideOrSuspend` and **not** `evaluate`,
  which would issue a second uninstall, be refused, and come straight back —
  every failure an endless retry.
- **An OEM reinstalling a blocked app got a free pass.** `PackageWatcher` skipped
  `EXTRA_REPLACING` broadcasts on the reasoning that an update is something
  already evaluated — true, and it assumes the evaluation *stuck*. It is exactly
  what the Moto's YouTube looks like in `dumpsys`: reinstalled mid-testing by
  `com.google.android.partnersetup`. Updates are evaluated now.
- **Diagnostics says what became of the blocklist**, per package: `gone`,
  `hidden`, `suspended`, `present`. `still usable` is the line that answers in
  one glance the question that cost 2026-08-14 an evening, a new build and a
  cable. Read live off the phone rather than recorded from the last sweep,
  because this screen only opens while unlocked and a stored result would be
  overwritten with "nothing to do" by the first sweep after the unlock.
- **`Action.SUSPENDED` used to mean *hidden*.** Harmless with one mechanism,
  actively misleading with two — build 27 added that logging precisely to tell
  branches apart. It is `HIDDEN` and `SUSPENDED` now.

**Decided 2026-08-15, closing the open question:** hiding stays the first rung
and suspension is the fallback, rather than suspending everything. Suspending
everything would make one phone consistent with itself, at the price of a row of
dead icons on every managed phone and a launcher that advertises the blocklist.

**One thing the emulator caught that no test could.** `isApplicationHidden`
returns **true** for a package that was never installed — "hidden" and "not
installed for this user" are the same bit underneath — and `isPackageSuspended`
*throws* for one, with the platform logging a stack trace at error level on the
way out. The restore walks every package the policy names, and a handset carries
a handful of them, so a single lock sweep produced four false *Unhid
com.ecosia.android* lines and four stack traces for apps that phone has never
had. Nothing was broken by it, which is the problem: the log that cries wolf is
the log somebody has to read when a phone really does misbehave. Both reads are
now gated on the package existing at all.

### What is verified, and what is not

**Verified on the API 36 emulator, 2026-08-15**, with drawbridge as Device Owner
against policy 54: locking uninstalled both disallowed browsers and reported
`Lock sweep removed 2 packages`; switching *Allow YouTube* on unhid
`com.google.android.youtube` and `com.google.android.apps.youtube.music` and
nothing else, and both were visible in `pm list packages` afterwards where they
had been hidden before; Diagnostics moved from `2 hidden, 2 present` to all
clear; and no stack traces or false restore lines in any of it.

**The refusal itself cannot be reproduced there** — the emulator hides both
YouTube packages happily, which is what made this a hardware bug in the first
place. `AppBlockerLadderTest` covers it instead, using Robolectric's
`failSetApplicationHiddenFor` to make the platform refuse exactly the package the
Moto refuses, with YouTube Music beside it succeeding — the pair rather than one
example, because this file's own lesson is that a rule checked against one
example is checked against nothing.

### What to do on the Moto, in order

**Build 29 is cut and published to the dev site** — 0.2.8 build 29, policy 60,
`dpc-0f45a23a895fb552.apk`. herald is unchanged at 0.1.13. Everything below needs
a handset and nothing below has been done. It supersedes build 28, which carried
the same fixes and was never installed on anything; there is no reason to look
for it.

1. **Update the Moto and prove the fallback.** A debug APK cannot install over a
   release-signed build and the DPC cannot be uninstalled, so this is
   `tools/provision-adb.sh --update` through the Play Protect verifier window.
   Then: *Allow YouTube* off, lock, and check that YouTube is suspended rather
   than sitting there usable. **Diagnostics answers it without a cable** — under
   `blocklist state`, `still usable` naming YouTube is the failure and
   `suspended` is the fix working.
2. **Then check that suspended apps come back.** This is the half that can
   strand a phone. Switch *Allow YouTube* back on and confirm the app opens
   again, then run removal from the overflow menu and confirm nothing is left
   suspended on a phone drawbridge no longer manages. Both halves were watched on
   the emulator, but only ever on packages that *hid* — the un-suspend path has
   never run on hardware, because nothing on an emulator gets itself suspended.
3. **Then the browser policy's own missing half: herald coming back.** Choose *no
   browser*, lock, then choose *the allowed browsers* and lock again. herald is
   user-installed, so it is uninstalled rather than hidden and has to be
   downloaded afresh by `required_apps`; the emulator could not show this because
   its own filter blocks the fetch. Watch that it does not loop — the installer
   skips a browser the current choice excludes, and without that guard the phone
   would remove and re-download herald forever.

---

## Policy 59: the AI companion category was open on the Play Store side

**2026-08-16, found by the owner on the reference phone.** Cycle AI, Rosytalk,
Star Girl and Trend AI all installed and ran on a locked handset. Twenty-two
verified package ids added, plus six web fronts.

**The diagnosis matters more than the additions.** This category had been carried
almost entirely by `ai-companions.txt` — sixty domains, three packages' worth of
ids beside them — and a domain list cannot hold it. These apps talk to their own
backends over their own hostnames, and nobody reaches them by typing a domain:
they are found by name in the Play Store and installed with one tap. The web
front is the part a parent never sees.

Every id was fetched from its Play listing and the title had to be the app it
claimed to be. **`com.saylo.app` was dropped for failing exactly that test** — it
resolves, but to a different app of the same name; the real one is
`com.xverse.aistory`. That is the failure mode the rule exists for, and it would
have removed somebody's unrelated app. Two domains were dropped as well:
`fantasia.ai` did not respond and `cycleai.com` served a generic "Home", and both
names are ordinary enough to belong to anything.

### And the owner's real question: this cannot be kept up with

**Answered on 2026-08-16 and specified in
[app-ratings](app-ratings.md).** The short version: use Play's own PEGI rating
and category instead of only a list of names. Measured on the twenty-two apps
below plus 48 apps a real phone needs — useful apps are never PEGI 7 or above
(banking 8/8 PEGI 3, tools 8/8, transport 3/3), while 15 of these 22 are PEGI 12
or higher. Games have to go by *category* rather than rating, because Candy Crush
and FIFA Mobile are PEGI 3 while Minecraft is PEGI 7. Not built.

**It cannot, and the list should stop being the plan.** New companion apps appear
weekly, each with a fresh package id, and a signed document updated by hand will
always trail them. Three answers exist, in ascending order of how completely they
settle it, and **two of the three are already built or half-built**:

1. **Keep curating.** What this section is. Useful, cheap, and permanently
   behind — worth doing for the big names because it costs a policy re-sign and
   nothing else, but it is a rearguard action.
2. **The install lock.** ~~`DISALLOW_INSTALL_APPS` at the lock, and drawbridge
   does not currently set it.~~ **Built the same day — see the next section.**
   With it, the blocklist only has to cover what is already on the device at lock
   time, and the category stops growing underneath it. The cost is real, which is
   why it wanted a decision rather than a commit and why the switch defaults off:
   no new apps at all without unlocking, which is a different phone from the one
   the alpha describes.
3. **Allowlist mode.** `Policy.allowedPackages` exists and `AppBlocker.notAllowed`
   already implements it — naming what *may* be installed rather than what may
   not. Still unbuilt, and now the only one of the three that is. It is a
   different promise from 2 and worth keeping distinct: 2 freezes the phone's app
   list at the lock, 3 would let a parent name a set of apps in advance and have
   the phone accept those and nothing else. With 2 built, this is a refinement
   rather than the answer to the question above.

**The honest summary for whoever picks this up:** the blocklist is a filter for a
phone whose app store is wide open, and the fix is upstream of the list. That fix
is in as of 2026-08-16. Curating the list stays worth doing for the big names —
it costs a policy re-sign and nothing else, and it is what protects a phone whose
parent has left the install lock off, which is every phone by default.

**The owner's call on 2026-08-16 was to build number 2**, in the shape the
documentation already promises — *no new app installs after locking, and updates
of the apps already there still come through*. That is the next section.

---

## Build 33: deferral was a property of the package; it is a property of the reason

**2026-08-17, the second report from the Moto that day, and the inverse of the
first.** Build 32 made the install lock outrank everything. This one stops it
rescuing apps that other rules should have removed on sight.

**`dpc-9be19a55f51e8727.apk`, policy 66.**

### What was seen

With the install lock on and drawbridge **unlocked**, the owner installed TikTok,
Firefox, Temu and Crunchyroll. None was removed. Instagram, installed the same
way, **was**.

| app | rule | should act unlocked? | did it |
|---|---|---|---|
| Instagram | blocklist | yes | **yes** |
| TikTok | blocklist | yes | no |
| Firefox | browser the policy never sanctioned | yes | no |
| Temu | store rule, *Parental guidance* | yes | no |
| Crunchyroll | blocklist **and** the streaming option | **no** | no — correct |

### Instagram is the one that explained it

Four apps behaving wrongly says very little; one behaving *rightly* says
everything. Instagram was on the phone at the previous lock, so it was **in the
install lock's set**, so it was not a newcomer — and nothing deferred it. The
other three had just been installed, which makes them newcomers by definition.

`deferred` was asked about the **package**: is it option-governed, is it a
narrowed-away browser, is it outside the closed set. With the install lock on,
every app installed during an unlock answers yes to the third, so every arrival
waited for the lock **whatever else was wrong with it**. The blocklist, the
browser rule and the store rule were all being overruled by a rule that only
meant *this app is new*.

### The fix

Deferral now travels with the **reason**, not the package. Being *new* waits for
the lock, because with the install lock on, unlocking is the only route a person
has to add something. Being *blocked by name*, *an unsanctioned browser* or *rated
out* does not, and the install lock cannot rescue it: an app can be new **and**
disallowed, and the disallowed half decides.

**A correction to how that was first written**, on the owner's reading of
2026-08-17: *"the unlock window is the only way to add an app"* is not true in
general and was stated as though it were. The install lock is **off by default**,
and without it a locked phone installs whatever the policy allows — the blocklist,
the browser rule and the store rule still apply, and anything surviving those
simply arrives. The switch does not change *allowed versus not*; it changes
*already here versus not*. Even with it on, drawbridge's own installs still come
through, because they join the set rather than being exempted from it.

`AppBlocker.Removal` carries the pair, and `deferred` went back to answering the
one question it should ever have answered — *is there a switch on the
configuration screen still governing this app*. The install lock is not a switch.

Crunchyroll is the case that keeps the rule honest. It is blocklisted *and*
covered by the streaming option, so it genuinely waits: the parent may be about
to switch streaming on. "Act always" would have been the wrong fix.

### Twice in one day, same class of bug

Build 32 fixed the install lock being **too weak** — protected packages bypassed
it. Build 33 fixes it being **too strong** — it deferred everything else. Both
were precedence between rules rather than the rules themselves, and both were
invisible to the pure-function tests this class leans on, because every
individual rule was returning the right answer the whole time.

The suite now tests the *interactions*: six cases through `evaluate`, plus the
table above expressed against `deferred`.

---

## Build 32: the install lock had an exception it should never have had

**2026-08-17, reported from the Moto within hours of build 31.** With *Only the
apps already on this phone* switched on and the device locked, **Claude, DeepSeek
and Session installed and stayed** — all three are on the policy whitelist — and
so did **Telegram**, which the *Allow Telegram* option permits. Telegram had never
been on that phone.

**`dpc-8c1f79a18994c52e.apk`, policy 64.** herald unchanged.

### The cause was one line of precedence

`AppBlocker.evaluate` asked `isProtected` first and returned early. Every rule
that answers *is this app acceptable* — the whitelist, the options, the allowed
browsers — therefore short-circuited the one rule that answers a completely
different question: *did this phone have it when it was sealed*.

Those are not the same kind of rule and should never have shared a gate. "Only
the apps already on this phone" cannot have exceptions and still mean anything,
and the owner's report is the correct reading: an option saying Telegram is
*allowed* does not say this phone may *acquire* it while locked.

The install lock now outranks everything, `isProtected` included.

### Why that costs herald nothing

The reappearing-browser path never depended on the bypass, and this is the
payoff for building it three ways in build 29. drawbridge's own installs are put
**into the set** rather than exempted from it: `InstallLockSettings.allow` before
the session is committed, `closeTheInstalledSet` counting a still-downloading
package as present, and the result broadcast adding it again on success.

So *no browser → lock → unlock → the allowed browsers → lock* is unaffected, and
there is now a test that says so rather than a comment claiming it.

### What the tests learned

`InstallLockTest` gained four cases, using herald as the stand-in for a protected
package because `isProtected` keeps it under the default browser choice without a
policy having to be injected. The regression case fails against build 31.

The gap is worth naming: everything about the install lock was tested as a *pure
rule*, and the rule was right. What was wrong was the order it got asked in,
which no pure test could see. The four new cases go through `evaluate`.

### The whitelist grew, and it needs no build

**Policy 65 adds nine apps and no APK**, which is the point of keeping the rule
and its exceptions in the signed document: a household discovering a missing app
costs a re-sign, not a release.

**Shazam** (`com.shazam.android`) and then **music streaming as a category** —
TIDAL, Apple Music, Qobuz, Deezer, SoundCloud, Amazon Music, Bandcamp and TuneIn.
All *Parental guidance*, all for the same structural reason: a music service
carries user playlists, comments or podcasts, and that is ungraded content
whatever the music is.

**YouTube Music is deliberately absent, and it is the sharpest case in the
file.** It is *Parental guidance* like the rest and it is exempted by *Allow
YouTube*, so whitelisting it would override the parent's switch and leave a
control that moves and changes nothing. `policytool.py sign` refuses it twice
over — the package is also on `blocked_packages`, which the whitelist is
consulted before. Checked by constructing the mistake rather than trusting the
check.

The band is behaving exactly as the measurements predicted: ordinary apps land in
it because they carry some ungraded element, and the whitelist is what pays for
blocking it.

### Also in this build

**Shazam is on the whitelist** — `com.shazam.android`, *Parental guidance*,
reported by the owner. The band is doing what the measurements said it would:
ordinary apps land in it because they carry some ungraded element, and the
whitelist is the thing that pays for blocking it.

### Still unconfirmed after this build

The closed set has now been *observed removing nothing when it should have
removed something*, which is a different state from never having run — but it has
still never been watched removing an app it was right about. That is the first
thing to try: with the lock on and the phone locked, install anything and watch it
go. `logcat | grep "not among the apps"`.

---

## Build 31: the store rule ships, and two mechanisms go live untested

**2026-08-17. `dpc-f2a64e8605a293ea.apk`, policy 63.** herald did not move.

This is the largest behaviour change since the app blocker was written, and the
honest headline is at the top rather than the bottom: **installing it turns on
two rules that have never run on a handset.**

### What it does

Apps are admitted by the Play Store's own rating and category instead of only by
a hand-written list of names. Specified and measured in
[app-ratings](app-ratings.md), which carries every number; the short version:

- **PEGI 3 passes, everything else does not**, *Parental guidance included*.
  Useful apps are almost never rated above PEGI 3 — banking was 8/8 across
  Belfius, KBC, Argenta, itsme and BNP; transport 3/3; tools 8/8 — while 15 of
  the 22 AI companion apps from policy 59 are PEGI 12 or higher.
- **Games and dating go by category**, not rating, because the rating cannot see
  addictive design: Candy Crush, Royal Match and FIFA Mobile are PEGI 3 while
  Minecraft is PEGI 7.
- **A 23-package whitelist pays for it**, in the signed policy. Every private
  messenger is *Parental guidance* — ungraded conversation is what the band
  means — so Threema, Session and SimpleX are named there, with Zoom, Webex,
  Strava, the recipe apps, Spotify, Audible and the general-purpose AI
  assistants.
- **Chatbots are not blocked**, and the reason is architectural rather than a
  concession. drawbridge cannot suppress the assistant inside the search engines
  it already allows: SafeSearch is deliverable over DNS because Google and Bing
  run separate enforcement IPs, while `noai.duckduckgo.com` is a CNAME to the
  ordinary address and Google's mechanism is a URL parameter, which reaches only
  herald. That is the test Ecosia failed. The line is companion versus assistant;
  Grok, Character.AI, Chai and Talkie stay blocked.
- **`dist/lists/dating.txt`** closes the web side, which was open: the app side
  was covered twice over and a browser still reached tinder.com.

### The two untested mechanisms, and why they land together

1. **The store rule itself.** Policy 62 has carried `app_ratings` since
   yesterday, and there is no separate switch — by design, since games get no
   toggle. So the rule begins applying the moment this build is installed, and
   the first thing it does is queue a scan of every user-installed app.
2. **The install lock's closed set**, which *still* has never removed anything.
   Both sweeps on 2026-08-16 logged `Lock sweep removed 0 packages`, because
   layer 1 was preventing anything from reaching layer 2, and layer 1 is gone.

Two rules that have only ever run against synthetic tests, going live in one
build, on a phone in daily use. That is worth knowing before installing rather
than after.

### The pre-flight, which was not run

**The Moto was not connected when this build was cut**, so the check the spec
asks for is outstanding. Run it before installing, not after:

```bash
adb shell pm list packages -3 | sed 's/package://' > /tmp/phone.txt
python3 tools/app-ratings.py audit --corpus /tmp/phone.txt --expect keep
```

That prints exactly what the rule would remove from *that* handset, offline,
before anything is. The measurement says to expect roughly one app in eleven to
want a whitelist entry. Anything surprising in that list is a policy edit and a
re-sign — cheap — where finding it afterwards is an app that is already gone.

### What to watch, in order

1. **The pre-flight above.** It is the only step that costs nothing to do and
   cannot be undone if skipped.
2. **`store to scan` in Diagnostics.** It should fall to 0 once the phone is on
   Wi-Fi. A number that never falls is a scan waiting for a network it is not
   getting, which is a phone enforcing nothing while looking exactly like one
   that has finished.
3. **`store unverified`.** Fail-open is a decision only while it is countable. A
   large number here means the phone cannot reach `play.google.com`, and every
   app is being kept by default.
4. **The closed set finally removing something.** With the install lock on and
   the phone locked, install anything from the Play Store and watch it go within
   seconds. `logcat | grep "not among the apps"` is the line.
5. **Updates still work**, which build 30 fixed and nothing here should have
   touched.
6. **herald still comes back.** *No browser*, lock, unlock, *the allowed
   browsers*, lock again. Unchanged since build 30 and still unrun.

### Rolling back

Policy 63 can be re-signed with `app_ratings` removed and every phone stops
enforcing the rule at its next poll, without a build. That is the escape hatch
the field was put in the document for, and it is worth remembering it exists
before doing anything more drastic.

---

## The install lock is built, and three of its bugs were found by re-reading

**2026-08-16, the same day it was specified.** The phone closes at the lock:
nothing new can be installed, and everything already on it goes on updating. It
is the last of the three beta promises the website has been carrying, and the
third to land in two days.

**Built as specified further down this section, with one substantive departure
and three corrections the spec did not anticipate.** The spec is kept below,
because what it got wrong is more useful than what it got right.

### What shipped

- **`InstallLockSettings`** — device-local, off by default, holding the switch
  and the closed set. The set is `Set<String>?` and **the null is the point**: an
  empty set means *this phone carries nothing*, so every package on it would be a
  newcomer and the rule would take the device apart. A snapshot never taken has
  to say "I cannot say", which is the same distinction `protectedSince` got wrong
  on 2026-08-12.
- **The set is recorded at every lock**, in `AppBlocker.closeTheInstalledSet`,
  called from `DrawbridgeApplication.sweepOnLock` **before** the sweep it is
  named for. That order is the whole feature: the sweep is what enforces the set,
  so a sweep running against the previous lock's snapshot would remove exactly
  the app the parent unlocked the phone to install. Recorded whether the switch
  is on or not, so a phone that had it off is not carrying months of drift ready
  to be believed the moment somebody turns it back on.
- **One branch in `AppBlocker.reasonToRemove`**, joining the existing ladder
  untouched — uninstall for user apps — and `deferred` returns true for it.
- **`DISALLOW_INSTALL_APPS`** in `restrictionsFor`, keyed on the lock and on the
  switch. It is in `MANAGED_RESTRICTIONS`, so `applyUserRestrictions` clears it
  when it is not wanted.
- **The switch** sits between the browser policy and the options, with its
  explanation on the card — like the disconnect philosophies and unlike the
  options, because *only the apps already on this phone* does not say that
  updates still arrive, and that is the half that makes it livable. The cost is
  behind the ⓘ, in all three languages.
- **Diagnostics reports it**: `install lock`, `installed set` (or *(never
  taken)*, which with the lock on is the failure), and when the set was taken. A
  wrong snapshot is invisible from every other angle — a phone that evicts the
  parent's new app and one that lets a stranger's stay look identical from the
  outside.
- **548 → 584 tests**: eighteen new cases, which is thirty-six because dpc's
  suite runs in both its variants. Build 29 added twenty; build 30 took two back
  out with the restriction they covered. Lint warnings unchanged, count for count.

  **That is a different baseline from the 574 this file has been claiming**, and
  the 574 does not reproduce: `./gradlew clean test` on the commit before this
  one gives 548, counted from every `test-results` XML in the tree. Stale result
  files from earlier runs will inflate it, which is the likeliest explanation and
  is worth knowing before somebody quotes a number out of a dirty build
  directory. Count after a `clean`.

### The three things the spec had wrong

**1. A package still downloading has to count as present.** The spec said
`AppInstaller.install` should add the package to the set *when it succeeds*. Both
halves of that are wrong, in opposite directions. It has to be added **before the
session is committed**, because `ACTION_PACKAGE_ADDED` can beat the
install-result broadcast and `PackageWatcher` would then evaluate a package not
yet in the set — and that still is not enough, because herald is 230 MB. *Choose
the allowed browsers, then lock* commits the install and then spends minutes
downloading, and the lock lands in the middle of it: it re-takes the snapshot
from the packages actually on the phone, does not find herald, and writes the
name straight back out. So `closeTheInstalledSet` unions the installed packages
with whatever drawbridge has in flight, and the result receiver adds it a second
time on success. Three mechanisms for one app, each covering a window the others
do not.

**2. Layer 1 could not be left to chance after all.** The spec's plan was to test
`DISALLOW_INSTALL_APPS` against drawbridge's own `PackageInstaller` sessions on
the Moto and drop layer 1 if it refused them. That blocks the feature on a
handset for something that can be made not to matter: the restriction is now
**stood down for the length of an install** and put back by the install's own
result broadcast — not by a `finally`, since `commit` returns long before the
package lands. If the Device Owner exemption exists this is two no-op calls; if
it does not, herald still comes back. What bounds the window when a broadcast is
lost is that `applyUserRestrictions` recomputes the whole set on every process
start, boot, lock and unlock.

The honest cost, stated where somebody will find it: while drawbridge is
downloading an app the parent's own signed policy named, a locked phone can
install apps. The closed set removes anything else that arrives through that
window, which is layer 2 doing exactly the job it exists for.

**3. The rule is limited to user-installed apps**, which the spec did not say and
`AppBlocker.notAllowed` had already learned. This is the second rule in that
class that removes what is *not* named, and the snapshot is the worse of the two:
it is generated rather than written, so nobody ever reads it, and it cannot know
about a package that does not exist yet. An Android version upgrade legitimately
adds system apps — a snapshot taken on 15 has never heard of what 16 ships — and
hiding those would be an OTA quietly subtracting from the phone, with nothing
able to restore them because `restoreNowAllowed` only brings back what the
*policy* names. Nothing is lost by the limit: the Play Store is what this is for,
and a preinstalled app was on the phone when the parent locked it.

### herald survives four separate ways, and that is deliberate

It is the thing that strands a phone if it goes wrong — a locked handset with no
browser and no way back short of the key — so it is over-defended on purpose:

1. `isProtected` declines it before the install-lock branch is reached, because
   it is an allowed browser;
2. `InstallLockSettings.allow` puts it in the set before the session commits;
3. `closeTheInstalledSet` counts it as present while it is still downloading;
4. the install-result broadcast adds it again on success.

Only the first is specific to browsers. The other three are what make the rule
right for **any** required app, including ones no policy names yet.

### The Moto answered, and layer 1 is gone — 2026-08-16, build 30

**Build 29 shipped both layers. Build 30 removes one of them, on evidence from
the handset the same evening.** The open question below — *does
`DISALLOW_INSTALL_APPS` let a Play Store update through* — is answered: **no.**

The owner switched the install lock on, locked, and tried to update **Bitwarden**
from the Play Store. It was refused. The platform's own log carries the whole
thing:

```
17:23:19  Changing user restriction no_install_apps to: true   caller: app.drawbridge.dpc
17:24:58  com.android.vending/…MultiInstallActivity  START … finish 57 ms  app-request
17:25:27  Changing user restriction no_install_apps to: false  caller: app.drawbridge.dpc
```

The update attempt sits squarely inside the window where the restriction was on,
and Play's install activity opened and closed again in 57 milliseconds.

**The cause is structural rather than a flag being wrong.** The restriction is
checked in `PackageInstaller.createSession`, and a Play Store update is an
ordinary session — the platform draws no distinction there between a new package
and a replacement. So **no AOSP user restriction can express *no new apps,
updates fine***, and this one could not deliver the promise at any setting.

**It is a worse failure than it first sounds**, which is why it was fixed the
same evening rather than tuned: blocking updates freezes security patches for
every app on the phone, and the app that surfaced it was a password manager.

#### What build 30 does

- **`DISALLOW_INSTALL_APPS` moves to `RETIRED_RESTRICTIONS`**, not merely out of
  `MANAGED_RESTRICTIONS`. That distinction is the whole fix: `applyUserRestrictions`
  computes what to *clear* from the managed list, so an entry only dropped from
  it stops being set on new devices and is never taken off one that already has
  it. Any phone that locked once under build 29 would have been left unable to
  update anything, permanently, with nothing on the device able to reach it. It
  is now cleared on every apply — every process start on a locked phone.
- **The closed set carries the install lock alone**, which is what the
  specification's own contingency said it would have to. Behaviour: a Play
  install succeeds, `PackageWatcher` sees `ACTION_PACKAGE_ADDED` and uninstalls
  anything outside the snapshot within seconds; the fifteen-minute sweep is the
  backstop. Updates are untouched by construction — an update never adds a
  package name that was not already there.
- **The restriction-lifting machinery around drawbridge's own installs is gone
  with it.** `DeviceOwnerManager.allowOwnInstalls` / `ownInstallFinished` existed
  only to work around the restriction. What survives is
  `InstallLockSettings.beginOwnInstall` / `endOwnInstall`, because the in-flight
  set has a second job that outlives layer 1: `closeTheInstalledSet` counts a
  still-downloading package as present, so a lock landing mid-download cannot
  evict herald.
- **`DISALLOW_INSTALL_UNKNOWN_SOURCES` was considered and declined**, on the
  owner's call. The snapshot catches a sideloaded package exactly as it catches a
  Play install, so the restriction would change *when* rather than *whether* —
  and adding a second restriction on an untested assumption, immediately after
  the neighbouring one behaved differently than assumed, is the mistake this
  section is about.

#### What the log also proved, on the way past

**The snapshot half works on hardware.** Both locks recorded it correctly:

```
17:19:37  AppBlocker: Install lock: sealed the phone with 294 packages on it
17:19:38  DrawbridgeApplication: Lock sweep removed 0 packages: []
17:23:19  AppBlocker: Install lock: sealed the phone with 294 packages on it
17:23:20  DrawbridgeApplication: Lock sweep removed 0 packages: []
```

**And the enforcement half still has not run**, which is the irony worth
recording: both sweeps removed nothing because layer 1 was preventing anything
from ever reaching layer 2. Removing layer 1 is what will finally exercise it.

#### One gap, recorded rather than closed

Android requires a matching signature to *update* an installed app, but nothing
stops uninstalling one and sideloading a differently-signed build under the same
package name — the snapshot allows it, because the name is in the set. That is an
adversary with an APK-patching toolchain rather than a child, and it is outside
drawbridge's threat model. `DISALLOW_INSTALL_UNKNOWN_SOURCES` would close it if
that ever changes.

### What still needs the Moto

**Build 30 is `dpc-ad41902ebeeeeef8.apk`, policy 61.** herald did not move, so its
`required_apps` pins are untouched and the only thing a phone fetches is
drawbridge itself.

1. **Confirm updates work again.** Install the lock, lock, update any app from
   the Play Store. This is the regression that build 30 exists for, and it is one
   tap.
2. **Watch the closed set actually remove something** — the half that has never
   run. With the install lock on and the phone locked, install anything from the
   Play Store and watch it disappear within seconds. Diagnostics shows the set
   size; a package that stays put is the failure.
3. **Does herald come back on a locked phone?** *No browser*, lock, unlock, *the
   allowed browsers*, lock again — with the install lock **on**. This exercises
   the three surviving herald defences at once, and it is the failure that
   strands a phone rather than merely annoying it. `installed set` should grow,
   and herald should neither appear under `still usable` nor vanish after
   arriving.

Item 3 in *What to do on the Moto* at the top of this file is the same herald
test **without** the install lock, and it is still unrun. Do that one first: a
failure there is the browser policy, not this.

---

### The specification this was built from, kept for what it got wrong

**That sentence is the specification, and Android expresses it exactly.** A
package arriving as `ACTION_PACKAGE_ADDED` carries `EXTRA_REPLACING`: false for a
package the phone did not have, true for an update of one it did. New app versus
update is not a heuristic here — it is a boolean the platform hands over.

*As built, nothing reads that flag. The set answers the question instead, and the
reason is in the spec's own "what to watch for" three sections below: the
fifteen-minute sweep has no broadcast to read a flag from. The spec settled its
own design question and did not notice.*

#### Two layers, and only the second is certain

**Layer 1, prevention: `DISALLOW_INSTALL_APPS`.** One entry in
`DeviceOwnerManager.restrictionsFor`, which is where the whole restriction set is
decided and the only place it should be touched. **Key it on the lock rather than
on protection**, exactly as `DISALLOW_DEBUGGING_FEATURES` is: installing apps is
the main thing a parent unlocks to do, and `applyUserRestrictions` already clears
whatever the current state leaves out, so the entry comes off at unlock for free.

**Whether it lets updates through is the open question, and it decides how much
layer 2 has to carry.** Google's own EMM documentation calls the equivalent field
*"whether user installation of apps is disabled"* and keeps update behaviour in a
separate field (`appAutoUpdatePolicy`), which points the right way — but that is
AMAPI's wording, not a promise about the AOSP restriction on a sideloaded DPC.
**Two things to try on the Moto, in this order:**

1. Set the restriction, lock, then force a Play Store update of an installed app.
   If it updates, prevention and the promise are compatible.
2. Then check drawbridge's *own* installs still work, because they are the ones
   that must not break: `required_apps` fetching herald after a browser-policy
   change from *no browser* back to *the allowed browsers*, and `UpdateActivity`
   installing a new drawbridge. Both go through `PackageInstaller` as Device
   Owner rather than through the user, so both *should* be exempt — and if
   either is not, this layer cannot ship as it stands and layer 2 carries it
   alone.

*Not how it went. The restriction stands down for the length of an install, so
the answer stops deciding anything — see "Layer 1 could not be left to chance"
above. Test 1 is still worth running; test 2 no longer gates the ship.*

**Layer 2, enforcement: the closed set.** This is the part that gives the
promised semantics whatever the restriction turns out to do, and most of it
exists.

- **The snapshot.** At the lock, record the packages installed at that moment.
  `LockActivity.sealWithKey` is the place — it is already where the full sweep
  runs, and already after `ParentKey.commit`. Store it device-locally beside the
  other household settings, the shape `BrowserSettings` and `DisconnectSettings`
  use. Re-taken at every lock, which is what makes the unlock window the way to
  add an app: unlock, install, lock again, and it is in the set.
- **The rule.** `AppBlocker.reasonToRemove` gains one branch: with the install
  lock on and the phone locked, a package that is not in the snapshot is not
  allowed. It joins the existing ladder untouched — uninstall for user apps,
  `hideOrSuspend` for the rest — and `deferred` should return true for it, so it
  waits for the lock like everything else a switch governs.
- **Updates need no special case at all**, and this is the neat part. An update
  never adds a package name that was not already there, so it is in the snapshot
  by construction and the rule never fires on it. `PackageWatcher` already
  evaluates `EXTRA_REPLACING` broadcasts as of 2026-08-15, and it can go on doing
  so: the snapshot answers the question, not the flag.
- **drawbridge's own installs must join the set.** `AppInstaller.install` should
  add the package to the snapshot when it succeeds, or herald would be removed
  moments after drawbridge fetched it — the same loop the browser policy already
  had to be guarded against. One line, and it is honest: drawbridge putting an
  app on the phone is the parent's decision arriving by proxy.

*The one line was three, and "when it succeeds" was the wrong moment twice over.
`hideOrSuspend` never happens either — the rule declines preinstalled packages.*

#### The switch, and what it must say

A device-local setting like the browser policy, defaulting **off**, because it
changes what the phone is rather than what it filters. The wording has to carry
the cost, which is not obvious until it bites: **an app you have not installed
yet, you cannot install** — no new bank app, no train ticket app, no app a
school asks for in March — without unlocking with the key. That is the whole
point of it and the whole objection to it, and a parent should meet the sentence
before the situation.

*Built as written, and the sentence is in `install_lock_info` in three
languages.*

#### What to watch for

- **The sweep is the backstop, not the receiver.** `PackageWatcher`'s receiver
  only fires while the filter service is alive, so an app installed during a
  restart is caught by the fifteen-minute sweep instead. The snapshot makes that
  work; a design relying on `EXTRA_REPLACING` alone would miss it.
- **Do not key it on `protectedSince`.** That reads as *has ever been locked* and
  stays true forever, which is the bug that cost 2026-08-12. The lock is the
  signal.
- **The pre-first-lock window is untouched**, as ever. Nothing is enforced before
  the parent locks, and that is when they install what the phone should have.

---

## The browser chooser exists now, three years of website copy later

**2026-08-15.** The website has described this since before it was built — *"by
default drawbridge allows a limited list of browsers… you can also choose to use
herald mono only… finally, you can choose to have no browser at all"* — and the
handoff has carried "browser choice is still only on the website" as an open gap
since the dev site was written. It is built.

Three cards, the same shape as the disconnect philosophy, **with the browser
icons as the description**, and a prohibition sign for *no browser*. "All the
allowed browsers" is a claim to take on trust; five icons somebody recognises is
the same claim, checkable at a glance. The long text — including the warning that
apps signing in through a browser cannot, on a phone without one — is behind
the ⓘ.

**The icons are the policy's list, not the phone's**, which was the correction on
the way through: the first version drew only installed apps, so the same choice
looked different on two phones and looked smaller than it is on one whose
browsers had just been removed. Each icon now resolves installed-icon → bundled
copy → globe, that last rung being for a browser a future policy names that this
build has never heard of. Chrome, Focus and Vivaldi logos are bundled from
Wikimedia Commons and herald's two from `site/assets/img/`; about 15 KB in all,
with provenance recorded beside the map.

Device-local like the disconnect philosophy, and it can only ever *narrow* what
the signed document sanctions. See
[design-decisions](design-decisions.md#the-browser-choice-narrows-the-policy-and-can-only-narrow-it).

**Watched working on the API 36 emulator, both directions:**

- choosing *herald mono only* while unlocked removed nothing — the deferral —
  and moved the link handler to herald mono straight away;
- locking then hid Chrome, uninstalled herald and kept herald mono, logging
  *"is a browser, and this phone allows only app.drawbridge.heraldmono"*;
- unlocking and choosing *the allowed browsers* unhid Chrome immediately.

herald does not come back on that emulator because it is user-installed and
therefore uninstalled rather than hidden, and reinstalling it needs a network the
emulator's own filter is blocking. **That path is the one still unverified**, and
it is worth watching on the Moto: choose *no browser*, lock, then choose the
allowed browsers, lock again, and herald should download and reappear.

### drawbridge stopped deciding the default browser

The first build pinned herald as the web-link handler and grew a picker to let a
parent change it. Both are gone, and the reason is worth keeping: the only Device
Owner API for a default handler keeps its activity *"even if the intent
preferences are reset"*. It is built to be un-overridable — right for a kiosk,
wrong for a recommendation — so "herald is the default" and "you can change it
the normal way" could not both be true. Android's own chooser and
Settings → Default apps do the job, and `releaseDefaultBrowser` clears any claim
on every policy application so phones updating from the pinning build are freed.

herald is therefore not pre-selected; it is one entry in the chooser. Under
*herald mono only* and *no browser* that is moot, there being nothing to choose
between.

**One older bug surfaced and was then made moot.** `resolveBrowserActivity` used
a plain query, and a persistent preferred activity makes the platform answer that
filter with the preferred activity alone — so it could only ever resolve the
browser that was already the default, and changing it failed silently. It had
been that way since it was written, never exercised because herald was the only
answer anything asked for. Deleted with the pinning. The shape is the familiar
one for this file: correct code that could not work, behind a call nobody had
reason to make.

---

## The configuration screen got shorter, and removal got narrower

**2026-08-15, and the second half is a behaviour change rather than a tidy-up.**

### The paragraphs moved behind ⓘ — the policy's and the options', not all of them

Every policy card and every option row carried an explanatory paragraph, and
they were good paragraphs — the policy's own words, translated with the
document. They were also about 1,600 characters on screen at once, above the
Lock button. **It took eight swipes to reach that button; it now takes three**,
with the policy, all four options and the button on one screen.

Nothing was cut. The name and the "who this is for" line stay on the card, and
the paragraph is one tap away. An option with nothing to say gets no button
rather than an empty dialog.

**The three disconnect philosophies keep their text on the card**, which was a
correction on the way through: they moved behind ⓘ first, and the owner's call
was that they should not have. They cost almost nothing — one short line each
rather than a paragraph — and unlike a policy or an option the name alone does
not carry the meaning. *Curfew for the internet* does not say the clock gets
locked; *always blissfully offline* does not say calls and SMS still work. That
is the sentence somebody needs in front of them while choosing, not one tap
away. They have their own `item_disconnect.xml` now rather than borrowing the
policy card's layout, because the two have genuinely diverged.

### Three symbols, and the third was a proposal

A lotus for *always blissfully offline*, a crescent for *curfew*, and — where
there was nothing obvious — a **robot whose body is a plug** for *sadly always
online*. Three earlier candidates went: a sun pairs neatly with the crescent but
reads as *daytime*, which is the curfew's subject rather than this one's; an
open drawbridge was the thematic answer and sits too close to the app's own icon
at 24dp; and a globe, which shipped for one round and said "the whole internet,
all the time" without saying anything about how that feels — which is what the
option's own name, *sadly* always online, is getting at.

**The robot and the plug are one shape rather than two.** A head, a cord and a
plug is three small elements at 24dp and reads as clutter; merged, it is a face
on two prongs, which says *only works while plugged in* in one glance. It is
deliberately not the Android droid silhouette — that is Google's mark — but a
rounded box with an aerial.

**The lotus took three attempts and the first two failed identically**, which is
worth recording because the diagnosis was not obvious: both drew the outer
shapes as broad blades sweeping outward and *downward* from a stem, which is
exactly what foliage looks like, so both read as a sprout. A lotus is a fan of
narrow petals, each pointed, all the same length, all radiating from one point
and all tipped *upward*. It is now one petal drawn once and rotated five times
about the base, so the symmetry is exact rather than eyeballed.

They sit on the title's line rather than beside the card. Centred against the
whole text block, the symbol landed level with the *description* instead, which
is the difference between "the lotus one" and "a lotus somewhere over there".

**The one thing to be careful with is the policy card**, which is itself
tappable and selects a policy. The ⓘ takes its own click, so the touch never
reaches the card — watched on the emulator by tapping the info button on the
*unselected* Curfew card and confirming its radio stayed empty.

### The age is a shield now, and it is drawn rather than borrowed

"Allow Telegram  18+" became a filled shield with the age in it, banded
**yellow (14+) → orange (16+) → purple-pink (18+)**, with *various ages* taking
the same orange as 16+. A number in running text is something to read; a coloured
shield is something to recognise, which is what somebody scanning four options is
doing.

**There is no red band, and its absence is the finding.** The middle band was red
for a round, and red against purple-pink was two dark warm shields that looked
alike at exactly the distance these get looked at. Moving the middle to orange
opened the gap between all three. *Various ages* shares that orange rather than
having its own: what separates it from a 14+ is the glyph — an exclamation mark
against a number — not the hue. The shields keep the `+`, because "18" alone
reads as *at* that age and the point is that it is a floor.

**The label colour is per band, and yellow is the reason.** A yellow dark enough
for white text is a mustard, which is not what anyone means by yellow, so that
shield takes near-black text and the other three take white — which is what
every rating system with a yellow does. Contrast was measured rather than
eyeballed: 9.9:1 for near-black on yellow, and 4.6:1 to 7.4:1 for white on the
others. In dark mode the fills lighten and the labels flip to near-black, except
the yellow, which is already bright and is deliberately identical in both themes.

**The policy card carries one too, as of policy 57.** Its age used to live inside
the subtitle as "(+14)", spelled three different ways across the translations —
the one number somebody compares policies by, buried mid-sentence. `Profile`
gained `recommended_age`, the parenthesis came out of all three subtitles, and
both card types now go through one binder.

**They are not PEGI, Kijkwijzer or the Parental Advisory label, deliberately.**
Those are licensed marks and they mean *those bodies graded this content* —
none of them has been near WhatsApp or Telegram on anyone's behalf, and the
footnote under these very options says the ages come from pediatricians,
psychologists and neurologists. A borrowed badge would have contradicted it
without a word being written. The shield is a shield because this app is named
after a castle gate.

**The streaming shield reads "various ages"** — an exclamation mark on an orange
shield, with the words in its content description. That wording was itself a
correction: it said *parental advisory* first, which is a registered mark and a
recognisable look. "Various ages" says the true thing — one switch covers fifty
services and a service carries children's films and adult drama through the same
app — and cannot be mistaken for anyone's label. It takes its own step between the yellow
and the red, because "various" is neither a 14 nor an adult rating; what really
marks it out is the glyph.

Policy 56 adds `various_ages` to a policy option to carry it; the field is
optional and defaults to false, so a build reading the document without it is
unaffected. `PolicyOptionTest` asserts it parses under the document's own
spelling, because a mismatched `@SerialName` would leave it false on every phone
and the shield would simply never appear — the same silent shape as the Shorts
rewrite that shipped twice without running.

### The policy moved to the top, and one sentence stopped needing a list

The screen ran Language → Welcome → *the filter sentence* → Disconnect → Policy →
Options. The sentence was stranded: it sat at the very top describing controls
three sections away, so it had to name every one of them.

**Policy is first now, with that sentence directly beneath it.** The old ordering
put the disconnect philosophy above the policy on the reasoning that whether the
phone reaches the web at all is the larger question — true, and beside the point
once the sentence has to live somewhere. The policy is also the one thing on this
screen that acts immediately, which is what lets the sentence say two things in
the right order and in half the words:

> The web filter is already running, and apps this policy does not allow are
> removed as soon as they appear. The other options below only take effect after
> the lock.

Read top to bottom, the screen now explains itself: the part that is already
working, then everything that waits.

### Removal narrowed: only what a switch could change waits for the lock

The rule was "everything except browsers waits for the lock". It is now:

| | Removed while unlocked? |
|---|---|
| Browsers | **Yes** — a way around a DNS-only filter, not one more app |
| What an **option** covers — WhatsApp, Telegram, YouTube, streaming | **No.** Waits for the lock |
| Everything else the policy blocks | **Yes**, as of today |

**Why.** The old rule meant an unlocked phone slowly refilled with exactly the
apps drawbridge was installed to remove. Somebody installs this *because* they
want social media off the phone; there is no second question there. What is
still an open question is what an option covers — the phone arrives with every
option off, and taking an app away from a parent halfway through deciding to
allow it is the taunt the unlock window exists to prevent. That asymmetry is
real rather than theoretical: `restoreNowAllowed` can unhide a *preinstalled*
app when its option comes on, but nothing reinstalls one that was uninstalled.

**The window before the first lock is untouched.** It is a separate gate and it
is what lets a parent move bookmarks and data across — the reason drawbridge
needs no factory reset. Nothing is removed before the first lock, browsers
included. This was the fork worth being explicit about: "immediately after
installing" would have meant taking a parent's own Instagram before they had
exported anything, which is the complaint that produced the lock gate in the
first place on 2026-08-12.

**Watched on the emulator, both halves, on an unlocked phone that had been
locked once:**

- installing a package on `blocked_packages` that no option covers →
  `Removing …: on the blocked package list` and gone within seconds;
- installing one an option covers, with the option off → no log line at all, and
  the app still there.

The screen stopped claiming otherwise: the line above the controls now says the
filter is running *and* the policy's apps go as they appear, while the options
and the disconnect setting land at the lock. A policy change toasts "applied"
rather than "applied after lock", because by then it has.

---

## Policy 55: Ecosia is out, and the rule it broke is worth keeping

**2026-08-15.** Nothing was wrong with the engine. Ecosia honours `safesearch=2`,
and herald's `SafeSearch` put that parameter back on every load, so **inside
herald it really was forced**. It is gone because that is all it ever was.

**herald is not the only browser on the phone.** Chrome, Firefox Focus and
Vivaldi are allowed too, and in any of them ecosia.org was ordinary unfiltered
search with nothing to rewrite it — no safe hostname exists to point DNS at, so
the filter could not reach it and the parameter reached only the browser that
writes it. A parent choosing Chrome got an unfiltered search engine on a phone
that says it filters search.

**The general rule, which is the part to keep:** an engine is only as forced as
its *weakest* browser. Google, Bing and DuckDuckGo survive that test because a
DNS rewrite reaches every browser on the device; Kagi survives it because it
filters logged-out with nothing to turn off. A URL parameter is not in that
class, and Ecosia was the only engine that rested on one.

It went the way Brave, Startpage and Qwant went on 2026-08-10, in one policy:

- out of `browser.search_engines` and out of herald's `SearchEngineCatalogue`;
- **`ecosia.org` added to `dist/lists/search.txt`** — dropping an engine from the
  browser never made it unreachable, which is the whole reason that list exists.
  This also stops the Ecosia app, which talks to `api.ecosia.org`;
- **`com.ecosia.android` out of `allowed_browser_packages` and into
  `blocked_packages`**, so it is removed like every other browser that is not
  allowed. Removing it from the allowed list alone would have done it — the
  blocker takes any browser the policy does not name — but naming it matches how
  Opera, Aloha, UC and Tor are handled;
- the `SafeSearch` rule deleted rather than left to sit. A rewrite rule for a
  domain the same policy blocks can never fire, and this project has already
  shipped one release whose entire content was a rule that never ran.

**One thing was found on the way, and it is older than this change.**
`Policy.searchEngines` — the compiled-in default, used when a document omits the
field — still named Brave Search, Qwant and Startpage nine days after they were
dropped, plus Ecosia. That default is not inert: `SearchEngineCatalogue.apply`
*hides* every engine the list does not name, so on a phone falling back to it a
locale bundling one of those four would have kept it, unforced. It now matches
the shipped list. Both earlier removals updated every list but this one.

**Not verified on a handset.** The policy is signed and sits in `dist/` on this
branch; no phone has polled it. What to watch for when one does: Ecosia gone
from herald's engine list, ecosia.org showing the block page, and the Ecosia app
removed at the next lock.

---

## "Calls and SMS only" now says what it excludes

**2026-08-15, and it is a wording fix with one open question behind it.** The
offline mode's copy said the phone can still *call and text (no RCS)*. The RCS
half was right — RCS is SIP and HTTP over the ordinary data connection, so a
lockdown that drops every non-DNS packet stops it registering at all, and
Messages reports it unavailable.

**MMS was the gap.** It is IP as well, over a dedicated APN, which sounds like an
exemption and is not: the only carve-out `setAlwaysOnVpnPackage` accepts is a
list of package names. So **picture messages and group messages do not go
through either** — and since MMS is exactly what RCS falls back to for those,
both halves go rather than one degrading into the other. "Text" was doing a lot
of work in a sentence read by someone whose phone does not distinguish the three.

Now stated in all three languages on the website, in
`disconnect_offline_description` on the configuration screen where the mode is
actually chosen, and reasoned out in
[design-decisions](design-decisions.md#what-survives-it-calls-and-sms-and-that-is-the-whole-list).
The owner's note, which belongs with it: MMS is being sunset market by market as
RCS replaces it, so this is a shrinking loss rather than a growing one.

**The open question, and it is one photo long.** The MMS claim is reasoning from
the mechanism, not something a handset has been asked — Android's DPC
documentation names no telephony carve-out from lockdown, but that is an absence
of evidence. An emulator has no carrier to test it with. **Send a photo message
from the Moto during a curfew.** If it goes through, the copy above is wrong in
the generous direction and should be corrected again.

The same paragraph carries a second unverified claim, in the other direction:
Wi-Fi calling is reported to bypass Android VPNs rather than ride inside them, so
a phone with poor coverage may still be able to call over Wi-Fi while offline.
That would support the promise rather than undermine it, and it is worth knowing
which way it goes on real hardware.

---

## Read this first: two channels, and which is which

| | `main` (the alpha) | `dev` |
|---|---|---|
| drawbridge | 0.2.7, build 18 | **0.2.8, build 33** |
| herald | 0.1.9 | **0.1.13** |
| policy | **50** | **68** |
| install page | <https://drawbridge-project.pages.dev/install/usb/> | <https://dev.drawbridge-project.pages.dev/install/usb/> |
| provisioned devices | the owner's Nothing Phone (A059) | the Moto G15 |

**`main` is deliberately behind, and that is not neglect.** The alpha is what a
tester installs and what the owner's own daily phone runs. Policy *content* is
kept in step (policy 50 is policy 49's rules); the *builds* are not, because the
0.2.8 work is still being found wanting on hardware roughly once a day.

**Three things keep the two apart, and getting any of them wrong breaks the
alpha:**

1. **`required_apps` resolves through `/releases/latest/download/`.** Whichever
   GitHub release holds the **latest** flag is what every alpha phone installs.
   v0.2.5 holds it, which is why herald 0.1.9 is what `main` delivers. The dev
   releases (`v0.2.8-dev.1` through `v0.2.8-dev.4`) are pre-releases explicitly **not**
   flagged latest, and dev's policy pins their **versioned** URLs instead. A
   herald release that took `latest` would change the alpha without drawbridge
   moving at all.
2. **`policytool.py sign` rewrites blocklist URLs to the branch it runs on.** So
   signing on `main` produces `main` URLs and the merge trap cannot be set by
   hand. `app_update` and `required_apps` are *not* rewritten — they are
   deliberate values, and they are the two fields that must never travel between
   branches.
3. **Both apps read `drawbridgePolicyUrl`** from `gradle.properties`, which only
   `dev` sets. herald did not until 2026-08-13, and until then a dev phone ran
   drawbridge against dev's policy and herald against the alpha's.

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
| Repo | https://github.com/Nilss3/drawbridge — public, `main` + `dev` |
| Alpha | **[v0.2.7](https://github.com/Nilss3/drawbridge/releases/tag/v0.2.7)** is what testers install, from `main`. [v0.2.5](https://github.com/Nilss3/drawbridge/releases/tag/v0.2.5) stays **latest** because `required_apps` resolves herald through it |
| Dev | **drawbridge build 33, herald 0.1.13, policy 68**, served from the dev site. herald is unchanged since v0.2.8-dev.4, whose versioned URLs `required_apps` still names |
| Devices | Two managed phones: the Moto G15 on dev, and the owner's **Nothing Phone A059** on the alpha since 2026-08-13 |
| Tests | **666** unit tests across eight variant suites, lint clean. Counted after a `clean`; see the install-lock section for why the old 574 was wrong |
| Website | trilingual, generated into `site/`, both channels served from Cloudflare Pages |

**herald is no longer frozen.** It sat at 0.1.9 from 2026-08-10 to 2026-08-13
because a rebuild costs six 230 MB APKs and a policy re-sign. Two releases in one
day ended that, and the cost is real: budget ~2 minutes of build and a 1.3 GB
upload per herald release.

## v0.2.7 is the first alpha, and `main` is frozen around it

**Declared on 2026-08-11.** drawbridge **0.2.7** (versionCode 18) plus **policy
36** is what testers get, installed from
<https://drawbridge-project.pages.dev/install/usb/>. The owner runs it on the
Moto G15 as a daily phone for a few days, then tries a Nothing Phone 3(a).

**Leave this alone.** Not "avoid breaking it" — do not change what `main`
publishes at all while the alpha is being lived with, because everything a tester
touches is served from `main`: the install page, the APK it hands out, and the
policy every device polls. A commit to `main` is live within minutes on the site
and within three hours on the phones. Development goes to the dev channel below.

What the alpha contains that nothing before it did: provisioning from a website
over WebUSB with no factory reset, herald arriving before the lock so bookmarks
can move, updates over the same cable after unlocking, and no QR path at all.

**It ships with the emergency key**, deliberately — see the back-door section.
Testers are a handful of people who know what they signed up for, the key is in
the offline backup, and it comes out before any phone belongs to someone who is
not a knowing tester.

### The dev channel exists, on the `dev` branch

**Built 2026-08-11.** `dev` is forked from the alpha commit and is identical to
`main` except for the plumbing below. Cloudflare serves it at
**<https://dev.drawbridge-project.pages.dev>** with no further setup, because
Branch control is already set to all non-Production branches.

Three differences from `main`, and nothing else:

1. **`gradle.properties` sets `drawbridgePolicyUrl`** to this branch's copy of the
   signed policy, so a dev build polls `dev/dist/policy.signed.json` instead of
   the document every alpha phone fetches. Edit and re-sign `dist/` here and only
   dev phones see it.
2. **`site-src/channel.txt`** contains `dev`, which puts a rust band across the
   top of every page: *DEV CHANNEL — test builds, not the release.* `main` has no
   such file and renders nothing. This is not decoration — the two sites are the
   same pages with different builds behind them, and one of them provisions
   phones people rely on.
3. **The plumbing itself**: `BuildConfig.POLICY_URL` in `dpc/build.gradle.kts`,
   read by `DrawbridgeApplication.policyConfig`. Its fallback is
   character-for-character `PolicyConfig.DEFAULT_POLICY_URL`, verified, so
   merging this into `main` changes no behaviour there.

**And from 2026-08-14 the dev site describes the beta rather than the build, on
purpose.** The Q&A page was rewritten from the owner's Dutch document and now
documents the state drawbridge is meant to reach at public beta: a long-form
video streaming option, the browser choice (herald, herald mono only, or no
browser at all), and the app-install lock behind "only certain apps". The app
catches up step by step, and **the first step landed the same day**: policy 52
adds the `streaming` option. **All three are now real**, in three days: the
streaming option on 2026-08-14, the browser chooser on 2026-08-15 and the install
lock on 2026-08-16. Both later ones have their own sections at the top of this
file.

That closes the gap this paragraph was written to explain, and it does not close
the *rule*. The dev site still describes the target and this file still describes
the position, which is now "built, and no handset has seen it" rather than "not
built" — a different kind of gap and a shorter one, but the same reason the two
sites must not converge: `main` describes what a tester's phone actually does.
Read the page as the target and this file as the position.

**`dev` is on drawbridge 0.2.8 build 24, herald 0.1.10 and policy 46**; `main` on
0.2.7 (18), herald 0.1.9 and policy 37. Built 2026-08-12 — the first builds this channel has ever carried,
and the point at which the plumbing stopped being theoretical. Build 19 lasted
one round: the owner read the screen and found two things it said that were not
true, which is exactly what a dev channel is for.

What 0.2.8 has that the alpha does not: app removal follows the lock rather than
nothing at all, the disconnect philosophy with its curfew, Diagnostics reporting
the policy's last check / success / error / URL, and a no-cache header on the
manual check. Policy 38 is policy 37 plus an `app_update` pointing at this build.

**Policy 38 must not be merged to `main` as it stands.** Its `app_update` names
versionCode 19 and a URL on the dev site; `main`'s names 0.2.7, which is what
testers install. Re-signing on `main` fixes every list URL automatically but not
this — `app_update` is a deliberate value, not a derived one.

**The APK carries policy 37 as its bundled fallback, not 38.** That is inherent
rather than an oversight: the policy pins the APK's hash, so it can only be
signed after the APK exists, and rebuilding to embed it would change the hash it
pins. Same shape as herald's bundled copy being one behind. Harmless — the
bundled document only applies until the first poll.

To install it: <https://dev.drawbridge-project.pages.dev/install/usb/>, which
serves `dpc-9ad503c822d247e0.apk` and refuses anything whose hash does not match.

**What build 20 fixes**, all found by looking at the screen rather than the code:
selecting a disconnect philosophy no longer toasts "applied", because it is
selected rather than applied and the radio moving says so already; the curfew
explanation no longer claims drawbridge keeps its own connection, which stopped
being true the moment the lockdown exemption came out; the default policy's own
words are rewritten in three languages; and the lock confirmation now describes
what locking actually does — removes apps, so migrate bookmarks first, and
applies the disconnect philosophy.

**herald 0.1.10 is released, and the alpha did not move — 2026-08-13.** The
first herald build since 0.1.9, carrying only the Shorts rewrite: 102 lines
across two files, `Shorts.kt` and a hook in `HeraldRequestInterceptor`.

**How it reaches dev without touching main is worth understanding, because it is
not obvious.** `required_apps` on `main` points at
`/releases/latest/download/herald-*.apk`, so whichever release holds the `latest`
flag is what every alpha phone installs. The new release, **v0.2.8-dev.1**, is
marked pre-release and explicitly **not latest** — v0.2.5 keeps that flag — and
dev's policy 46 pins the new APKs at their **versioned** URLs instead. So main
resolves herald 0.1.9 exactly as before and dev names 0.1.10 explicitly. A
herald release that took `latest` would have changed the alpha without drawbridge
moving at all.

**A tag that does not describe what it released.** Checking the change surface
turned up that `v0.2.5` points at a commit from 01:05 on 2026-08-10, while the
release assets were uploaded at 11:45 — after the safe-search work landed. So the
published herald 0.1.9 contains code that is not in the tree at its own tag, and
cannot be reproduced from it. Worth fixing the habit rather than the tag: tag the
commit the artefacts were built from.

**A list's URL follows the branch it is signed on, as of 2026-08-12.** The
channel gave the *document* a staging path, but the lists it names are separate
URLs, and a policy signed here that still pointed at `main`'s copy of a list
could not test a change to that list at all — the file does not exist on `main`
yet, so `PolicyStore` drops it and compiles the rest, silently. `policytool.py
sign` now rewrites the URL of every list this repo hosts to the checked-out
branch and prints each one it moves, so signing on `main` produces `main` URLs
and the merge trap cannot be set by hand. See
[policy](policy.md#a-lists-url-follows-the-channel-it-is-signed-on).

**Working on dev:** commit as usual, push, and the site rebuilds itself. A dev
APK goes in the same way a release does — bump `versionCode`, build, copy to
`dist/release/`, re-sign this branch's policy so `app_update` names the new hash,
`python3 tools/build-site.py`. That last step refuses to build unless the staged
APK matches the pin, which keeps dev exactly as honest as the alpha.

**Keep one monotonic `versionCode`.** Dev must outrank the alpha's 18 to install
over it, and the next alpha must outrank whatever dev reached. A dev build that
turns out good becomes the release, with no renumbering.

**A phone takes one channel or the other.** Same package name — Device Owner
binds to it — so installing dev on a phone replaces the alpha there. The Moto is
the dev phone; anything a tester holds stays on `main`.

### Policy 52: streaming is blocked by default, and one switch brings it back

**2026-08-14, shipped in v0.2.8-dev.3.** The first category added since the policy
took its current shape, and the first option that stands in front of a list
rather than a handful of package names: `dist/lists/streaming.txt` with 103
domains across about fifty services, 70 app ids added to `blocked_packages`, and
an `Allow long-form video streaming` option whose `allowed_domains` mirror the
list exactly. It exists because the Q&A page already promised it. See
[policy](policy.md#an-option-can-cover-a-whole-category) for the shape.

**The mirror is the part that can go wrong quietly.** Allow beats block *per
domain*, and there is no "unblock this category" instruction, so a service added
to the list and not to the option is one the switch fails to restore while
reporting success. `policytool.py sign` now refuses to sign when the two
disagree and names the missing domains; it was tested by removing two and
watching it refuse.

**Every package id was checked rather than guessed**, which the project has paid
for before: a Play Store listing was fetched per id and the page title had to be
the service it claimed to be. That caught more than expected. `com.blim` is a
dead service — Blim TV was dissolved in 2023 and folded into ViX, and its domain
no longer resolves, so it was left out despite being on the requested list.
Showmax's old `com.showmax.app` is superseded by `com.showmax.showmax.google`.
Four listings answer 404 from outside their market **whatever `gl` says**, so a
404 is not proof of a wrong id for a region-locked app; those were confirmed
against APKMirror with the developer name matching.

**Four broadcasters were a judgement call, not a lookup.** ITV, TV4 and the
Norwegian and Danish TV 2 serve news and streaming from one registrable domain,
and DNS cannot see a path. Both the streaming host and the parent are listed, in
their own section at the foot of the file, so whoever disagrees deletes lines
instead of unpicking the list. It is the one part of this that will annoy
somebody.

**PeerTube cannot really be blocked this way and the list says so.** It is
federated across thousands of instances on their own domains; what is listed is
the project's own hosts and the official app. The app id is the half that works.

### Build 27: back stopped offering to lock the phone forever

**2026-08-14, v0.2.8-dev.4, and the first of these is the worst thing found in
this file for a while.**

**Back on the key screen offered to seal the device permanently.** The reveal
shows the key and says, in `lock_reveal_not_sealed_yet`, that leaving before Done
forgets it and locks nothing. Pressing back opened a dialog whose confirming
button, *Close anyway*, called `sealWithKey` — commit the key, apply the
restrictions, sweep. So the way out of a screen telling a parent to write
something down was an offer to lock the phone forever with the thing they had
just declined to keep, and the screen contradicted itself about which was true.
Back now leaves, and the deliberate forever-lock is reached the way the Q&A
already described it: press Done without writing the key down.

The fix needed two passes, and the second is the interesting one. Stopping the
seal was one line; back then landed on the **launcher**, because `MainActivity`
finishes itself on the way into the reveal so that a sealed phone cannot have the
configuration waiting in its back stack. Back therefore starts it again
explicitly. Watched on the emulator: back returns to the configuration screen and
`parent_key.xml` holds no hash, so nothing was sealed.

**herald has no private tabs.** The long-press menu offered *Open in private tab*
and the tabs tray then showed it in the same grid, with the same card and the
same counter, as every other tab — nothing distinguished it once opened. That
entry was the only way to make one, so removing it removes the feature; the
menu's *New tab* and every incoming intent open ordinary tabs. The tray is
deliberately **not** filtered to non-private tabs, because a phone updating from
an older build can already be carrying one and hiding it would leave a tab that
exists, holds a session and cannot be reached or closed.

**Every control on the configuration screen now says "applied after lock"**, and
a line above all of them says the web filter is already running and stays on
whatever is chosen there. Choosing a disconnect philosophy used to say nothing at
all, on the reasoning that the radio moving was feedback enough — which is true
about the radio and wrong about the phone: a tick that moves says the app heard
you, not that anything changed.

**And the YouTube regression is open, with the instrumentation to close it.**
*(Diagnosed the next day and fixed on 2026-08-15 — see the section at the top of
this file. The instrumentation below is what found it.)* The
owner reported that switching *Allow YouTube* off and locking left
`com.google.android.youtube` on the Moto while `com.google.android.apps.youtube.music`
went, both preinstalled. It does not reproduce on the emulator: both hide, and
the log now proves which branch each took. Three causes remain possible and build
27 tells them apart — no `Removing …` line means `evaluate` declined, *The
platform refused to hide …* means `setApplicationHidden` returned false, and
*Unhid …* means something restored it afterwards. `hide` used to return `FAILED`
without logging, which is why nothing on the device could say.

One real bug fell out of looking: `PackageRemovalReceiver` treated
`STATUS_SUCCESS` as *gone*. Uninstalling an app that shipped with the phone and
was later updated removes the **update** and leaves the factory build installed,
and the session reports success either way. It now checks whether the package is
still there and re-evaluates, which hides it on the second pass.

### Why a branch beat a second page

The owner asked for a hidden second install page so end-to-end testing can
continue while the alpha sits still. A **`dev` branch with its own Cloudflare
preview deployment** is the better shape, and costs less.

Cloudflare Pages builds non-production branches automatically and serves each at
`<branch>.<project>.pages.dev`. So pushing to `dev` yields a complete second site
— its own install page, its own JavaScript, its own APK, its own policy — at an
address nothing links to. That beats a second page on the production site on
every axis that matters here:

- **`main` stays byte-frozen.** A second page still means committing to the
  branch every tester is served from, which is exactly what must not happen.
- **The whole flow gets exercised, not part of it.** The page, the module, the
  hash pin and the APK are the production machinery, so a dev run tests the
  thing that will ship rather than a special case of it.
- **No new code.** `build-site.py` already refuses to build unless the staged APK
  hashes to `app_update` in the signed policy — so if the dev branch carries its
  *own* `dist/policy.signed.json`, signed with the same key, that check works
  unchanged and keeps dev honest the same way it keeps the alpha honest.

Three things had to be settled before building it. All three are:

1. ~~**Confirm preview deployments are on.**~~ **Checked 2026-08-11: they are.**
   The setting is **Cloudflare dashboard → Workers & Pages → `drawbridge-project`
   → Settings → Builds & deployments → Branch control**, and it is already set to
   *All non-Production branches*. Production stays `main`. So a push to `dev` is
   served at `dev.drawbridge-project.pages.dev` with no further setup, and each
   commit also gets its own `<hash>.drawbridge-project.pages.dev` — useful for
   pinning a tester to one build rather than to whatever `dev` last became.
2. ~~**Make `policyUrl` a build-time value.**~~ **Done 2026-08-11.**
   `BuildConfig.POLICY_URL` in `dpc/build.gradle.kts`, read by
   `DrawbridgeApplication.policyConfig`, taking `drawbridgePolicyUrl` from
   `gradle.properties` and falling back to `PolicyConfig.DEFAULT_POLICY_URL`
   everywhere else. Without it a dev build polled the *alpha's* policy, so policy
   changes could not be tested at all and a dev phone would have taken live
   policy edits. With it, `dist/` on this branch is a real staging path for the
   document.
3. ~~**Keep one monotonic `versionCode`.**~~ **Settled, and it is a standing rule
   rather than a task.** A dev build must outrank the alpha's 18 to install over
   it on the Moto, and the next alpha must outrank whatever dev reached. Keep
   counting: a dev build that turns out good becomes the release, with no
   renumbering.

**Same package name, deliberately.** Device Owner binds to it permanently, so a
dev build must be `app.drawbridge.dpc` to replace the alpha on a provisioned
phone. That does mean a phone can carry the dev build or the alpha but not both,
which is the real cost of this design and the reason the Nothing Phone should
take the alpha while the Moto takes dev.

**Dev builds need no GitHub release.** The dev site serves its APK from
`site/assets/` exactly as the alpha site does, so nothing is published and
`latest` never moves.

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
- **drawbridge still cannot update itself unaided.** Self-update is a
  `PackageInstaller` session, not an adb install, and that global does not govern
  it. 0.2.5's manual
  [UpdateActivity](../dpc/src/main/java/app/drawbridge/dpc/ui/UpdateActivity.kt)
  is still the route, and it still asks the parent to pause Play Protect —
  **which the owner did on the Moto G15 on 2026-08-13, and the update installed.**
  See [0.2.5: drawbridge stops updating itself](#025-drawbridge-stops-updating-itself-and-says-why).

So: new devices can be provisioned today, on certified hardware, without an
appeal and without a rename.

### The alpha provisioned a Nothing Phone, 2026-08-12

**The second handset this project has ever managed, and the first that is not the
G15.** A059, Android 16, provisioned from the alpha install page over WebUSB
after the owner removed the Private Space and all seven accounts. Reported as
working as it should.

What that adds beyond "it worked twice": a different OEM, a different Android
version, a phone in daily use rather than a disposable target, and an install
page that until now had been exercised by the person who wrote it on the handset
he wrote it for. The alpha's own warning — *"tested on exactly one device, by the
people who built it"* — is now one device out of date, in the good direction.

**It also confirms the account finding on the way in.** Seven accounts, none of
them Google, refused; the same phone accepted the grant once they were gone.

**One observation from it, and it is a fair challenge to a design decision:** the
VPN, the DNS filter and the clock lock only came on at the *lock*, not at
install. See the next section.

### Should the filter start before the lock? Raised 2026-08-12, not decided

The owner's observation from the Nothing Phone: the VPN, the DNS filter and the
clock lock all wait for the lock, and a web filter arguably should not — it is
protection rather than confiscation, and a phone sitting provisioned-but-unlocked
is unprotected while looking finished.

**The instinct matches a line this project already drew.** herald's install was
moved out of the deferral on 2026-08-10 for exactly this reason: *"the deferral
exists to stop drawbridge taking things away before the parent has asked"*, and
installing a browser adds rather than takes. A DNS filter is the same shape — it
removes nothing, and it does not obstruct anything the pre-lock window is for
(adding the account, setting a screen lock, enabling USB debugging, moving
bookmarks; none of those touch a blocked domain).

**Two of the three original reasons no longer apply.** Enforcing inside the setup
wizard bricked a QR provision on 2026-08-07 — and the QR path is retired.
Enforcing at first launch uninstalled Facebook, pulled 470 MiB and killed USB
debugging before anyone had agreed — but that was the removals and the
restrictions, not the filter.

**And a fourth reason disappeared last week without anyone noticing.** Starting
`DnsFilterService` also starts `PackageWatcher`, which used to mean starting the
filter dragged app removal along with it. Since removal is gated on the lock,
that entanglement is gone: the filter can now start early *without* anything
being taken away. That is what makes this cheap to change rather than a
re-litigation.

**What argues against, and it is about consent rather than mechanism.** Before
the lock the parent may not have chosen a profile, so an early filter runs on the
document's default — the phone would start filtering before anyone said what to
filter. And the always-on VPN is visible: a key icon, a battery-optimisation
prompt, a phone that announces it is managed, before the parent pressed the
button that means "manage it".

**The clock lock should stay where it is** either way. `DISALLOW_CONFIG_DATE_TIME`
takes something away, and it exists to keep a curfew honest — and the curfew only
applies once locked.

**Decided the same day: always filter, from provisioning.** The owner's reasoning
carried it, and it is about consistency rather than urgency — *if the phone is
unfiltered before the first lock, then unlocking after a lock should un-filter it
too, because that is the same state.* It never did. The filter survived an unlock
while the pre-lock window had none, so one visible state behaved two ways
depending on history nobody could see. Starting at provisioning removes the seam
rather than documenting it. The deliberate act is installing drawbridge.

Built in 0.2.8 build 24: `DrawbridgeApplication.startFiltering`, called at
provisioning and on every process start. The setup-wizard guard is kept — that is
the one failure that actually happened — and the restrictions, the clock lock and
the app removals still wait for the lock.

**What should exist and does not**: a policy that says "no web filter", so a phone
without one is a choice somebody made rather than a state reached by leaving a
button unpressed. Out of scope for now, and the right shape when it comes.

### A second handset, 2026-08-12: a Private Space blocks provisioning, and the alpha installed

**Three findings from the owner's Nothing Phone (A059, Android 16), and the first
of them contradicts this file.**

**1. The alpha installed with `adb install`, first try, verifier untouched.**
`app.drawbridge.dpc` versionCode 18, hash-checked against the alpha's pin,
`Success`. No `verifier_verify_adb_installs` lever, no Play Protect dialog, on a
phone with zero Google accounts — which is exactly the condition under which the
G15 refuses the same package by name. **So the package-name verdict is not
universal.** One handset refuses it and another does not, and nothing here says
which is the exception. It could be the GMS build, the OEM, or a verdict that has
moved since 2026-08-10; a rename is no better justified than it was.

**2. Device Owner was refused for a reason nothing here had considered:**

> `Not allowed to set the device owner because there are already several users on
> the device.`

`pm list users` showed `UserInfo{10:Private space:1090}` — Android 15's hidden
profile. It does not appear in the user switcher, does not show in
`dumpsys account`, and the owner did not remember creating it. **This check runs
before the accounts check**, so a phone carrying both is refused for the users
first, and clearing the accounts earns the same refusal in different words.

Android is refusing for the reason drawbridge would: always-on VPN is per-user, so
a second profile gets unfiltered network — the same argument that makes
`DISALLOW_ADD_USER` unconditional after provisioning.

**Neither tool checked for this.** Both preflighted accounts and device owner and
nothing else, so this phone would have passed preflight, taken both APKs, and
failed at the grant with a stack trace. A users check is now the *first* thing
both do, naming Private Space and where to delete it, and the same step is in all
three install guides and on both website install pages.

**3. And with the Private Space gone, the accounts really do block — including
non-Google ones.** Seven accounts, none of them Google (three DAVx5, Telegram,
three banking apps), and `dpm set-device-owner` was refused with *"there are
already some accounts on the device"*. That settles a question this file has been
guessing at: the platform enumerates every `AccountManager` account, and the only
ones it tolerates carry a feature no ordinary app declares. Every account counts.

**Whether a Private Space is created by default is unknown**, and worth finding
out rather than assuming — the owner could not say whether they had ever set one
up. It is opt-in as far as the documentation goes; this phone had one anyway,
and Nothing OS is a skin rather than stock, so the honest answer is that nobody
here knows.

**Next time one turns up, read its creation date before deleting it**:
`adb shell dumpsys user` carries a `creationTime` per user, so a Private Space
made on the day the phone was set up and one made two years later are
distinguishable — which is the difference between "the phone did this" and "I
did this and forgot". This one was deleted before anyone thought to look.

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

### herald arrives before the lock, and accounts are left alone

**Two decisions taken on 2026-08-10, after the first full provision from the
website.**

**herald now installs at provisioning rather than at lock.** The gate on required
apps was `protectedSince`, on the reasoning that installing what the policy
requires is enforcement. It is not: nothing is restricted and nothing is removed,
a browser is added. What made the wait look necessary was the QR path, where this
ran inside the setup wizard and a ~470 MiB download competed with it — and that
path is retired.

The reason to want it early is **bookmarks**. The window before the lock is the
only time the parent has both their old browser and herald in front of them, and
locking is what removes the old one. herald arriving after the lock is herald
arriving after the bookmarks it should have inherited are gone. All three install
guides and the website now carry a step for moving them across, before locking.

Device Owner is still required — these are silent `PackageInstaller` sessions —
and an unrequested run still waits for an unmetered network.
`DrawbridgeApplication.startEnforcing` is renamed `fetchPolicyAndRequiredApps`,
because it only ever added things and the old name claimed otherwise.

**`DISALLOW_MODIFY_ACCOUNTS` was wired up and taken straight back out.** It was
requested, implemented, and then rejected on seeing the behaviour: people carry
several online accounts legitimately, and blocking all of them to stop one is the
wrong trade. It also blocks *removing* accounts, so anything signing in through
`AccountManager` would have been unusable on a locked phone.

What actually matters is `DISALLOW_ADD_USER`, which is unconditional and already
applied: always-on VPN is per-user, so a second profile would get unfiltered
network. That is the restriction the concern was really about.

Note the observation that prompted this was made on **0.2.6, which predates the
change** — so it was never evidence the restriction failed, only that it was not
in the build. The install guides' line about account changes closing at lock is
gone with it.

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

### App removal followed nothing, and now follows the lock

**Found by the owner on the reference phone, 2026-08-12: "drawbridge removes apps
even when not locked. That's not the point. A person needs to be able to still
move out his contents."** Correct on every count, and the code was worse than the
report — removal was gated on *nothing at all*.

`AppBlocker.evaluate` had no protection check. `PackageWatcher` lives inside
`DnsFilterService`, which deliberately keeps running after an unlock so the phone
stays filtered, so its install receiver and its fifteen-minute sweep went on
uninstalling from an unlocked phone. The only gate anywhere was in
`MainActivity.sweep`, and it asked `protectedSince != 0` — *has ever been
locked*, which is true forever after the first lock and therefore never reopens.

Now `evaluate` declines every package unless `ParentKey.isLocked`, which is the
one choke point all three callers pass through. Removal joins USB debugging as
the second thing keyed on the lock rather than on protection; the filter, safe
boot and the multi-user restrictions stay on through an unlock, because dropping
those would leave an unlocked phone unfiltered rather than merely open.

**Locking still removes immediately**, and that part needed its own line:
`LockActivity.sealWithKey` now runs a full sweep after `ParentKey.commit`. It
cannot be left to the watcher — the watcher's startup sweep runs from
`lockDevice`, which is *before* the parent has decided to keep the key, so it
would find an unlocked phone and do nothing, and the next pass is fifteen minutes
out.

**The cost is real and belongs in the same breath.** Other browsers are removed
because a browser with its own encrypted DNS routes around a DNS-only filter, so
an unlocked phone carrying one is filtered less than it looks. That is now the
deliberate shape of the unlock window rather than an oversight.

**It also unblocks the browser testing the owner asked for**: unlock, install the
browsers to be tried, test them against the filter — which stays on — then lock,
and the sweep takes them away.

The configuration screen stops claiming otherwise. It only exists while unlocked,
so its toast no longer says "0 apps removed"; it says the change lands when the
phone is locked, in all three languages.

Covered by `AppBlockerLockGateTest`, including the specific case that was broken:
locked, then unlocked, with `protectedSince` still set.

### Shorts become ordinary videos in herald, and tier 1 turned out to be a no-op

**Built 2026-08-12, in herald, and not yet shipped** — see the release note at the
end of this section, which is the part that matters for planning.

`Shorts.redirected` rewrites `youtube.com/shorts/<id>` to
`youtube.com/watch?v=<id>`, and the bare `/shorts` feed entry point to the site
root. A Short and a normal video are the same video; what differs is the player
around it, a vertical feed that advances by itself. So the clip somebody sends
you still opens and still plays, in a player that ends and stops, and the feed
cannot be opened on purpose.

It sits in `HeraldRequestInterceptor` next to `SafeSearch.enforced`, which is the
same shape of rule — a URL that should have been another URL — so it is plain
JVM code with eleven tests and no Robolectric.

**The policy-pattern version was asked for and cannot work**, which is worth
recording so nobody tries it again. `browser.blocked_url_patterns` exists,
`ContentFilter` compiles it, and `isUrlBlocked` consults it — but only after
returning **false** the moment an allowed domain matches. Allowing YouTube is
exactly what puts `youtube.com` in `allowed_domains`, so a `/shorts/` pattern
would never be reached in the one state where it would matter. And a blocked URL
is checked before any rewrite, so even if it fired it would pre-empt this and
turn a shared link into a wall. Both intents are rewrites now.

**What it does not reach, and this was decided rather than overlooked**: the
YouTube app, which the same option restores, and the four other browsers the
policy allows. Shorts are untouched in all of them. The owner's call on
2026-08-12 was that the app stays allowed — people pay for YouTube and want the
ad-free experience they are paying for — so this is a reason to prefer herald,
not a property of the phone. Do not let the option's wording imply otherwise.

**It ships with the next herald release, which is not cheap.** herald is still
0.1.9 / versionCode 9 and has deliberately not been rebuilt since, because every
rebuild changes six 230 MB APKs, every hash in `required_apps`, and forces a
policy re-sign. This change is worth carrying until there is a second reason to
cut a herald release; it should not be the only one.

### Build 25 and herald 0.1.11: four bugs a phone found in a day

**2026-08-13, v0.2.8-dev.2.** Every one of these was invisible to 522 unit tests
and obvious within minutes of using the previous build. Worth reading as a set,
because three of the four are the same mistake: a rule checked against the one
case that happened to work.

- **herald polled the alpha's policy on a dev phone.** It read `PolicyConfig`'s
  default while drawbridge read its channel's, so one handset ran two apps
  against two documents, one unblocking what the other still blocked. The dev
  channel had this from the day it was built: the plumbing went into the DPC and
  never into herald.
- **Allowing an app did not bring it back.** `restoreNowAllowed` subtracted
  `blocked_packages` from its own candidates, and **an option exempts a package
  without unlisting it**, which is the shape of *every* option here. So the
  subtraction removed exactly what the toggle had just allowed. It could only
  ever have worked for Chrome, which is allowed and happens not to be blocked by
  name, and Chrome is the case that was checked when it was written.
- **The restore only ran inside a sweep** the configuration screen skips while
  unlocked, which is the only state that screen is ever in. So even correct, it
  would not have run.
- **Shorts did not unwind when tapped inside YouTube.** The Kotlin rewrite is
  exact and never fired: GeckoView reports *navigations*, and a tap in the feed
  is a `history.pushState`. It worked only for a typed URL or a link from another
  app, which is not how anyone meets a Short. `shorts.js` was added to watch
  `location` from inside the page — **and that fix did not work either. See
  below.**

**The lesson, since it has now cost four bugs in one build:** a rule verified
against a single example is verified against nothing. Both blocker rules are pure
functions with tests now — `actsNow` and `restorable` — and the test for
`restorable` asserts the case that was broken rather than the one that worked.

### The Shorts fix shipped twice before it worked, 2026-08-14

**The owner reported it a third time: tapping a Short inside YouTube still left
`/shorts` in the address bar and still opened the infinite feed.** 0.1.11's
`shorts.js` hooked `history.pushState` and reacted when YouTube called it. It
never fired once, and could not have.

**A content script runs in an isolated world with Xray vision.** Assigning to
`history.pushState` from one replaces it *for the content script only*; the page
goes on calling the original. MDN says so plainly, and it is the first thing its
page on content scripts explains. So the wrapper sat in herald for a release,
waiting for a call that by construction never arrived, and the only line in that
file that ever ran was the one-off pass at `document_start` — the case the Kotlin
side already covered. **The build did nothing new whatsoever.**

`shorts.js` now polls `location.href` every 250ms while the tab is visible, and
rewrites when it moves. `location` is the document's own state rather than a page
object, so the isolated world sees every change to it, `pushState` included. The
alternative was `window.eval` or `exportFunction` to reach the page's real
`history`, which means running our code in YouTube's world under YouTube's CSP,
for a rewrite that has to keep working.

**Verified on a device this time, twice over**, on the API 36 emulator with
drawbridge as Device Owner and the YouTube option on: tapping the Shorts tab in
the bottom bar landed on `m.youtube.com/watch?v=6J_3xJVXUx8`, and tapping a Short
in a search-results shelf landed on `/watch?v=6kS…`, both in the ordinary player
with a recommendation list under it and no vertical feed. Two independent in-app
routes rather than one, because this file's own lesson is that one example is no
example.

**Nothing catches this class of bug in the test suite.** The rewrite arithmetic is
tested in `ShortsTest`, and it was never the arithmetic that was wrong — both
times the correct string was computed by code that never ran. The only check that
would have failed is one that runs the extension in a page, which nothing here
does. Treat any future change to `shorts.js` as unverified until a phone or an
emulator has opened a Short.

### Browsers are removed even when unlocked, and there are now three of them

**2026-08-12, reversing part of the rule set two days earlier — deliberately, and
only part.** App removal follows the lock; **browsers do not**. They go whether
the phone is locked or not.

The reason is that a browser is not one more blocked app. The filter is DNS-only,
so anything tunnelling over 443 to an unnamed host is invisible to it, and
several browsers ship that as a feature: Opera's "VPN" is an in-browser proxy
rather than an Android `VpnService`, so `DISALLOW_CONFIG_VPN` never sees it. A
browser surviving an unlock is the filter switched off on a phone that still
claims to be protected. Migrating data does not need a browser, so the unlock
window survives intact for everything else.

**Chrome and Firefox Focus are now allowed**, alongside herald and herald mono.
One browser was never going to be enough, and some sites do not render on Gecko
at all. Firefox is out because it offers a VPN; Focus does not, and Chrome has
neither extensions nor a proxy on Android.

**Policy 41 also names thirty proxy, Tor, VPN and DNS-changer packages**, each id
verified against the Play Store — four in the first draft were wrong and were
dropped rather than guessed at, because a wrong package id is invisible. Note
most consumer VPNs there were already dead on a locked device, since
`DISALLOW_CONFIG_VPN` stops a second VPN being configured at all. The ones that
matter are the apps that proxy inside themselves.

**Hidden apps can now come back.** A preinstalled browser is hidden rather than
uninstalled, and nothing reversed that except complete removal — so allowing
Chrome would have left every phone that had already hidden it hidden forever.
`restoreNowAllowed` unhides what the policy names explicitly. It cannot be
generalised: a hidden app answers no intent queries, so asking whether it is a
browser means unhiding it, which would hide it again fifteen minutes later.

**Vivaldi and Ecosia were added on the same day**, so the list is herald, herald
mono, Chrome, Focus, Vivaldi and Ecosia — a choice rather than one imposed
browser. And **herald is the default link handler**, which is no longer cosmetic
now that five browsers can coexist: `addPersistentPreferredActivity` is a Device
Owner API, so a tapped link opens in herald and Settings cannot change that. The
others still open when launched directly.

While fixing that, a quiet lie came out: `allowed_browser_package` has been in
the policy document since the beginning and **nothing read it**. The DPC used a
`BuildConfig` constant that happened to hold the same value, so editing the
document changed nothing. It is read now, with the constant as the fallback.

**Consumer VPN apps were tested by the owner on 2026-08-12 and do not work**:
they cannot change the VPN, because `DISALLOW_CONFIG_VPN` stops a second one
being configured and Android runs one at a time. Their blocklist entries are
defence in depth rather than the mechanism.

**Three questions this raised, all answered by the owner on 2026-08-12:**

- **Chrome's secure DNS** — accepted as inert here. Note that is the owner's
  call rather than a hardware test written up in this file, and the reasoning
  above still holds: one of the three mechanisms stopping browser DoH is
  Chromium's own, and it is re-decided at every update.
- **Nothing re-locks the phone** — left alone for now, deliberately. The browser
  rule covers the case that mattered.
- **A block page for other browsers** — dropped, because it is not buildable
  without TLS interception. herald stays the recommended browser instead, which
  is what the default-handler change above is for.

**Two things this does not settle:**

- **What a blocked site looks like in the other five.** herald shows the
  policy's block page; every other browser shows its own DNS error. Parents
  should be told what that looks like, since "the internet is broken" and "this
  site is blocked" are the same screen in Chrome.
- **Whether any of the four new browsers has been run on a managed device.**
  None has. The allowlist is a claim about what they do not carry, checked
  against their feature lists rather than against a phone.

### Diagnostics now says why the policy is what it is

**2026-08-12.** Policy 37 was published and the phone reported 36 in both apps.
Nothing was wrong — `raw.githubusercontent.com` sends `max-age=300`, so an edge
served the previous document for five minutes — but **there was no way to tell
that from the phone.** The manual check's toast is transient and binary, and a
stale success, a failed refresh and a poll that never ran all look identical
afterwards.

`StoredState` has recorded `lastCheckMillis`, `lastSuccessMillis` and `lastError`
on every refresh since the beginning, and Diagnostics printed none of them. It
now prints all three, plus the policy URL — which also makes it obvious at a
glance whether a handset is on the dev channel or the alpha.

The manual check now sends `Cache-Control: no-cache`; the scheduled poll does
not, since it runs every three hours and a five-minute window costs it nothing.
**Whether the CDN honours it is unverified** — some deliberately ignore
request-side no-cache to stop cache-busting — so it is a polite ask rather than a
mechanism, and worth watching on a device before anyone relies on it.

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

**And the whole flow has now been walked on a phone — 2026-08-13.** The owner
updated drawbridge on the Moto G15 using the built-in path exactly as written:
press the button, pause Play Protect, install. It worked. Until then this route
was a design that had been reasoned about rather than one anybody had completed
end to end on hardware, and the difference matters, because every other Play
Protect finding in this file arrived by surprise.

What that does and does not settle. It does **not** lift the refusal: pausing
Play Protect was still necessary, so the verdict on the package stands and the
five rounds above are not reopened. What it settles is that the fallback the
project chose actually delivers a new build to a deployed phone, with no cable,
by a parent following on-screen instructions. Read alongside the adb path, which
needs a computer, this is the route for a phone already in someone's hands.

**Twice now, on consecutive releases.** Build 26 reached the Moto the same way on
2026-08-14, hours after v0.2.8-dev.3 was published, and the owner reported the
phone working. So this is the normal way a dev build travels rather than a thing
that happened once.

**And the two things in it were watched working on that phone the same day.**
Disney+ was refused with the streaming option off and came back when it was
switched on, so the category and its switch are confirmed on hardware rather than
only in the document. Shorts unwind there too, which is the first time that fix
has been seen working anywhere except the emulator — and the third build to
attempt it.

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
survives every lock. The key itself lives in `keystore.properties` on the build
machine, which is git-ignored and **not backed up** — the same risk as the two
signing keys, and it belongs in the same offline copy.

**It ships through the alpha, by the owner's decision on 2026-08-11.** This file
used to say "do not ship a build carrying one to anybody else"; that is overruled
for now, and the reasoning is worth recording because it turns on facts that are
easy to get wrong in both directions.

*What it is not:* a hole a stranger can walk through. The APK carries only the
SHA-256 of a twenty-character Crockford key — a hundred bits — so downloading the
public build from the website yields nothing. Every published release since the
key was introduced has carried it, verified against the dex of v0.2.7 and of the
APK the site serves.

*What it is:* a single key that opens every phone running such a build, held in
one unbacked-up file on one machine. The exposure is entirely the secrecy of that
file. If it leaks, every device running an alpha build is unlockable by whoever
has it.

*What it buys:* a way back into a tester's phone when something goes wrong —
which has already happened once, when the reveal screen sealed the reference
device with a key nobody had read.

**Two things follow, and neither is optional.** The key belongs in the offline
backup alongside the signing keys, because losing it now costs a tester's handset
rather than an afternoon. And this stays an alpha-only measure: the sanctioned
answer is still the delayed self-removal on the roadmap, and the day a phone
belongs to someone who is not a knowing tester is the day the build must stop
carrying it.

**Note the build already announces it.** `DiagnosticsActivity` prints
`emergency key: true`, and Diagnostics is reachable from the lock screen's
overflow without the key — so a tester who looks will find out that a second
route exists, though never its value. That is deliberate and should stay:
somebody discovering an undisclosed back door is a far worse outcome than
somebody reading a line they were told about.

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
  being the only one on the device — and nothing stops a child adding their own,
  since `DISALLOW_MODIFY_ACCOUNTS` is deliberately never applied. Weaker still
  than that: FRP turned out not to be armed at all, see
  [FRP does not protect this phone](#frp-does-not-protect-this-phone-and-never-did).
- **The protected-since date**, which is why the clock is now locked on every
  device: winding it back a year, locking, and winding it forward forges a year
  of protection that never happened.

Putting the restriction back is on the roadmap, but **only behind a working
delayed self-removal** — the two ship together or not at all.

### The browser installer exists, and the USB half works — on one handset

**Built 2026-08-10.** `/install/usb/` provisions a phone from Chrome or Edge over
WebUSB — the same sequence as `tools/provision-adb.sh`, with the same promise
about `verifier_verify_adb_installs` being restored on every exit path.

**This heading said "the USB half is untested" until 2026-08-12**, by which point
the section under it recorded the transport working from the deployed site, the
whole path provisioning a phone end to end, and the alpha being installed this
way by everyone who has one. A heading is the only part of a long section most
readers take away, so it is worth correcting even when the body is right.

Two decisions worth not re-litigating:

- **The APK is served from the site**, named after its own hash
  (`/assets/dpc-<sha16>.apk`), and is committed. GitHub's release downloads carry **no `Access-Control-Allow-Origin`
  header** — measured through the redirect to `release-assets.githubusercontent.com`
  — so a browser `fetch` of them is blocked outright. Hosting it here is also the
  only version that keeps the page's no-third-party-requests property.
  `.gitignore` only excludes `dist/release/*.apk`, and that rule is about
  herald's 230 MB rather than about APKs in principle.

  **The hashed filename is not cosmetic.** The page carries the expected checksum
  inline and refuses anything that does not match it — and with a stable
  filename, page and APK are two URLs with two cache lifetimes. A page held in a
  tab from before a release fetches the *new* APK and refuses it, which reads as
  a corrupt download rather than a stale page. That happened on 2026-08-10, on
  the 0.2.6 → 0.2.7 release, to the owner. Content-addressing turns it into a
  404, which the page can name exactly: reload. A checksum mismatch now means
  what it says.

  **Except that Cloudflare Pages does not return 404 for a missing asset.** It
  serves the site's HTML error page with status **200**, measured on 2026-08-10 —
  so the page's 404 branch is largely decorative there and what actually catches
  a stale page is the checksum failing against 7 KB of HTML. That is why the
  mismatch message tells the reader to reload as well; it is the branch that
  fires. Do not remove that sentence on the grounds that the 404 handler covers
  it.
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

**It updates as well as provisions**, which is the half that matters for a phone
already in someone's hands: Play Protect refuses drawbridge's own installs, so a
cable is how a fix arrives. The page detects which it is doing rather than asking
— drawbridge owns the phone or nothing does, so offering the choice would only be
a chance to get it wrong — and says which it picked before it changes anything.
A device owner that is *not* drawbridge is refused outright.

Only provisioning checks for accounts. It is `dpm set-device-owner` that Android
refuses while an account is present, not the install, so an update runs happily
on a phone carrying whatever account its owner chose. An update also needs the
phone **unlocked**, which is not a check the page makes: a locked phone has no
USB debugging, so it never reaches the page at all.

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

- ~~**`lockAccounts()` is dead code, and a doc promises otherwise.**~~ **Resolved
  2026-08-10, though not the way this entry expected.**
  `DISALLOW_MODIFY_ACCOUNTS` had never been applied on any device:
  the function existed, `unlockAccounts()` ran during removal, and nothing ever
  called `lockAccounts()` — while all three install guides told parents that
  account changes close at lock. Confirmed on hardware on 2026-08-08, when an
  account was added to a *locked* G15 and nothing objected.

  It was then keyed on the **lock** rather than on `protectedSince`, alongside
  USB debugging — and **taken straight back out the same day**, on seeing the
  behaviour: people legitimately carry several accounts, and it blocks *removing*
  one as well as adding it. See
  [accounts are left alone](#herald-arrives-before-the-lock-and-accounts-are-left-alone).
  `lockAccounts()` is gone either way, and the install guides' claim went with it.

  **There is nothing here left to watch on a device.** `restrictionsFor` never
  adds `DISALLOW_MODIFY_ACCOUNTS` in either state, and
  `DeviceOwnerRestrictionsTest` asserts exactly that, locked and unlocked. An
  earlier version of this entry said no phone had yet been seen refusing an
  account after a lock; none ever will, because that is not what the build does.
  **Corrected 2026-08-12**, having claimed a live restriction for two days after
  it was removed.

  What does the work instead is `DISALLOW_ADD_USER`, which is unconditional and
  already applied: always-on VPN is per-user, so a second profile is what would
  have got unfiltered network.

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
[the cable is now repeatable](#and-the-cable-is-now-repeatable-because-usb-debugging-follows-the-lock).
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

### 3. ~~herald must force safe search everywhere, or refuse the engine~~ — done, with one code fix left

**Both halves are done.** herald refuses what it cannot force — Brave, Startpage
and Qwant were dropped on 2026-08-10 — and **policy 37, on `dev`, blocks the
engines it does not offer**, because dropping an engine from a browser's list
never made it unreachable: it is still a website, and its name typed into the
address bar reached it unfiltered. `dist/lists/search.txt` covers the three
dropped, the majors never offered, the independents, and the ones marketed on not
filtering.

**The find that mattered more than the tail.** The engines that *are* allowed had
unforced front doors: `html.duckduckgo.com`, `lite.duckduckgo.com`,
`start.duckduckgo.com`, `cn.bing.com`, `www4.bing.com`, `encrypted.google.com`
and `images.google.com` all resolve and match none of `DnsFilter`'s rewrite
rules, so each was the allowed engine with its safe hostname skipped — including
the default one. They are blocked in the policy rather than fixed in `DnsFilter`
deliberately: **a list entry reaches a phone in three hours and a code change
needs a release Play Protect will not let a deployed phone install.** That trade
is worth remembering for anything else of this shape.

**Still open, and it needs a build:**

- **`SafeSearch` and `DnsFilter` are documented as mirrors and are not.**
  `SafeSearch.isGoogleSearchHost` folds an `images.` prefix;
  `DnsFilter.safeSearchTargetFor` does not. The KDoc at
  [SafeSearch.kt](../herald/src/main/java/app/drawbridge/herald/search/SafeSearch.kt)
  says to change the two together, and nobody has. Small, and exactly the drift
  this project keeps finding.
- ~~**Ecosia rests entirely on a parameter herald puts back**, with no DNS
  rewrite behind it, so an in-page second search may not be enforced.~~
  **Closed on 2026-08-15 by dropping the engine** — see the Ecosia section at the
  top of this file. The in-page gap was the smaller half of it: the parameter
  reached only herald, and the policy allows three other browsers.
- **The tail is not closeable.** Anyone can run a SearXNG instance in five
  minutes. See [blocklist-notes](blocklist-notes.md) for what was left out and
  why — public instances, AI search, archive sites, the translate proxy.

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

### 7. ~~The curfew~~ — built 2026-08-12, as three philosophies

**Done, and shaped by the owner's spec rather than by the old draft.** The
configuration screen gained a **Disconnect philosophy** section *above* Policy,
with three choices: *Always blissfully offline* (calls, SMS, GPS and FM radio
only), *Sadly always online*, and *Curfew for the internet*. Choosing the curfew
reveals two editable windows — Monday–Friday and Saturday–Sunday — defaulting to
**21:00–08:00**.

The mechanism was already there and needed no invention: the always-on VPN's
lockdown flag, `CurfewController`, and `Curfew`'s window arithmetic. What was
missing was everything around it — the manifest `<receiver>`, the boot re-apply,
the settings store, the screen, and a caller.

**Three things worth knowing before touching it:**

- **The schedule is device-local, not policy.** Hours belong to a household; a
  document signed by this project cannot carry them for somebody else's
  teenager. `Policy.curfew` still parses and is now a *suggestion* nothing
  enforces.
- **Nothing is exempt from the lockdown, including drawbridge.** The first
  version of this kept the DPC's own package out of it so a phone could still
  poll — reasoning that belonged to a design where the schedule came from the
  signed document, and that did not survive the schedule becoming device-local
  in the same commit. **The owner caught it**: the way back online is unlocking
  and changing the setting, which needs no network. So offline means offline,
  and the screen's promise is literally true.

  The policy going stale on a permanently offline phone is harmless — the
  blocklists filter traffic and there is none. A curfewed phone polls in its
  online hours, and a refresh is asked for the moment it comes back, at the
  morning boundary or at an unlock, rather than waiting up to three hours.
- **It follows the lock, like the app blocker** — not `protectedSince`, which is
  what the first version used. An unlocked drawbridge is a parent working on the
  phone, and installing something, moving data off or trying a browser all need
  a network. It goes dark again at the next lock. The clock lock stays keyed on
  protection, since a child does not stop being able to wind the clock forward
  because a parent is mid-setting.

**Bluetooth tethering is covered, and USB ethernet cannot be spared.** Lockdown
is a rule about the user rather than about a network, so it catches Wi-Fi, mobile
data, a second phone's Bluetooth tether and USB ethernet without naming any of
them. The requested *"always allow ethernet over USB"* toggle is **not
buildable**: the only exemption `setAlwaysOnVpnPackage` accepts is a set of
package names, and no per-transport carve-out exists in the Device Owner API.
Exempting particular *apps* is expressible, but that leaks on every transport
rather than one, which is a different and worse thing.

**First run on hardware, 2026-08-12, and it half worked.** Always-offline works.
The curfew goes offline at its boundary and **did not come back online** at the
morning one — which is the failure direction that matters, and the one everything
here is supposed to be designed against. Also reported: changing only the *times*
did not take effect until the philosophy was re-selected.

Neither is explained by reading the code, so two things were built rather than a
guess being shipped:

- **`CurfewWorker`**, a periodic re-evaluation every fifteen minutes,
  deliberately **unconstrained** — every other worker here waits for a network,
  which is precisely wrong for the one that repairs *having no network*. The
  alarm stays the primary mechanism because it is punctual; this makes a missed
  boundary late rather than permanent, and it self-heals both reports.
- **Diagnostics now prints the curfew's own state**: mode, both windows, whether
  it should be offline now, and the **next boundary**. That last line is the one
  that will settle it — a missing next boundary is an alarm that was never set,
  and no amount of reading the source can tell you which happened on a device.

**Build 21: the curfew lifted.** Reported by the owner on 2026-08-12 — the phone
came back online at its boundary, which is the half that failed on build 19.

**Which mechanism did it is unknown, and the difference matters.** A lift exactly
on the boundary is the alarm working; a lift up to fifteen minutes late is
`CurfewWorker` carrying a broken alarm and nobody noticing. Diagnostics'
`next boundary` line plus the time it actually came back separates them. Worth
one deliberate look before treating the alarm as sound, because a phone that
depends on the backstop is one OEM battery policy away from a curfew that lasts
until someone opens the app.

Still unconfirmed: whether calls and SMS survive the lockdown on a real network.

**The lock screen now shows what the phone is set to** — the policy name and the
disconnect philosophy, with the curfew's hours spelled out. Asked for by the
owner, and it earns its place: those hours are what gets questioned at half past
nine, and reading them used to cost the key, which mints a new one.

### 7a. The old curfew note, for the reasoning

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

### 8. ~~A dev branch~~ — built on 2026-08-11

**Done, and this entry is kept only so the roadmap's numbering does not move.**
The channel is described under
[the dev channel](#the-dev-channel-exists-on-the-dev-branch): `dev` on a
Cloudflare preview deployment at <https://dev.drawbridge-project.pages.dev>,
carrying its own signed policy, with `policyUrl` a build-time value so a dev
build stops polling the alpha's document.

That also answers the question this entry used to leave open — whether the
*policy* gets a channel of its own. It does: a dev build polls `dev`'s copy of
`dist/policy.signed.json`, so the document has a staging path for the first time
rather than being edited live on the branch every device fetches.

**What exists is the plumbing, not the proof.** No dev APK has been built, no dev
policy has been re-signed on this branch, and no phone has ever run a build from
it — so the first real use of the channel is still ahead, and it is what would
find any mistake in it.

**Nothing blocks that.** `dev` and `main` are the same code today and serve the
same APK, so putting the Moto on the dev channel costs nothing and changes
nothing about what it runs. The Moto is a test phone rather than a handset anyone
depends on, and it already runs the current build. The one-channel-per-phone rule
below is a real constraint on the day the two branches diverge, not a reason to
wait now.

### 9. Lock factory reset — last, and only with the timer

**The prerequisite exists as of 2026-08-17**, and is not quite what this entry
assumed: what was built is a delayed *unlock* rather than a delayed self-removal,
which answers the same problem because removal lives behind the lock. See the
lock-timer section at the top of this file. Everything else in this entry stands.

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

### 12. herald mono: take out always-on reader view

**Asked for 2026-08-17, from use.** `Edition.autoReaderView` is `isMono`, and
`ReaderViewIntegration` enters reader view on every page Gecko calls readerable.
It is not working out:

- **pages hang in a loop** — reported from the phone, and the likeliest shape is
  the entry racing a load that has not settled, so entering re-triggers a load
  that is entered again;
- **slow pages never get there**, because readerability is decided on a document
  that has not finished arriving;
- and when it does work it is still the wrong default often enough to be noticed.

Removing it is small: `autoReaderView` goes, `ReaderViewIntegration` keeps the
manual button, and mono keeps greyscale, one tab and the load pause. Reader view
remains available on request, which is where it started — see
[reader-view-back](reader-view-back.md), which is worth reading first, since this
feature has been rebuilt once already for a different reason.

**The replacement idea is the owner's and is worth taking seriously rather than
dropping the goal**: a *lower scrolling speed*. Mono's thesis is friction, not
stripping — `loadDelayMillis` is the same idea already shipped — and a slower
fling is friction that no page can fight, whereas reader view depends on a
Readability pass that either works or leaves the reader worse off. Note it is a
Gecko-side scroll behaviour, so scope it before promising it.

### 13. A copy pass over the app, then the website

**Asked for 2026-08-17.** The strings have grown by accretion — three languages,
several features added in a week, and each one written in the moment. They want
reading end to end for consistency of voice, length and terminology: `values`,
`values-nl`, `values-fr`, and the ⓘ dialogs in particular, which are the longest
text in the app and the least re-read.

Do the app first and the site second, so the site can quote what the app actually
says. The website half is item 11 above, which lists what is out of date there;
this is the same pass carried through to `tools/build-site.py`.

### 14. Cut this file down

**Asked for 2026-08-17, and overdue.** This handoff is past 4,800 lines and
carries a full narrative of things that are settled: Play Protect, the QR path,
FRP, several bugs fixed in builds nobody runs any more. It is meant to be *what a
new person needs to pick this up*, and at this length it hides that.

The shape that would work: keep the top sections (current state, what is
untested, next steps), move settled investigations into `design-decisions.md`
where they belong as *why the code is like this*, and delete the rest — git
history has it if anyone wants it back. **Do not summarise the traps section
away**; that one earns its length, and every entry in it cost a day.

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
