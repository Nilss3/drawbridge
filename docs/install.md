# Installing drawbridge over USB

**English** · [Nederlands](install.nl.md) · [Français](install.fr.md)

This takes about fifteen minutes. You connect the phone to a computer once, run
the installer, and the phone configures the rest by itself — both the content
filter and the browser.

**This works on an ordinary Android phone that is already in use.** It does not
need a factory reset, and it does not erase your photos, messages or apps.

> **One thing does get removed: the apps drawbridge blocks.** This starts at
> **installation**, not when you lock: within minutes, every app the policy never
> allows — social media, harmful games, browsers it does not sanction — is
> uninstalled, and anything saved only inside one of them goes with it. Move what
> you want to keep off the phone first: export bookmarks, save photos out of an
> app that holds them. Apps you can still decide about, like WhatsApp or YouTube,
> are left alone until you lock. On a phone already in use this is a real change —
> check [what is blocked](blocked-apps.md) before you start.

---

## Before you begin

You will need:

- **A computer and a USB cable.**
- A Wi-Fi network and its password.
- **A Google account, or none** — see [step 4](#step-4--sign-back-in-or-do-not).
  Whatever is on the phone now comes off in step 1.
- About 300 MB of download, so use Wi-Fi rather than mobile data.

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

**Every account counts, not only Google ones.** A phone whose accounts were a
password manager, a messaging app and three banking apps was refused just the
same. Whatever appears on that screen has to go.

---

## Step 1b — Delete the Private Space, if the phone has one

**Settings → Security & privacy → Private Space → Delete private space**

Android refuses this level of control while the phone carries a *second profile*,
and on Android 15 and later a Private Space is exactly that. It is checked before
the accounts are, so a phone with both is refused for this first — clear the
accounts and you get the same refusal in different words.

**It is easy not to know you have one.** A Private Space does not appear in the
user switcher, has its own PIN, and hides its apps from the launcher. On the
phone this step was written for, the owner had one and did not remember setting
it up.

**Deleting it removes everything inside it**, so look before you delete. If you
cannot get in — the PIN is separate from the phone's — deleting is still
possible from that same screen.

Any other extra user lives in **Settings → System → Multiple users** and has to
go as well.

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
<https://drawbridge-project.pages.dev/install/>.

If you would rather use a terminal, from a copy of this repository:

```bash
tools/provision-adb.sh
```

It installs drawbridge and herald and makes drawbridge the owner of the phone.
It refuses to start if any account is still on the phone, which is the usual
reason it stops.

---

## Step 4 — Sign back in, or do not

**Settings → Passwords, passkeys & accounts → Add account**, if you want one.

**Use an account you do not mind the child having — or none at all.** Whichever
account is signed in, whoever holds the phone can install from the Play Store, so
signing in with *your* account does not hold anything back from them. It does the
opposite: it puts your mail, photos, files and saved payment method on a phone
somebody else is carrying.

Leaving the phone with no account at all is the stricter option and a perfectly
good one. The Play Store cannot install anything without an account, and
drawbridge itself needs none.

You can add or remove accounts later too, locked or not — drawbridge does not
restrict that. What it does stop, permanently, is adding a second *user* to the
phone, which would otherwise get its own unfiltered internet.

Make sure the phone is on Wi-Fi as well: it downloads about 300 MB of browser
during the next step.

---

## Step 5 — Move your bookmarks into herald

**herald installs itself right after drawbridge**, on the same Wi-Fi, and it is
there before you lock. Give it a few minutes on a fresh provision.

Do this now, because locking is what removes the other browsers — and their
bookmarks go with them.

1. In the browser you are leaving, export bookmarks to an HTML file. In Chrome:
   ⋮ → Bookmarks → Bookmark manager → ⋮ → Export bookmarks.
2. Open **herald** → ⋮ → Bookmarks → ⋮ → Import, and pick that file.

herald reads the same format Chrome and Firefox write. Anything it cannot make
safe — `javascript:` entries, for instance — it drops rather than imports.

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
