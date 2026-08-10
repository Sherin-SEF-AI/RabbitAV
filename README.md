# RabbitAV

Part of LabeloxAV

A windshield-mounted Android phone becomes two fused systems for Indian roads:

1. **On-device ADAS** — forward collision warning (FCW), headway monitoring, and vulnerable-road-user warnings. All inference runs on the phone. Nothing is uploaded, ever.
2. **IMU + vision road hazard mapper** — the accelerometer feels the jolt, a camera lookback frame classifies pothole vs speed breaker (when a capable model is installed), GPS pins it, and hazards accumulate into a local map that warns you on the next approach.

Floor device class: 3–4 GB RAM, Helio G85 / Snapdragon 680, CPU-only inference. Bundled detector: **YOLOv8n full-INT8 @ 320×320** (exported and verified in this repo — see `scripts/`).

---

## Build

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # 49 unit tests (geometry, tracker, jolt engine, sync, ...)
./gradlew assembleRelease        # R8-minified release (debug-signed for local testing)
```

Requirements: JDK 17+, Android SDK with platform 36. `local.properties` must point at your SDK (`sdk.dir=...`). No other manual steps — the model, tones, and test fixtures are committed.

## ADB quickstart

```bash
# 1. Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Grant permissions from the shell (or use the in-app onboarding)
adb shell pm grant com.deepmost.rabbitav android.permission.CAMERA
adb shell pm grant com.deepmost.rabbitav android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.deepmost.rabbitav android.permission.POST_NOTIFICATIONS

# 3. Launch
adb shell am start -n com.deepmost.rabbitav/.MainActivity

# 4. Watch the logs (all pipeline tags)
adb logcat -s RAV-App RAV-Svc RAV-Camera RAV-Infer RAV-Model RAV-Track RAV-Geom \
              RAV-Alert RAV-IMU RAV-Hazard RAV-Ego RAV-Gov RAV-Data RAV-Sync RAV-Calib
```

### Log tags

| Tag | Subsystem |
|---|---|
| `RAV-Svc` | Foreground service + pipeline lifecycle |
| `RAV-Camera` | CameraX binding, resolution, FrameRouter, replay decode |
| `RAV-Infer` | Engine init, delegate benchmark, per-decoder events |
| `RAV-Model` | Model loading, sidecar validation, hot-swap imports |
| `RAV-Track` | Tracker capacity events |
| `RAV-Alert` | Every alert onset with distance/TTC |
| `RAV-IMU` | Sensor rates, jolt classifications, rough patches |
| `RAV-Hazard` | Fusion results, site clustering, approach alerts |
| `RAV-Ego` | GPS staleness, synthetic mode |
| `RAV-Gov` | Thermal readings + every governor level transition |
| `RAV-Sync` | Batch pushes, tile pulls |

### Video replay (test ADAS at your desk — Section 5.12)

```bash
# Push any road MP4 (dashcam clips work great)
adb push road.mp4 /sdcard/Android/data/com.deepmost.rabbitav/files/test/

# In-app: Debug tab -> Frame source -> select the file -> Start replay.
# Use the synthetic speed slider on the HUD to exercise speed-gated alerts:
# drive the slider to 50+ km/h while a vehicle approaches in the video and
# FCW CAUTION -> CRITICAL fire deterministically.
```

Replay decodes through MediaCodec into the exact same YUV/FrameRouter path as the live camera, paced at native video rate, timestamps monotonic across loops (enable **Loop video** for soak testing).

### Model hot-swap (drop in your IDD-trained model)

```bash
adb push model.tflite model_config.json \
    /sdcard/Android/data/com.deepmost.rabbitav/files/models-staging/
# then: Debug tab -> "Import staged model" (validates magic bytes + sidecar, copies
# into filesDir/models/active/, reloads). "Remove override" reverts to bundled.
```

The app consumes only canonical classes via the sidecar `classMap` and enables features strictly by `capabilities` — a multi-task model with `road_hazard_detection` / `road_hazard_classification: true` lights up visual hazard alerts and vision-fused jolt classification with **zero app changes** (Section 6 contract).

### Thermal governor test (M4 gate)

```bash
adb shell cmd thermalservice override-status 3   # SEVERE
# hold 60 s -> logcat RAV-Gov: "governor PROMOTE L0 -> L1" ... up to L3
adb shell cmd thermalservice reset               # restore
# after 120 s of headroom the governor demotes one level at a time
```

L3 pauses the detector and suspends ADAS with a HUD notice + tone; **IMU hazard
mapping and GPS never degrade**.

### IMU replay / recording

```bash
# record a real drive's sensors from the Debug tab ("Record sensors CSV"), then:
adb pull /sdcard/Android/data/com.deepmost.rabbitav/files/imu-recordings/ .
```

Unit tests replay bundled CSVs (`app/src/test/resources/imu/`) through the full
band-pass → MAD trigger → feature → classifier chain.

### Benchmark & soak

- First drive start runs the **delegate benchmark** (XNNPACK vs GPU vs NNAPI, 30 timed inferences each, output-validated against the XNNPACK reference). Winner is persisted; re-run from the Debug tab.
- 45-minute soak: push a long MP4 (or enable Loop video), start replay, watch `RAV-Gov`/memory in the Debug tab. Memory must stay flat (all hot-path buffers are preallocated).

### Automated on-device acceptance gates

Prefer raw `am instrument` over `gradlew connectedDebugAndroidTest` — the UTP
runner is flaky on some OEM builds. Install both APKs first
(`assembleDebug assembleDebugAndroidTest`, then `adb install -r` each):

```bash
R=com.deepmost.rabbitav.test/com.deepmost.rabbitav.HiltTestRunner

