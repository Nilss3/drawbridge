#!/usr/bin/env python3
"""Captures the six configuration-screen sections that the homepage explainer
shows, for one language, from a connected device or emulator.

    tools/capture-explainer-shots.py en

Writes `images/app-<lang>-<n>-<section>.png` (the crop as captured) and
`site/assets/img/app-<lang>-<n>-<section>.webp` (what the site serves), then
rebuild the site. The widget's *text* is read from the policy and the app's
strings at build time and needs nothing; only the pictures are captured, and
only when the screen itself changes.

Wants a phone with drawbridge installed and **unlocked**, in light mode. The app
locale is set here rather than through the picker, so nothing on the device has
to be touched between languages.

Two things this gets right that a first attempt did not, and both are worth
keeping if it is ever rewritten:

**The screenshot and the view dump must come from the same scroll offset.**
`input swipe` returns when the gesture ends and the list keeps flinging, so a
screenshot taken immediately and a dump taken a second later describe different
positions — and every crop comes out shifted by however far the list coasted.
Hence: settle, dump, screenshot, dump again, and only trust it if the two dumps
agree.

**A section is only taken when the whole of it is on screen.** Letting the
screen bottom stand in for a section's end lets a truncated section win on
height and be cropped silently.
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
PACKAGE = "app.drawbridge.dpc"

TOP_SAFE, BOTTOM_SAFE = 250, 2280      # below the action bar, above the gesture bar
PAD_TOP, PAD_BOTTOM = 24, 6            # air above the heading; almost none below the end
STEP_PX, STOPS = 240, 46               # see the note on STEP_PX below
SECTIONS = ["policy", "disconnect", "browser", "installs", "options", "locking"]

# The headings that begin each section, per language, in page order. They are
# literals rather than resource lookups because this walks a *screen*, and what
# is on the screen is the translated string.
HEADINGS = {
    "en": ["Policy", "Disconnect philosophy", "Browser policy",
           "App installs", "Options", "Lock drawbridge"],
    "nl": ["Beleid", "Verbindingsfilosofie", "Browserbeleid",
           "App-installaties", "Opties", "drawbridge vergrendelen"],
    "fr": ["Politique", "Philosophie de déconnexion", "Politique de navigateur",
           "Installation d'applications", "Options", "Verrouiller drawbridge"],
}

# STEP_PX has to be smaller than the narrowest window a section can be caught
# in, which is BOTTOM_SAFE - TOP_SAFE - span. French runs longest: its
# disconnect philosophy is 1758px, leaving 272px. A 420px step walked straight
# over it and the section was never captured whole.


def adb(*args: str, binary: bool = False):
    result = subprocess.run([ADB, *args], capture_output=True)
    return result.stdout if binary else result.stdout.decode(errors="replace")


def bounds(path: pathlib.Path, wanted: list[str]) -> dict[str, tuple[int, int]]:
    found: dict[str, tuple[int, int]] = {}
    for node in ET.parse(path).iter("node"):
        text = (node.get("text") or "").strip()
        if not text:
            continue
        box = node.get("bounds")
        top = int(box.split("][")[0].split(",")[1])
        bottom = int(box.split("][")[1].rstrip("]").split(",")[1])
        if text in wanted:
            found.setdefault(text, (top, bottom))
        last = found.get("__last__")
        if last is None or bottom > last[1]:
            found["__last__"] = (top, bottom)
    return found


def main() -> int:
    if len(sys.argv) != 2 or sys.argv[1] not in HEADINGS:
        print(f"usage: {sys.argv[0]} {{{'|'.join(HEADINGS)}}}", file=sys.stderr)
        return 2
    lang = sys.argv[1]
    heads = HEADINGS[lang]
    work = pathlib.Path(os.environ.get("TMPDIR", "/tmp")) / f"explainer-{lang}"
    work.mkdir(parents=True, exist_ok=True)

    adb("shell", "cmd", "locale", "set-app-locales", PACKAGE, "--locales", lang)
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.ui.MainActivity")
    time.sleep(6)

    for _ in range(12):
        adb("shell", "input", "swipe", "540", "600", "540", "2100", "200")
    time.sleep(2)

    best: dict[str, tuple[pathlib.Path, int, int]] = {}
    for stop in range(STOPS):
        for _ in range(4):
            time.sleep(1.4)
            adb("shell", "uiautomator", "dump", "/sdcard/e-a.xml")
            adb("pull", "/sdcard/e-a.xml", str(work / "a.xml"))
            before = bounds(work / "a.xml", heads)
            png = adb("exec-out", "screencap", "-p", binary=True)
            adb("shell", "uiautomator", "dump", "/sdcard/e-b.xml")
            adb("pull", "/sdcard/e-b.xml", str(work / "b.xml"))
            after = bounds(work / "b.xml", heads)
            if before == after:
                break
        shot = work / f"stop{stop}.png"
        shot.write_bytes(png)

        for index, head in enumerate(heads):
            if head not in after:
                continue
            top = after[head][0]
            if top < TOP_SAFE:
                continue
            following = heads[index + 1] if index + 1 < len(heads) else None
            if following is not None:
                if following not in after:
                    continue                       # runs off the bottom here
                end = after[following][0]
            else:
                end = min(after["__last__"][1], BOTTOM_SAFE)
            if not top < end <= BOTTOM_SAFE:
                continue
            span = end - top
            if head not in best or span > best[head][2]:
                best[head] = (shot, top, span)

        adb("shell", "input", "swipe", "540", "1800", "540", str(1800 - STEP_PX), "900")

    missing = [h for h in heads if h not in best]
    for number, (head, section) in enumerate(zip(heads, SECTIONS), start=1):
        if head in best:
            src, top, span = best[head]
            y = max(0, top - PAD_TOP)
            height = min(2400 - y, span + PAD_TOP + PAD_BOTTOM)
            png_out = REPO / "images" / f"app-{lang}-{number}-{section}.png"
            web_out = REPO / "site/assets/img" / f"app-{lang}-{number}-{section}.webp"
            subprocess.run(["magick", str(src), "-crop", f"1080x{height}+0+{y}",
                            "+repage", str(png_out)], check=True)
            subprocess.run(["magick", str(png_out), "-resize", "640x",
                            "-quality", "82", str(web_out)], check=True)
            print(f"  {lang} {number} {section:11} {height:5}px  {web_out.name}")
        else:
            print(f"  {lang} {number} {section:11} NOT CAPTURED WHOLE", file=sys.stderr)

    if missing:
        print("\nSome sections never fitted between the safe margins. Lower STEP_PX,\n"
              "or check the phone is unlocked and on the configuration screen.",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
