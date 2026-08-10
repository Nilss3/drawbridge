# Provisioning a device

drawbridge needs **Device Owner** status. That can only be granted on a device
with **no accounts on it at that moment** — which is a removable condition, not a
permanent one.

**No factory reset is required.** This file used to say otherwise, and it was
wrong. Measured on the reference Moto G15 on 2026-08-10: a phone reporting
`user_setup_complete: 1`, in use, with a Google account signed in, had that
account removed in Settings and `dpm set-device-owner` succeeded immediately.
Nothing was wiped. The account was then signed back in.

So the flow on an already-configured phone is **remove accounts → provision →
sign back in**, and the parent keeps their photos, messages and apps.

> **One thing does get removed.** Locking drawbridge uninstalls every app in
> `blocked_packages`, immediately, and turning an option back on afterwards does
> not reinstall them. On a fresh phone that is a no-op; on a phone in daily use
> it is the change the owner will actually notice. Say so before they start.

There are two ways in. **On a Google-certified handset only one of them
currently works**, and it is adb — Play Protect refuses to install the DPC, and
the QR wizard has no way round that while adb does. On a device without Play
Protect both should work, and the QR one gives a cleaner phone.

> Looking for a guide to hand to a parent rather than a developer? Use
> [install.md](install.md) — available in
> [English](install.md), [Nederlands](install.nl.md) and [Français](install.fr.md).

## Before you start

Three gates are easy to confuse, and only one of them cares about accounts:

| Gate | Mechanism | Accounts matter? |
|---|---|---|
| Installing the APK | Play Protect verification | **No.** Refused with zero accounts and with one alike; `verifier_verify_adb_installs` is the lever either way |
| Granting Device Owner | Android platform check | **Yes**, and this is the only one |
| QR provisioning | Play Protect, at install | No — it fails at the first gate |

Measured on 2026-08-10 with one account signed in: the APK installed normally
once the verifier was paused, and `dpm set-device-owner` then threw
`Not allowed to set the device owner because there are already some accounts on
the device`. Two different subsystems, and only the second is about the account.

Then the order, which matters:

1. Remove every account.
2. Provision drawbridge (below).
3. Add **the parent's** Google account back — and only the parent's.
4. Run setup in the drawbridge app and lock it.

