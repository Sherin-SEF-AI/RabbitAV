#!/usr/bin/env python3
"""Run the SAME preprocessing + decode pipeline as the app, in Python, so app
decode parity can be eyeballed (Section 6). Prints the surviving detections.

Usage:
    python3 scripts/verify_model.py \
        --model app/src/main/assets/models/default \
        --image app/src/main/assets/benchmark/test_scene_640x480.png

Requires numpy and a TFLite interpreter (ai-edge-litert, tensorflow, or
tflite-runtime — first one found wins).
"""
from __future__ import annotations

import argparse
import json
import zlib
import struct
from pathlib import Path

import numpy as np


def load_interpreter(model_path: str):
    try:
        from ai_edge_litert.interpreter import Interpreter  # type: ignore
        return Interpreter(model_path=model_path)
    except ImportError:
        pass
    try:
        from tensorflow.lite.python.interpreter import Interpreter  # type: ignore
        return Interpreter(model_path=model_path)
    except ImportError:
        pass
    from tflite_runtime.interpreter import Interpreter  # type: ignore
    return Interpreter(model_path=model_path)


def read_png(path: Path) -> np.ndarray:
    """Minimal PNG reader for the RGB8 non-interlaced files this repo generates."""
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos = 8
    width = height = 0
    idat = b""
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if tag == b"IHDR":
            width, height, bit_depth, color_type = struct.unpack(">IIBB", chunk[:10])
            assert bit_depth == 8 and color_type == 2, "expect RGB8 PNG"
        elif tag == b"IDAT":
            idat += chunk
        elif tag == b"IEND":
            break
        pos += 12 + length
    raw = zlib.decompress(idat)
    stride = width * 3 + 1
    img = np.zeros((height, width, 3), dtype=np.uint8)
    prev = np.zeros(width * 3, dtype=np.uint8)
    for y in range(height):
        row = raw[y * stride:(y + 1) * stride]
        filt, payload = row[0], np.frombuffer(row[1:], dtype=np.uint8).copy()
        if filt == 0:
            pass
        elif filt == 1:  # Sub
            for i in range(3, len(payload)):
                payload[i] = (int(payload[i]) + int(payload[i - 3])) & 0xFF
        elif filt == 2:  # Up
            payload = ((payload.astype(np.int16) + prev) & 0xFF).astype(np.uint8)
        elif filt == 3:  # Average
            for i in range(len(payload)):
                left = int(payload[i - 3]) if i >= 3 else 0
                payload[i] = (int(payload[i]) + ((left + int(prev[i])) >> 1)) & 0xFF
        elif filt == 4:  # Paeth
            for i in range(len(payload)):
                a = int(payload[i - 3]) if i >= 3 else 0
                b = int(prev[i])
                c = int(prev[i - 3]) if i >= 3 else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                payload[i] = (int(payload[i]) + pred) & 0xFF
        prev = payload
        img[y] = payload.reshape(width, 3)
    return img


def letterbox(img: np.ndarray, dst_w: int, dst_h: int):
    h, w = img.shape[:2]
    scale = min(dst_w / w, dst_h / h)
    cw, ch = (int(w * scale) & ~1), (int(h * scale) & ~1)
    pad_x, pad_y = (dst_w - cw) // 2, (dst_h - ch) // 2
    ys = (np.arange(ch) * h / ch).astype(int).clip(0, h - 1)
    xs = (np.arange(cw) * w / cw).astype(int).clip(0, w - 1)
    resized = img[ys][:, xs]
    out = np.full((dst_h, dst_w, 3), 114, dtype=np.uint8)
    out[pad_y:pad_y + ch, pad_x:pad_x + cw] = resized
    return out, scale, pad_x, pad_y, cw, ch


