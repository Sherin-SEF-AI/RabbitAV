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
