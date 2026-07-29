#!/usr/bin/env bash
#
# Vendors uBlock Origin into herald's assets.
#
# herald installs uBO as a *built-in* web extension, which GeckoView loads
# unpacked from a resource://android/assets/ URI. That means the add-on has to
# be in the APK rather than fetched at runtime — which is the point: no
# dependency on addons.mozilla.org being reachable through the device's own DNS
# filter, no permission prompt, no add-on management surface, and a version
# pinned to the release.
#
# The XPI is downloaded from AMO and checked against the hash below, so the
# thing committed to the repo is reproducible rather than a blob someone
# dropped in. Re-run this to move to a newer uBO, updating VERSION, URL and
# SHA256 together.
#
# uBlock Origin is GPLv3 and ships here unmodified, as a separate program.
# See docs/design-decisions.md.
#
# Usage:  tools/vendor-ublock.sh
#
set -euo pipefail

VERSION="1.72.2"
URL="https://addons.mozilla.org/firefox/downloads/file/4888680/ublock_origin-${VERSION}.xpi"
SHA256="40c315b0da7871868155ecfae7a50a58dfa0920aebd865e008214986f1b7c578"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="${repo_root}/herald/src/main/assets/extensions/ublock"

workdir="$(mktemp -d)"
trap 'rm -rf "${workdir}"' EXIT

echo "Downloading uBlock Origin ${VERSION}…"
curl --fail --location --silent --show-error --output "${workdir}/ublock.xpi" "${URL}"

echo "Verifying…"
actual="$(shasum -a 256 "${workdir}/ublock.xpi" | cut -d' ' -f1)"
if [ "${actual}" != "${SHA256}" ]; then
    echo "SHA-256 mismatch." >&2
    echo "  expected ${SHA256}" >&2
    echo "  actual   ${actual}" >&2
    echo "Refusing to vendor an add-on that is not the pinned build." >&2
    exit 1
fi

echo "Unpacking into ${dest#"${repo_root}/"}…"
rm -rf "${dest}"
mkdir -p "${dest}"
unzip -q "${workdir}/ublock.xpi" -d "${dest}"

# The AMO signature covers the packed XPI and means nothing once unpacked; a
# built-in extension is trusted because it came out of the APK. Dropping it
# saves a quarter of a megabyte of noise in the repo and in every APK.
rm -rf "${dest}/META-INF"

# Recorded so the installed version is visible without reading a manifest, and
# so a stale checkout is obvious.
cat > "${dest}/VENDORED" <<EOF
uBlock Origin ${VERSION}
${URL}
sha256 ${SHA256}
Vendored by tools/vendor-ublock.sh. Do not edit by hand.
EOF

echo "uBlock Origin ${VERSION} vendored ($(du -sh "${dest}" | cut -f1))."
