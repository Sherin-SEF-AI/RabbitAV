package com.deepmost.rabbitav.core.imu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.HandlerThread
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Android sensor wiring for the jolt engine (Section 5.6): accelerometer (and
 * gyroscope when present) at SENSOR_DELAY_FASTEST on a dedicated thread,
 * vehicle-frame reorientation, band-pass, trigger, classify. Also provides the
 * debug CSV recorder (Section 5.12) and a decimated trace for the debug UI.
 */
@Singleton
class ImuPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val hasGyro: Boolean = gyro != null

    private var thread: HandlerThread? = null
    val aligner = VehicleFrameAligner()
    private var detector: JoltDetector? = null

    @Volatile var onCandidate: (HazardCandidate) -> Unit = {}
    @Volatile var egoSpeedProvider: () -> Float = { 0f }

    @Volatile private var lastPitchRate = Float.NaN
    private var lastAccelTs = 0L
    private var rateEstimateHz = 0f
    private var rateSamples = 0
    private var detectorRebuilt = false

    @Volatile var measuredRateHz: Float = 0f
        private set

    // debug trace: ~10 Hz ring of band-passed values for the debug screen chart
    private val traceCap = 300
    private val trace = FloatArray(traceCap)
    private var traceIdx = 0
    private var lastTraceNs = 0L

    // CSV recorder (debug screen; Section 5.12 IMU replay capture)
    private var recorder: BufferedWriter? = null
    private var recorderFile: File? = null
    private val recorderLock = Any()

    val isRunning: Boolean get() = thread != null

    fun start() {
        if (thread != null) return
        if (accel == null) {
            Timber.tag(TAG).e("no accelerometer; hazard mapping unavailable")
            return
        }
        val t = HandlerThread("rav-sensor", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
        thread = t

        // Nominal rate from the sensor's minDelay; the detector is rebuilt at
        // the measured rate after ~3 s if reality deviates (some OEMs throttle).
        val nominalHz = if (accel.minDelay > 0) (1_000_000f / accel.minDelay).coerceIn(50f, 500f) else 100f
        buildDetector(nominalHz)

        val handler = android.os.Handler(t.looper)
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST, handler)
        if (gyro != null) {
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, handler)
        } else {
            Timber.tag(TAG).w("no gyroscope: gyro features off, confidence capped at %.1f", JoltDetector.NO_GYRO_CONF_CAP)
        }
        Timber.tag(TAG).i("IMU started (accel nominal %.0f Hz, gyro=%b)", nominalHz, hasGyro)
    }

    private fun buildDetector(fsHz: Float) {
        detector = JoltDetector(fsHz, hasGyro) { candidate -> onCandidate(candidate) }.also { d ->
            d.egoSpeedMps = { egoSpeedProvider() }
            d.onCaptureStateChanged = { capturing -> aligner.freezeAdaptation = capturing }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        stopRecording()
        aligner.reset()
        detector?.reset()
        rateSamples = 0
        detectorRebuilt = false
        Timber.tag(TAG).i("IMU stopped")
    }

    /** GPS-derived longitudinal acceleration for the forward-axis learner. */
    fun setEgoAccel(accelMps2: Float) {
        aligner.egoAccelMps2 = accelMps2
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val ts = event.timestamp
                val dt = if (lastAccelTs == 0L) 0.005f else ((ts - lastAccelTs) / 1e9f).coerceIn(1e-4f, 0.1f)
                if (lastAccelTs != 0L) {
                    // running rate estimate; rebuild filters once at measured rate
                    rateEstimateHz = if (rateSamples == 0) 1f / dt else 0.98f * rateEstimateHz + 0.02f / dt
                    rateSamples++
                    if (!detectorRebuilt && rateSamples > 300) {
                        detectorRebuilt = true
                        measuredRateHz = rateEstimateHz
                        val nominal = detector
                        if (nominal != null) {
                            buildDetector(rateEstimateHz)
                            if (rateEstimateHz < 80f) {
                                Timber.tag(TAG).w("IMU effective rate %.0f Hz < 80 Hz; jolt fidelity reduced", rateEstimateHz)
                            } else {
                                Timber.tag(TAG).i("IMU measured rate %.0f Hz", rateEstimateHz)
                            }
                        }
                    }
                }
                lastAccelTs = ts

                val vertical = aligner.processAccel(event.values[0], event.values[1], event.values[2], dt)
                detector?.process(vertical, lastPitchRate, ts)

                // debug trace at ~10 Hz
                if (ts - lastTraceNs > 100_000_000L) {
                    lastTraceNs = ts
                    trace[traceIdx] = detector?.lastBandPassed ?: 0f
                    traceIdx = (traceIdx + 1) % traceCap
                }

                record(ts, event.values, isGyro = false)
            }
            Sensor.TYPE_GYROSCOPE -> {
                lastPitchRate = aligner.pitchRate(event.values[0], event.values[1], event.values[2])
                record(event.timestamp, event.values, isGyro = true)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Ordered copy of the debug trace (oldest first). */
    fun traceSnapshot(): FloatArray {
        val out = FloatArray(traceCap)
        for (i in 0 until traceCap) out[i] = trace[(traceIdx + i) % traceCap]
        return out
    }

    val currentThresholdMps2: Float get() = detector?.lastThreshold ?: JoltDetector.TRIGGER_FLOOR_MPS2

    // ------------------------------------------------------------- recorder

    fun startRecording(): File? {
        synchronized(recorderLock) {
            if (recorder != null) return recorderFile
            val dir = File(context.getExternalFilesDir(null), "imu-recordings").apply { mkdirs() }
            val f = File(dir, "imu_${System.currentTimeMillis()}.csv")
            return try {
                recorder = BufferedWriter(FileWriter(f), 1 shl 16).also {
                    it.write("t_ns,sensor,x,y,z\n")
                }
                recorderFile = f
                Timber.tag(TAG).i("IMU recording -> %s", f)
                f
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "recorder start failed")
                null
            }
        }
    }

    fun stopRecording(): File? {
        synchronized(recorderLock) {
            val f = recorderFile
            try {
                recorder?.flush()
                recorder?.close()
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "recorder close failed")
            }
            recorder = null
            recorderFile = null
            if (f != null) Timber.tag(TAG).i("IMU recording saved: %s (%d B)", f, f.length())
            return f
        }
    }

    val isRecording: Boolean get() = synchronized(recorderLock) { recorder != null }

    private fun record(ts: Long, values: FloatArray, isGyro: Boolean) {
        val r = recorder ?: return
        synchronized(recorderLock) {
            try {
                recorder?.let {
                    it.write(ts.toString())
                    it.write(if (isGyro) ",g," else ",a,")
                    it.write("${values[0]},${values[1]},${values[2]}\n")
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "record write failed; stopping recorder")
                stopRecording()
            }
        }
    }

    companion object {
        private const val TAG = "RAV-IMU"
    }
}
