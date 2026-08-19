#!/usr/bin/env python3
"""Runs the app-admission rule against the Play Store, offline from any phone.

This is step 1 of the build order in docs/app-ratings.md, and it comes before any
device code on purpose. The rule it implements decides what gets uninstalled from
somebody's phone, and the only honest way to choose its thresholds is to run it
over a few hundred real apps and look at what it would have removed.

Nothing here touches a handset, needs an account, or uses Google's private
storefront API. It reads the public listing page, which carries the same
structured fields — see docs/app-ratings.md for why that matters on a project
whose founding constraint is "no account, no backend".

  tools/app-ratings.py check com.whatsapp ai.x.grok
  tools/app-ratings.py audit --corpus tools/corpora/useful.txt
  tools/app-ratings.py audit --policy-blocklist          # is the curated list still needed?
  tools/app-ratings.py search "ai girlfriend" "ai companion"

**Two extractions were written and thrown away before this one.** The listing
page contains two to four different rating strings — carousels, "similar apps" —
so a text search for "PEGI" silently returns a neighbouring app's rating. Always
anchor on the schema.org microdata or the JSON-LD, and treat a disagreement
between them as a parse failure rather than picking one.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import html
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE_PATH = REPO_ROOT / "tools" / ".app-ratings-cache.json"
POLICY_PATH = REPO_ROOT / "dist" / "policy.json"

# Thirty days. Ratings change rarely, and the device-side design invalidates on
# versionCode change instead -- which this script cannot see, so it uses time.
CACHE_TTL_SECONDS = 30 * 24 * 3600

USER_AGENT = "Mozilla/5.0 (compatible; drawbridge-policy-tool/1.0)"

# The two structured fields, in the order the spec names them. Never a bare
# text search: see the module docstring.
MICRODATA_RATING = re.compile(
    r'itemprop="contentRating"[^>]*>\s*<span>([^<]+)</span>'
)
JSONLD_RATING = re.compile(r'"contentRating"\s*:\s*"([^"]+)"')
JSONLD_CATEGORY = re.compile(r'"applicationCategory"\s*:\s*"([^"]{1,40})"')
DESCRIPTORS = re.compile(r'<div class="JHmoid">([^<]*)</div>')
TITLE = re.compile(r"<title[^>]*>([^<]+)</title>")

# Mirrors the `app_ratings` block the signed policy will carry. Kept here as a
# fallback so the script runs against a policy that predates the field.
DEFAULT_RULE = {
    "store_region": "BE",
    "allowed_ratings": ["pegi 3", "everyone", "usk: all ages", "rated 3+"],
    "neutral_ratings": ["parental guidance"],
    "blocked_category_prefixes": ["GAME_"],
    "blocked_categories": ["DATING"],
}


# --- fetching ---------------------------------------------------------------


class Cache:
    """Package -> metadata, on disk, so a re-run costs nothing.

    Git-ignored: it is derived data, it goes stale, and a policy decision should
    never be reviewed against a checked-in copy of what Play said last month.
    """

    def __init__(self, path: pathlib.Path = CACHE_PATH) -> None:
        self.path = path
        self.data: dict[str, dict] = {}
        if path.exists():
            self.data = json.loads(path.read_text())

    def get(self, key: str) -> dict | None:
        entry = self.data.get(key)
        if entry is None:
            return None
        if time.time() - entry.get("fetched_at", 0) > CACHE_TTL_SECONDS:
            return None
        return entry

    def put(self, key: str, value: dict) -> None:
        self.data[key] = {**value, "fetched_at": time.time()}

    def save(self) -> None:
        self.path.write_text(json.dumps(self.data, indent=1, sort_keys=True))


def fetch(package: str, region: str, cache: Cache) -> dict:
    key = f"{package}@{region}"
    cached = cache.get(key)
    if cached is not None:
        return cached

    url = (
        "https://play.google.com/store/apps/details"
        f"?id={urllib.parse.quote(package)}&hl=en&gl={region}"
    )
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        body = urllib.request.urlopen(request, timeout=30).read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        # 404 is a real answer: this package is not on Play at all, which the
        # device-side rule treats as "no verdict" rather than as a block.
        result = {"package": package, "error": f"HTTP {error.code}"}
        cache.put(key, result)
        return result
    except Exception as error:  # noqa: BLE001 - network, and the caller wants the reason
        return {"package": package, "error": type(error).__name__}

    micro = MICRODATA_RATING.search(body)
    jsonld = JSONLD_RATING.search(body)
    micro_value = micro.group(1).strip() if micro else None
    jsonld_value = jsonld.group(1).strip() if jsonld else None

    # Their agreement is a free integrity check on the parser. They agreed on all
    # 86 pages measured on 2026-08-16, so a disagreement means the page shape
    # moved and this script is no longer reading what it thinks it is.
    if micro_value and jsonld_value and micro_value != jsonld_value:
        result = {
            "package": package,
            "error": f"anchors disagree: {micro_value!r} vs {jsonld_value!r}",
        }
        return result

    rating = micro_value or jsonld_value
    if rating is None:
        return {"package": package, "error": "no rating field on the page"}

    category = JSONLD_CATEGORY.search(body)
    title = TITLE.search(body)
    descriptors = DESCRIPTORS.search(body)
    result = {
        "package": package,
        "title": (
            html.unescape(title.group(1)).replace(" - Apps on Google Play", "").strip()
            if title
            else ""
        ),
        "rating": rating,
        "category": category.group(1) if category else "",
        "descriptors": descriptors.group(1).strip() if descriptors else "",
    }
    cache.put(key, result)
    return result


def fetch_all(packages: list[str], region: str, cache: Cache) -> list[dict]:
    # Six at a time: enough to be quick over a few hundred, gentle enough not to
    # look like scraping to whatever sits in front of the listing pages.
    with concurrent.futures.ThreadPoolExecutor(6) as pool:
        return list(pool.map(lambda p: fetch(p, region, cache), packages))


# --- the rule ---------------------------------------------------------------


def load_rule() -> dict:
    if not POLICY_PATH.exists():
        return DEFAULT_RULE
    policy = json.loads(POLICY_PATH.read_text())
    return {**DEFAULT_RULE, **policy.get("app_ratings", {})}


def verdict(meta: dict, rule: dict) -> tuple[str, str]:
    """The store half of the rule -- branches 6 and 7 of docs/app-ratings.md.

    Deliberately *not* the whole rule. Branches 1-5 are answered by the policy's
    own lists and by the device (is this preinstalled? is it a browser?), and
    branch 8 by a phone's snapshot. Re-implementing those here would be a second
    copy of rules this project has already paid for keeping in one place; what
    this answers is the part that needs the network.
    """
    if "error" in meta:
        # Fail open, visibly. See docs/app-ratings.md: drawbridge has no
        # parent-approval channel on a locked phone, so the alternative to
        # keeping is uninstalling on a network blip.
        return "unverified", meta["error"]

    category = (meta.get("category") or "").upper()
    for prefix in rule["blocked_category_prefixes"]:
        if category.startswith(prefix.upper()):
            return "remove", f"category {category}"
    if category in {c.upper() for c in rule["blocked_categories"]}:
        return "remove", f"category {category}"

    rating = (meta.get("rating") or "").strip().lower()
    if rating in {r.lower() for r in rule["allowed_ratings"]}:
        return "keep", f"rating {meta['rating']}"
    if rating in {r.lower() for r in rule["neutral_ratings"]}:
        return "neutral", f"rating {meta['rating']} — the rest of policy decides"
    return "remove", f"rating {meta['rating']}"


# --- reporting --------------------------------------------------------------

MARK = {"keep": "keep     ", "remove": "REMOVE   ", "neutral": "neutral  ", "unverified": "unverified"}


def report(rows: list[tuple[dict, tuple[str, str]]], *, expect: str | None) -> int:
    width = max((len(m.get("title") or m["package"]) for m, _ in rows), default=10)
    width = min(width, 34)
    for meta, (call, why) in sorted(rows, key=lambda r: (r[1][0], r[0]["package"])):
        flag = ""
        if expect and call != expect and call != "neutral":
            flag = "   <-- unexpected"
        name = (meta.get("title") or meta["package"])[:width]
        print(f"  {MARK[call]} {name:{width}}  {meta['package']:38} {why}{flag}")

    counts: dict[str, int] = {}
    for _, (call, _) in rows:
        counts[call] = counts.get(call, 0) + 1
    print("\n  " + ", ".join(f"{v} {k}" for k, v in sorted(counts.items())))

    if expect:
        surprises = [m for m, (c, _) in rows if c != expect and c != "neutral"]
        if surprises:
            print(
                f"\n  {len(surprises)} of {len(rows)} did not come out as '{expect}'. "
                "Those are the ones worth reading."
            )
        return len(surprises)
    return 0


def read_corpus(path: pathlib.Path) -> list[str]:
    packages = []
    for line in path.read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            packages.append(line)
    return packages


# --- commands ---------------------------------------------------------------


def cmd_check(args: argparse.Namespace) -> int:
    cache, rule = Cache(), load_rule()
    rows = [(m, verdict(m, rule)) for m in fetch_all(args.packages, rule["store_region"], cache)]
    cache.save()
    return report(rows, expect=None)


def cmd_audit(args: argparse.Namespace) -> int:
    cache, rule = Cache(), load_rule()

    if args.policy_blocklist:
        policy = json.loads(POLICY_PATH.read_text())
        packages = sorted(set(policy.get("blocked_packages", [])))
        expect = "remove"
        print(
            f"Running the store rule over {len(packages)} packages the policy already "
            "blocks by name.\nAnything that comes out 'keep' is a package the rule does "
            "NOT catch — i.e. one\nthe curated list is still carrying on its own.\n"
        )
    else:
        packages = read_corpus(pathlib.Path(args.corpus))
        expect = args.expect
        print(f"Running the store rule over {len(packages)} packages from {args.corpus}.\n")

    rows = [(m, verdict(m, rule)) for m in fetch_all(packages, rule["store_region"], cache)]
    cache.save()
    return report(rows, expect=expect)


def cmd_search(args: argparse.Namespace) -> int:
    """Discovery, which is the half a per-package lookup cannot do.

    A rating answers "tell me about com.x.y". The problem this whole spec exists
    for is "what is the newest AI companion app called", and no amount of
    per-package lookup answers it. Play's public search results carry package ids
    in ordinary links, so candidates can be harvested for a human to review --
    which is the rule policy 59 already works to: every id checked against its
    listing by hand, and com.saylo.app dropped for resolving to a different app
    of the same name.
    """
    found: dict[str, None] = {}
    for term in args.terms:
        url = (
            "https://play.google.com/store/search"
            f"?q={urllib.parse.quote(term)}&c=apps&hl=en&gl={load_rule()['store_region']}"
        )
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            body = urllib.request.urlopen(request, timeout=30).read().decode("utf-8", "replace")
        except Exception as error:  # noqa: BLE001
            print(f"  {term}: {type(error).__name__}", file=sys.stderr)
            continue
        ids = re.findall(r"/store/apps/details\?id=([A-Za-z0-9_.]+)", body)
        print(f"  {term}: {len(set(ids))} candidates")
        for package in ids:
            found.setdefault(package, None)

    known = set()
    if POLICY_PATH.exists():
        policy = json.loads(POLICY_PATH.read_text())
        known = set(policy.get("blocked_packages", []))

    fresh = [p for p in found if p not in known]
    print(f"\n{len(found)} distinct ids, {len(fresh)} not already on the blocklist.\n")

    if not fresh:
        return 0

    cache, rule = Cache(), load_rule()
    rows = [(m, verdict(m, rule)) for m in fetch_all(fresh, rule["store_region"], cache)]
    cache.save()
    print("Candidates, for a human to accept or reject — see docs/blocklist-notes.md:\n")
    return report(rows, expect=None)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)

    check = sub.add_parser("check", help="look up specific packages")
    check.add_argument("packages", nargs="+")
    check.set_defaults(func=cmd_check)

    audit = sub.add_parser("audit", help="run the rule over a corpus and count surprises")
    group = audit.add_mutually_exclusive_group(required=True)
    group.add_argument("--corpus", help="file of package ids, one per line, # comments")
    group.add_argument(
        "--policy-blocklist",
        action="store_true",
        help="use the packages dist/policy.json already blocks by name",
    )
    audit.add_argument(
        "--expect",
        choices=["keep", "remove"],
        help="what this corpus should come out as; anything else is flagged",
    )
    audit.set_defaults(func=cmd_audit)

    search = sub.add_parser("search", help="harvest candidate package ids from Play search")
    search.add_argument("terms", nargs="+")
    search.set_defaults(func=cmd_search)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(0 if main() == 0 else 1)
