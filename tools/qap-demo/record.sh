#!/bin/zsh
#
# Records the video Play's Permissions Declaration Form asks for when a listing
# declares QUERY_ALL_PACKAGES: 38 seconds showing what the permission buys, which
# is a prompt that can name the app a link is about to be handed to.
#
# Needs a running emulator (or phone) on a 1080x1920 screen with herald mono
# installed, plus a dialer and a mail app for the first two links to resolve to.
# The taps are hard-coded screen coordinates against that resolution; the two
# dialog buttons are found by label instead, so their position can drift.
#
# The take lands in site/assets/ and is published from there, so re-running this
# is how the video is updated rather than a copy somebody makes by hand:
# site/assets/ is the one directory build-site.py does not clear, which is also
# the one place a stale file can sit unnoticed.
#
#   ./tools/qap-demo/record.sh [output.mp4]
#
set -e
DIR="${0:A:h}"
OUT="${1:-${DIR:h:h}/site/assets/play/herald-mono-query-all-packages.mp4}"
mkdir -p "${OUT:h}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=app.drawbridge.heraldmono
PORT=8099

# The page is served rather than opened from disk: Gecko will not navigate to a
# file:// URL from an intent, and a served page can carry no-store, so a re-run
# after an edit shows the edit rather than yesterday's copy.
python3 -I - "$DIR" "$PORT" <<'PY' &
import http.server, os, sys
os.chdir(sys.argv[1])
class H(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()
    def log_message(self, *a): pass
http.server.HTTPServer(("127.0.0.1", int(sys.argv[2])), H).serve_forever()
PY
SERVER=$!
trap "kill $SERVER 2>/dev/null" EXIT
sleep 1

tap() { $ADB shell input tap $1 $2 }
btn() { python3 -I "$DIR/tap_text.py" "$ADB" "$1" }

# 10.0.2.2 is the host loopback as the emulator sees it.
$ADB shell am force-stop $PKG
$ADB shell am start -a android.intent.action.VIEW -d "http://10.0.2.2:$PORT/" \
    -n $PKG/app.drawbridge.herald.IntentReceiverActivity >/dev/null
# herald pauses before every page on purpose, and a cold start adds to it.
sleep 10

$ADB shell screenrecord --bit-rate 6M --size 720x1280 --time-limit 120 /sdcard/qap.mp4 &
REC=$!
sleep 4

# 1. tel: resolves to the dialer, and the handoff is carried through.
tap 540 745;  sleep 3
btn Open;     sleep 6

$ADB shell am start -n $PKG/app.drawbridge.herald.BrowserActivity >/dev/null
sleep 4

# 2. mailto: resolves to a different package, and is declined, because the
#    prompt is the point rather than the mail app.
tap 540 1000; sleep 3
btn Cancel;   sleep 3

# 3. whatsapp: resolves to nothing here, so no prompt appears at all — the query
#    decides, and an unowned scheme is not guessed at.
tap 540 1255; sleep 5
sleep 2

$ADB shell pkill -INT screenrecord || true
wait $REC 2>/dev/null || true
sleep 3
$ADB pull /sdcard/qap.mp4 "$OUT"

# screenrecord puts the index after the frames, which a browser reads as "not
# seekable" when the host will not serve byte ranges — and ours does not.
python3 -I "$DIR/faststart.py" "$OUT"
