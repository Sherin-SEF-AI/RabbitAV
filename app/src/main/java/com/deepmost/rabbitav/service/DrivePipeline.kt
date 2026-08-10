package com.deepmost.rabbitav.service

import android.content.Context
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraInfo
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.deepmost.rabbitav.R
import com.deepmost.rabbitav.core.alerts.ActiveAlert
import com.deepmost.rabbitav.core.alerts.AdasAlertEngine
import com.deepmost.rabbitav.core.alerts.AlertArbiter
import com.deepmost.rabbitav.core.alerts.AlertTuning
import com.deepmost.rabbitav.core.alerts.AudioEngine
import com.deepmost.rabbitav.core.alerts.Tone
import com.deepmost.rabbitav.core.camera.CameraXFrameSource
import com.deepmost.rabbitav.core.camera.FrameRouter
import com.deepmost.rabbitav.core.camera.FrameSource
import com.deepmost.rabbitav.core.camera.VideoFileFrameSource
import com.deepmost.rabbitav.core.data.repo.CalibrationRepository
import com.deepmost.rabbitav.core.data.repo.HazardRepository
import com.deepmost.rabbitav.core.data.repo.SettingsRepository
import com.deepmost.rabbitav.core.data.repo.TripRepository
import com.deepmost.rabbitav.core.ego.EgoEstimator
import com.deepmost.rabbitav.core.ego.LocationPipeline
import com.deepmost.rabbitav.core.geometry.CalibrationState
import com.deepmost.rabbitav.core.geometry.CameraIntrinsics
import com.deepmost.rabbitav.core.geometry.GroundGeometry
import com.deepmost.rabbitav.core.governor.PerfGovernor
import com.deepmost.rabbitav.core.governor.ThermalMonitor
import com.deepmost.rabbitav.core.hazard.ApproachMonitor
import com.deepmost.rabbitav.core.hazard.HazardFusion
import com.deepmost.rabbitav.core.imu.HazardType
import com.deepmost.rabbitav.core.imu.ImuPipeline
import com.deepmost.rabbitav.core.inference.DelegateBenchmark
import com.deepmost.rabbitav.core.inference.DelegateKind
import com.deepmost.rabbitav.core.inference.FramePreprocessor
import com.deepmost.rabbitav.core.inference.InferenceEngine
import com.deepmost.rabbitav.core.inference.ModelManager
import com.deepmost.rabbitav.core.inference.SingleSlotExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The composition root of a drive session (Section 4). Owns every pipeline
 * thread, wires camera -> inference -> tracking -> alerts and IMU -> fusion ->
 * store, and exposes read-only StateFlows to the UI. The HUD is a pure
 * observer: killing the UI never stops this.
 */
