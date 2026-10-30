#!/usr/bin/env python3
"""Moves an mp4's index in front of its frames, in place.

`adb shell screenrecord` writes `moov` — the table saying which byte each frame
starts at — after the frames themselves. A local player reads the whole file and
does not care. A browser does: until it has the index it does not know the video
is seekable, so dragging the scrubber snaps back to zero. It would normally fix
that by asking for the end of the file first, with a byte-range request, and
Cloudflare Pages does not serve ranges for this file.

So the index moves to the front, which is the same fix `ffmpeg -movflags
+faststart` performs. Nothing is re-encoded: the frames are copied byte for byte
and only the offsets inside `moov` are rewritten, because every chunk has just
moved further down the file by the size of the index now sitting above it.

    python3 tools/qap-demo/faststart.py video.mp4
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

# Boxes that hold other boxes rather than data; the offset tables are at the
# bottom of this nesting, in stbl.
CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl", b"edts", b"dinf", b"udta"}


def boxes(data: bytes, start: int = 0, end: int | None = None):
    """Yields (offset, header_size, size, type) for each box in a range."""
    end = len(data) if end is None else end
    pos = start
    while pos + 8 <= end:
        size, typ = struct.unpack(">I4s", data[pos:pos + 8])
        header = 8
        if size == 1:
            size = struct.unpack(">Q", data[pos + 8:pos + 16])[0]
            header = 16
        elif size == 0:
            size = end - pos
        if size < header or pos + size > end:
            raise ValueError(f"malformed box {typ!r} at {pos}")
        yield pos, header, size, typ
        pos += size


def shift_chunk_offsets(moov: bytearray, delta: int, start: int = 0, end: int | None = None) -> int:
    """Adds delta to every chunk offset in stco/co64 tables. Returns the count."""
    moved = 0
    for pos, header, size, typ in boxes(bytes(moov), start, end):
        if typ in CONTAINERS:
            moved += shift_chunk_offsets(moov, delta, pos + header, pos + size)
        elif typ in (b"stco", b"co64"):
            wide = typ == b"co64"
            width = 8 if wide else 4
            # Past the box header: 1 byte version, 3 bytes flags, 4 bytes count.
            count = struct.unpack(">I", moov[pos + header + 4:pos + header + 8])[0]
            table = pos + header + 8
            for i in range(count):
                at = table + i * width
                value = struct.unpack(">Q" if wide else ">I", moov[at:at + width])[0]
                struct.pack_into(">Q" if wide else ">I", moov, at, value + delta)
            moved += count
    return moved


def faststart(path: Path) -> str:
    data = path.read_bytes()
    top = list(boxes(data))
    kinds = [typ for _, _, _, typ in top]
    if kinds.index(b"moov") < kinds.index(b"mdat"):
        return "already fast-start; left alone"

    ftyp = next(d for d in top if d[3] == b"ftyp")
    moov = next(d for d in top if d[3] == b"moov")
    mdat = next(d for d in top if d[3] == b"mdat")

    ftyp_bytes = data[ftyp[0]:ftyp[0] + ftyp[2]]
    moov_bytes = bytearray(data[moov[0]:moov[0] + moov[2]])
    mdat_bytes = data[mdat[0]:mdat[0] + mdat[2]]

    # Every chunk offset is absolute from the start of the file, and mdat has
    # moved by however much now sits above it minus whatever sat above it before.
    delta = (len(ftyp_bytes) + len(moov_bytes)) - mdat[0]
    count = shift_chunk_offsets(moov_bytes, delta)

    path.write_bytes(ftyp_bytes + bytes(moov_bytes) + mdat_bytes)
    return f"index moved to the front; {count} chunk offsets shifted by {delta:+}"


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    print(faststart(Path(sys.argv[1])))
