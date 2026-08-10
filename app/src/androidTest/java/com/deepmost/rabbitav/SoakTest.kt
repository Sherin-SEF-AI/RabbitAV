package com.deepmost.rabbitav

import android.Manifest
import android.os.Build
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.deepmost.rabbitav.core.data.repo.CalibrationRepository
import com.deepmost.rabbitav.core.geometry.VehiclePreset
import com.deepmost.rabbitav.service.DriveForegroundService
import com.deepmost.rabbitav.service.DriveMode
import com.deepmost.rabbitav.service.DrivePipeline
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The M5 soak gate (Section 7): a long looped-replay session with the full
 * pipeline live; asserts no crash, detector still producing frames, and
 * steady-state memory stable (no monotonic growth) at the end.
 *
 * Duration comes from the instrumentation arg `soakMinutes` (default 45):
 *
 *   adb shell am instrument -w -e class com.deepmost.rabbitav.SoakTest \
 *       -e soakMinutes 45 \
 *       com.deepmost.rabbitav.test/com.deepmost.rabbitav.HiltTestRunner
 *
 * Run via gradle-managed devices with the default only when you have 45 min;
 * CI can pass a shorter smoke duration.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SoakTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    } else {
        GrantPermissionRule.grant(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    @Inject lateinit var pipeline: DrivePipeline
    @Inject lateinit var calibrationRepository: CalibrationRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    private fun totalMemMb(): Float {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1048576f +
            Debug.getNativeHeapAllocatedSize() / 1048576f
    }

    @Test
    fun sustainedReplaySessionStaysHealthy() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val minutes = InstrumentationRegistry.getArguments()
            .getString("soakMinutes")?.toIntOrNull() ?: 45

        val testDir = File(appContext.filesDir, "test-videos").apply { mkdirs() }
        val video = File(testDir, "approach.mp4")
        instrumentation.context.assets.open("approach.mp4").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
        calibrationRepository.saveProfile(
            "soak", VehiclePreset.SEDAN, 1.30f, Math.toRadians(1.7).toFloat(), makeActive = true
        )

        DriveForegroundService.start(appContext, DriveMode.REPLAY, video.absolutePath)
        try {
            withTimeout(60_000) {
                while (!pipeline.hud.value.running) delay(200)
            }
            pipeline.setSyntheticSpeedKmh(45f)

            val samples = ArrayList<Float>(minutes + 1)
            var inferredLast = 0f
            val endAt = System.currentTimeMillis() + minutes * 60_000L
            while (System.currentTimeMillis() < endAt) {
                delay(60_000)
                assertTrue("pipeline died mid-soak", pipeline.isRunning)
                val perf = pipeline.perf.value
                // Low fps is legitimate whenever the governor is mitigating
                // (L1+): on-device runs showed Samsung applies REAL core
                // clamping under `thermalservice override-status`, so fps
                // collapse at L1/L2 is environment, not a pipeline stall.
                assertTrue(
                    "detector stalled (fps=${perf.detectorFps}, gov=${perf.governorLevel})",
                    perf.detectorFps > 0.5f || perf.governorLevel != com.deepmost.rabbitav.core.governor.PerfGovernor.Level.L0
                )
                inferredLast = perf.detectorFps
                val mem = totalMemMb()
                samples.add(mem)
                android.util.Log.i(
                    "RAV-Soak",
                    "minute ${samples.size}: mem=%.0fMB fps=%.1f p50=%.0fms gov=%s".format(
                        mem, perf.detectorFps, perf.p50Ms, perf.governorLevel
                    )
                )
            }

            // Memory stability: the mean of the last quarter must not exceed the
            // mean of the second quarter by more than 15% (startup allocations
            // settle in the first quarter; monotonic growth would trip this).
            if (samples.size >= 8) {
                val q = samples.size / 4
                val early = samples.subList(q, 2 * q).average()
                val late = samples.subList(samples.size - q, samples.size).average()
                assertTrue(
                    "memory grew: early=%.0fMB late=%.0fMB".format(early, late),
                    late <= early * 1.15
                )
            }
            // Meaningful only when the governor is idle; under mitigation the
            // detector is legitimately capped or paused.
            if (pipeline.perf.value.governorLevel == com.deepmost.rabbitav.core.governor.PerfGovernor.Level.L0) {
                assertTrue("no inference in final minute", inferredLast > 0.5f)
            }
        } finally {
            DriveForegroundService.stop(appContext)
            withTimeout(20_000) {
                while (pipeline.isRunning) delay(250)
            }
        }
    }
}
