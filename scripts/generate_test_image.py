#!/usr/bin/env python3
"""Generate the deterministic 640x480 synthetic road-scene PNG used by the
delegate benchmark (Section 5.2). Pure numpy + zlib PNG writer (no PIL).

The content does not need to contain detectable objects (the benchmark measures
latency and validates delegate output against the XNNPACK reference on the SAME
image), but a road-like scene keeps activations realistic: sky gradient, road
trapezoid, lane dashes, two car-shaped boxes, one pedestrian-shaped figure.
"""
import struct
import zlib
from pathlib import Path

import numpy as np

W, H = 640, 480
OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/benchmark/test_scene_640x480.png"


def png_write(path: Path, rgb: np.ndarray) -> None:
    raw = b"".join(b"\x00" + rgb[y].tobytes() for y in range(rgb.shape[0]))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", rgb.shape[1], rgb.shape[0], 8, 2, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", zlib.compress(raw, 9)))
        f.write(chunk(b"IEND", b""))


def rect(img, x0, y0, x1, y1, color):
    img[max(0, y0):y1, max(0, x0):x1] = color


def main() -> None:
    img = np.zeros((H, W, 3), dtype=np.uint8)
    # sky gradient
    for y in range(H // 2):
        c = np.array([120 - y // 8, 160 - y // 10, 210 - y // 12], dtype=np.uint8)
        img[y, :] = c
    # ground
    img[H // 2:, :] = (92, 88, 84)
    # road trapezoid narrowing to a vanishing point at (320, 240)
    for y in range(H // 2, H):
        f = (y - H // 2) / (H // 2)
        half = int(30 + f * 290)
        img[y, 320 - half:320 + half] = (58, 58, 62)
        # dashes
        if (y // 24) % 2 == 0:
            img[y, 318:322] = (200, 200, 190)
    # two cars
    rect(img, 250, 250, 320, 300, (160, 30, 30))     # nearer car body
    rect(img, 258, 292, 272, 306, (20, 20, 20))      # wheels
    rect(img, 298, 292, 312, 306, (20, 20, 20))
    rect(img, 262, 236, 308, 252, (170, 60, 60))     # cabin
    rect(img, 360, 246, 400, 274, (30, 60, 140))     # farther car
    rect(img, 366, 270, 374, 278, (15, 15, 15))
    rect(img, 386, 270, 394, 278, (15, 15, 15))
    # pedestrian-ish figure at road edge
    rect(img, 585, 240, 597, 300, (150, 120, 90))    # body
    img[228:242, 586:596] = (205, 170, 140)          # head
    # deterministic speckle so INT8 activations aren't flat
    rng = np.random.default_rng(1234)
    img = np.clip(img.astype(np.int16) + rng.integers(-6, 7, img.shape), 0, 255).astype(np.uint8)
    png_write(OUT, img)
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
