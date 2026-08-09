package com.deepmost.rabbitav

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.deepmost.rabbitav.service.DriveForegroundService
import com.deepmost.rabbitav.service.DriveMode
import com.deepmost.rabbitav.service.DrivePipeline
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
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
 * Instrumented smoke test (Section 8): the drive service starts, the camera
 * binds, and at least one real inference completes on this device. Includes
 * the first-launch delegate benchmark, so the timeout is generous.
 *
 * Run: ./gradlew connectedDebugAndroidTest (device required).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    } else {
        GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    @Inject lateinit var pipeline: DrivePipeline

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun serviceStartsCameraBindsOneInferenceCompletes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DriveForegroundService.start(context, DriveMode.FULL_ADAS)
        try {
            withTimeout(60_000) {
                while (!pipeline.hud.value.running) delay(250)
            }
            assertTrue("pipeline should be running", pipeline.isRunning)

            // First launch runs the delegate benchmark before live inference.
            withTimeout(180_000) {
                while (pipeline.perf.value.p50Ms <= 0f && pipeline.perf.value.detectorFps <= 0f) {
                    delay(500)
                }
            }
            val perf = pipeline.perf.value
            assertTrue(
                "expected at least one completed inference (p50=${perf.p50Ms} fps=${perf.detectorFps})",
                perf.p50Ms > 0f || perf.detectorFps > 0f
            )
        } finally {
            DriveForegroundService.stop(context)
            withTimeout(20_000) {
                while (pipeline.isRunning) delay(250)
            }
        }
    }
}