Keep the child's account off the device. Note that the reason given here used to
be Factory Reset Protection, and **that reason is void**: FRP is not armed on a
fully managed device by default, and a Settings reset does not trigger it
whatever accounts are present — tested on the G15 on 2026-08-10, which was reset
and never asked for the account. See
[design-decisions](design-decisions.md#drawbridge-does-not-prevent-a-factory-reset).
The remaining reasons are ordinary ones: the child's account is a Play Store that
is not the parent's, and account changes close at lock.

## Method 1 — adb (the only method that works on a certified device)

Use `tools/provision-adb.sh`. It installs both apps and grants Device Owner:

```bash
tools/provision-adb.sh
```

`--serial` picks a device when more than one is attached, `--abi` overrides the
detected ABI, `--no-herald` skips the browser, `--mono` adds herald mono, and
`--dry-run` prints the sequence without touching anything. It refuses to start
if the device has any account on it or already has a device owner, because both
fail *after* the APKs are pushed and the error is less legible than this one.

### Why it is a script and not three commands

The three commands underneath it are still these:

```bash
adb install dpc-release.apk
adb install herald-arm64-v8a-release.apk
adb shell dpm set-device-owner app.drawbridge.dpc/app.drawbridge.dpc.admin.DrawbridgeDeviceAdminReceiver
```

**On a Google-certified handset the first one fails.** Play Protect refuses
`app.drawbridge.dpc` by name, with `INSTALL_FAILED_VERIFICATION_FAILURE`, on a
device with no account signed in and nothing else installed. See
[handoff](handoff.md).

adb has one lever that the QR path does not:

```bash
adb shell settings put global verifier_verify_adb_installs 0
```

That global decides whether adb installs are put to the verifier at all. It is
writable only from a shell, which is precisely why the QR wizard cannot do the
equivalent and this method can. Measured on the Moto G15 on 2026-08-10, with the
same APK each time:

| `verifier_verify_adb_installs` | `adb install app.drawbridge.dpc` |
|---|---|
| unset (platform default) | `INSTALL_FAILED_VERIFICATION_FAILURE` |
| `0` | installs |
| `1` | `INSTALL_FAILED_VERIFICATION_FAILURE` |

Restoring it afterwards leaves the **installed** copy alone: Device Owner
survives, `provisioningState` stays 3, and the app runs. Play Protect's verdict
is applied at install time and is not re-litigated against what is already
there.

### What the script guarantees about that setting

Turning the verifier off is a real reduction in a device's protection, so the
script treats putting it back as the thing it must not get wrong:

- the *original* value is read first, and `null` — never written, platform
  default applies — is restored by deleting the row rather than by writing a
  value, so the phone is left as it was found;
- the restore runs on every exit path: success, any failed install, and Ctrl-C;
- it happens **before** Device Owner is granted, since nothing after the pushes
  needs it;
- and if the restore cannot be confirmed, the script says so loudly and exits
  non-zero rather than printing a success message.

The window is the length of two `adb install` calls. Once the phone is locked,
`DISALLOW_DEBUGGING_FEATURES` removes USB debugging altogether and no adb install
is possible on that handset — until somebody unlocks drawbridge with the parent's
key, which hands it back. See
[design-decisions](design-decisions.md#usb-debugging-follows-the-lock-not-the-protection).

**The parent never touches Play Protect.** That matters: the alternative doing
the rounds is to have them switch Play Protect off in the Play Store, which is
device-wide, indefinite, and asks somebody to disable a protection they should
not be disabling on a child's phone.

If it fails with "Not allowed to set the device owner because there are already
some accounts on the device", remove every account in Settings first — or factory
reset.

**Release builds disconnect adb at lock**, not at provisioning — the restrictions
land when the parent locks the phone, and USB debugging goes with them. That is
the point: it closes the adb removal route on a phone in a child's hands.

**Unlocking hands it back**, which is the supported way to put a new build on a
deployed phone:

```bash
tools/provision-adb.sh --update
```

Unlock drawbridge with the parent's key, re-enable USB debugging in developer
options, run that, then lock again. drawbridge cannot update *itself* — Play
Protect refuses its `PackageInstaller` session as well — so this is the delivery
channel. Debug builds skip the restriction entirely so the device stays testable.

## Method 2 — QR code (devices without Play Protect)

On a factory-reset device, tap the welcome screen **six times** to open a hidden
QR scanner. The device then downloads, verifies and installs drawbridge and
grants Device Owner, all before any account exists.

> **This does not work on a Google-certified handset today.** The wizard has to
> install `app.drawbridge.dpc`, Play Protect refuses that package by name at
> install, and the wizard has no way to switch the verifier off — it is running
> before anyone can reach a shell or a Settings screen. What you see is
> *"this device belongs to your organization"* followed by **"Something went
> wrong"** and a factory reset. Verified on the Moto G15 on 2026-08-10, on the
> byte-identical build that provisioned the same phone three days earlier.
>
> It should still work on a device with no Play Protect — LineageOS, /e/OS,
> GrapheneOS — where nothing verifies the install. **That is untested.** Use
> adb until it is.

**Prefer this where it works.** It replaces the consumer setup wizard rather
than running after it, and the difference is visible: the OEM's *downloaded*
preloads never arrive at all. On a Moto G15 the adb route produced a phone
carrying Temu, LinkedIn, Fitbit and a handful of games, none of which drawbridge
blocks; the QR route produced a phone without them. Preloads baked into the
system image — Facebook, on that handset — are still there, and are removed by
the app blocker in the usual way.

That advantage is the reason to keep the QR path alive rather than retire it: a
phone provisioned over adb carries preloads a QR-provisioned one never receives,
and no policy removes them because none of them is blocked.

### What the managed setup skips, and what you must do by hand

The managed flow is much shorter than the consumer one, and two of the things it
drops matter:

- **No Google account is added.** Factory Reset Protection is therefore *not
  armed*, and FRP is the whole backstop against a recovery-mode wipe — see
  [removal](removal.md). Add the parent's account yourself, before locking.
- **No screen lock is set.** The phone has no PIN, pattern or fingerprint until
  someone sets one.

Both have to be done in the window described below.

### Nothing is enforced until you lock

drawbridge deliberately does nothing to a freshly provisioned phone. No
restrictions, no filter, no app removal, no browser download — not at
provisioning, not when the app is first opened, and not on the daily poll. It
waits for *Lock drawbridge*.

That window is what the two steps above need: adding a Google account and
setting a screen lock are both impossible once the restrictions land, and so is
enabling USB debugging. Use it, then lock.

This was not always true. Applying the lockdown from inside the setup wizard is
what stopped a Moto G15 finishing setup at all: Device Owner was granted, the
policy compiled, and `USER_SETUP_COMPLETE` never flipped, leaving a phone with no
notification shade and a Settings that closed itself on launch.

Generate the payload from a signed APK:

```bash
python3 tools/qrpayload.py \
    --apk release/dpc-release.apk \
    --url https://github.com/Nilss3/drawbridge/releases/download/v1.0.0/dpc-release.apk \
    --wifi-ssid "Home" --wifi-password "..."
```

Then encode it, e.g. `qrencode -o provisioning.png -r dist/provisioning-qr.json`.

The payload pins the **signing certificate**, not the APK file, so it stays valid
across releases signed with the same key.

Custom ROMs sometimes ship a setup wizard without the six-tap gesture. Use adb
there.

## After provisioning

1. Open **drawbridge**. Everything is on one screen: language, policy, options,
   status.
2. Pick the language, read the policy, and set the options under it.
3. Tap *Lock drawbridge*. That one button applies the restrictions, starts the
   filter and seals the screen; accept the battery-optimisation exemption when
   prompted.
4. **Write down the key it shows you.** It is displayed once and only its hash is
   stored. There is no email reset — that would tie the device to an account,
   which this project exists to avoid. Lose the key and the only way back into
   that screen is a destructive recovery wipe. A fresh key is minted at every
   lock, so an old one stops working.
5. herald installs itself. drawbridge downloads it from the `required_apps` list
   in the policy as soon as it becomes device owner — it has to, because by then
   every other browser has been removed or hidden and nothing else on the device
   could fetch it. Any browser that appears later is removed automatically.

### OEMs that need a manual step

Xiaomi, Huawei, Oppo/Realme and some other budget brands run proprietary
"autostart" managers that no Android API can reach. On those devices, find the
security or battery app and enable autostart for drawbridge by hand. Without it,
the policy poller can be delayed for days — the filter itself keeps running,
since it is an always-on VPN, but it will be filtering against a stale list.

## Diagnostics

**⋮ → Diagnostics**, in English on every build. Device and Android version,
drawbridge's own version, Device Owner state, whether the filter is running,
`USER_SETUP_COMPLETE`, the restrictions actually in force, whether the phone is
exempt from battery optimisation, and a timestamped record of which provisioning
callbacks fired. There is a copy button, because the point of the screen is that
its contents end up in a bug report.

Reach for it first on any handset that misbehaves. A phone whose provisioning
went wrong cannot enable USB debugging — no shade, no Settings — so there is no
logcat to collect, and drawbridge's own screen is the only way to see what
happened.

**"battery exempt: false" is worth acting on.** Declining the battery prompt at
lock time does not stop the filter, which is an always-on foreground service. It
delays the daily policy and update poll, on some OEMs for days — so the phone
goes on filtering against a blocklist that is quietly out of date, and nothing
else says so.

## Verifying

Open drawbridge. The status screen should show:

- *Managed: drawbridge is the device owner*
- *Content filter: running*
- a policy version and a recent update time
- the active restrictions

From a terminal, a blocked domain should fail to resolve and a normal one should
not:

```bash
adb shell ping -c 1 www.pornhub.com   # ping: unknown host
adb shell ping -c 1 example.com       # resolves normally
adb shell ping -c 1 www.google.com    # resolves to forcesafesearch.google.com
```

(Only on a debug build; release builds have already taken adb away.)
