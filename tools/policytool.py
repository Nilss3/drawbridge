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


LOCAL_LIST_URL_BASE = "https://raw.githubusercontent.com/Nilss3/drawbridge/"

# The branches a signed policy may name. A list URL is rewritten to whichever of
# these is checked out, so the channel a policy belongs to is derived rather than
# typed — see rewrite_local_list_urls.
CHANNELS = ("main", "dev")


def local_list_path(url: str) -> str | None:
    """The repo-relative path of a blocklist this repo hosts, or None.

    Recognises the URL on any channel, because the same list is served from
    `main` for the alpha and from `dev` for test builds.
    """
    if not url.startswith(LOCAL_LIST_URL_BASE):
        return None
    rest = url[len(LOCAL_LIST_URL_BASE):]
    branch, _, path = rest.partition("/")
    return path if branch in CHANNELS and path else None


def current_channel() -> str | None:
    """The checked-out branch, when it is one a policy may be signed on."""
    branch = subprocess.run(
        ["git", "rev-parse", "--abbrev-ref", "HEAD"],
        cwd=REPO_ROOT, capture_output=True, text=True,
    ).stdout.strip()
    return branch if branch in CHANNELS else None


def rewrite_local_list_urls(document: dict) -> None:
    """Point every list this repo hosts at the branch being signed on.

    The dev channel gives the *document* a staging path, but a document is a set
    of URLs and the lists it names are fetched separately. A policy signed on
    `dev` that still names `main`'s copy of a list cannot test a change to that
    list at all: the file does not exist on `main` yet, PolicyStore drops an
    unreachable source and compiles the rest, and the device quietly filters
    less than the document promises.

    Hand-editing the branch into the URL is the obvious fix and the dangerous
    one, because it survives a merge: `dev`'s policy landing on `main` would
    point every alpha phone at `dev`'s lists. Deriving it from the checked-out
    branch instead means signing on `main` always produces `main` URLs, and the
    trap cannot be set.

    On any other branch the URLs are left exactly as they are, since a policy
    signed there names a branch that may never be pushed.
    """
    channel = current_channel()
    if channel is None:
        return

    for source in document.get("blocklists", []):
        path = local_list_path(source.get("url", ""))
        if path is None:
            continue
        wanted = f"{LOCAL_LIST_URL_BASE}{channel}/{path}"
        if source["url"] != wanted:
            print(f"  channel {source['id']}: -> {channel}")
            source["url"] = wanted


def pin_local_lists(document: dict) -> None:
    """Recompute the SHA-256 of any blocklist served straight out of this repo.

    Lists we host ourselves are pinned, so their hash has to be refreshed
    whenever they are edited. Doing it at signing time means the two can never
    drift apart.
    """
    import hashlib

    for source in document.get("blocklists", []):
        path = local_list_path(source.get("url", ""))
        if path is None:
            continue
        local = REPO_ROOT / path
        if not local.exists():
            sys.exit(f"Blocklist {source['id']!r} points at {local}, which does not exist")
        digest = hashlib.sha256(local.read_bytes()).hexdigest()
        if source.get("sha256") != digest:
            print(f"  pinned {source['id']}: {digest}")
        source["sha256"] = digest


def check_options_mirror_lists(document: dict) -> list[str]:
    """An option that unblocks a whole category has to name every domain in it.

    Options only ever widen: `withOptions` merges `allowed_domains` into the
    policy and allow beats block. So a domain that is in a category's list but
    missing from the option meant to restore that category is one the switch
    silently fails to bring back, and nothing anywhere would say so — the
    parent turns streaming on, and one service in fifty stays dark.

    The rule is narrow on purpose: it only fires where an option's `id` matches
    the `category` of a list this repo hosts, which is the case where the two
    are meant to be the same set. YouTube is not caught by it and should not be
    — those domains live inside the social list, mixed in with everything else.
    """
    by_category: dict[str, list[str]] = {}
    for source in document.get("blocklists", []):
        path = local_list_path(source.get("url", ""))
        category = source.get("category")
        if path is None or not category:
            continue
        local = REPO_ROOT / path
        if not local.exists():
            continue
        entries = [
            line.strip()
            for line in local.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")
        ]
        by_category.setdefault(category, []).extend(entries)

    problems = []
    for option in document.get("options", []):
        wanted = by_category.get(option.get("id"))
        if not wanted:
            continue
        missing = [d for d in wanted if d not in set(option.get("allowed_domains", []))]
        if missing:
            problems.append(
                f"option {option['id']!r} does not allow "
                f"{len(missing)} domain(s) its own list blocks: "
                + ", ".join(missing[:5])
                + (" ..." if len(missing) > 5 else "")
            )
    return problems


