# Installing drawbridge over USB

**English** · [Nederlands](install.nl.md) · [Français](install.fr.md)

This takes about fifteen minutes. You connect the phone to a computer once, run
the installer, and the phone configures the rest by itself — both the content
filter and the browser.

**This works on an ordinary Android phone that is already in use.** It does not
need a factory reset, and it does not erase your photos, messages or apps.

> **One thing does get removed: the apps drawbridge blocks.** When you lock it,
> every app the policy blocks is uninstalled from the phone, straight away.
> Switching a setting back on afterwards does not reinstall them. On a phone
> already in use that is a real change — check
> [what is blocked](blocked-apps.md) before you start.

---

## Before you begin

You will need:

- **A computer and a USB cable.**
- A Wi-Fi network and its password.
- **The parent's Google account** — the child's account must never be added. You
  will sign out of the phone in step 1 and back in at step 4.
- About 300 MB of download, so use Wi-Fi rather than mobile data.

If your phone runs a de-Googled Android (LineageOS, /e/OS, GrapheneOS) and is
fresh out of the box or just reset, you can use
[the QR code](#a-phone-without-google-services-the-qr-code) instead and skip the
cable entirely.

---

## Step 1 — Remove every account from the phone

**Settings → Passwords, passkeys & accounts**

Tap each account, then **Remove account**. Android only hands over this level of
control to a phone that has no account on it — that is the one hard requirement,
and it is the only reason the old version of this guide told you to wipe the
phone. You do not have to. You sign back in at step 4.

Removing an account deletes that account's mail, contacts and synced data *from
the phone*. Nothing is deleted from your Google account itself, and it all comes
back when you sign in again.

---

## Step 2 — Turn on USB debugging

**Settings → About phone**, then tap **Build number** seven times. The phone
tells you that you are now a developer.

Then **Settings → System → Developer options → USB debugging**, and switch it on.

---

## Step 3 — Run the installer from your computer

Connect the phone with the USB cable. Accept the *Allow USB debugging* prompt
that appears on the phone.

**The easiest way is the installer page on the website**, which does all of this
from Chrome or Edge with nothing to set up:
<https://drawbridge-project.pages.dev/install/usb/>.

If you would rather use a terminal, from a copy of this repository:

```bash
tools/provision-adb.sh
```

It installs drawbridge and herald and makes drawbridge the owner of the phone.
It refuses to start if any account is still on the phone, which is the usual
reason it stops.

---

## Step 4 — Sign back in with your own Google account

**Settings → Passwords, passkeys & accounts → Add account.** Sign in with **your
own** Google account — never the child's.

Do this now. Once drawbridge is locked, account changes are closed off, and
opening them again costs you the key.

You can skip the account entirely if you do not need the Play Store.

Make sure the phone is on Wi-Fi as well: it downloads about 300 MB of browser
during the next step.

---

## Step 5 — Set the phone up in the drawbridge app

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

## Step 6 — Lock it, and write down the key

Tap **Lock drawbridge**. This is the one button that matters: it applies the
policy, starts the content filter, and seals the screen. Allow the battery
optimisation exemption when asked.

**This is the moment the blocked apps are uninstalled.** If the phone was already
in use, they go now, and turning a setting back on later does not bring them
back.

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

## A factory reset removes drawbridge, and nothing stops one

Anyone can hold down the power and volume buttons to reach recovery mode and wipe
the phone, or do it from Settings if they know the screen lock. No app can
prevent this, drawbridge included.

**Factory Reset Protection does not cover this, despite what you may read** —
including in earlier versions of this guide. On a fully managed phone it is not
switched on by default, and a reset from Settings does not trigger it whatever
accounts are on the device. This was tested on real hardware on 2026-08-10: the
phone was reset and setup never asked for the Google account. Do not rely on it.

What you get instead is **notice that it happened**. drawbridge writes the date it
was locked onto the lock screen and into the app. A phone that has been wiped and
set up again stops showing a date you recognise, which is the cheapest tamper
check there is — as long as you know what the phone is supposed to say.

Keep the child's account off the phone anyway. It costs nothing and it closes the
easiest route to a Play Store that is not yours.

---

## A phone without Google services: the QR code

**For de-Googled open-source Android devices only** — LineageOS, /e/OS,
GrapheneOS — **and only fresh out of the box or straight after a factory reset.**
It needs no computer and no cable.

1. On the welcome screen, tap the same spot **six times** to reveal a hidden QR
   scanner. On some phones it asks for Wi-Fi first, then downloads the scanner;
   that is normal.
2. Scan the code below.
3. Wait. The phone downloads and installs drawbridge, makes it the device owner,
   downloads herald, and switches on the filter — a few minutes, most of it the
   browser. Leave it on Wi-Fi.

<p align="center">
  <img src="img/provisioning-qr.png" alt="drawbridge provisioning QR code" width="340">
</p>

For a clean printout, use the vector version:
[provisioning-qr.svg](img/provisioning-qr.svg).

Then carry on from [step 4](#step-4--sign-back-in-with-your-own-google-account).

> This has not yet been confirmed on such a phone. On a phone **with** Google
> Play it does not work at all — use the USB method above.

---

## A note on the cable

**Locking switches USB debugging off.** It comes back when you unlock drawbridge
with your key, which is how you would put a later version on the phone over the
same cable — so the cable is not a one-time chance, but it is closed until you
next have the key in your hand.

The technical detail is in [provisioning.md](provisioning.md).

---

## Removing it later

Open drawbridge, type your key, then **⋮ → Deactivate drawbridge restrictions**.
It lives in the overflow menu rather than on the screen: it happens once in the
life of a phone and does not belong next to the button used every time.

Everything is lifted, hidden apps come back, and **nothing is erased**. It cannot
be switched back on from the phone itself — that means the cable again, from
step 1 — but it does not need a factory reset. See [removal](removal.md).
