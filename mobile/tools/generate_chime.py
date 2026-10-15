#!/usr/bin/env python3
"""Renders Maggie's chime to mobile/app/src/main/res/raw/maggie_chime.ogg (needs ffmpeg with libvorbis).

Same sound as admin/src/components/maggie/chime.ts: two sine notes (880 Hz, then 1318.5 Hz
0.14 s later), each with a 20 ms exponential attack to its peak and an exponential decay
to silence at 0.9 s. Run once, commit the .ogg; the app plays the file, nothing else generates it.
"""
import math
import struct
import subprocess
import sys
from pathlib import Path

RATE = 44_100
NOTES_HZ = (880.0, 1318.5)
GAP_S = 0.14
FADE_S = 0.9
ATTACK_S = 0.02
FLOOR = 0.0001
# The admin's peak is 0.12 of a browser's volume; a notification must be heard on a phone.
PEAK = 0.35

OUT = Path(__file__).resolve().parents[1] / "app/src/main/res/raw/maggie_chime.ogg"


def gain(t: float) -> float:
    if t < ATTACK_S:
        return FLOOR * (PEAK / FLOOR) ** (t / ATTACK_S)
    return PEAK * (FLOOR / PEAK) ** ((t - ATTACK_S) / (FADE_S - ATTACK_S))


def render() -> bytes:
    total = int((GAP_S * (len(NOTES_HZ) - 1) + FADE_S) * RATE)
    mix = [0.0] * total
    for index, hz in enumerate(NOTES_HZ):
        start = int(index * GAP_S * RATE)
        for i in range(int(FADE_S * RATE)):
            t = i / RATE
            mix[start + i] += math.sin(2 * math.pi * hz * t) * gain(t)
    return b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v)) * 32767)) for v in mix)


if __name__ == "__main__":
    OUT.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(RATE), "-ac", "1", "-i", "-",
         "-c:a", "libvorbis", "-q:a", "5", "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", str(OUT)],
        input=render(), check=True,
    )
    print(f"{OUT} ({OUT.stat().st_size} bytes)", file=sys.stderr)
