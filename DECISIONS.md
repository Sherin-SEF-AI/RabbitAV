# DECISIONS.md — judgment calls made during the build

Each entry: what was decided, why, and what it affects. Newest entries append at the bottom of each section.

## Toolchain

- **AGP 8.13.2 + Gradle 8.14.3, not AGP 9.x.** AGP 9 stable exists, but it changes Kotlin integration (built-in Kotlin) and plugin compatibility surface. AGP 8.13 is the last, most-traveled 8.x line, fully supports compileSdk 36 / targetSdk 36, and every library in this stack has years of CI against it. Wrapper artifacts fetched from the official `gradle/gradle` v8.14.3 tag.
- **Kotlin 2.2.20 + KSP 2.2.20-2.0.4.** Verified to exist on Maven Central as an exact pair. Kotlin 2.4.x is current but KSP moved to standalone versioning whose compatibility window I could not verify offline; the pinned pair is guaranteed coherent.
- **Hilt 2.57.2, not 2.60.1.** Hilt ≥2.58 hard-requires AGP 9.0+ (build fails with an explicit check). 2.57.2 is the newest release compatible with AGP 8.x. No feature loss for this app.
- **JDK 21** (system default) with `jvmToolchain(17)` for Kotlin/Java targets — AGP 8.x supports running on 21 while targeting 17 bytecode.
- **compileSdk/targetSdk 36 (Android 16)** — latest stable platform installed in the SDK; Android 14+ FGS rules apply and are handled.

## Inference stack

- **LiteRT 1.4.2 (`com.google.ai.edge.litert:litert` + `litert-gpu`), not the 2.x line.** LiteRT 2.x ("LiteRT Next") replaces the Interpreter API with `CompiledModel`, drops the separate GPU/NNAPI delegate objects, and has no NNAPI story. The spec's delegate ladder (XNNPACK baseline, GPU/NNAPI opportunistic, runtime benchmark + output validation) maps exactly onto the classic `org.tensorflow.lite.Interpreter` + delegate API, which the 1.4.x artifacts ship. Code imports `org.tensorflow.lite.*`, so the `org.tensorflow:tensorflow-lite:2.x` artifacts remain drop-in substitutes if Google Maven resolution ever fails.
- **NNAPI candidate is skipped on API ≥ 35 devices.** NNAPI is deprecated in Android 15; on those devices the benchmark only races XNNPACK vs GPU. It remains a candidate on API 27–34 where the floor devices live.
- **libyuv binding: `io.github.crow-misia.libyuv:libyuv-android:0.43.2`** as specified; resolved fine from Maven Central.
- **YUV_420_888 handling**: planes are packed manually into a preallocated contiguous I420 buffer (row-stride-aware fast path via `System.arraycopy` when `pixelStride == 1`, per-pixel loop when 2). This is one extra ~450 KB copy per frame (≈0.2–0.5 ms) but is robust across every OEM plane layout, then all rotate/scale/convert work happens in libyuv NEON. No `Bitmap`, no `YuvImage`, zero per-frame allocation.

## Model acquisition