def nms(boxes, scores, iou_thr):
    order = np.argsort(-scores)
    keep = []
    while len(order):
        i = order[0]
        keep.append(i)
        if len(order) == 1:
            break
        rest = order[1:]
        xx1 = np.maximum(boxes[i, 0], boxes[rest, 0])
        yy1 = np.maximum(boxes[i, 1], boxes[rest, 1])
        xx2 = np.minimum(boxes[i, 2], boxes[rest, 2])
        yy2 = np.minimum(boxes[i, 3], boxes[rest, 3])
        inter = np.maximum(0, xx2 - xx1) * np.maximum(0, yy2 - yy1)
        area_i = (boxes[i, 2] - boxes[i, 0]) * (boxes[i, 3] - boxes[i, 1])
        area_r = (boxes[rest, 2] - boxes[rest, 0]) * (boxes[rest, 3] - boxes[rest, 1])
        iou = inter / np.maximum(area_i + area_r - inter, 1e-9)
        order = rest[iou <= iou_thr]
    return keep


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="app/src/main/assets/models/default")
    ap.add_argument("--image", default="app/src/main/assets/benchmark/test_scene_640x480.png")
    args = ap.parse_args()

    model_dir = Path(args.model)
    config = json.loads((model_dir / "model_config.json").read_text())
    interp = load_interpreter(str(model_dir / "model.tflite"))
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    outs = interp.get_output_details()

    img = read_png(Path(args.image))
    dst_h, dst_w = inp["shape"][1], inp["shape"][2]
    lb, scale, pad_x, pad_y, cw, ch = letterbox(img, dst_w, dst_h)

    x = lb.astype(np.float32) / 255.0
    if inp["dtype"] == np.int8:
        s, zp = inp["quantization"]
        x = np.clip(np.round(x / s + zp), -128, 127).astype(np.int8)
    elif inp["dtype"] == np.uint8:
        s, zp = inp["quantization"]
        x = np.clip(np.round(x / s + zp), 0, 255).astype(np.uint8)
    interp.set_tensor(inp["index"], x[None])
    interp.invoke()

    family = config["decode"]["family"]
    conf_thr = config["decode"]["confThreshold"]
    iou_thr = config["decode"]["iouThreshold"]
    classes = config["classes"]

    if family == "yolo_v8":
        y = interp.get_tensor(outs[0]["index"])
        if outs[0]["dtype"] in (np.int8, np.uint8):
            s, zp = outs[0]["quantization"]
            y = (y.astype(np.float32) - zp) * s
        y = y[0]
        if y.shape[0] == 4 + len(classes):
            y = y.T  # -> [N, 4+nc]
        boxes_xywh = y[:, :4]
        cls_scores = y[:, 4:]
        cls_idx = cls_scores.argmax(1)
        score = cls_scores.max(1)
        m = score >= conf_thr
        boxes_xywh, cls_idx, score = boxes_xywh[m], cls_idx[m], score[m]
        if len(score) and boxes_xywh.max() <= 2.5:
            boxes_xywh = boxes_xywh * [dst_w, dst_h, dst_w, dst_h]
        xyxy = np.stack([
            boxes_xywh[:, 0] - boxes_xywh[:, 2] / 2, boxes_xywh[:, 1] - boxes_xywh[:, 3] / 2,
            boxes_xywh[:, 0] + boxes_xywh[:, 2] / 2, boxes_xywh[:, 1] + boxes_xywh[:, 3] / 2,
        ], 1)
        keep = nms(xyxy, score, iou_thr)
        print(f"{len(keep)} detections (conf>={conf_thr}):")
        for i in keep:
            x1 = (xyxy[i, 0] - pad_x) / cw
            y1 = (xyxy[i, 1] - pad_y) / ch
            x2 = (xyxy[i, 2] - pad_x) / cw
            y2 = (xyxy[i, 3] - pad_y) / ch
            print(f"  {classes[cls_idx[i]]:<14} conf={score[i]:.2f} box=({x1:.3f},{y1:.3f})-({x2:.3f},{y2:.3f})")
    elif family == "ssd":
        boxes = interp.get_tensor(outs[0]["index"])[0]
        cls = interp.get_tensor(outs[1]["index"])[0]
        score = interp.get_tensor(outs[2]["index"])[0]
        n = int(interp.get_tensor(outs[3]["index"])[0])
        print(f"{n} raw detections:")
        for i in range(n):
            if score[i] < conf_thr:
                continue
            ymin, xmin, ymax, xmax = boxes[i]
            label = classes[int(cls[i])] if int(cls[i]) < len(classes) else f"cls{int(cls[i])}"
            print(f"  {label:<14} conf={score[i]:.2f} box=({xmin:.3f},{ymin:.3f})-({xmax:.3f},{ymax:.3f})")
    else:
        raise SystemExit(f"unknown decode family {family}")
    return 0


if __name__ == "__main__":
    main()
