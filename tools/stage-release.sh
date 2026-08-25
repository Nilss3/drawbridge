#!/usr/bin/env bash
#
# Copies built APKs into dist/release/ under the names the release publishes,
# then checks that every name the signed policy pins is actually there.
#
# This exists because of one silent failure mode. `required_apps` in the policy
# names each APK by URL under /releases/latest/download/, so the published asset
# names are load-bearing: a name that drifts means every provisioned device
# fetches a 404 and quietly stops updating herald. Nothing would report it.
#
# Adding the mono flavour is exactly the kind of change that drifts them —
# Gradle now emits herald-standard-<abi>-release.apk, while the policy has
# always pinned herald-<abi>-release.apk. The standard edition therefore keeps
# its historical names here, mono takes its own, and the check at the end fails
# loudly rather than letting a release go out that devices cannot install.
#
# One more failure mode, found the hard way on 2026-08-24 while cutting a
# dpc-only release. This script re-copies herald out of herald/build/outputs
# every time it runs, whatever it is being run for — and on a dpc-only release
# herald has not been rebuilt, so what sits in the build tree is some older
# local build that is *not* the one the published release carries. Copying it
# over dist/release overwrites the published APKs with binaries that no phone
# will accept, and the check below then correctly reports six STALE files.
# Restoring them meant re-downloading 1.3 GB from the GitHub release.
#
# The check caught it, which is what it is for. --dpc-only stops it happening:
# it leaves the herald APKs in dist/release exactly as they are and still runs
# the check against them, which is the honest thing to verify on a release that
# does not touch herald.
#
# Usage:  tools/stage-release.sh [debug|release] [--dpc-only]   (default: release)
#
set -euo pipefail

build_type="release"
dpc_only=0
for arg in "$@"; do
    case "${arg}" in
        --dpc-only) dpc_only=1 ;;
        debug|release) build_type="${arg}" ;;
        *) echo "unknown argument: ${arg}" >&2; exit 2 ;;
    esac
done
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

# Release staging goes to dist/release/, which is what gets published and what
# is tracked in git. Debug staging goes somewhere else entirely: mixing the two
# once left debug APKs sitting next to the published release assets with a
# SHA256SUMS covering both.
if [ "${build_type}" = "release" ]; then
    dest="dist/release"
else
    dest="dist/${build_type}"
fi
mkdir -p "${dest}"

abis=(arm64-v8a armeabi-v7a x86_64)

echo "Staging ${build_type} APKs into ${dest}/"

if [ "${dpc_only}" = "1" ]; then
    echo "--dpc-only: leaving the herald APKs in ${dest}/ untouched"
else
    # Standard edition keeps the names the policy has always pinned.
    for abi in "${abis[@]}"; do
        src="herald/build/outputs/apk/standard/${build_type}/herald-standard-${abi}-${build_type}.apk"
        [ -f "${src}" ] || { echo "missing: ${src}" >&2; exit 1; }
        cp "${src}" "${dest}/herald-${abi}-${build_type}.apk"
    done

    # Mono is a separate app and has never been published, so it takes the
    # straightforward name.
    for abi in "${abis[@]}"; do
        src="herald/build/outputs/apk/mono/${build_type}/herald-mono-${abi}-${build_type}.apk"
        if [ -f "${src}" ]; then
            cp "${src}" "${dest}/herald-mono-${abi}-${build_type}.apk"
        else
            echo "note: no mono ${abi} build, skipping"
        fi
    done
fi

dpc="dpc/build/outputs/apk/${build_type}/dpc-${build_type}.apk"
[ -f "${dpc}" ] && cp "${dpc}" "${dest}/"

echo "Regenerating SHA256SUMS"
(cd "${dest}" && shasum -a 256 ./*.apk | sed 's|\./||' > SHA256SUMS)

# The check that makes the rest of this worth doing: every APK the signed
# policy names by URL must exist here, under exactly that name. Only meaningful
# for a release — the policy pins release builds.
if [ "${build_type}" != "release" ]; then
    echo "Staged ${build_type} builds in ${dest}/ (policy check skipped — release only)."
    ls -lh "${dest}"
    exit 0
fi

echo "Checking against required_apps in dist/policy.signed.json"
python3 - "${dest}" <<'PY'
import base64, hashlib, json, os, re, shutil, subprocess, sys

dest = sys.argv[1]
policy = json.loads(
    base64.b64decode(json.load(open("dist/policy.signed.json"))["payload"])
)


def version_code_of(path):
    """The versionCode inside an APK, or None when aapt2 is not to hand.

    Read from the binary because typing it is exactly what went wrong on
    2026-08-25: required_apps was re-pinned for a new release, the URLs and
    checksums were updated, and the version codes were left behind. Every
    checksum matched, so this script said ok, and the phones compared the
    declared 14 against the installed 14 and never fetched herald 15.
    """
    aapt = shutil.which("aapt2")
    if aapt is None:
        sdk = os.path.expanduser("~/Library/Android/sdk/build-tools")
        if os.path.isdir(sdk):
            found = sorted(
                os.path.join(sdk, d, "aapt2")
                for d in os.listdir(sdk)
                if os.path.exists(os.path.join(sdk, d, "aapt2"))
            )
            aapt = found[-1] if found else None
    if aapt is None:
        return None
    out = subprocess.run([aapt, "dump", "badging", path], capture_output=True).stdout.decode()
    found = re.search(r"versionCode='(\d+)'", out)
    return int(found.group(1)) if found else None


failed = False
unchecked = 0
for app in policy.get("required_apps", []):
    name = app["url"].rsplit("/", 1)[1]
    path = os.path.join(dest, name)
    if not os.path.exists(path):
        print(f"  MISSING  {name} — the policy pins it; devices would get a 404")
        failed = True
        continue
    digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if digest != app["sha256"]:
        print(f"  STALE    {name} — hash differs from the policy pin")
        print(f"           policy {app['sha256']}")
        print(f"           staged {digest}")
        failed = True
        continue

    actual = version_code_of(path)
    declared = app.get("version_code")
    if actual is None:
        unchecked += 1
        print(f"  ok       {name} (version code unchecked: no aapt2)")
    elif actual != declared:
        # Not fatal to the download and fatal to the point of it: a phone
        # installs a required app only when the declared version is higher than
        # the one it has, so a stale number is an update that silently never
        # happens.
        print(f"  STALE    {name} — the policy declares version_code {declared},")
        print(f"           the APK is {actual}. A phone already on {declared} compares")
        print(f"           {declared} > {declared}, finds it false, and never fetches it.")
        failed = True
    else:
        print(f"  ok       {name} (version_code {actual})")

if unchecked:
    print(f"\n{unchecked} version code(s) unchecked — put aapt2 on PATH to check them.")

if failed:
    print("\nStaged artefacts do not match the signed policy.", file=sys.stderr)
    print("If herald was rebuilt, re-pin required_apps — url, sha256 *and*", file=sys.stderr)
    print("version_code — and re-sign.", file=sys.stderr)
    sys.exit(1)
print("\nEvery APK the policy pins is staged under the right name and version.")
PY

ls -lh "${dest}"
