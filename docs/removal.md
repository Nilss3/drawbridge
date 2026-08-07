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
was hidden, and gives up Device Owner. **No wipe and no data loss.** Use it when
the child grows up and you are keeping the phone, or before selling it (followed
by a normal factory reset, as you would with any phone you are selling — that
part has nothing to do with drawbridge).

### It is one-way

**Deactivating cannot be undone from the phone.** Device Owner can only be
granted on a device with no accounts configured, so switching the restrictions
back on means a factory reset and a fresh drawbridge install — the same
procedure as [provisioning](provisioning.md) a new phone.

The one exception is a device that still has *no* account and still has adb,
where `dpm set-device-owner` simply works again. That is a bench condition, not
a deployed one: on a real phone the parent's account is present and
`DISALLOW_DEBUGGING_FEATURES` removed adb at provisioning time.

Apps that were *uninstalled* do not come back on their own; reinstall them
normally. Apps that were *hidden* — preinstalled browsers, mostly — reappear
immediately.

It also clears the record of how long the phone has been protected, which is the
one thing here that cannot be undone. Removing and setting up again reads, to
anyone who looks at that date afterwards, exactly like a factory reset — because
from the record's point of view it is one.

### If the key is gone

There is no way back in. This is a deliberate design choice, and the price of
having no account: an email reset would reintroduce exactly the dependency the
project exists to avoid. Your only remaining option is the destructive path
below.

Wrong attempts are **not** throttled, and do not need to be. The key is twenty
Crockford base-32 characters — a hundred bits — so guessing is not a threat
model, and a lockout on the only way in would strand you for half an hour with
nothing else to try. That was a real cost of the six-digit PIN this replaced.

## The destructive way

Booting into hardware recovery mode (usually power + volume) and choosing "Wipe
data / factory reset" removes everything, including drawbridge.
`DISALLOW_FACTORY_RESET` only hides the option inside Settings; it cannot block
the recovery-mode path, and no app can.

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
