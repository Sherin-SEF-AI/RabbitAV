#!/usr/bin/env python3
"""Generate deterministic IMU test fixtures for the jolt engine unit tests.

Writes CSVs (columns: t_s,az) at 200 Hz into app/src/test/resources/imu/.
`az` is the RAW vehicle-frame vertical accelerometer channel including the
9.81 m/s^2 gravity offset; tests run the full band-pass -> MAD trigger ->
feature -> classifier chain on it.

Signal shapes are modeled on published pothole/speed-bump accelerometry:
  speed_breaker: front-axle positive lift, dip, second (rear-axle) lift ~0.45 s
                 later with comparable amplitude (double bump, positive-first).
  pothole:       sharp negative drop (wheel falls in), hard positive rebound,
                 total < 0.35 s (single event, negative-first).
  rough_patch:   3 s of elevated broadband vibration, no dominant peak.
  smooth:        baseline sensor noise only; must NOT trigger.
"""
from pathlib import Path

import numpy as np

FS = 200
G = 9.81
OUT = Path(__file__).resolve().parent.parent / "app/src/test/resources/imu"


def bump(t: np.ndarray, t0: float, amp: float, width: float) -> np.ndarray:
    """One suspension half-cycle: raised-cosine lift then a weaker rebound."""
    x = (t - t0) / width
    lift = np.where((x >= 0) & (x < 1), amp * 0.5 * (1 - np.cos(2 * np.pi * np.clip(x, 0, 1))), 0)
    reb = np.where((x >= 1) & (x < 1.8),
                   -0.45 * amp * np.sin(np.pi * np.clip((x - 1) / 0.8, 0, 1)), 0)
    return lift + reb


def base(dur: float, rng: np.ndarray | None = None, sigma=0.12):
    t = np.arange(int(FS * dur)) / FS
    r = np.random.default_rng(42)
    return t, G + r.normal(0, sigma, len(t))


def write(name: str, t: np.ndarray, az: np.ndarray) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / name, "w") as f:
        f.write("t_s,az\n")
        for ti, ai in zip(t, az):
            f.write(f"{ti:.5f},{ai:.5f}\n")
    print(f"wrote {OUT / name} ({len(t)} samples)")


def main() -> None:
    # Speed breaker at 20 km/h: axle spacing ~2.5 m -> ~0.45 s between axles.
    t, az = base(8.0)
    az += bump(t, 3.0, 4.2, 0.18) + bump(t, 3.45, 3.8, 0.18)
    write("speed_breaker.csv", t, az)

    # Pothole: wheel drops (negative), slams the far edge (positive), fast.
    t, az = base(8.0)
    x = (t - 3.0)
    drop = np.where((x >= 0) & (x < 0.10), -5.5 * np.sin(np.pi * np.clip(x / 0.10, 0, 1)), 0)
    slam = np.where((x >= 0.10) & (x < 0.22),
                    4.6 * np.sin(np.pi * np.clip((x - 0.10) / 0.12, 0, 1)), 0)
    az += drop + slam
    write("pothole.csv", t, az)

    # Rough patch: ~2.7 s of ~1.35 m/s^2 RMS broadband vibration. Soft-clipped
    # at 2.15 m/s^2 — physically, suspension travel limits single-sample
    # extremes — which keeps every sample below the 2.5 m/s^2 jolt trigger
    # floor: the patch must register through sustained RMS, not as a jolt.
    t, az = base(10.0)
    r = np.random.default_rng(7)
    noise = r.normal(0, 5.2, len(t))
    kernel = np.ones(3) / 3
    noise = np.convolve(noise, kernel, mode="same")  # sigma -> ~3.0
    clip = 2.15
    noise = np.tanh(noise / clip) * clip
    mask = (t >= 3.0) & (t <= 6.5)
    # plateau envelope: full amplitude for ~2.3 s, 0.6 s ramps at the edges
    ramp = np.clip((1 - np.abs((t - 4.75) / 1.75)) * 3, 0, 1)
    az += noise * mask * ramp
    write("rough_patch.csv", t, az)

    # Smooth road: negative control.
    t, az = base(8.0)
    write("smooth.csv", t, az)


if __name__ == "__main__":
    main()