def check_whitelist_is_coherent(document: dict) -> list[str]:
    """`app_ratings.allowed_packages` must not quietly undo a decision.

    The whitelist exists because blocking every rating above PEGI 3 takes a
    phone's messengers with it — see docs/app-ratings.md. It is consulted
    **before** the blocklist and before the options, which is what makes it
    useful and also what makes these two mistakes silent:

    1. **A package on both lists is unblocked**, not blocked. The whitelist wins,
       so adding a name to it can undo a `blocked_packages` entry that somebody
       put there deliberately — and nothing on a phone or in this tool would say
       so. The first draft of the whitelist was assembled from measurements of
       apps the *rating rule* would remove, which is exactly the process that
       could drift into naming one the *policy* removes.
    2. **A package an option governs stops being the parent's decision.**
       WhatsApp and Telegram are reached by a switch on the configuration screen;
       whitelisting either would keep it whatever that switch said, leaving a
       control that moves and changes nothing. That is the failure this project
       already paid for once, when a restore subtracted exactly what an option
       had allowed.

    Both are cheap to check and impossible to notice by hand once the whitelist
    is a few dozen names long.
    """
    ratings = document.get("app_ratings") or {}
    allowed = ratings.get("allowed_packages") or []
    if not allowed:
        return []

    problems = []

    blocked = set(document.get("blocked_packages", []))
    overlap = sorted(set(allowed) & blocked)
    if overlap:
        problems.append(
            f"app_ratings.allowed_packages unblocks {len(overlap)} package(s) that "
            "blocked_packages names — the whitelist is consulted first: "
            + ", ".join(overlap[:5])
            + (" ..." if len(overlap) > 5 else "")
        )

    governed: dict[str, str] = {}
    for option in document.get("options", []):
        for package in option.get("exempt_packages", []) + option.get("allowed_packages", []):
            governed.setdefault(package, option.get("id", "?"))
    captured = sorted(p for p in allowed if p in governed)
    if captured:
        problems.append(
            "app_ratings.allowed_packages overrides an option for "
            f"{len(captured)} package(s), so the switch would do nothing: "
            + ", ".join(f"{p} (option {governed[p]!r})" for p in captured[:5])
            + (" ..." if len(captured) > 5 else "")
        )

    duplicates = sorted({p for p in allowed if allowed.count(p) > 1})
    if duplicates:
        problems.append("app_ratings.allowed_packages repeats: " + ", ".join(duplicates))

    return problems


