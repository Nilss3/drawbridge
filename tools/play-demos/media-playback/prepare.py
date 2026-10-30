#!/usr/bin/env python3
"""Writes the sound the media demo plays: three minutes of a soft two-note loop.

Generated rather than committed, and deliberately dull. screenrecord captures no
audio, so what the video has to show is the clock advancing and the notification
sitting in the shade — the sound only has to exist, and to last longer than the
recording does.
"""
import math
import struct
import sys
import wave
from pathlib import Path

RATE = 22050
SECONDS = 180
target = Path(sys.argv[1]) / "herald-sample-audio.wav"
if target.exists():
    raise SystemExit(0)

frames = bytearray()
for i in range(RATE * SECONDS):
    t = i / RATE
    # Two notes, two seconds each, with a soft attack so nothing clicks.
    freq = 220.0 if int(t / 2) % 2 == 0 else 277.2
    envelope = min(1.0, (t % 2) * 4, (2 - t % 2) * 4)
    frames += struct.pack("<h", int(8000 * envelope * math.sin(2 * math.pi * freq * t)))

with wave.open(str(target), "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes(bytes(frames))
print(f"wrote {target.name} ({target.stat().st_size // 1024} KB)")