@Singleton
class DrivePipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val benchmark: DelegateBenchmark,
    private val settings: SettingsRepository,
    private val egoEstimator: EgoEstimator,
    private val locationPipeline: LocationPipeline,
    private val imuPipeline: ImuPipeline,
    private val hazardRepository: HazardRepository,
    private val tripRepository: TripRepository,
    private val calibrationRepository: CalibrationRepository,
    private val audioEngine: AudioEngine,
    private val thermalMonitor: ThermalMonitor,
) {
    // ---------------- public state ----------------
    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud

    private val _overlay = MutableStateFlow(OverlayFrame())
    val overlay: StateFlow<OverlayFrame> = _overlay

    private val _perf = MutableStateFlow(PerfStats())
    val perf: StateFlow<PerfStats> = _perf

    val isRunning: Boolean get() = scope != null
    @Volatile var currentMode: DriveMode = DriveMode.FULL_ADAS
        private set

    // ---------------- session members ----------------
    private var scope: CoroutineScope? = null
    private var analyzerThread: HandlerThread? = null
    private var analyzerExecutor: Executor? = null
    private var inferenceExecutor: SingleSlotExecutor? = null
    private var alertLoop: ScheduledExecutorService? = null

    private var engine: InferenceEngine? = null
    private var preprocessor: FramePreprocessor? = null
    private var frameRouter: FrameRouter? = null
    private var frameSource: FrameSource? = null
    private var cameraSource: CameraXFrameSource? = null

    private val trackerHub = com.deepmost.rabbitav.core.tracking.TrackerHub()
    private var alertEngine: AdasAlertEngine? = null
    private var arbiter: AlertArbiter? = null
    private var approachMonitor: ApproachMonitor? = null
    private var hazardFusion: HazardFusion? = null
    private var governor: PerfGovernor? = null
    private val perfMonitor = PerfMonitor()

    @Volatile private var tuning = AlertTuning()
    @Volatile private var calibration: CalibrationState = CalibrationState.INVALID
    @Volatile private var language = "en"
    private var clipRecorder: IncidentClipRecorder? = null
    private var clipFrameW = 0
    private var clipFrameH = 0

    private val _calibrationDrift = MutableStateFlow(false)
    val calibrationDrift: StateFlow<Boolean> = _calibrationDrift
    @Volatile private var geometry: GroundGeometry? = null
    @Volatile private var corridorOverlay: CorridorOverlay? = null
    @Volatile private var benchmarking = false
    @Volatile private var hazardsThisTrip = 0
    @Volatile private var lastEgoSpeedMps = 0f
    @Volatile private var lastEgoSpeedAtMs = 0L
    @Volatile private var adasSuspendedByGovernor = false

    private val mergedAlerts = ArrayList<ActiveAlert>(8)
    private val engineAlerts = ArrayList<ActiveAlert>(8)

    // ------------------------------------------------------------------ start

    /**
     * Starts a session. Heavy (model load + possible first-run benchmark);
     * call from the service on a background dispatcher.
     */
    suspend fun start(mode: DriveMode, lifecycleOwner: LifecycleOwner, replayFile: File? = null) {
        if (isRunning) {
            Timber.tag(TAG).w("start ignored; already running")
            return
        }
        Timber.tag(TAG).i("pipeline starting in %s", mode)
        currentMode = mode
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = sessionScope
        hazardsThisTrip = 0

        tuning = settings.tuningNow()
        calibration = calibrationRepository.activeStateNow()

        // --- alert plumbing (exists in every mode; POCKET only uses approach alerts) ---
        audioEngine.volume = settings.audioVolume.first()
        audioEngine.ttsEnabled = settings.ttsEnabled.first()
        audioEngine.ttsLocale = if (settings.language.first() == "hi") java.util.Locale("hi", "IN") else java.util.Locale.ENGLISH
        val arb = AlertArbiter(audioEngine) { alert ->
            sessionScope.launch { tripRepository.onAlert(alert, egoEstimator.state.value) }
            // Incident clips persist on critical warnings (Section 5.11)
            if (alert.level == com.deepmost.rabbitav.core.alerts.AlertLevel.CRITICAL) {
                clipRecorder?.trigger(alert.kind.name.lowercase())
            }
        }
        arbiter = arb
        val engineAlertsEval = AdasAlertEngine { tuning }
        engineAlertsEval.wrongSideEnabled = settings.wrongSideEnabled.first()
        alertEngine = engineAlertsEval
        approachMonitor = ApproachMonitor(hazardRepository, { tuning }) { type -> hazardSpeech(type) }
        approachMonitor?.ttsEnabled = audioEngine.ttsEnabled

        // --- trip + ego + IMU (all modes) ---
        tripRepository.startTrip(mode.name)
        egoEstimator.reset()
        egoEstimator.setSyntheticMode(mode == DriveMode.REPLAY)
        if (mode != DriveMode.REPLAY) locationPipeline.start()

        val fusion = HazardFusion(
            scope = sessionScope,
            egoEstimator = egoEstimator,
            visualClassifier = DetectorBackedVisualClassifier(
                executor = inferenceExecutorOrCreate(),
                engineProvider = { engine },
                capabilityProvider = {
                    modelManager.active.value?.config?.supportsRoadHazardClassification == true
                },
            ),
            store = hazardRepository,
            ringProvider = { frameRouter?.ring },
            tripIdProvider = { tripRepository.currentTripId },
            onStored = { _, _ ->
                hazardsThisTrip++
                sessionScope.launch { tripRepository.onHazardLogged() }
            },
        )
        hazardFusion = fusion
        imuPipeline.onCandidate = { candidate -> fusion.onCandidate(candidate) }
        imuPipeline.egoSpeedProvider = { egoEstimator.state.value.let { if (it.speedValid) it.speedMps else 0f } }
        imuPipeline.start()

        // ego collectors: trip stats, approach monitor, IMU forward-axis accel
        sessionScope.launch {
            egoEstimator.state.collect { ego ->
                if (ego.timeMs == 0L) return@collect
                tripRepository.onEgoUpdate(ego)
                approachMonitor?.onEgoUpdate(ego)
                if (ego.speedValid) {
                    val now = SystemClock.elapsedRealtime()
                    if (lastEgoSpeedAtMs != 0L) {
                        val dt = (now - lastEgoSpeedAtMs) / 1000f
                        if (dt > 0.2f) imuPipeline.setEgoAccel((ego.speedMps - lastEgoSpeedMps) / dt)
                    }
                    lastEgoSpeedMps = ego.speedMps
                    lastEgoSpeedAtMs = now
                    settings.setLastPosition(ego.lat, ego.lon)
                }
            }
        }
        // calibration live updates
        sessionScope.launch {
            calibrationRepository.activeState.collect { c ->
                calibration = c
                rebuildGeometry()
            }
        }
        // tuning live updates
        sessionScope.launch { settings.tuning.collect { tuning = it } }
        sessionScope.launch { settings.language.collect { language = it } }

        // Drive-start calibration drift check (Section 5.9): if the mount
        // pitch deviates > 3 deg from the active profile, prompt recalibration.
        _calibrationDrift.value = false
        if (mode == DriveMode.FULL_ADAS) {
            sessionScope.launch { checkCalibrationDrift() }
        }

        // --- vision chain (FULL_ADAS + REPLAY) ---
        if (mode != DriveMode.POCKET) {
            startVisionChain(mode, lifecycleOwner, replayFile, sessionScope)
        }

        // --- 25 Hz alert loop (all modes; pocket = approach alerts only) ---
        startAlertLoop(sessionScope)

        // weekly-ish prune opportunity
        sessionScope.launch { hazardRepository.pruneDecayed() }

        Timber.tag(TAG).i("pipeline started (%s)", mode)
    }

    private fun inferenceExecutorOrCreate(): SingleSlotExecutor {
        return inferenceExecutor ?: SingleSlotExecutor("rav-infer").also { inferenceExecutor = it }
    }

    private suspend fun startVisionChain(
        mode: DriveMode,
        lifecycleOwner: LifecycleOwner,
        replayFile: File?,
        sessionScope: CoroutineScope,
    ) {
        benchmarking = false
        val model = modelManager.load()
        val executor = inferenceExecutorOrCreate()

        // Delegate: persisted winner for this model, or run the first-launch benchmark.
        var delegateKind = settings.chosenDelegateFor(model.config.name)
        if (delegateKind == null) {
            benchmarking = true
            publishHud() // HUD shows "benchmarking"
            Timber.tag(TAG).i("no persisted delegate for %s; benchmarking", model.config.name)
            val report = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                executor.submit {
                    try {
                        cont.resumeWith(Result.success(benchmark.run(model)))
                    } catch (t: Throwable) {
                        cont.resumeWith(Result.failure(t))
                    }
                }
            }
            settings.setBenchmarkReport(report)
            delegateKind = report.winner
            benchmarking = false
        }

        // Engine construction on the inference thread.
        val eng = kotlinx.coroutines.suspendCancellableCoroutine<InferenceEngine> { cont ->
            executor.submit {
                try {
                    val e = InferenceEngine(model.modelFile, model.config, delegateKind, numThreads = 4)
                    e.prepare()
                    cont.resumeWith(Result.success(e))
                } catch (t: Throwable) {
                    Timber.tag(TAG).e(t, "engine init failed on %s; falling back to XNNPACK", delegateKind)
                    try {
                        val e = InferenceEngine(model.modelFile, model.config, DelegateKind.XNNPACK, numThreads = 4)
                        e.prepare()
                        cont.resumeWith(Result.success(e))
                    } catch (t2: Throwable) {
                        cont.resumeWith(Result.failure(t2))
                    }
                }
            }
        }
        engine = eng
        alertEngine?.visualHazardCapability = model.config.supportsRoadHazardDetection

        val prep = FramePreprocessor()
        preprocessor = prep
        val router = FrameRouter(
            preprocessor = prep,
            executor = executor,
            onDetections = { frame ->
                perfMonitor.recordInference(frame.latencyMs)
                trackerHub.update(frame)
                if (geometry == null) rebuildGeometry()
            },
            onFrameStats = { _, analyzed -> perfMonitor.recordFrame(analyzed) },
        )
        router.engine = eng
        frameRouter = router

        // governor
        val gov = PerfGovernor(thermalMonitor)
        governor = gov
        thermalMonitor.start()
        sessionScope.launch {
            gov.state.collect { gs -> applyGovernorState(gs) }
        }

        // Incident clips (opt-in, camera-bearing modes; Section 5.11). The
        // recorder starts lazily on the first fed frame (dims known then) and
        // follows the settings toggle live.
        sessionScope.launch {
            settings.incidentClipEnabled.collect { enabled ->
                if (enabled) {
                    router.clipFeeder = { prep, ts ->
                        val rec = clipRecorderOrStart(prep.uprightW, prep.uprightH)
                        val buf = rec?.borrowBuffer()
                        if (rec != null && buf != null) {
                            if (prep.copyUprightTo(buf)) rec.submitFrame(buf, ts)
                            else rec.returnBuffer(buf)
                        }
                    }
                } else {
                    router.clipFeeder = null
                    clipRecorder?.stop()
                    clipRecorder = null
                }
            }
        }

        // frame source
        val source: FrameSource = if (mode == DriveMode.REPLAY) {
            val file = replayFile ?: File(settings.replayVideoPath.first())
            if (!file.isFile) {
                Timber.tag(TAG).e("replay file missing: %s", file)
                throw IllegalStateException("replay file missing: $file")
            }
            VideoFileFrameSource(file, loop = settings.replayLoop.first())
        } else {
            val analyzer = HandlerThread("rav-analyzer", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
            analyzerThread = analyzer
            val exec = Executor { r -> android.os.Handler(analyzer.looper).post(r) }
            analyzerExecutor = exec
            CameraXFrameSource(context, lifecycleOwner, exec).also { cameraSource = it }
        }
        frameSource = source
        source.start(router)
        rebuildGeometry()

        // initial preview per settings + governor
        if (mode == DriveMode.FULL_ADAS) {
            Timber.tag(TAG).i("camera analysis size: %s", cameraSource?.actualSize)
        }
    }

    // ------------------------------------------------------------ alert loop

    private fun startAlertLoop(sessionScope: CoroutineScope) {
        val loop = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "rav-alert").apply { priority = Thread.NORM_PRIORITY + 2 }
        }
        alertLoop = loop
        var tickCount = 0L
        loop.scheduleAtFixedRate({
            try {
                tick(tickCount++)
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "alert loop tick failed")
            }
        }, 0, ALERT_PERIOD_MS, TimeUnit.MILLISECONDS)
    }

    private fun tick(count: Long) {
        val tNs = SystemClock.elapsedRealtimeNanos()
        egoEstimator.tick()
        val ego = egoEstimator.state.value

        val snapshots = trackerHub.predictAndSnapshot(tNs, geometry, if (ego.speedValid) ego.speedMps else Float.NaN)

        engineAlerts.clear()
        if (currentMode != DriveMode.POCKET && !adasSuspendedByGovernor) {
            alertEngine?.evaluate(snapshots, ego, calibration.valid, tNs, engineAlerts)
        }
        mergedAlerts.clear()
        mergedAlerts.addAll(engineAlerts)
        approachMonitor?.currentAlerts(System.currentTimeMillis())?.let { mergedAlerts.addAll(it) }
        arbiter?.submit(mergedAlerts)

        // publish overlay every other tick (~12 Hz)
        if (count % 2 == 0L) {
            val prep = preprocessor
            val aspect = if (prep != null && prep.uprightH > 0 && prep.uprightW > 0) {
                prep.uprightW.toFloat() / prep.uprightH
            } else 4f / 3f
            _overlay.value = OverlayFrame(snapshots, corridorOverlay, tNs, aspect)
        }
        publishHud()

        // 1 Hz housekeeping
        if (count % 25 == 0L) {
            val snap = perfMonitor.snapshot()
            governor?.let { g ->
                g.inferenceP90Ms = snap.p90Ms
                g.frameDropRatio = snap.dropRatio
                g.tick(System.currentTimeMillis())
            }
            _perf.value = PerfStats(
                detectorFps = snap.detectorFps,
                cameraFps = snap.cameraFps,
                p50Ms = snap.p50Ms,
                p90Ms = snap.p90Ms,
                delegate = engine?.delegateKind ?: DelegateKind.XNNPACK,
                dropRatio = snap.dropRatio,
                totalMemMb = snap.memMb,
                thermalPressure = thermalMonitor.pressure(),
                batteryTempC = thermalMonitor.batteryTempC(),
                governorLevel = governor?.state?.value?.level ?: PerfGovernor.Level.L0,
                modelName = modelManager.active.value?.config?.name ?: "",
                inputSize = engine?.inputWidth ?: 0,
                benchmarking = benchmarking,
            )
        }
    }

    private fun publishHud() {
        val ego = egoEstimator.state.value
        val lead = _overlay.value.tracks
            .filter { it.inCorridor && it.canonical.isVehicle && it.confirmed }
            .minByOrNull { if (it.zMeters.isNaN()) Float.MAX_VALUE else it.zMeters }
        val reason = when {
            benchmarking -> AdasInactiveReason.BENCHMARKING
            currentMode == DriveMode.POCKET -> AdasInactiveReason.POCKET_MODE
            adasSuspendedByGovernor -> AdasInactiveReason.GOVERNOR_SUSPENDED
            !calibration.valid -> AdasInactiveReason.NO_CALIBRATION
            !ego.speedValid -> AdasInactiveReason.NO_GPS
            ego.speedKmh < tuning.globalMinSpeedKmh -> AdasInactiveReason.LOW_SPEED
            else -> AdasInactiveReason.NONE
        }
        _hud.value = HudState(
            running = true,
            mode = currentMode,
            speedKmh = if (ego.speedValid) ego.speedKmh else 0f,
            speedValid = ego.speedValid,
            calibrationValid = calibration.valid,
            adasActive = reason == AdasInactiveReason.NONE,
            adasInactiveReason = reason,
            topAlert = arbiter?.topAlert?.value,
            leadHeadwayS = lead?.headwayS ?: Float.POSITIVE_INFINITY,
            leadDistanceM = lead?.zMeters ?: Float.NaN,
            governorLevel = governor?.state?.value?.level ?: PerfGovernor.Level.L0,
            hazardsThisTrip = hazardsThisTrip,
            tripDistanceKm = (tripRepository.currentDistanceM / 1000.0).toFloat(),
            synthetic = ego.synthetic,
            calibrationDrift = _calibrationDrift.value,
        )
    }

    // --------------------------------------------------------------- governor

    private suspend fun applyGovernorState(gs: PerfGovernor.GovernorState) {
        val router = frameRouter ?: return
        Timber.tag(TAG).i("applying governor %s (%s)", gs.level, gs.reason)
        router.minSubmitIntervalNs = if (gs.inferenceCapFps > 0) 1_000_000_000L / gs.inferenceCapFps else 0L

        val wasSuspended = adasSuspendedByGovernor
        adasSuspendedByGovernor = !gs.detectorEnabled
        router.detectorEnabled = gs.detectorEnabled
        if (adasSuspendedByGovernor && !wasSuspended) {
            trackerHub.clear()
            audioEngine.play(Tone.ADAS_SUSPENDED)
        } else if (!adasSuspendedByGovernor && wasSuspended) {
            audioEngine.play(Tone.ADAS_RESUMED)
        }

        // input resize (only when the model supports it)
        val eng = engine
        if (eng != null && gs.inputSize > 0 && eng.config.input.resizable) {
            inferenceExecutor?.submit {
                router.reconfigure {
                    if (eng.resizeInput(gs.inputSize, gs.inputSize)) {
                        // preprocessor reconfigures lazily on the next frame
                        preprocessor?.close()
                    }
                }
            }
        } else if (eng != null && gs.inputSize == 0 && eng.inputWidth != eng.config.input.width) {
            inferenceExecutor?.submit {
                router.reconfigure {
                    if (eng.resizeInput(eng.config.input.width, eng.config.input.height)) {
                        preprocessor?.close()
                    }
                }
            }
        }

        // preview visibility is applied by the UI collecting governor state
        // through hud.governorLevel + settings; camera preview unbinds here:
        cameraSource?.let { cam ->
            if (!gs.previewEnabled) cam.setPreviewEnabled(false, null)
        }
    }

    /** UI attaches/detaches the HUD preview surface (governor permitting). */
    suspend fun attachPreview(surfaceProvider: Preview.SurfaceProvider?) {
        val allowed = governor?.state?.value?.previewEnabled ?: true
        cameraSource?.setPreviewEnabled(allowed && surfaceProvider != null, surfaceProvider)
    }

    // --------------------------------------------------------------- geometry

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun rebuildGeometry() {
        val prep = preprocessor ?: return
        if (prep.uprightW == 0) return
        val intrinsics = buildIntrinsics(prep.uprightW, prep.uprightH)
        val geo = GroundGeometry(intrinsics, calibration)
        geometry = geo
        corridorOverlay = if (calibration.valid) {
            CorridorOverlay(
                horizonVNorm = geo.horizonV() / intrinsics.height,
                rungs = listOf(10f, 25f, 50f).map { z ->
                    val v = geo.vForDistance(z)
                    val halfPx = intrinsics.fx * GroundGeometry.CORRIDOR_HALF_VEHICLE_M / z
                    floatArrayOf(
                        v,
                        (intrinsics.cx - halfPx) / intrinsics.width,
                        (intrinsics.cx + halfPx) / intrinsics.width,
                    )
                },
            )
        } else null
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun buildIntrinsics(uprightW: Int, uprightH: Int): CameraIntrinsics {
        val info = cameraSource?.cameraInfo
        if (info != null) {
            try {
                val c2 = Camera2CameraInfo.from(info)
                val focals = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val physical = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val active = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val rotation = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                if (focals != null && focals.isNotEmpty() && physical != null && active != null) {
                    return CameraIntrinsics.fromPhysical(
                        focalMm = focals[0],
                        sensorWidthMm = physical.width,
                        sensorHeightMm = physical.height,
                        activeW = active.width(),
                        activeH = active.height(),
                        uprightW = uprightW,
                        uprightH = uprightH,
                        rotationDegrees = rotation,
                    )
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "camera2 intrinsics unavailable")
            }
        }
        return CameraIntrinsics.fallback(uprightW, uprightH)
    }

    // ------------------------------------------------------------------ misc

    /** Manual report chips (HUD). */
    fun reportManualHazard(type: HazardType) {
        audioEngine.play(Tone.REPORT_ACK)
        hazardFusion?.onManualReport(type)
    }

    /** Replay-mode synthetic ego speed slider (Section 5.12). */
    fun setSyntheticSpeedKmh(kmh: Float) = egoEstimator.setSyntheticSpeedKmh(kmh)

    fun imuTraceSnapshot(): FloatArray = imuPipeline.traceSnapshot()

    /** Re-runs the delegate benchmark (debug screen). Heavy. */
    suspend fun rerunBenchmark(): DelegateBenchmark.BenchmarkReport? {
        val model = modelManager.active.value ?: return null
        val executor = inferenceExecutor ?: return null
        benchmarking = true
        return try {
            val report = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                executor.submit {
                    try {
                        cont.resumeWith(Result.success(benchmark.run(model)))
                    } catch (t: Throwable) {
                        cont.resumeWith(Result.failure(t))
                    }
                }
            }
            settings.setBenchmarkReport(report)
            report
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "benchmark rerun failed")
            null
        } finally {
            benchmarking = false
        }
    }

    private fun clipRecorderOrStart(w: Int, h: Int): IncidentClipRecorder? {
        val existing = clipRecorder
        if (existing != null && existing.isRunning && w == clipFrameW && h == clipFrameH) return existing
        if (w <= 0 || h <= 0) return null
        existing?.stop()
        val rec = IncidentClipRecorder(context)
        rec.start(w, h)
        clipRecorder = rec
        clipFrameW = w
        clipFrameH = h
        return rec
    }

    /** Section 5.9: stationary-ish gravity sample at drive start; > 3 deg
     *  deviation from the active profile raises the recalibration prompt. */
    private suspend fun checkCalibrationDrift() {
        kotlinx.coroutines.delay(2000) // let the mount settle after start
        val calib = calibration
        if (!calib.valid) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val accel = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                sx += event.values[0]; sy += event.values[1]; sz += event.values[2]; n++
                if (n >= 100) done.complete(Unit)
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, accel, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        try {
            kotlinx.coroutines.withTimeoutOrNull(4000) { done.await() }
        } finally {
            sm.unregisterListener(listener)
        }
        if (n < 20) return
        val pitch = com.deepmost.rabbitav.core.geometry.PitchMath.pitchFromGravity(
            (sx / n).toFloat(), (sy / n).toFloat(), (sz / n).toFloat()
        )
        if (pitch.isNaN()) return // moving/vibrating: skip rather than mis-warn
        val deltaDeg = Math.toDegrees(kotlin.math.abs(pitch - calib.pitchRad).toDouble())
        if (deltaDeg > CALIBRATION_DRIFT_DEG) {
            Timber.tag(TAG).w("mount pitch drift %.1f deg vs profile; prompting recalibration", deltaDeg)
            _calibrationDrift.value = true
        }
    }

    private fun hazardSpeech(type: HazardType): String {
        // Localize to the chosen app language (TTS strings must follow the
        // audio language even when the UI process locale differs).
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(if (language == "hi") java.util.Locale("hi", "IN") else java.util.Locale.ENGLISH)
        val res = context.createConfigurationContext(config).resources
        return when (type) {
            HazardType.SPEED_BREAKER -> res.getString(R.string.tts_breaker_ahead)
            HazardType.POTHOLE -> res.getString(R.string.tts_pothole_ahead)
            HazardType.WATERLOGGING -> res.getString(R.string.tts_water_ahead)
            else -> res.getString(R.string.tts_rough_ahead)
        }
    }

    // ------------------------------------------------------------------ stop

    suspend fun stop() {
        if (!isRunning) return
        Timber.tag(TAG).i("pipeline stopping")

        alertLoop?.shutdownNow()
        alertLoop = null

        frameSource?.stop()
        frameSource = null
        cameraSource = null

        imuPipeline.stop()
        locationPipeline.stop()
        egoEstimator.setSyntheticMode(false)

        arbiter?.reset()
        alertEngine?.reset()
        approachMonitor?.reset()
        trackerHub.clear()

        val exec = inferenceExecutor
        if (exec != null) {
            val done = java.util.concurrent.CountDownLatch(1)
            exec.submit {
                try {
                    engine?.close()
                } finally {
                    done.countDown()
                }
            }
            done.await(2, TimeUnit.SECONDS)
            exec.close()
        }
        inferenceExecutor = null
        engine = null

        clipRecorder?.stop()
        clipRecorder = null

        preprocessor?.close()
        preprocessor = null
        frameRouter = null
        analyzerThread?.quitSafely()
        analyzerThread = null

        tripRepository.endTrip()

        governor?.reset()
        governor = null
        adasSuspendedByGovernor = false
        perfMonitor.reset()

        scope?.cancel()
        scope = null

        _hud.value = HudState()
        _overlay.value = OverlayFrame()
        Timber.tag(TAG).i("pipeline stopped")
    }

    companion object {
        private const val TAG = "RAV-Svc"

        /** 25 Hz alert loop (Section 4). */
        const val ALERT_PERIOD_MS = 40L

        /** Recalibration prompt threshold (Section 5.9): 3 degrees. */
        const val CALIBRATION_DRIFT_DEG = 3.0
    }
}