def check_urls_resolve(document: dict, *, published: bool = False) -> list[str]:
    """Fetch every URL the policy names and report the ones that do not resolve.

    Returns the fatal failures; callers decide what to do about them. The
    caller also decides *which* failures are fatal, through `published`.

    A signed, verifying policy is not necessarily a working one: the
    signature covers what the document *says*, not whether the internet still
    agrees. HaGeZi restructured its repository and moved `domains/` to
    `wildcard/`, and two blocklists 404'd on every device for as long as
    nobody happened to click them. Nothing failed loudly, because
    PolicyStore drops an unreachable source and compiles the rest.

    **Signing and checking a live policy want different answers.** When
    signing, only third-party blocklists are fatal — the lists this repo hosts
    are pinned from the working tree and legitimately 404 until the commit is
    pushed (a brand-new list always would), and `required_apps` / `app_update`
    404 until the release assets are uploaded, which the documented order does
    *after* the policy is written. Treating those as fatal would block a
    correct re-sign.

    Once the policy is published, none of that holds: every URL in it is one
    devices are fetching right now, so a 404 anywhere means a device is
    silently getting less than the document promises. `published=True` makes
    them all fatal, which is what the health check wants.

    Use --skip-url-check to sign offline.
    """
    import urllib.error
    import urllib.request

    def reachable(url: str) -> tuple[bool, str]:
        # HEAD first; some hosts refuse it, so fall back to a ranged GET
        # rather than pulling a 200k-line blocklist just to see a status.
        for method, headers in (("HEAD", {}), ("GET", {"Range": "bytes=0-0"})):
            try:
                request = urllib.request.Request(
                    url, method=method, headers={"User-Agent": "drawbridge-policytool", **headers}
                )
                with urllib.request.urlopen(request, timeout=30) as response:
                    return True, str(response.status)
            except urllib.error.HTTPError as e:
                if method == "HEAD" and e.code in (403, 405, 501):
                    continue  # HEAD not allowed here; try the ranged GET
                return False, str(e.code)
            except Exception as e:  # DNS failure, TLS error, timeout
                return False, type(e).__name__
        return False, "unreachable"

    targets: list[tuple[str, str, bool]] = []  # (label, url, fatal)
    for source in document.get("blocklists", []):
        url = source.get("url", "")
        own = local_list_path(url) is not None
        targets.append((f"blocklist {source.get('id')!r}", url, published or not own))
    for app in document.get("required_apps", []):
        targets.append((f"required_app {app.get('package_name')} ({app.get('abi')})",
                        app.get("url", ""), published))
    if document.get("app_update"):
        targets.append(("app_update", document["app_update"].get("url", ""), published))

    failures: list[str] = []
    warnings: list[str] = []
    for label, url, fatal in targets:
        ok, detail = reachable(url)
        if ok:
            continue
        message = f"{label}: {detail} for {url}"
        (failures if fatal else warnings).append(message)

    for message in warnings:
        print(f"  warning: unreachable, not yet published? {message}")
    print(f"  checked {len(targets)} URLs")
    return failures


def cmd_sign(args: argparse.Namespace) -> None:
    payload = pathlib.Path(args.input).read_bytes()

    document = json.loads(payload)
    if "version" not in document:
        sys.exit("Policy document has no 'version' field")

    rewrite_local_list_urls(document)
    pin_local_lists(document)

    mismatches = check_options_mirror_lists(document) + check_whitelist_is_coherent(document)
    if mismatches:
        sys.exit("Refusing to sign:\n  " + "\n  ".join(mismatches))

    if not args.skip_url_check:
        failures = check_urls_resolve(document)
        if failures:
            sys.exit(
                "Refusing to sign: these URLs do not resolve.\n  "
                + "\n  ".join(failures)
                + "\n(--skip-url-check to sign anyway)"
            )

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

    # The health check the signature cannot give you. A valid signature says
    # what the document claims; it says nothing about whether the internet
    # still agrees. Two HaGeZi lists once 404'd on every device for as long as
    # nobody happened to click one, because an unreachable source is dropped
    # and the rest compiled. Run this against the *published* policy on a
    # schedule -- it needs no key, mints no signature, and changes no file.
    if getattr(args, "check_urls", False):
        failures = check_urls_resolve(document, published=True)
        if failures:
            sys.exit(
                "\nSignature is valid, but these URLs do not resolve:\n  "
                + "\n  ".join(failures)
                + "\n\nDevices fetching this policy are silently getting less than it "
                  "promises: an unreachable blocklist is dropped and the rest compiled, "
                  "and an unreachable app simply never installs."
            )
        print("Every URL this policy names resolves.")


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
    sign.add_argument("--skip-url-check", action="store_true",
                      help="do not fetch the URLs the policy names (for signing offline)")
    sign.add_argument("--out", dest="output",
                      default=str(REPO_ROOT / "dist" / "policy.signed.json"))
    sign.set_defaults(func=cmd_sign)

    verify = sub.add_parser("verify", help="check a signed envelope against the trusted keys")
    verify.add_argument("--check-urls", action="store_true",
                        help="also fetch every URL the policy names, and fail if any is dead "
                             "(needs no key; the monthly health check for a published policy)")
    verify.add_argument("--in", dest="input",
                        default=str(REPO_ROOT / "dist" / "policy.signed.json"))
    verify.set_defaults(func=cmd_verify)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
