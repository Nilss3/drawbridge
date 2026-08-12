#!/usr/bin/env bash
#
# Provisions a device over adb: installs drawbridge, installs herald, and grants
# Device Owner. Works on a Google-certified handset, which is the whole point.
#
# Why this exists rather than the three commands in provisioning.md:
#
# Play Protect refuses `app.drawbridge.dpc` by name. On a certified device
# `adb install dpc-release.apk` fails with INSTALL_FAILED_VERIFICATION_FAILURE
# whether or not an account is signed in, and the QR path fails with it too —
# the setup wizard cannot install the DPC it just downloaded, so it reports a
# generic error and factory-resets the phone. See docs/handoff.md.
#
# adb has one lever the QR path does not: `verifier_verify_adb_installs`. It is
# a global setting, writable only from a shell, that decides whether adb
# installs go past the verifier at all. Turned off, the refused package installs
# in full; turned back on, the *installed* copy is left alone and Device Owner
# survives. Measured on a Moto G15 on 2026-08-10, three flips, one variable.
#
# So the parent never touches Play Protect. Nothing about the device's ordinary
# protection changes: the setting is off only for the seconds it takes to push
# two APKs, it is restored on every exit path including Ctrl-C and failure, and
# once the phone is locked USB debugging is gone and no adb install is possible
# regardless. Leaving verification off on a child's phone is the one outcome
# this script must never produce, which is what the trap below is for.
#
# It deliberately stops short of locking. Locking mints the parent's key, shows
# it once, and takes adb away — that is the parent's act in the app, not a
# script's.
#
# --update is the same verifier window without the provisioning: it pushes new
# APKs to a phone that is already a managed device and skips Device Owner. That
# is the other half of the delivery story. drawbridge cannot update itself —
# Play Protect refuses its PackageInstaller session too — so the supported route
# for a deployed handset is: unlock drawbridge with the parent's key, which
# hands USB debugging back, then run this. A locked phone has no adb and is
# deliberately unreachable.
#
# Usage:  tools/provision-adb.sh [--serial SERIAL] [--abi ABI] [--no-herald]
#                                [--mono] [--dir DIR] [--update] [--dry-run]
#
set -euo pipefail

serial=""
abi=""
install_herald=1
install_mono=0
apk_dir=""
dry_run=0
update_only=0

while [ $# -gt 0 ]; do
    case "$1" in
        --serial)     serial="$2"; shift 2 ;;
        --abi)        abi="$2"; shift 2 ;;
        --dir)        apk_dir="$2"; shift 2 ;;
        --no-herald)  install_herald=0; shift ;;
        --mono)       install_mono=1; shift ;;
        --update)     update_only=1; shift ;;
        --dry-run)    dry_run=1; shift ;;
        -h|--help)    sed -n '2,39p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'; exit 0 ;;
        *)            echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"
apk_dir="${apk_dir:-dist/release}"

# adb is not on PATH on the build machine; it lives under the SDK.
if command -v adb >/dev/null 2>&1; then
    adb_bin="$(command -v adb)"
elif [ -x "${ANDROID_HOME:-}/platform-tools/adb" ]; then
    adb_bin="${ANDROID_HOME}/platform-tools/adb"
elif [ -x "${HOME}/Library/Android/sdk/platform-tools/adb" ]; then
    adb_bin="${HOME}/Library/Android/sdk/platform-tools/adb"
else
    echo "adb not found. Install platform-tools, or put adb on PATH." >&2
    exit 1
fi

# Every device on the machine answers adb, and this script writes a global
# setting and grants Device Owner. Naming the wrong one is not recoverable by
# retrying, so an ambiguous device list is an error rather than a guess.
# macOS ships bash 3.2, which has no `mapfile` and no `readarray`. This is the
# build machine's own shell, so anything clever here fails on the one computer
# that matters.
if [ -z "${serial}" ]; then
    attached="$("${adb_bin}" devices | awk 'NR>1 && $2=="device" {print $1}')"
    count="$(printf '%s' "${attached}" | grep -c . || true)"
    case "${count}" in
        0) echo "No authorised device. Check the cable and accept the USB debugging prompt." >&2
           "${adb_bin}" devices -l >&2; exit 1 ;;
        1) serial="${attached}" ;;
        *) echo "More than one device attached; pass --serial." >&2
           "${adb_bin}" devices -l >&2; exit 1 ;;
    esac
fi

adb() { "${adb_bin}" -s "${serial}" "$@"; }

