#!/usr/bin/env python3
"""Generate the deterministic FCW replay-gate video (M2 gate, Section 7).

A real bus cutout (from the ultralytics bus.jpg sample) is composited onto the
synthetic road scene, approaching from Z0 to Z1 meters over DURATION seconds.
Size and vertical placement are IPM-consistent with the app's default replay
geometry (66-deg-HFOV fallback intrinsics at 640x480, camera height 1.30 m,
pitch ~1.7 deg), so the app's fused distance estimate tracks the scripted Z
and the closing speed is (Z0-Z1)/DURATION — comfortably above the 1.5 m/s FCW
precondition. TTC crosses the 2.5 s CAUTION and 1.6 s CRITICAL thresholds near
the end of the clip.

Usage:
    python3 scripts/generate_test_video.py --bus bus.jpg \
        --out app/src/androidTest/assets/approach.mp4
Requires opencv-python + numpy (present in the model-export venv).
"""
import argparse
import math

import cv2
import numpy as np

W, H = 640, 480
FPS = 30
# Approach envelope sits inside the bundled INT8 model's small-object
# detection floor (~8-10 m for this composite); closing speed (Z0-Z1)/APPROACH_S
# must exceed the 1.5 m/s FCW precondition. A 2 s hold at Z1 pads the loop.
APPROACH_S = 5.0
HOLD_S = 2.0
Z0, Z1 = 10.0, 1.5

# Mirror of the app's replay geometry (CameraIntrinsics.fallback + defaults)
FX = (W / 2) / math.tan(math.radians(66 / 2))  # ~492.7
CY = H / 2
CAM_HEIGHT = 1.30
PITCH = math.radians(1.7)
BUS_WIDTH_M = 2.5
BUS_ASPECT = 0.66  # bbox h/w of the source cutout


def road_background() -> np.ndarray:
    img = np.zeros((H, W, 3), dtype=np.uint8)
    for y in range(H // 2):
        img[y, :] = (210 - y // 12, 160 - y // 10, 120 - y // 8)  # BGR sky
    img[H // 2:, :] = (84, 88, 92)
    for y in range(H // 2, H):
        f = (y - H // 2) / (H // 2)
        half = int(30 + f * 290)
        img[y, max(0, 320 - half):min(W, 320 + half)] = (62, 58, 58)
        if (y // 24) % 2 == 0:
            img[y, 318:322] = (190, 200, 200)
    return img


def v_for_distance(z: float) -> float:
    """Row of the ground contact point at distance z (app's vForDistance)."""
    phi = math.atan(CAM_HEIGHT / z)
    return CY + FX * math.tan(phi - PITCH)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bus", required=True, help="path to ultralytics bus.jpg")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    src = cv2.imread(args.bus)
    if src is None:
        raise SystemExit(f"cannot read {args.bus}")
    sh, sw = src.shape[:2]
    # bus bbox in bus.jpg (from verify_model.py output, normalized)
    x0, y0, x1, y1 = int(0.023 * sw), int(0.220 * sh), int(0.985 * sw), int(0.694 * sh)
    bus = src[y0:y1, x0:x1]

    bg = road_background()
    out = cv2.VideoWriter(args.out, cv2.VideoWriter_fourcc(*"mp4v"), FPS, (W, H))
    n_approach = int(APPROACH_S * FPS)
    n_hold = int(HOLD_S * FPS)
    rng = np.random.default_rng(11)
    for i in range(n_approach + n_hold):
        f = min(1.0, i / (n_approach - 1))
        z = Z0 + (Z1 - Z0) * f
        wpx = FX * BUS_WIDTH_M / z
        hpx = wpx * BUS_ASPECT
        bottom = v_for_distance(z)
        cx = W / 2 + rng.normal(0, 0.6)

        frame = bg.copy()
        bw = max(6, int(round(wpx)))
        bh = max(4, int(round(hpx)))
        scaled = cv2.resize(bus, (bw, bh), interpolation=cv2.INTER_AREA)
        px0 = int(round(cx - bw / 2))
        py0 = int(round(bottom - bh))
        sx0, sy0 = max(0, -px0), max(0, -py0)
        dx0, dy0 = max(0, px0), max(0, py0)
        dx1, dy1 = min(W, px0 + bw), min(H, py0 + bh)
        if dx1 > dx0 and dy1 > dy0:
            patch = scaled[sy0:sy0 + (dy1 - dy0), sx0:sx0 + (dx1 - dx0)].astype(np.float32)
            region = frame[dy0:dy1, dx0:dx1].astype(np.float32)
            # feathered alpha (soft ~6% border) so the composite reads as an
            # object in the scene, not a sticker — measurably improves the
            # INT8 model's small-scale detection on this synthetic scene
            ph, pw = patch.shape[:2]
            fx_ = max(2, int(pw * 0.06))
            fy_ = max(2, int(ph * 0.06))
            ax = np.minimum(np.arange(pw) / fx_, np.arange(pw)[::-1] / fx_).clip(0, 1)
            ay = np.minimum(np.arange(ph) / fy_, np.arange(ph)[::-1] / fy_).clip(0, 1)
            alpha = np.minimum(ay[:, None], ax[None, :])[..., None]
            frame[dy0:dy1, dx0:dx1] = (patch * alpha + region * (1 - alpha)).astype(np.uint8)
            # soft contact shadow under the object grounds it on the road
            shy0, shy1 = min(H - 1, dy1), min(H, dy1 + max(2, bh // 12))
            if shy1 > shy0:
                frame[shy0:shy1, dx0:dx1] = (frame[shy0:shy1, dx0:dx1] * 0.55).astype(np.uint8)
        noise = rng.integers(-5, 6, frame.shape, dtype=np.int16)
        frame = np.clip(frame.astype(np.int16) + noise, 0, 255).astype(np.uint8)
        out.write(frame)
    out.release()
    print(
        f"wrote {args.out}: {n_approach + n_hold} frames, Z {Z0}->{Z1} m over {APPROACH_S}s "
        f"(closing {(Z0 - Z1) / APPROACH_S:.2f} m/s) + {HOLD_S}s hold"
    )


if __name__ == "__main__":
    main()
