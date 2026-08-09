package com.deepmost.rabbitav.core.inference

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Locates, validates, and stages detector models per the Section 6 contract.
 *
 * Priority: filesDir/models/active/ (developer override, imported from the ADB
 * staging dir via the debug screen) then the bundled asset model staged into
 * filesDir/models/bundled/ (interpreters need a real File to mmap).
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class Source { ACTIVE_OVERRIDE, BUNDLED }

    data class LoadedModel(
        val config: ModelConfig,
        val modelFile: File,
        val dir: File,
        val source: Source,
    )

    private val _active = MutableStateFlow<LoadedModel?>(null)
    val active: StateFlow<LoadedModel?> = _active

    private val modelsRoot: File get() = File(context.filesDir, "models")
    private val activeDir: File get() = File(modelsRoot, "active")
    private val bundledDir: File get() = File(modelsRoot, "bundled")

    /** ADB-visible staging dir: adb push model.tflite model_config.json here. */
    fun stagingDir(): File =
        File(context.getExternalFilesDir(null), "models-staging").apply { mkdirs() }

    suspend fun load(): LoadedModel = withContext(Dispatchers.IO) {
        stageBundledIfNeeded()
        val fromActive = tryLoadDir(activeDir, Source.ACTIVE_OVERRIDE)
        val model = fromActive ?: tryLoadDir(bundledDir, Source.BUNDLED)
            ?: throw IOException("no usable model: bundled staging failed and no override present")
        _active.value = model
        Timber.tag(TAG).i(
            "model loaded: %s from %s (caps=%s)",
            model.config.name, model.source, model.config.capabilities
        )
        model
    }

    private fun tryLoadDir(dir: File, source: Source): LoadedModel? {
        val problems = validateDir(dir)
        if (problems.isNotEmpty()) {
            if (dir.exists()) Timber.tag(TAG).w("model dir %s rejected: %s", dir, problems)
            return null
        }
        val config = ModelConfig.parse(File(dir, SIDECAR_NAME).readText())
        return LoadedModel(config, File(dir, MODEL_NAME), dir, source)
    }

    /** Empty list = valid. */
    fun validateDir(dir: File): List<String> {
        val problems = mutableListOf<String>()
        val model = File(dir, MODEL_NAME)
        val sidecar = File(dir, SIDECAR_NAME)
        if (!model.isFile) return listOf("missing $MODEL_NAME")
        if (!sidecar.isFile) return listOf("missing $SIDECAR_NAME")
        if (model.length() < 1024) problems += "model.tflite implausibly small (${model.length()} B)"
        // TFLite flatbuffer magic: bytes 4..7 == "TFL3"
        try {
            model.inputStream().use { s ->
                val head = ByteArray(8)
                if (s.read(head) < 8 || head[4] != 'T'.code.toByte() || head[5] != 'F'.code.toByte() ||
                    head[6] != 'L'.code.toByte() || head[7] != '3'.code.toByte()
                ) {
                    problems += "model.tflite lacks TFL3 magic"
                }
            }
            val config = ModelConfig.parse(sidecar.readText())
            problems += config.validate()
        } catch (t: Throwable) {
            problems += "sidecar/model unreadable: ${t.message}"
        }
        return problems
    }

    /**
     * Copies assets/models/default -> filesDir/models/bundled when missing or
     * when the APK's model changed (detected by name+size marker).
     */
    private fun stageBundledIfNeeded() {
        val assetDir = "models/default"
        val assetFiles = try {
            context.assets.list(assetDir)?.toList().orEmpty()
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "asset listing failed")
            emptyList()
        }
        if (MODEL_NAME !in assetFiles || SIDECAR_NAME !in assetFiles) {
            Timber.tag(TAG).e("APK is missing the bundled model (%s)", assetFiles)
            return
        }
        val sidecarText = context.assets.open("$assetDir/$SIDECAR_NAME").bufferedReader().readText()
        val marker = File(bundledDir, ".staged")
        val expected = "v1:" + sidecarText.hashCode() + ":" + sidecarText.length
        if (marker.isFile && marker.readText() == expected && validateDir(bundledDir).isEmpty()) return

        Timber.tag(TAG).i("staging bundled model to %s", bundledDir)
        bundledDir.mkdirs()
        for (name in listOf(MODEL_NAME, SIDECAR_NAME)) {
            context.assets.open("$assetDir/$name").use { input ->
                File(bundledDir, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        marker.writeText(expected)
    }

    /** Validates + installs the staged override, then reloads. */
    suspend fun importStaged(): Result<LoadedModel> = withContext(Dispatchers.IO) {
        val staging = stagingDir()
        val problems = validateDir(staging)
        if (problems.isNotEmpty()) {
            return@withContext Result.failure(IOException("staged model invalid: $problems"))
        }
        activeDir.mkdirs()
        for (name in listOf(MODEL_NAME, SIDECAR_NAME)) {
            File(staging, name).copyTo(File(activeDir, name), overwrite = true)
        }
        runCatching { load() }
    }

    suspend fun clearOverride(): Result<LoadedModel> = withContext(Dispatchers.IO) {
        activeDir.deleteRecursively()
        runCatching { load() }
    }

    companion object {
        private const val TAG = "RAV-Model"
        const val MODEL_NAME = "model.tflite"
        const val SIDECAR_NAME = "model_config.json"
    }
}
