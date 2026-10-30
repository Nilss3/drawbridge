#!/usr/bin/env python3
"""Writes the file the download demo downloads: 12 MB of nothing in particular.

Generated rather than committed. It carries no information — its only job is to
be large enough that a progress notification exists long enough to film.
"""
import sys
from pathlib import Path

target = Path(sys.argv[1]) / "herald-sample-file.bin"
if target.exists() and target.stat().st_size == 12 * 1024 * 1024:
    raise SystemExit(0)
with target.open("wb") as f:
    for _ in range(12):
        f.write(bytes(1024 * 1024))
print(f"wrote {target.name} (12 MB)")
