#!/usr/bin/env python3
"""Generate the QR provisioning payload for drawbridge.

On a factory-reset device, tapping the welcome screen six times opens a hidden
QR scanner. Scanning this payload makes the device download, verify and install
drawbridge, and grants it Device Owner — all before any account is ever added,
which is what makes the Factory Reset Protection backstop meaningful.

The checksum is the SHA-256 of the APK's *signing certificate*, not of the APK
file, so the payload stays valid across releases signed with the same key.

    tools/qrpayload.py --apk release/dpc-release.apk \\
        --url https://github.com/Nilss3/drawbridge/releases/download/v1/dpc-release.apk

Some custom ROMs ship a setup wizard without the six-tap gesture. There, use adb
on a device with no accounts configured:

    adb shell dpm set-device-owner \\
        app.drawbridge.dpc/app.drawbridge.dpc.admin.DrawbridgeDeviceAdminReceiver
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys

ADMIN_COMPONENT = (
    "app.drawbridge.dpc/app.drawbridge.dpc.admin.DrawbridgeDeviceAdminReceiver"
)


def find_apksigner() -> str:
    found = shutil.which("apksigner")
    if found:
        return found

    # apksigner lives inside the SDK's build-tools rather than on PATH.
    roots = [pathlib.Path.home() / "Library/Android/sdk"]
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(variable)
        if value:
            roots.append(pathlib.Path(value))

    for root in roots:
        candidates = sorted((root / "build-tools").glob("*/apksigner"), reverse=True)
        if candidates:
            return str(candidates[0])

    sys.exit("Could not find apksigner. Install Android build-tools or put it on PATH.")


def signing_certificate_digest(apk: pathlib.Path) -> str:
    """SHA-256 of the signing certificate, URL-safe base64 without padding."""
    output = subprocess.run(
        [find_apksigner(), "verify", "--print-certs", str(apk)],
        capture_output=True,
        text=True,
    )
    if output.returncode != 0:
        sys.exit(
            f"apksigner could not verify {apk}. Is it signed?\n{output.stderr.strip()}"
        )

    match = re.search(r"SHA-256 digest:\s*([0-9a-fA-F]+)", output.stdout)
    if not match:
        sys.exit(f"No certificate digest in apksigner output:\n{output.stdout}")

    raw = bytes.fromhex(match.group(1))
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--apk", required=True, type=pathlib.Path,
                        help="the signed drawbridge release APK")
    parser.add_argument("--url", required=True,
                        help="HTTPS URL the device downloads the APK from")
    parser.add_argument("--out", type=pathlib.Path,
                        default=pathlib.Path("dist/provisioning-qr.json"))
    parser.add_argument("--wifi-ssid", help="optional: join this network before downloading")
    parser.add_argument("--wifi-password")
    parser.add_argument("--wifi-security", default="WPA", choices=["WPA", "WEP", "NONE"])
    parser.add_argument("--skip-encryption", action="store_true",
                        help="skip the encryption step during provisioning")
    args = parser.parse_args()

    if not args.url.lower().startswith("https://"):
        sys.exit("The download URL must be https; the device refuses plain http.")

    if not args.apk.exists():
        sys.exit(f"{args.apk} does not exist")

    payload = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": ADMIN_COMPONENT,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": args.url,
        # The signature checksum is preferred over the package checksum: it is
        # tied to the signing key rather than to one specific build.
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM":
            signing_certificate_digest(args.apk),
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": args.skip_encryption,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
    }

    if args.wifi_ssid:
        payload["android.app.extra.PROVISIONING_WIFI_SSID"] = args.wifi_ssid
        payload["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = args.wifi_security
        if args.wifi_password:
            payload["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = args.wifi_password

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, indent=2) + "\n")

    print(f"Wrote {args.out}")
    print()
    print("Turn it into a QR code with any encoder, e.g.:")
    print(f"  qrencode -o provisioning.png -r {args.out}")
    print()
    print("Then, on a factory-reset device, tap the welcome screen six times to")
    print("open the scanner. Do this before adding any account.")


if __name__ == "__main__":
    main()
