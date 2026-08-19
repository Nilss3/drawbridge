#!/usr/bin/env python3
"""Refreshes the display names the blocklist page's generated tables use.

The tables themselves are built from `dist/policy.json` at site-build time, so
their *rows* cannot drift from the policy. What they cannot get from the policy
is a human name and a store rating for each package, which is what this writes
into `site-src/app-names.json`.

Run it after adding packages to the policy. `build-site.py` prints every package
it has no name for and falls back to the package id, so a stale file degrades
the page rather than breaking it.

Source is the cache `tools/app-ratings.py` fills, which is gitignored — so this
is run by whoever has fetched those ratings, and its output is committed.
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE = ROOT / "tools" / ".app-ratings-cache.json"
OUT = ROOT / "site-src" / "app-names.json"

# Titles that mark a blocked package as an AI companion. The policy has no
# category for this — `blocked_packages` is a flat list — so the grouping is a
# judgement, kept here rather than in the page. build-site.py refuses to build if
# one of the grouped packages has stopped being blocked, which is the drift that
# would make the page claim something untrue.
COMPANION_TITLES = [
    "character", "replika", "chai:", "chai ", "talkie", "polybuzz", "poly.ai", "nomi",
    "kindroid", "anima", "paradot", "eva ai", "simsimi", "linky", "botify", "igirl",
    "joyland", "romantic", "soulmate", "ai friend", "ai girlfriend", "companion",
    "waifu", "fantasy", "fantasia", "mimo", "roleplai", "rosytalk", "kajiwoto", "cycle ai",
]


def main() -> None:
    if not CACHE.exists():
        raise SystemExit(f"no ratings cache at {CACHE}; run tools/app-ratings.py first")
    cache = {
        v["package"]: v
        for v in json.loads(CACHE.read_text()).values()
        if isinstance(v, dict) and "package" in v and "title" in v
    }
    policy = json.loads((ROOT / "dist" / "policy.json").read_text())
    streaming = next(o for o in policy["options"] if o["id"] == "streaming")

    companions = sorted(
        p for p in policy["blocked_packages"]
        if p in cache and any(t in cache[p]["title"].lower() for t in COMPANION_TITLES)
    )
    wanted = sorted(
        set(policy["app_ratings"]["allowed_packages"])
        | set(streaming["exempt_packages"])
        | set(companions)
    )
    previous = json.loads(OUT.read_text()) if OUT.exists() else {"names": {}}
    names = dict(previous.get("names", {}))
    for package in wanted:
        if package in cache:
            names[package] = {"title": cache[package]["title"], "rating": cache[package]["rating"]}

    OUT.write_text(json.dumps({
        "_comment": previous.get("_comment", ""),
        "ai_companions": companions,
        "names": {p: names[p] for p in sorted(names) if p in wanted},
    }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    missing = [p for p in wanted if p not in names]
    print(f"wrote {OUT.relative_to(ROOT)}: {len(wanted)} packages, {len(companions)} companions")
    if missing:
        print("no name for, will show as the package id:")
        for p in missing:
            print(f"  {p}")


if __name__ == "__main__":
    main()
