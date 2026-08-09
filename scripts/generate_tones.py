#!/usr/bin/env python3
"""Generate the alert tone WAV assets (16-bit PCM mono, 44.1 kHz) with numpy.

Every tone is deterministic and documented here; regenerating overwrites
app/src/main/assets/audio/. Frequency/pattern choices:

  fcw_caution      880 Hz double beep          "attention, closing fast"
  fcw_critical     1245 Hz rapid pulses        loops while CRITICAL holds
  headway_advisory 660 Hz soft decaying chime  polite nudge, max 1/10 s
  headway_warning  770 Hz firm double tone     tailgating hard
  vru              990/1180 Hz warble          distinct "human/animal ahead"
  hazard_mapped    523+784 Hz two-tone ding    approaching stored hazard
  hazard_visual    620 Hz short ding           live pothole/breaker detection
  wrong_side       1400/1000 Hz alternation    oncoming in your lane
  adas_suspended   descending tri-tone         governor paused the detector
  adas_resumed     ascending tri-tone          detector back
  report_ack       1000 Hz 80 ms blip          manual report chip feedback
"""
import struct
import wave
from pathlib import Path

import numpy as np

SR = 44100
OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/audio"


def env(sig: np.ndarray, attack=0.005, release=0.02) -> np.ndarray:
    """5 ms attack / 20 ms release linear ramps to avoid clicks."""
    n = len(sig)
    a, r = int(SR * attack), int(SR * release)
    e = np.ones(n)
    if a > 0:
        e[:a] = np.linspace(0, 1, a)
    if r > 0 and r < n:
        e[-r:] = np.linspace(1, 0, r)
    return sig * e


def tone(freq: float, dur: float, vol=0.85, shape="sine") -> np.ndarray:
    t = np.arange(int(SR * dur)) / SR
    if shape == "sine":
        s = np.sin(2 * np.pi * freq * t)
    elif shape == "square":  # band-limited-ish: soft-clip a sine for a firmer timbre
        s = np.tanh(3.0 * np.sin(2 * np.pi * freq * t)) / np.tanh(3.0)
    else:
        raise ValueError(shape)
    return env(s * vol)


def silence(dur: float) -> np.ndarray:
    return np.zeros(int(SR * dur))


def decay_chime(freq: float, dur: float, vol=0.7) -> np.ndarray:
    t = np.arange(int(SR * dur)) / SR
    s = np.sin(2 * np.pi * freq * t) * np.exp(-t * 6.0)
    # add a soft octave partial for a bell-like body
    s += 0.3 * np.sin(2 * np.pi * freq * 2 * t) * np.exp(-t * 9.0)
    return env(s * vol / np.max(np.abs(s)))


def warble(f0: float, f1: float, dur: float, rate=8.0, vol=0.85) -> np.ndarray:
    t = np.arange(int(SR * dur)) / SR
    f = f0 + (f1 - f0) * 0.5 * (1 + np.sin(2 * np.pi * rate * t))
    phase = 2 * np.pi * np.cumsum(f) / SR
    return env(np.sin(phase) * vol)


def write(name: str, sig: np.ndarray) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    pcm = np.clip(sig, -1, 1)
    data = (pcm * 32767).astype("<i2").tobytes()
    with wave.open(str(OUT / name), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data)
    print(f"wrote {OUT / name} ({len(data)} bytes)")


def main() -> None:
    write("fcw_caution.wav", np.concatenate([
        tone(880, 0.12), silence(0.08), tone(880, 0.12)]))
    write("fcw_critical.wav", np.concatenate(
        [np.concatenate([tone(1245, 0.06, vol=0.95, shape="square"), silence(0.04)])
         for _ in range(6)]))
    write("headway_advisory.wav", decay_chime(660, 0.5))
    write("headway_warning.wav", np.concatenate([
        tone(770, 0.22, shape="square"), silence(0.06), tone(770, 0.22, shape="square")]))
    write("vru.wav", warble(990, 1180, 0.45))
    write("hazard_mapped.wav", np.concatenate([
        decay_chime(523, 0.22), decay_chime(784, 0.3)]))
    write("hazard_visual.wav", decay_chime(620, 0.3))
    write("wrong_side.wav", np.concatenate(
        [np.concatenate([tone(1400, 0.09, vol=0.95, shape="square"),
                         tone(1000, 0.09, vol=0.95, shape="square")]) for _ in range(3)]))
    write("adas_suspended.wav", np.concatenate([
        tone(880, 0.14), tone(740, 0.14), tone(587, 0.2)]))
    write("adas_resumed.wav", np.concatenate([
        tone(587, 0.12), tone(740, 0.12), tone(880, 0.16)]))
    write("report_ack.wav", tone(1000, 0.08, vol=0.6))


if __name__ == "__main__":
    main()