# M1: service starts, camera binds, delegate benchmark, one real inference
adb shell am instrument -w -e class com.deepmost.rabbitav.SmokeTest $R

# M2: replay approach video fires FCW CAUTION then CRITICAL deterministically
# (self-contained: stages its own video + calibration profile)
adb shell am instrument -w -e class com.deepmost.rabbitav.ReplayGateTest $R

# M5: looped-replay soak with per-minute RAV-Soak memory/fps log lines
adb shell am instrument -w -e class com.deepmost.rabbitav.SoakTest -e soakMinutes 45 $R
```

The soak pairs naturally with the thermal override to exercise the governor
under load (`override-status 3`, hold ≥60 s per level, then `reset`).

### Screen mirroring

`scrcpy` mirrors the HUD nicely while the phone stays windshield-mounted:
`scrcpy --stay-awake --max-fps 30`.

---

## Model export / verify (scripts/)

```bash
# Export YOLOv8n (or your weights) to full-INT8 TFLite + sidecar:
python3 scripts/export_model.py --weights yolov8n.pt --imgsz 320 \
    --out app/src/main/assets/models/default

# Prove decode parity outside the app (same preprocessing + decode in Python):
python3 scripts/verify_model.py \
    --model app/src/main/assets/models/default \
    --image path/to/photo.png
```

Known-good export toolchain (what produced the bundled model): Python 3.11,
`ultralytics==8.2.103 torch==2.5.1 tensorflow==2.16.2 tf_keras==2.16.0
onnx2tf==1.22.3 onnx==1.16.1 onnxruntime==1.18.1 tflite_support==0.4.4`.
YOLO11n currently fails through onnx2tf (attention op) — see DECISIONS.md.

Other included generators: `generate_tones.py` (alert WAVs), `generate_imu_fixtures.py` (test CSVs), `generate_test_image.py` (benchmark scene).

---

## Tuning guide

All ADAS thresholds live in one persisted `AlertTuning` object (Settings → Alerts (advanced), or edit defaults in `core/alerts/AlertTuning.kt` — every field carries its meaning and sane range):

| Feels wrong | Turn this |
|---|---|
| FCW fires too late | `fcwTtcCautionS` up (max 4.0) |
| FCW cries wolf in dense traffic | `fcwTtcCautionS` down, `fcwMinClosingMps` up |
| Headway chime nags | `headwayAdvisoryS` down or `headwayAdvisoryCooldownS` up |
| Distance estimates look off | Re-run calibration; verify the 10/25/50 m rungs on a flat road |
| Too many UNKNOWN jolts | Raise `TRIGGER_FLOOR_MPS2` in `JoltDetector` (soft suspension SUVs) |
| Washboard roads mint hazards | Raise `POTHOLE_DOMINANCE` / lower rough `ROUGH_RMS_FLOOR_MPS2` |

Geometry margins (corridor half-widths, IPM cutoffs, width priors) are constants in `core/geometry/GroundGeometry.kt` / `core/inference/CanonicalClass.kt`, each commented with its control meaning and range.

## Architecture

Single `:app` module, package-per-subsystem under `com.deepmost.rabbitav` (see Section 3 of the build spec). Threading: CameraX analyzer thread → double-buffered upright I420 → single-slot inference thread (drops when busy) → tracker update; a dedicated 25 Hz alert loop predicts tracks between detector frames (the trick that makes 8 FPS detection feel continuous), annotates distance/TTC through calibrated ground-plane geometry, and drives the alert state machines → single-channel audio arbiter. IMU jolts fuse with lookback ring frames and land in a geohash-clustered Room store with 30-day confidence decay. Everything is observable via StateFlows; the UI is a pure observer.

Privacy: imagery never leaves the device. Optional sync (disabled unless built with `rabbitav.syncBaseUrl`) uploads hazard events only — type, position, heading, speed, confidence, timestamp, random resettable device ID.

## Repo docs

- `DECISIONS.md` — every judgment call made during the build, with rationale.
- `app/schemas/` — Room schema history.
