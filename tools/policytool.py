#!/usr/bin/env python3
"""Key generation, signing and verification for drawbridge policy documents.

The apps accept a policy only if it is wrapped in an envelope signed by a key
whose public half is baked into the APK, so publishing an update is:

    tools/policytool.py sign --in dist/policy.json --out dist/policy.signed.json

Uses the system `openssl` binary and nothing else, so there is nothing to
install and the private key never has to leave an offline machine.

Signature scheme: ECDSA over NIST P-256 with SHA-256 (`SHA256withECDSA` in JCA),
chosen over Ed25519 because java.security only exposes Ed25519 from API 33 and
these apps support API 28.
"""

from __future__ import annotations

import argparse
import base64
import json
import pathlib
import subprocess
import sys
import tempfile

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_KEY_DIR = REPO_ROOT / "keys"
TRUSTED_KEYS_ASSET = (
    REPO_ROOT / "policy" / "src" / "main" / "assets" / "drawbridge" / "trusted-keys.json"
)
ALGORITHM = "ecdsa-p256-sha256"


def run(args: list[str], stdin: bytes | None = None) -> bytes:
    result = subprocess.run(args, input=stdin, capture_output=True)
    if result.returncode != 0:
        sys.exit(f"{' '.join(args)} failed:\n{result.stderr.decode(errors='replace')}")
    return result.stdout


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def cmd_genkey(args: argparse.Namespace) -> None:
    key_dir = pathlib.Path(args.key_dir)
    key_dir.mkdir(parents=True, exist_ok=True)
    private_key = key_dir / f"{args.key_id}.pem"

    if private_key.exists() and not args.force:
        sys.exit(f"{private_key} already exists; pass --force to overwrite it")

    run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout",
         "-out", str(private_key)])
    private_key.chmod(0o600)

    spki_der = run(["openssl", "ec", "-in", str(private_key), "-pubout", "-outform", "DER"])

    entry = {"key_id": args.key_id, "public_key": b64(spki_der)}
    if args.comment:
        entry["comment"] = args.comment

    if TRUSTED_KEYS_ASSET.exists():
        trusted = json.loads(TRUSTED_KEYS_ASSET.read_text())
    else:
        trusted = {"keys": []}

    trusted["keys"] = [k for k in trusted["keys"] if k["key_id"] != args.key_id]
    trusted["keys"].append(entry)
    TRUSTED_KEYS_ASSET.parent.mkdir(parents=True, exist_ok=True)
    TRUSTED_KEYS_ASSET.write_text(json.dumps(trusted, indent=2) + "\n")

    print(f"Private key:  {private_key}")
    print(f"Trusted keys: {TRUSTED_KEYS_ASSET}")
    print()
    print("Back this private key up offline now. Losing it means every deployed")
    print("device is stuck on its current policy until it is re-provisioned.")


LOCAL_LIST_URL_PREFIX = "https://raw.githubusercontent.com/Nilss3/drawbridge/main/"


def pin_local_lists(document: dict) -> None:
    """Recompute the SHA-256 of any blocklist served straight out of this repo.

    Lists we host ourselves are pinned, so their hash has to be refreshed
    whenever they are edited. Doing it at signing time means the two can never
    drift apart.
    """
    import hashlib

    for source in document.get("blocklists", []):
        url = source.get("url", "")
        if not url.startswith(LOCAL_LIST_URL_PREFIX):
            continue
        local = REPO_ROOT / url[len(LOCAL_LIST_URL_PREFIX):]
        if not local.exists():
            sys.exit(f"Blocklist {source['id']!r} points at {local}, which does not exist")
        digest = hashlib.sha256(local.read_bytes()).hexdigest()
        if source.get("sha256") != digest:
            print(f"  pinned {source['id']}: {digest}")
        source["sha256"] = digest


def cmd_sign(args: argparse.Namespace) -> None:
    payload = pathlib.Path(args.input).read_bytes()

    document = json.loads(payload)
    if "version" not in document:
        sys.exit("Policy document has no 'version' field")

    pin_local_lists(document)

    # Re-emit canonically so the signed bytes and the on-disk source agree.
    payload = (json.dumps(document, indent=2) + "\n").encode()
    pathlib.Path(args.input).write_bytes(payload)

    key_path = pathlib.Path(args.key or DEFAULT_KEY_DIR / f"{args.key_id}.pem")
    if not key_path.exists():
        sys.exit(f"No private key at {key_path}; run 'policytool.py genkey' first")

    with tempfile.NamedTemporaryFile(suffix=".json") as temp:
        temp.write(payload)
        temp.flush()
        signature = run(["openssl", "dgst", "-sha256", "-sign", str(key_path), temp.name])

    envelope = {
        "key_id": args.key_id,
        "algorithm": ALGORITHM,
        "payload": b64(payload),
        "signature": b64(signature),
    }
    out = pathlib.Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(envelope, indent=2) + "\n")
    print(f"Signed policy version {document['version']} -> {out}")


def cmd_verify(args: argparse.Namespace) -> None:
    envelope = json.loads(pathlib.Path(args.input).read_text())

    if envelope.get("algorithm") != ALGORITHM:
        sys.exit(f"Unexpected algorithm {envelope.get('algorithm')!r}")

    trusted = json.loads(TRUSTED_KEYS_ASSET.read_text())
    match = next((k for k in trusted["keys"] if k["key_id"] == envelope["key_id"]), None)
    if match is None:
        sys.exit(f"Envelope is signed by unknown key {envelope['key_id']!r}")

    payload = base64.b64decode(envelope["payload"])
    signature = base64.b64decode(envelope["signature"])

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = pathlib.Path(tmp)
        pub_der = tmp_path / "pub.der"
        pub_der.write_bytes(base64.b64decode(match["public_key"]))
        payload_file = tmp_path / "payload.json"
        payload_file.write_bytes(payload)
        sig_file = tmp_path / "sig.bin"
        sig_file.write_bytes(signature)

        run(["openssl", "dgst", "-sha256", "-verify", str(pub_der), "-keyform", "DER",
             "-signature", str(sig_file), str(payload_file)])

    document = json.loads(payload)
    print(f"Signature OK. Policy version {document['version']}, "
          f"{len(document.get('blocklists', []))} blocklist sources, "
          f"{len(document.get('blocked_packages', []))} blocked packages.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    gen = sub.add_parser("genkey", help="create a signing key and register its public half")
    gen.add_argument("--key-id", required=True, help="e.g. drawbridge-2026-07")
    gen.add_argument("--key-dir", default=str(DEFAULT_KEY_DIR))
    gen.add_argument("--comment")
    gen.add_argument("--force", action="store_true")
    gen.set_defaults(func=cmd_genkey)

    sign = sub.add_parser("sign", help="wrap a policy document in a signed envelope")
    sign.add_argument("--key-id", required=True)
    sign.add_argument("--key", help="path to the private key PEM")
    sign.add_argument("--in", dest="input", default=str(REPO_ROOT / "dist" / "policy.json"))
    sign.add_argument("--out", dest="output",
                      default=str(REPO_ROOT / "dist" / "policy.signed.json"))
    sign.set_defaults(func=cmd_sign)

    verify = sub.add_parser("verify", help="check a signed envelope against the trusted keys")
    verify.add_argument("--in", dest="input",
                        default=str(REPO_ROOT / "dist" / "policy.signed.json"))
    verify.set_defaults(func=cmd_verify)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
