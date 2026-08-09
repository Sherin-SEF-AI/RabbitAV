package com.deepmost.rabbitav.core.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Live CameraX source bound to the drive service's lifecycle (survives
 * activity death). Analysis at 640x480 YUV_420_888, KEEP_ONLY_LATEST; an
 * optional Preview use case feeds the HUD when the governor allows it.
 */
class CameraXFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val analyzerExecutor: Executor,
) : FrameSource {

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private val frameAdapter = ImageProxyFrame()

    /** Resolution the camera actually granted; null until started. */
    @Volatile var actualSize: Size? = null
        private set

    val cameraInfo get() = camera?.cameraInfo

    override suspend fun start(consumer: FrameConsumer) {
        val provider = ProcessCameraProvider.getInstance(context).await()
        this.provider = provider

        // 640x480 preferred; ladder handled by CLOSEST_HIGHER_THEN_LOWER within
        // a 4:3 preference (Section 2). The preprocessor adapts to whatever
        // arrives, so an OEM forcing 16:9 still works.
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analysis.setAnalyzer(analyzerExecutor) { proxy ->
            consumer.onFrame(frameAdapter.wrap(proxy))
        }
        this.analysis = analysis

        withContext(Dispatchers.Main) {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                analysis,
            )
        }
        actualSize = analysis.resolutionInfo?.resolution
        Timber.tag(TAG).i("camera bound, analysis resolution=%s", actualSize)
    }

    /**
     * Attaches/detaches the HUD preview. Preview is a separate use case bound
     * to the same lifecycle; the governor detaches it at L2+.
     */
    suspend fun setPreviewEnabled(
        enabled: Boolean,
        surfaceProvider: Preview.SurfaceProvider?,
    ) = withContext(Dispatchers.Main) {
        val provider = provider ?: return@withContext
        val current = preview
        if (!enabled || surfaceProvider == null) {
            if (current != null) {
                provider.unbind(current)
                preview = null
                Timber.tag(TAG).i("preview unbound")
            }
            return@withContext
        }
        if (current != null) {
            current.setSurfaceProvider(surfaceProvider)
            return@withContext
        }
        val p = Preview.Builder().build()
        p.setSurfaceProvider(surfaceProvider)
        try {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, p)
            preview = p
            Timber.tag(TAG).i("preview bound")
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "preview bind failed; HUD stays preview-less")
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.Main) {
            try {
                provider?.unbindAll()
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "unbindAll failed")
            }
        }
        analysis?.clearAnalyzer()
        analysis = null
        preview = null
        camera = null
        provider = null
        Timber.tag(TAG).i("camera stopped")
    }

    companion object {
        private const val TAG = "RAV-Camera"
    }
}
