#!/bin/zsh
#
# Records one of the videos Play's declarations ask for. Each demo is a page the
# script taps through while screenrecord runs:
#
#   ./tools/play-demos/record.sh query-all-packages
#   ./tools/play-demos/record.sh data-sync
#   ./tools/play-demos/record.sh media-playback
#
# The take lands in site/assets/ and is published from there, so re-running this
# is how a video is updated rather than a copy somebody makes by hand:
# site/assets/ is the one directory build-site.py does not clear, which is also
# the one place a stale file can sit unnoticed.
#
# Needs an emulator (or phone) on a 1080x1920 screen with herald mono installed.
# Taps are hard-coded against that resolution; buttons that move — dialogs,
# notification actions — are found by label instead.
#
set -e
DIR="${0:A:h}"
DEMO="${1:?usage: record.sh <query-all-packages|data-sync|media-playback> [out.mp4]}"
DEMO_DIR="$DIR/$DEMO"
[ -d "$DEMO_DIR" ] || { echo "no such demo: $DEMO" >&2; exit 1 }
OUT="${2:-${DIR:h:h}/site/assets/play/herald-mono-$DEMO.mp4}"
mkdir -p "${OUT:h}"

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=app.drawbridge.heraldmono
PORT=8099

# Demo payloads (a file big enough to download, a sound long enough to leave
# playing) are generated rather than committed: they are bytes with no meaning.
[ -f "$DEMO_DIR/prepare.py" ] && python3 -I "$DEMO_DIR/prepare.py" "$DEMO_DIR"

python3 -I "$DIR/serve.py" "$DEMO_DIR" "$PORT" >/dev/null 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT INT TERM
sleep 1

# --- what the steps scripts are written against -----------------------------
tap()    { $ADB shell input tap $1 $2 }
btn()    { python3 -I "$DIR/tap_text.py" "$ADB" "$1" }
key()    { $ADB shell input keyevent $1 }
home()   { $ADB shell input keyevent KEYCODE_HOME }
shade()  { $ADB shell cmd statusbar expand-notifications }
unshade(){ $ADB shell cmd statusbar collapse }
herald() { $ADB shell am start -n $PKG/app.drawbridge.herald.BrowserActivity >/dev/null }
source "$DEMO_DIR/steps.sh"

# A demo may need the app's own state cleared first; see each steps.sh.
typeset -f prelaunch >/dev/null && prelaunch

# A download notification is the evidence in one of these videos, so the
# permission has to be granted before filming rather than during it.
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true

# 10.0.2.2 is the host loopback as the emulator sees it.
$ADB shell am force-stop $PKG
$ADB shell am start -a android.intent.action.VIEW -d "http://10.0.2.2:$PORT/" \
    -n $PKG/app.drawbridge.herald.IntentReceiverActivity >/dev/null
# herald pauses before every page on purpose, a cold start adds to it, and a
# demo that wiped the app's data first pays for a fresh profile on top.
sleep 16

$ADB shell screenrecord --bit-rate 6M --size 720x1280 --time-limit 180 /sdcard/demo.mp4 &
REC=$!
sleep 4

steps

sleep 2
$ADB shell pkill -INT screenrecord || true
wait $REC 2>/dev/null || true
sleep 3
$ADB pull /sdcard/demo.mp4 "$OUT"

# screenrecord puts the index after the frames, which a browser reads as "not
# seekable" when the host will not serve byte ranges — and ours does not.
python3 -I "$DIR/faststart.py" "$OUT"