run() {
    if [ "${dry_run}" = 1 ]; then
        echo "  would run: $*"
    else
        "$@"
    fi
}

state="$("${adb_bin}" devices | awk -v s="${serial}" '$1==s {print $2}')"
[ "${state}" = "device" ] || {
    echo "Device ${serial} is '${state:-absent}', not 'device'." >&2
    echo "If it says 'unauthorized', accept the USB debugging prompt on the phone." >&2
    exit 1
}

model="$(adb shell getprop ro.product.model | tr -d '\r')"
release="$(adb shell getprop ro.build.version.release | tr -d '\r')"
echo "Device:  ${model} (${serial}), Android ${release}"

# --- preconditions ----------------------------------------------------------
#
# Each of these would otherwise fail the run later, and less legibly — after two
# APKs have been pushed, which on herald is 230 MB down a USB cable.

has_owner=0
adb shell dumpsys device_policy | grep -q "^  Device Owner:" && has_owner=1

if [ "${update_only}" = 1 ]; then
    # The mirror image of the provisioning checks: this mode is only meaningful
    # on a phone drawbridge already owns, and reaching one over adb at all means
    # somebody has unlocked it with the parent's key.
    if [ "${has_owner}" = 0 ]; then
        echo "--update is for a phone drawbridge already manages, and this one has" >&2
        echo "no device owner. Run without --update to provision it." >&2
        exit 1
    fi
else
    # Secondary users block Device Owner, and this check has to come *first*
    # because Android checks it first: a phone with both a second user and
    # accounts reports only the user, so fixing the accounts alone gets you the
    # same refusal again with a different sentence.
    #
    # Found on a Nothing Phone (A059, Android 16) on 2026-08-12, where the second
    # "user" was a **Private Space** — Android 15's hidden profile, which the
    # owner did not remember creating. It does not appear in the user switcher
    # and is invisible from `dumpsys account`, so without this check the failure
    # is a stack trace after both APKs have been pushed.
    #
    # Android refuses for the reason drawbridge would: the always-on VPN is
    # per-user, so a second profile gets unfiltered network. That is also why
    # DISALLOW_ADD_USER is applied unconditionally after provisioning.
    users="$(adb shell pm list users | grep -c 'UserInfo{')"
    if [ "${users:-1}" -gt 1 ]; then
        echo >&2
        echo "This device has ${users} users or profiles on it, so Device Owner cannot be" >&2
        echo "granted. Android refuses while any secondary user exists." >&2
        echo >&2
        adb shell pm list users | grep 'UserInfo{' | sed 's/^/    /' >&2
        echo >&2
        echo "A 'Private space' is Android 15's hidden profile. Delete it in" >&2
        echo "Settings -> Security & privacy -> Private Space -> Delete private space." >&2
        echo "It needs its own PIN to open, and deleting it removes everything inside." >&2
        echo "Any other extra user is in Settings -> System -> Multiple users." >&2
        exit 1
    fi

    # Device Owner can only be granted over adb on a device with no accounts. The
    # error `dpm` gives is clear enough, but it arrives after both APKs are pushed.
    #
    # Every account counts, not only Google ones — confirmed on the A059 on
    # 2026-08-12, which was refused with seven accounts of which none was Google
    # (DAVx5, Telegram and three banking apps).
    accounts="$(adb shell dumpsys account | sed -n 's/.*Accounts: \([0-9][0-9]*\).*/\1/p' | head -1)"
    if [ "${accounts:-0}" != "0" ]; then
        echo >&2
        echo "This device has ${accounts} account(s) on it, so Device Owner cannot be granted." >&2
        echo "Remove every account in Settings, or factory reset, and run this again." >&2
        echo "The parent's account is added *after* provisioning, before locking." >&2
        exit 1
    fi

    if [ "${has_owner}" = 1 ]; then
        current="$(adb shell dumpsys device_policy | sed -n 's/.*admin=ComponentInfo{\([^/]*\).*/\1/p' | head -1)"
        echo "This device already has a device owner (${current:-unknown})." >&2
        echo "To push new APKs to it instead, use --update." >&2
        echo "To provision from scratch, remove it from inside drawbridge or factory reset." >&2
        exit 1
    fi
fi

abi="${abi:-$(adb shell getprop ro.product.cpu.abi | tr -d '\r')}"

dpc_apk="${apk_dir}/dpc-release.apk"
herald_apk="${apk_dir}/herald-${abi}-release.apk"
mono_apk="${apk_dir}/herald-mono-${abi}-release.apk"

