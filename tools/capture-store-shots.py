#!/usr/bin/env python3
"""Captures herald mono's Play Store screenshots, for one language, from a
connected device or emulator.

    tools/capture-store-shots.py en

Writes `images/store/mono-<lang>-<n>-<shot>.png`, which is what Play Console
takes. **PNG, not WebP**: the store rejects WebP, so these are their own set
rather than a reuse of what `site/assets/img/` holds.

**Use an emulator, and make it 16:9.** Play refuses a screenshot whose long
side is more than twice its short side, and every modern phone is past that —
1080x2400 is 2.22:1. The emulator takes the screen as a launch flag, which
sidesteps the whole problem and needs no new AVD:

    emulator -avd <name> -skin 1080x1920 -no-snapshot

`-no-snapshot` matters if the AVD is one somebody else uses: it neither loads
nor writes their saved state.

The app's language is set here with `cmd locale set-app-locales` rather than
through the device's own settings, so nothing has to be touched between
languages -- the same trick `capture-explainer-shots.py` uses for drawbridge.

**The pause screen is the one shot with a deadline.** mono holds a page for two
and a half seconds before showing it, so the capture has to land inside that
window: too early and the screen is still blank, too late and the article is
already up. PAUSE_AT_MS is deliberately near the middle of it.

The filtering shots need the policy, which herald fetches over the network on
first run. A device that has never had network will show nothing blocked, and
the refusal shot will silently come out as an ordinary page.
"""
from __future__ import annotations

import os
import pathlib
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parent.parent
ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
PACKAGE = "app.drawbridge.heraldmono"

# mono is a product flavour: it takes its own applicationId but keeps herald's
# class package, so every component is `heraldmono/app.drawbridge.herald.X`.
# `am start -n heraldmono/.X` resolves against the applicationId and fails with
# "Activity class does not exist", which reads like a missing activity rather
# than a naming one.
CLASSES = "app.drawbridge.herald"

OUT = REPO / "images" / "store"

LANGUAGES = ["en", "nl", "fr"]

# Where the pause screen is caught, inside mono's 2.5s hold.
PAUSE_AT_MS = 1200

# A page that reads as an ordinary article in every language, so the
# black-and-white shot shows reading working rather than a mock-up. Wikipedia
# is chosen over a newspaper because it is not blocked anywhere, carries no
# cookie wall to dismiss before the screenshot, and looks the same next year.
ARTICLE = {
    "en": "https://en.wikipedia.org/wiki/Atlantic_puffin",
    "nl": "https://nl.wikipedia.org/wiki/Papegaaiduiker",
    "fr": "https://fr.wikipedia.org/wiki/Macareux_moine",
}

# A site the policy blocks, picked so the refusal needs no explaining and the
# screenshot is safe to put in a store listing.
BLOCKED = "https://instagram.com"

SHOTS = ["pause", "article", "refused", "menu", "settings"]

# How far down the article to scroll before shooting it. A page of grey text
# does not say "no colour" -- it says nothing at all. The photograph is the only
# part of the shot that proves the point, so each language is scrolled to where
# its own Wikipedia puts one: the French article leads with the infobox picture,
# the English and Dutch ones open with a paragraph and need a flick.
ARTICLE_FLICKS = {"en": 1, "nl": 1, "fr": 0}


def adb(*args: str, binary: bool = False):
    result = subprocess.run([ADB, *args], capture_output=True)
    return result.stdout if binary else result.stdout.decode(errors="replace")


def screencap(path: pathlib.Path) -> None:
    path.write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    print(f"  {path.relative_to(REPO)}")


def open_url(url: str) -> None:
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-d", url, "-n", f"{PACKAGE}/{CLASSES}.IntentReceiverActivity")


