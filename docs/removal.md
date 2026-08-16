# Removing drawbridge

There are two ways off a managed device. One is clean and loses nothing; the
other wipes the phone.

## The sanctioned way

Open **drawbridge**, type the key you were given when the phone was locked, then
**⋮ → Deactivate drawbridge restrictions**. It is in the overflow menu rather
than on the screen, because it happens once in the life of a phone.

Nothing else is asked for. Getting past the lock screen already answered the only
question there is, and asking the same thing twice is ceremony rather than
security.

This lifts every restriction, stops the filter, un-hides any system browser that
was hidden, un-suspends anything that could only be suspended, forgets the
install lock's record of which apps the phone was sealed with, and gives up
Device Owner. **No wipe and no data loss.** Use it when
the child grows up and you are keeping the phone, or before selling it (followed
by a normal factory reset, as you would with any phone you are selling — that
part has nothing to do with drawbridge).

### It cannot be undone from the phone, but it does not need a factory reset

**Deactivating cannot be undone from the phone itself.** Device Owner can only be
granted on a device with no accounts on it at that moment, and nothing inside
drawbridge can grant it.

**It does not, however, mean a factory reset.** This file used to say it did, on
two grounds that have both since been tested and found not to hold:

- *"the parent's account is present"* — remove it in Settings and put it back
  afterwards. Measured on 2026-08-10: `dpm set-device-owner` succeeded on a phone
  that had been in use with an account, with no wipe. See
  [provisioning](provisioning.md).
- *"`DISALLOW_DEBUGGING_FEATURES` removed adb at provisioning time"* — USB
  debugging now follows the lock rather than the protection, so it is available
  whenever drawbridge is unlocked, which is exactly the state a removal happens
  from. See
  [design-decisions](design-decisions.md#usb-debugging-follows-the-lock-not-the-protection).

So switching the restrictions back on is: remove the accounts, run
`tools/provision-adb.sh` over a cable, sign back in, lock. The same procedure as
[provisioning](provisioning.md) a new phone, and it keeps the phone's contents.

Apps that were *uninstalled* do not come back on their own; reinstall them
normally. Apps that were *hidden* — preinstalled browsers, mostly — reappear
immediately.

It also clears the record of how long the phone has been protected, which is the
one thing here that cannot be undone. Removing and setting up again reads, to
anyone who looks at that date afterwards, exactly like a factory reset — because
from the record's point of view it is one.

### If the key is gone

There is no way back into the *settings*. This is a deliberate design choice, and
the price of having no account: an email reset would reintroduce exactly the
dependency the project exists to avoid.

The phone itself is not lost, though. A factory reset — from Settings or from
recovery — always works and always has drawbridge off the other side of it. You
lose the data on the device, which is the same thing you would lose by replacing
the phone, and nothing more. See the destructive path below.

Wrong attempts are **not** throttled, and do not need to be. The key is twenty
Crockford base-32 characters — a hundred bits — so guessing is not a threat
model, and a lockout on the only way in would strand you for half an hour with
nothing else to try. That was a real cost of the six-digit PIN this replaced.

## The destructive way

Booting into hardware recovery mode (usually power + volume) and choosing "Wipe
data / factory reset" removes everything, including drawbridge. A factory reset
from Settings does the same.

**Both routes stay open on purpose.** drawbridge does not set
`DISALLOW_FACTORY_RESET`, and an earlier version that did has been corrected —
that restriction turned out to strip the wipe entry out of the *recovery menu*
too, not merely out of Settings, which is documented nowhere and was measured on
a Moto G15 on 2026-08-07. A phone whose key had been lost was then reclaimable
only by reflashing firmware from a PC. Nothing this project protects is worth a
dead handset, so nothing prevents a reset any more.

What holds the line instead is Factory Reset Protection and the protected-since
date, both described below.

If you are looking at a phone provisioned by an older build and it still refuses
to offer a reset, open drawbridge once: the restriction is cleared on sight from
version 0.2.0 onwards.

What happens next depends on the device:

- **Google-certified devices.** The wipe is "untrusted", so Factory Reset
  Protection is not cleared. Setup afterwards demands a Google account that was
  previously on the device. If you followed [provisioning](provisioning.md) and
  only ever added your own account, the device is unusable until you sign in —
  which turns a successful bypass into a brick rather than a clean phone. FRP is
  not unbreakable; model-specific bypass tools circulate publicly.
- **De-Googled ROMs.** No FRP equivalent exists. A recovery wipe is a complete,
  clean removal with no backstop at all.

Either way the device does not heal itself: drawbridge has to be re-provisioned
by hand afterwards.

## Removing herald on its own

herald is an ordinary app. Uninstall it normally — but on a drawbridge-managed
device, doing so leaves the phone with no browser at all, since every other one
has been removed or hidden. Remove drawbridge first if that is what you want.