[ -f "${dpc_apk}" ] || { echo "missing: ${dpc_apk} — run tools/stage-release.sh first" >&2; exit 1; }
[ "${install_herald}" = 1 ] && [ ! -f "${herald_apk}" ] && {
    echo "missing: ${herald_apk} (device ABI is ${abi})" >&2; exit 1; }
[ "${install_mono}" = 1 ] && [ ! -f "${mono_apk}" ] && {
    echo "missing: ${mono_apk}" >&2; exit 1; }

# --- the verifier window ----------------------------------------------------

# `settings get` prints the string "null" when a setting was never written, and
# that is not the same as 0 or 1 — the platform default applies. Restoring means
# deleting the row rather than writing a value, so the phone is left exactly as
# it was found.
original="$(adb shell settings get global verifier_verify_adb_installs | tr -d '\r')"
verifier_restored=1

restore_verifier() {
    local status=$?
    if [ "${dry_run}" = 1 ]; then return "${status}"; fi
    if [ "${original}" = "null" ]; then
        adb shell settings delete global verifier_verify_adb_installs >/dev/null 2>&1 || true
    else
        adb shell settings put global verifier_verify_adb_installs "${original}" >/dev/null 2>&1 || true
    fi
    local now
    now="$(adb shell settings get global verifier_verify_adb_installs 2>/dev/null | tr -d '\r')"
    if [ "${now}" = "${original}" ]; then
        verifier_restored=1
        echo "adb install verification restored (${original})."
    else
        verifier_restored=0
        echo >&2
        echo "WARNING: could not restore verifier_verify_adb_installs — it reads '${now}'," >&2
        echo "and was '${original}'. This phone is not safe to hand over until it is put" >&2
        echo "back by hand:" >&2
        echo "  ${adb_bin} -s ${serial} shell settings put global verifier_verify_adb_installs 1" >&2
    fi
    return "${status}"
}
trap restore_verifier EXIT
# An interrupt during the ~230 MB herald push is the likely way out of this
# script, and it must not be the way a phone keeps a disabled verifier. Exiting
# from the signal handler is what puts the EXIT trap above in the path.
trap 'exit 130' INT TERM

echo "Pausing adb install verification (was: ${original})"
run adb shell settings put global verifier_verify_adb_installs 0

echo "Installing drawbridge…"
run adb install -r "${dpc_apk}"

if [ "${install_herald}" = 1 ]; then
    echo "Installing herald (${abi}, ~230 MB, a few minutes)…"
    run adb install -r "${herald_apk}"
fi
if [ "${install_mono}" = 1 ]; then
    echo "Installing herald mono (${abi})…"
    run adb install -r "${mono_apk}"
fi

# Restore before granting Device Owner rather than after. Nothing below needs
# the verifier off, and the shorter the window the smaller the claim this script
# has to make about it.
restore_verifier
trap - EXIT
[ "${verifier_restored}" = 1 ] || exit 1

if [ "${update_only}" = 1 ]; then
    if [ "${dry_run}" = 1 ]; then
        echo
        echo "Dry run: nothing was changed on the device."
        exit 0
    fi
    installed="$(adb shell dumpsys package app.drawbridge.dpc |
        sed -n 's/.*versionName=\(.*\)/\1/p' | head -1 | tr -d '\r')"
    cat <<EOF

Updated. drawbridge on ${model} is now ${installed:-unknown}.

Lock drawbridge again when you are done. Leaving it unlocked leaves USB
debugging available, which is the one thing the lock is there to close.
EOF
    exit 0
fi

echo "Granting Device Owner…"
run adb shell dpm set-device-owner \
    app.drawbridge.dpc/app.drawbridge.dpc.admin.DrawbridgeDeviceAdminReceiver

if [ "${dry_run}" = 1 ]; then
    echo
    echo "Dry run: nothing was changed on the device."
    exit 0
fi

adb shell dumpsys device_policy | grep -q "^  Device Owner:" || {
    echo "Device Owner does not appear to be set. Read the dpm output above." >&2
    exit 1
}

cat <<EOF

Provisioned. drawbridge is Device Owner on ${model}.

Nothing is enforced yet — that is deliberate, and this window is the only time
some of the following is possible.

Do these on the phone, in this order:

  1. Sign in with the PARENT's Google account. Never the child's.
  2. Set a screen lock.
  3. Open drawbridge, choose the language, read the policy, set the options.
  4. Tap Lock drawbridge, and write the key down before leaving that screen.

Locking switches USB debugging off, so adb will disconnect and cannot be used on
this phone again. Everything you want to install by cable must be installed now.
EOF