def nodes() -> list[dict]:
    """Every node on screen, as dicts with text, content-desc, id and centre."""
    adb("shell", "uiautomator", "dump", "/sdcard/store-shot.xml")
    xml = adb("shell", "cat", "/sdcard/store-shot.xml")
    found = []
    for node in ET.fromstring(xml).iter("node"):
        box = node.get("bounds") or ""
        if not box:
            continue
        left, top = (int(v) for v in box.split("][")[0].lstrip("[").split(","))
        right, bottom = (int(v) for v in box.split("][")[1].rstrip("]").split(","))
        found.append({
            "text": (node.get("text") or "").strip(),
            "desc": (node.get("content-desc") or "").strip(),
            "id": node.get("resource-id") or "",
            "at": ((left + right) // 2, (top + bottom) // 2),
        })
    return found


def tap(match, what: str) -> None:
    for node in nodes():
        if match(node):
            adb("shell", "input", "tap", str(node["at"][0]), str(node["at"][1]))
            return
    raise SystemExit(f"Could not find {what} on screen.")


def menu_label(name: str, lang: str) -> str:
    """One string, read from the app's own resources rather than copied here.

    The menu is walked by its visible text, and that text is translated — so
    taking it from `strings.xml` means a reworded menu moves the script with it
    instead of breaking it silently in one language."""
    values = "values" if lang == "en" else f"values-{lang}"
    for flavour in ("mono", "main"):
        path = REPO / "herald" / "src" / flavour / "res" / values / "strings.xml"
        if not path.exists():
            continue
        for string in ET.parse(path).getroot().iter("string"):
            if string.get("name") == name and (string.text or "").strip():
                return string.text.strip()
    raise SystemExit(f"No {name} string for {lang}.")


def capture(lang: str) -> None:
    out = OUT
    out.mkdir(parents=True, exist_ok=True)

    adb("shell", "cmd", "locale", "set-app-locales", PACKAGE, "--locales", lang)
    adb("shell", "am", "force-stop", PACKAGE)
    print(f"{lang}:")

    # 1 + 2. The pause, then the article it was holding back. One navigation
    # gives both shots, which is also the only way to be sure the pause shot
    # and the article shot are of the same page.
    open_url(ARTICLE[lang])
    time.sleep(PAUSE_AT_MS / 1000)
    screencap(out / f"mono-{lang}-1-pause.png")
    time.sleep(8)
    for _ in range(ARTICLE_FLICKS[lang]):
        adb("shell", "input", "swipe", "540", "1500", "540", "500", "120")
        time.sleep(1.5)
    time.sleep(2)
    screencap(out / f"mono-{lang}-2-article.png")

    # 4 + 5, taken here rather than last, while the article is still the page
    # underneath. Opening it again after the refusal does not reliably
    # navigate — the same URL arriving at an activity that is already showing
    # it can be coalesced away, and the shot then has a blocked page behind the
    # menu instead of a newspaper. Capture order and listing order are not the
    # same thing; the numbers in the names are the order Play should show them.
    #
    # The menu is where mono's colour switch lives, which says more about this
    # browser than a settings list does. Settings itself cannot be started with
    # `am start`: it is `exported="false"`, as an in-app screen should be, so
    # this walks the UI rather than launching an activity.
    tap(lambda n: n["id"].endswith(":id/mozac_browser_toolbar_menu"), "the menu button")
    time.sleep(2)
    screencap(out / f"mono-{lang}-4-menu.png")

    settings = menu_label("menu_settings", lang)
    tap(lambda n: n["text"] == settings, f'the "{settings}" menu item')
    time.sleep(4)
    screencap(out / f"mono-{lang}-5-settings.png")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(2)

    # 3. A refused site, last because it is the one navigation that cannot be
    # undone by going back to where the other shots were taken.
    open_url(BLOCKED)
    time.sleep(9)
    screencap(out / f"mono-{lang}-3-refused.png")


def main() -> int:
    args = sys.argv[1:] or LANGUAGES
    for lang in args:
        if lang not in LANGUAGES:
            print(f"usage: {sys.argv[0]} [{'|'.join(LANGUAGES)}]...", file=sys.stderr)
            return 2

    if "device" not in adb("devices"):
        print("No device. Boot the emulator first; see the note at the top.", file=sys.stderr)
        return 1

    for lang in args:
        capture(lang)

    # Back to the device's own language, so an emulator left running does not
    # quietly stay French for whoever opens it next.
    adb("shell", "cmd", "locale", "set-app-locales", PACKAGE, "--locales", "")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
