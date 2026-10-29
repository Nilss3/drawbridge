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
# The screen's own order, with the number and name each section keeps on the
# site (EXPLAINER_SECTIONS in tools/build-site.py). The configuration screen was
# regrouped in build 54 and no longer runs in the order the site's pages do, so
# the files are named by what they show, not by where they came on the screen.
SECTIONS = [(1, "policy"), (5, "options"), (3, "browser"),
            (4, "installs"), (2, "disconnect"), (6, "locking")]

# The headings that begin each section, per language, in *screen* order. They are
# literals rather than resource lookups because this walks a *screen*, and what
# is on the screen is the translated string.
HEADINGS = {
    "en": ["Base web and app filter",
           "Harmful tools you may still need on the road", "Browsers",
           "No further app installs", "Offline phone", "Lock drawbridge"],
    "nl": ["Basisfilter voor web en apps",
           "Schadelijke hulpmiddelen die je onderweg toch nodig kunt hebben",
           "Browsers", "Geen verdere app-installaties", "Offline telefoon",
           "drawbridge vergrendelen"],
    "fr": ["Filtre de base web et applications",
           "Outils nuisibles dont vous pourriez quand même avoir besoin en déplacement",
           "Navigateurs", "Plus aucune installation d'application",
           "Téléphone hors ligne", "Verrouiller drawbridge"],
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


def expand_collapsed(heads: list[str], work: pathlib.Path) -> None:
    """Opens the three collapsible sections (harmful tools, browsers, offline).

    The configuration screen collapses them behind a chevron, and a collapsed
    section is a heading and one line -- it explains nothing. Whether one is
    open is not in the accessibility tree, so it is judged by height: an open
    section runs past 450px before the next heading, a closed one does not.
    """
    wanted = [(heads[1], heads[2]), (heads[2], heads[3]), (heads[4], heads[5])]
    for head, following in wanted:
        for _ in range(40):
            adb("shell", "uiautomator", "dump", "/sdcard/e-x.xml")
            adb("pull", "/sdcard/e-x.xml", str(work / "x.xml"))
            found = bounds(work / "x.xml", [head, following])
            if head in found and following in found:
                break
            adb("shell", "input", "swipe", "540", "1800", "540", "1100", "500")
            time.sleep(1.2)
        else:
            raise SystemExit(f"Could not find {head!r} to expand it.")
        if found[following][0] - found[head][0] < 450:
            adb("shell", "input", "tap", "540", str((found[head][0] + found[head][1]) // 2))
            time.sleep(2)


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
    expand_collapsed(heads, work)
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
    for head, (number, section) in zip(heads, SECTIONS):
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