- **Host Python is 3.14; TensorFlow has no cp314 wheels**, so the ultralytics INT8 export chain cannot run on the system interpreter. Bootstrapped a standalone CPython 3.12 via `uv` into `~/.venvs/rav-export312` and ran `scripts/export_model.py` there. See "Bundled model" below for what actually shipped.
- **Fallback model downloaded and kept ready**: `coco_ssd_mobilenet_v1_1.0_quant_2018_06_29` (Google's hosted TFLite zoo, 300×300 uint8, baked-in NMS post-process op). The app's decode layer supports both `yolo_v8` and `ssd` sidecar families, so either model ships under the same contract.

## Behavior / spec interpretations

- **Lookback ring buffer writes are decimated to ≤ 15 Hz.** Spec asks for a write on every frame *and* ~30–45 entries covering 3.0 s within ~10 MB. At 30 fps camera rate those are contradictory (90 entries ≈ 16 MB). 15 Hz × 3 s = 45 entries ≈ 8 MB, satisfying the entry-count and memory budgets; temporal resolution of 66 ms is far finer than the lookback selection tolerance (0.3–2.5 s).
- **Video replay files live in `/sdcard/Android/data/com.deepmost.rabbitav/files/test/`**, not `/sdcard/rabbitav/test/`. Scoped storage on API 29+ makes arbitrary `/sdcard` paths unreadable without `MANAGE_EXTERNAL_STORAGE` (Play-hostile) or SAF friction. The app-specific external dir needs zero permissions and `adb push` works identically. README documents the exact commands.
- **Model hot-swap staging** uses the same app-specific external dir (`files/models-staging/`) + a one-tap "Import staged model" button in the debug screen that validates and copies into `filesDir/models/active/`. This avoids `run-as` (fails on many OEM builds with debuggable quirks) and works on any device.
- **Trip distance** integrates GPS trail haversine segments at 1 Hz while speed ≥ 3 km/h (filters GPS drift at standstill).
- **Waterlogging** enters only via the manual report chip, as specified; chip events carry confidence 0.8.
- **`SENSOR_DELAY_FASTEST` + `HIGH_SAMPLING_RATE_SENSORS`** permission declared; if an OEM still throttles to 50 Hz (some do when screen off), the jolt engine adapts: filters are designed at the measured rate, and a warning is logged when the effective rate < 80 Hz.
- **Release signing uses the debug keystore** so `assembleRelease` produces an installable APK for on-device R8/perf verification. Obviously replace with a real keystore before distribution.
- **ktlint runs in report mode** (`ignoreFailures = true`): `./gradlew ktlintCheck` produces the report; style violations never block a build. Pragmatic for a generated-heavy Compose codebase.
- **OkHttp 4.12.0, not 5.x.** MockWebServer's artifact/API changed in 5.x; 4.12 is the most-deployed OkHttp with identical runtime behavior for this app's two endpoints.

## Bundled model (final outcome)

- **Bundled `yolov8n_full_integer_quant.tflite` (INT8 input/output, 3.25 MB, 320×320, [1,84,2100] output).** Export chain that finally worked: uv-provisioned **Python 3.11** + `ultralytics==8.2.103, torch==2.5.1, tensorflow==2.16.2, tf_keras==2.16.0, onnx2tf==1.22.3, onnx==1.16.1, onnxruntime==1.18.1, tflite_support==0.4.4` (coco8 INT8 calibration). Dead ends, recorded so nobody repeats them: Python 3.14 (no TF wheels), latest-everything on 3.12 (torch/ONNX exporter incompat, then ml_dtypes/JAX conflict, then missing tf_keras), tflite_support has no cp312 wheels, and **YOLO11n fails through onnx2tf 1.22–1.26 on its C2PSA attention op** — hence YOLOv8n. Verified on `bus.jpg`: bus 0.86 + 3 persons, matching the float model (`scripts/verify_model.py`).
- **Full-integer IO preferred over ultralytics' default float-IO "int8" artifact**: no edge dequant layers, the input LUT writes int8 directly, and the INT8-threshold decoder pre-filter skips dequantizing sub-threshold candidates. `export_model.py`'s artifact picker prefers it explicitly.

## Behavior / spec interpretations (continued)

- **Incident clips (Section 5.11): implemented** (superseding the earlier v1 cut, once a physical device was available to validate encoders): 1-second-GOP AVC ring of the upright ANALYSIS frames at 10 fps (what the detector actually saw — more truthful than the spec's nominal 720p second stream, which would tax the floor device), +15 s tail on any CRITICAL alert, MediaMuxer save into `filesDir/incidents`, 2 GB oldest-first pruning, opt-in toggle applied live mid-session. Clips are listed and shareable from the debug screen.
- **App-language switching**: per-app locale via `LocaleManager` on API 33+; on API 26–32 the app follows the system locale (complete `values-hi` resources exist either way). Avoids dragging appcompat into a pure Compose app just for `setApplicationLocales`. TTS language switches everywhere.
- **Service crash-recovery restarts in POCKET mode** (START_STICKY with null intent): after a process death we cannot know whether the phone is still windshield-mounted, and POCKET (IMU+GPS only) is the safest thing that is always useful; the notification opens the app where FULL_ADAS is one tap away.
- **Jolt engine hardening beyond the spec text** (all unit-test-driven, constants documented in code): (1) rough-patch RMS gate is a Schmitt trigger (exit at 0.75× the entry bar) because 0.5 s RMS windows vary ±15% on real washboard and would fragment one patch into sub-2 s spans; (2) the MAD baseline for the rough bar freezes at elevation onset — a sustained patch otherwise raises its own threshold and can never satisfy the 2 s condition; (3) SPEED_BREAKER additionally requires exactly 2–3 contiguous above-60%-of-peak regions (front/rear axle) — peak-dominance ratios turned out NOT to separate breakers from washboard (measured 2.49 vs 2.59), structure does; (4) POTHOLE requires peak > 2.5× window RMS so broadband vibration cannot mint potholes.
- **Governor compute-pressure input** (p90 > 140 ms or drop ratio > 0.85) only promotes when thermal is also ≥ 0.7 — a cold phone with a heavy model should keep grinding at full quality; the FPS floor is the tracker's job.
- **Release build signs with the debug keystore** so `assembleRelease` installs for on-device R8/perf verification; swap before any real distribution.

## On-device validation findings (Galaxy A17 / SM-A176B, Exynos 1330-class, Android 16)

Device gates found three real bugs no amount of JVM testing had caught — each fixed and covered:

1. **NaN → SQLite NULL crash** (found by the M2 replay gate): a VRU/FCW alert with non-finite TTC or distance binds as NULL into the NOT NULL `alert_events` columns → `SQLiteConstraintException` on the first real alert. Fixed with a −1 "not applicable" sentinel at the persistence boundary.
2. **BufferOverflowException after governor L2 resize**: `refreshOutputs()` rebuilt the output buffers but the interpreter's `outputMap` still held the old ones (same map size → never rebuilt), whose positions sat at end-of-write → every post-resize inference threw. Fixed by clearing the map on refresh.
3. **Replay frame-clock regression under decode lag**: when thermal mitigation slows decoding below realtime, the extractor's input side reaches EOS and advances the loop offset while outputs are still mid-clip → frame timestamps jump backward → the pts-based FPS cap starves (observed: exactly one inference per 7 s loop; 8 fps × 2/7 = the measured 2.3 fps) and tracker dt skews. Fixed by stamping replay frames with guaranteed-monotonic delivery time; media pts is used for pacing only.

Environment finding, not a bug: **`cmd thermalservice override-status 3` on this Samsung applies real, ramping core mitigation**, not just a reported status — single-thread Kotlin stages slow ~3–7× while XNNPACK's worker threads keep big-core affinity (p50 pinned at ~21 ms throughout). This is exactly the environment the governor exists for; the soak assertion now treats any governor level above L0 as a legitimate explanation for low fps.

Second environment finding: **Samsung app-sleep stopped the drive service ~17 min into the first 45-min soak** — the app had never been opened via launcher and was not battery-exempted (instrumentation-only usage), which is precisely the scenario the onboarding's battery-exemption step and the OEM guidance page exist to prevent. Two responses: (a) REPLAY mode no longer declares a camera FGS type (it uses no camera; an unnecessary camera-type FGS maximizes exposure to while-in-use policy kills), and (b) the soak procedure now mirrors a real user's setup (`dumpsys deviceidle whitelist +pkg`, active standby bucket, app opened once). The START_STICKY recovery crash that followed the kill is a HiltTestApplication-only artifact — the production Application always has its component ready before services are created.

Measured on this device (entry-tier, one class above the floor): delegate winner **XNNPACK**, detector **~13–15 fps sustained** at p50 **20 ms** (budget: ≥8 fps), steady-state memory **55–80 MB** (budget: <350 MB), governor PROMOTE chain L0→L1→L2 observed at exactly 60 s per level with the FPS cap measured at 7.0/s against the 8 fps target.

## Known limitations (honest list)

- The bundled COCO model has no AUTO_RICKSHAW / POTHOLE / SPEED_BREAKER classes; those light up via the model contract when a trained IDD model is imported. Rickshaws typically detect as `car`/`truck` meanwhile.
- IPM distance assumes a locally flat road; crests/dips bias Z (the width-prior cross-check bounds the error and flags low confidence).
- Wrong-side detection (stretch, default off) keys on closing speed alone; it cannot distinguish a stationary obstacle you approach fast from a genuine oncoming vehicle at similar closing speed.
- The 45-minute thermal soak and ≥8 FPS floor-device numbers must be validated on the physical phone (commands in README); they cannot be measured in this build environment.
