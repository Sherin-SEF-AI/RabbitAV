#!/usr/bin/env python3
"""Export a YOLO detector to LiteRT (TFLite) INT8 and write the RabbitAV sidecar.

Produces a model directory consumable by the app's ModelManager:

    <out>/
      model.tflite
      model_config.json

Usage:
    python3 scripts/export_model.py \
        --weights yolo11n.pt --imgsz 320 \
        --out app/src/main/assets/models/default

Requires: ultralytics (which pulls torch), tensorflow (for the TFLite converter
chain), onnx. Ultralytics installs its own export helpers (onnx2tf etc.) on
first use. If full-INT8 calibration fails, the script retries with dynamic-range
quantization, then plain float32, and records which path succeeded inside the
sidecar's "name" field so the app's debug screen shows exactly what is running.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

COCO_CLASSES = [
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
    "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
    "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
    "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
    "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
    "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
    "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
    "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
    "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
    "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
    "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
    "hair drier", "toothbrush",
]

# COCO label -> RabbitAV canonical class. Everything not mapped is ignored by the
# app (decoded, then dropped before tracking).
CANONICAL_MAP = {
    "person": "PEDESTRIAN",
    "bicycle": "CYCLIST",
    "car": "CAR",
    "motorcycle": "MOTORCYCLE",
    "bus": "BUS",
    "truck": "TRUCK",
    "cow": "ANIMAL",
    "dog": "ANIMAL",
    "horse": "ANIMAL",
    "sheep": "ANIMAL",
    "elephant": "ANIMAL",
}


def export(weights: str, imgsz: int, out_dir: Path) -> tuple[Path, str]:
    """Try INT8 -> dynamic-range -> float32 export. Returns (tflite, variant)."""
    from ultralytics import YOLO

    model = YOLO(weights)

    attempts = [
        ("int8", dict(format="tflite", imgsz=imgsz, int8=True, data="coco8.yaml")),
        ("dynamic_range", dict(format="tflite", imgsz=imgsz, int8=False, half=False)),
    ]
    last_err: Exception | None = None
    for variant, kwargs in attempts:
        try:
            print(f"[export] attempting {variant} export: {kwargs}")
            result_path = Path(model.export(**kwargs))
            tflite = pick_tflite(result_path, variant)
            if tflite is not None:
                return tflite, variant
        except Exception as e:  # noqa: BLE001 - report and fall through the ladder
            print(f"[export] {variant} export failed: {e}")
            last_err = e
    raise RuntimeError(f"all export attempts failed; last error: {last_err}")


def pick_tflite(result_path: Path, variant: str) -> Path | None:
    """Ultralytics may return the tflite itself or a saved_model dir; search
    the sibling artifacts and take the best one for the requested variant
    (full-integer IO beats float-IO int8 on the floor device: no edge
    dequant layers and the app writes the input LUT directly as int8)."""
    search_dir = result_path if result_path.is_dir() else result_path.parent
    preference = {
        "int8": ["_full_integer_quant.tflite", "_int8.tflite", "_integer_quant.tflite"],
        "dynamic_range": ["_dynamic_range_quant.tflite", "_float32.tflite", "_float16.tflite"],
    }[variant]
    candidates = sorted(search_dir.rglob("*.tflite"))
    for suffix in preference:
        for c in candidates:
            if c.name.endswith(suffix):
                return c
    if result_path.suffix == ".tflite" and result_path.exists():
        return result_path
    return candidates[0] if candidates else None


def tensor_report(tflite_path: Path) -> dict:
    """Introspect the model so the sidecar matches reality, not assumptions."""
    try:
        from ai_edge_litert.interpreter import Interpreter  # type: ignore
    except ImportError:
        from tensorflow.lite.python.interpreter import Interpreter  # type: ignore

    interp = Interpreter(model_path=str(tflite_path))
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    return {
        "input_shape": [int(x) for x in inp["shape"]],
        "input_dtype": str(inp["dtype"].__name__),
        "output_shape": [int(x) for x in out["shape"]],
        "output_dtype": str(out["dtype"].__name__),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", default="yolo11n.pt",
                    help="ultralytics weights; falls back to yolov8n.pt if unavailable")
    ap.add_argument("--imgsz", type=int, default=320)
    ap.add_argument("--out", default="app/src/main/assets/models/default")
    ap.add_argument("--name", default=None, help="model name recorded in the sidecar")
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    try:
        tflite, variant = export(args.weights, args.imgsz, out_dir)
    except Exception as e:  # noqa: BLE001
        print(f"[export] primary weights failed entirely ({e}); trying yolov8n.pt")
        tflite, variant = export("yolov8n.pt", args.imgsz, out_dir)

    report = tensor_report(tflite)
    print(f"[export] artifact {tflite} -> {report}")

    dst = out_dir / "model.tflite"
    shutil.copyfile(tflite, dst)

    stem = Path(args.weights).stem
    name = args.name or f"{stem}-{variant}-{args.imgsz}"
    sidecar = {
        "schema": 1,
        "name": name,
        "input": {
            "width": int(report["input_shape"][2]),
            "height": int(report["input_shape"][1]),
            "layout": "NHWC",
            "quantized": report["input_dtype"] in ("int8", "uint8"),
            "resizable": True,
        },
        "decode": {
            "family": "yolo_v8",
            "outputShape": report["output_shape"],
            "confThreshold": 0.35,
            "iouThreshold": 0.5,
        },
        "classes": COCO_CLASSES,
        "classMap": CANONICAL_MAP,
        "capabilities": {
            "road_hazard_detection": False,
            "road_hazard_classification": False,
            "auto_rickshaw": False,
        },
    }
    (out_dir / "model_config.json").write_text(json.dumps(sidecar, indent=2))
    print(f"[export] wrote {dst} ({dst.stat().st_size} bytes) + model_config.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
