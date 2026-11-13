#!/usr/bin/env python3
"""Synthesizes the launcher's tactile UI sounds (original, no samples).

Usage: python3 tools/gen_sfx.py   -> writes app/src/main/assets/sfx/*.wav
"""
import math
import os
import random
import struct
import wave

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "sfx")
random.seed(7)


def silence(ms):
    return [0.0] * int(RATE * ms / 1000)


def click(ms=40, noise_tau=0.003, thump_hz=140.0, thump_tau=0.02, noise=0.8, thump=0.9, lp=0.35):
    """A mechanical click: filtered noise transient plus a decaying low thump."""
    out, prev = [], 0.0
    for i in range(int(RATE * ms / 1000)):
        t = i / RATE
        n = random.uniform(-1, 1) * math.exp(-t / noise_tau)
        prev += lp * (n - prev)  # one-pole low-pass keeps it from sounding hissy
        hz = thump_hz * (1.0 + 0.6 * math.exp(-t / 0.006))  # slight pitch drop
        s = math.sin(2 * math.pi * hz * t) * math.exp(-t / thump_tau)
        out.append(noise * prev * 2.2 + thump * s)
    return out


def beep(ms, f0, f1, vol=0.35, square=0.5):
    """A terminal blip gliding from f0 to f1, part sine part soft square."""
    out, phase = [], 0.0
    n = int(RATE * ms / 1000)
    for i in range(n):
        k = i / n
        phase += 2 * math.pi * (f0 + (f1 - f0) * k) / RATE
        s = math.sin(phase)
        sq = math.tanh(4 * s)
        env = min(1.0, i / (RATE * 0.003)) * min(1.0, (n - i) / (RATE * 0.012))
        out.append(vol * env * ((1 - square) * s + square * sq))
    return out


def mix(*tracks):
    out = [0.0] * max(len(t) for t in tracks)
    for t in tracks:
        for i, v in enumerate(t):
            out[i] += v
    return out


def save(name, samples, gain=0.8):
    peak = max(abs(s) for s in samples) or 1.0
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(
            struct.pack("<h", int(max(-1.0, min(1.0, s / peak * gain)) * 32767)) for s in samples))


# Rotary selector "ka-chunk" for switching screens.
save("tab", mix(
    click(30, thump_hz=210, thump_tau=0.008, thump=0.5),
    silence(48) + click(90, noise_tau=0.005, thump_hz=95, thump_tau=0.03, thump=1.2, lp=0.25),
))

# Light key tick for typing and small toggles.
save("tick", click(28, noise_tau=0.0015, thump_hz=1900, thump_tau=0.004, thump=0.35, lp=0.6), gain=0.5)

# Button press + rising confirm blip for launching.
save("select", mix(
    click(45, thump_hz=160, thump_tau=0.015),
    silence(18) + beep(70, 880, 1320),
))

# Button press + falling blip for cancel / back.
save("back", mix(
    click(45, thump_hz=130, thump_tau=0.018),
    silence(18) + beep(80, 620, 410, vol=0.3),
))

# Power-on: relay thunk, rising tube whine, ready beep.
save("boot", mix(
    click(120, noise_tau=0.008, thump_hz=70, thump_tau=0.05, thump=1.3, lp=0.2),
    silence(40) + beep(330, 180, 1100, vol=0.12, square=0.1),
    silence(400) + beep(90, 1320, 1320, vol=0.3),
))

print("wrote", sorted(os.listdir(OUT)))
