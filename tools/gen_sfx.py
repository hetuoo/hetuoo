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


def modal(ms, modes, drop=0.25):
    """A struck body: damped resonances (hz, level, decay seconds) with a soft 1 ms attack."""
    out = []
    for i in range(int(RATE * ms / 1000)):
        t = i / RATE
        bend = 1.0 + drop * math.exp(-t / 0.008)  # pitch settles as the part seats
        v = sum(a * math.sin(2 * math.pi * hz * bend * t) * math.exp(-t / tau) for hz, a, tau in modes)
        out.append(v * min(1.0, t / 0.001))
    return out


def soft_noise(ms, tau, cutoff, level):
    """The contact transient: a noise burst low-passed twice so it has no hiss."""
    k = 1 - math.exp(-2 * math.pi * cutoff / RATE)
    out, a, b = [], 0.0, 0.0
    for i in range(int(RATE * ms / 1000)):
        n = random.uniform(-1, 1) * math.exp(-(i / RATE) / tau)
        a += k * (n - a)
        b += k * (a - b)
        out.append(level * b * 4)
    return out


def warm(samples, drive=1.6):
    """Gentle saturation rounds the peaks, like a small speaker in a case."""
    return [math.tanh(drive * s) for s in samples]


def thock(scale=1.0, level=1.0):
    return mix(
        modal(130, [(95 * scale, 1.0 * level, 0.035), (190 * scale, 0.7 * level, 0.022),
                    (410 * scale, 0.35 * level, 0.012), (760 * scale, 0.12 * level, 0.006)]),
        soft_noise(30, 0.0025, 2200, 0.5 * level),
    )


# Screen-switch sounds. CONF > SWITCH SOUND picks one.
# KNOB: a rotary selector dropping into its detent - light click, then the seat.
save("tab_knob", warm(mix(
    modal(60, [(240, 0.6, 0.010), (520, 0.3, 0.006)]),
    soft_noise(20, 0.002, 2000, 0.35),
    silence(55) + mix(
        modal(130, [(110, 1.0, 0.030), (230, 0.6, 0.020), (480, 0.25, 0.010)]),
        soft_noise(30, 0.003, 1800, 0.45)),
)))

# THOCK: one deep, wooden key press.
save("tab_thock", warm(thock()))

# RELAY: a heavier clunk with a faint spring ring.
save("tab_relay", warm(mix(
    modal(170, [(70, 1.0, 0.045), (150, 0.7, 0.030), (330, 0.3, 0.015),
                (1150, 0.08, 0.050), (1730, 0.04, 0.035)]),
    soft_noise(40, 0.004, 3000, 0.6),
)))

# CLASSIC: the brighter original.
save("tab_classic", mix(
    click(30, thump_hz=210, thump_tau=0.008, thump=0.5),
    silence(48) + click(90, noise_tau=0.005, thump_hz=95, thump_tau=0.03, thump=1.2, lp=0.25),
))

# Small soft key tick for typing and toggles.
save("tick", warm(mix(
    modal(40, [(420, 0.6, 0.008), (880, 0.25, 0.004)], drop=0.15),
    soft_noise(15, 0.0012, 3000, 0.3),
)), gain=0.45)

# Key press + rising blip for launching.
save("select", mix(warm(thock(1.5, 0.8)), silence(20) + beep(70, 523, 784, vol=0.22, square=0.15)))

# Key press + falling blip for cancel / back.
save("back", mix(warm(thock(1.3, 0.8)), silence(20) + beep(80, 440, 294, vol=0.22, square=0.15)))

# Power-on: relay thunk, rising tube whine, ready beep.
save("boot", mix(
    click(120, noise_tau=0.008, thump_hz=70, thump_tau=0.05, thump=1.3, lp=0.2),
    silence(40) + beep(330, 180, 1100, vol=0.12, square=0.1),
    silence(400) + beep(90, 1320, 1320, vol=0.3),
))

print("wrote", sorted(os.listdir(OUT)))
