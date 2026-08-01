# Provisioning a device

drawbridge needs **Device Owner** status. That can only be granted on a device
with no accounts configured — in practice, a factory-reset or brand-new phone.
It cannot be added to a phone that is already set up without wiping it first.

There are two ways in, and one of them is much more reliable across ROMs.

> Looking for a guide to hand to a parent rather than a developer? Use
> [install.md](install.md) — available in
> [English](install.md), [Nederlands](install.nl.md) and [Français](install.fr.md).

## Before you start

Decide the order of these two things, because it matters:

1. Provision drawbridge (below).
2. Add **the parent's** Google account — and only the parent's.
3. Run setup in the drawbridge app, which locks account changes.

The reason is Factory Reset Protection. If someone boots into recovery mode and
wipes the device — which no software restriction can prevent — FRP demands an
account that was previously on the device before setup can continue. That is
only a deterrent if the child cannot satisfy it themselves. Adding the child's
account, even as a secondary, hands them the key.

On a de-Googled ROM there is no FRP, so a recovery wipe is a clean removal with
no backstop. That is inherent, not a bug.

## Method 1 — adb (works everywhere)

The reliable option, and the one to use on LineageOS, /e/OS and generic AOSP.

```bash
adb install dpc-release.apk
adb install herald-arm64-v8a-release.apk
adb shell dpm set-device-owner app.drawbridge.dpc/app.drawbridge.dpc.admin.DrawbridgeDeviceAdminReceiver
```

If it fails with "Not allowed to set the device owner because there are already
some accounts on the device", remove every account in Settings first — or factory
reset.

**Release builds disconnect adb.** Applying the restrictions switches off USB
debugging, so the adb connection drops immediately after provisioning succeeds.
That is the point: it closes the adb removal route. Install both APKs *before*
provisioning. Debug builds deliberately skip that one restriction so the device
stays testable.

## Method 2 — QR code (stock devices)

On a factory-reset device, tap the welcome screen **six times** to open a hidden
QR scanner. The device then downloads, verifies and installs drawbridge and
grants Device Owner, all before any account exists.

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
