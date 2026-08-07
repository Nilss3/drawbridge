# Installing drawbridge with a QR code

**English** · [Nederlands](install.nl.md) · [Français](install.fr.md)

This takes about fifteen minutes. You scan one code, and the phone installs and
configures everything by itself — both the content filter and the browser.

---

## Before you begin

> **This erases the phone.** drawbridge can only be installed on a phone that has
> just been reset, because Android only hands out this level of control before
> any account exists. Back up photos, messages and anything else you want to keep
> first.

You will also need:

- A Wi-Fi network and its password.
- **The parent's Google account** — the child's account must never be added. This
  is what stops the controls being removed by wiping the phone (see
  [Why the account matters](#why-the-account-matters)).
- About 300 MB of download, so use Wi-Fi rather than mobile data.

---

## Step 1 — Reset the phone

On the phone you are going to manage:

**Settings → System → Reset options → Erase all data (factory reset)**

Wait for it to restart. You should end up on the "Hi there" / "Welcome" screen.

If the phone is already brand new and has never been set up, skip this step.

---

## Step 2 — Tap the welcome screen six times

On that first welcome screen, **tap the middle of the screen six times in the
same spot**.

Nothing appears to happen for the first few taps — keep going. After the sixth
tap the phone opens a QR scanner. On some phones it first asks you to connect to
Wi-Fi, then downloads the scanner; that is normal.

> If tapping six times does nothing, your phone's setup screen does not have this
> feature. See [If the QR scanner does not appear](#if-the-qr-scanner-does-not-appear).

---

## Step 3 — Connect to Wi-Fi

If you have not been asked already, connect the phone to your Wi-Fi now. It needs
the internet to download drawbridge.

---

## Step 4 — Scan this code

<p align="center">
  <img src="img/provisioning-qr.png" alt="drawbridge provisioning QR code" width="340">
</p>

Point the phone's scanner at the code above — from another screen, or from a
printout.

For a clean printout, use the vector version:
[provisioning-qr.svg](img/provisioning-qr.svg).

The phone will then, on its own:

1. Download and install drawbridge.
2. Make drawbridge the device owner, so it cannot be removed without your key.
3. Download and install **herald**, the filtered browser.
4. Switch on the content filter and hide every other browser.

This takes a few minutes, most of it downloading the browser. Leave the phone on
Wi-Fi until it finishes.

---

## Step 5 — Add your own Google account

When the phone reaches the normal setup screens, sign in with **your own** Google
account — never the child's.

You can skip the account entirely if you do not need the Play Store, but then you
lose the protection described below.

---

## Step 6 — Set the phone up in the drawbridge app

Open the **drawbridge** app. Everything you decide is on that one screen.

1. **Pick your language** — English, Nederlands or Français. It is the first
   thing on the screen, so everything below it is in the language you chose.
2. **Read the policy.** It says who it is for and what it actually blocks. If
   the document offers more than one, switching to a stricter one uninstalls the
   apps it does not allow, straight away, and switching back does not reinstall
   them.
3. **Set the options** underneath it. Each allows one more thing on top of the
   policy, with the age it is usually reckoned suitable from beside it —
   *Allow WhatsApp 14+*, for instance.

---

## Step 7 — Lock it, and write down the key

Tap **Lock drawbridge**. This is the one button that matters: it applies the
policy, starts the content filter, and seals the screen. Allow the battery
optimisation exemption when asked.

You will then be shown a **key**: twenty characters in four groups, like
`4XRZS-7QC9N-SPSH9-AWAAE`.

**Write it down or print it before you close that screen.** It is shown once, it
is not stored anywhere you can read it again, and there is no reset — not by
email, not by anyone. That is deliberate: an email reset would tie the phone to
an account, which this project exists to avoid. The *Print or save the key*
button hands it to a printing or notes app; it is deliberately not offered to the
clipboard, which anyone holding the phone can read.

A **new key is minted every time you lock**, so a key that was photographed once
stops working the next time you seal the phone.

> **If you lose the key, the only way back into the settings is to erase the
> phone.** Put it somewhere safe — a drawer, a password manager, taped inside a
> cupboard.
>
> Choosing *not* to keep it is a real option, and the app will let you: the phone
> stays exactly as you set it, for good, and nobody can change it again —
> including you.

To change anything later, open drawbridge and type the key.

---

## Checking it worked

Open drawbridge. Before it asks for anything it tells you **how long it has been
protecting this phone** — the date and time you locked it.

That line is worth knowing about, because it is the cheapest tamper check there
is. It survives reboots and it survives you unlocking to change a setting. The
only things that clear it are removing drawbridge from inside the app and wiping
the phone. So if you pick the phone up in six months and it says it has been
protecting it since last Tuesday, it has been reset and set up again — whatever
else it looks like.

Type your key, and the screen behind it should say:

- *Managed: drawbridge is the device owner*
- *Content filter: running*
- a policy version and a recent update time

Then open **herald** and try visiting a blocked site. You should get a "Page
blocked" screen rather than the site.

You should also see a small key icon in the status bar. That is Android showing
the filter is active; it cannot be switched off.

---

## Why the account matters

Anyone can hold down the power and volume buttons to reach recovery mode and wipe
the phone. No app can prevent this, drawbridge included.

On a Google-certified phone, that kind of wipe does **not** clear Factory Reset
Protection: when the phone restarts, it demands a Google account that was
previously signed in on it. If that is only ever your account, wiping the phone
makes it unusable rather than free — which is the point.

If the child's account was ever added, even briefly, they can satisfy that prompt
themselves and end up with a clean, unrestricted phone.

On phones without Google services (LineageOS, /e/OS) there is no equivalent
protection, and a recovery wipe removes drawbridge completely.

---

## If the QR scanner does not appear

Some phones — mostly those running custom software — have a setup screen without
the six-tap gesture. Install it over USB from a computer instead:

```bash
adb install dpc-release.apk
adb shell dpm set-device-owner app.drawbridge.dpc/app.drawbridge.dpc.admin.DrawbridgeDeviceAdminReceiver
```

The phone must have **no accounts on it** for that second command to work.

---

## Removing it later

Open drawbridge, type your key, then **⋮ → Deactivate drawbridge restrictions**.
It lives in the overflow menu rather than on the screen: it happens once in the
life of a phone and does not belong next to the button used every time.

Everything is lifted, hidden apps come back, and **nothing is erased**. It is
also **one-way**: switching the restrictions back on means a factory reset and a
fresh drawbridge install. See [removal](removal.md) for the details.
