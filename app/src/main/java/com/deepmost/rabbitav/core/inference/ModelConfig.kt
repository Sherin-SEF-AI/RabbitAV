package com.deepmost.rabbitav.core.inference

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The model_config.json sidecar contract (Section 6). Versioned; schema 1.
 * A custom-trained multi-task model drops in later by shipping this same file
 * with extra classes and capabilities set true — zero app changes.
 */
@Serializable
data class ModelConfig(
    val schema: Int,
    val name: String,
    val input: InputSpec,
    val decode: DecodeSpec,
    val classes: List<String>,
    val classMap: Map<String, String> = emptyMap(),
    val capabilities: Map<String, Boolean> = emptyMap(),
) {
    @Serializable
    data class InputSpec(
        val width: Int,
        val height: Int,
        val layout: String = "NHWC",
        val quantized: Boolean = false,
        /** True when the graph is fully convolutional and supports resizeInput
         *  (YOLO). SSD's baked-in anchor post-process is NOT resizable. */
        val resizable: Boolean = false,
    )

    @Serializable
    data class DecodeSpec(
        /** "yolo_v8" (single [1, 4+nc, N] tensor, also covers YOLO11) or
         *  "ssd" (4-tensor TFLite_Detection_PostProcess). */
        val family: String,
        val outputShape: List<Int> = emptyList(),
        /** Detections below this score are dropped. Sane range 0.2–0.6. */
        val confThreshold: Float = 0.35f,
        /** Class-agnostic NMS IoU threshold. Sane range 0.4–0.7. */
        val iouThreshold: Float = 0.5f,
        /** "auto" sniffs pixel-vs-normalized coords on first decode;
         *  "normalized"/"pixels" force it. */
        val coords: String = "auto",
    )

    /** Canonical class for a raw model class index, or null if unmapped. */
    fun canonicalFor(rawIndex: Int): CanonicalClass? {
        val label = classes.getOrNull(rawIndex) ?: return null
        val mapped = classMap[label] ?: return null
        val canonical = CanonicalClass.fromName(mapped)
        return if (canonical == CanonicalClass.UNKNOWN) null else canonical
    }

    fun capability(name: String): Boolean = capabilities[name] == true

    val supportsRoadHazardDetection: Boolean get() = capability("road_hazard_detection")
    val supportsRoadHazardClassification: Boolean get() = capability("road_hazard_classification")

    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (schema != 1) problems += "unsupported schema $schema (expected 1)"
        if (input.width !in 64..1280 || input.height !in 64..1280) {
            problems += "implausible input size ${input.width}x${input.height}"
        }
        if (decode.family !in setOf(FAMILY_YOLO_V8, FAMILY_SSD)) {
            problems += "unknown decode family '${decode.family}'"
        }
        if (classes.isEmpty()) problems += "empty class list"
        if (decode.confThreshold !in 0.05f..0.9f) problems += "confThreshold out of range"
        if (decode.iouThreshold !in 0.2f..0.9f) problems += "iouThreshold out of range"
        val unknownTargets = classMap.values.filter { CanonicalClass.fromName(it) == CanonicalClass.UNKNOWN }
        if (unknownTargets.isNotEmpty()) problems += "classMap targets not canonical: $unknownTargets"
        return problems
    }

    companion object {
        const val FAMILY_YOLO_V8 = "yolo_v8"
        const val FAMILY_SSD = "ssd"

        val json = Json {
            ignoreUnknownKeys = true // forward-compatible with future sidecar fields
            isLenient = false
        }

        fun parse(text: String): ModelConfig = json.decodeFromString(serializer(), text)
    }
}
