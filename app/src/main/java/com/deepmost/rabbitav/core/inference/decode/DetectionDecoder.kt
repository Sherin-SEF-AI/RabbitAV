package com.deepmost.rabbitav.core.inference.decode

import com.deepmost.rabbitav.core.inference.DetectionBuffer
import com.deepmost.rabbitav.core.inference.LetterboxMeta
import com.deepmost.rabbitav.core.inference.ModelConfig
import java.nio.ByteBuffer
import org.tensorflow.lite.DataType

/** Snapshot of one output tensor after a run. Buffers are engine-owned. */
class OutputTensorInfo(
    val index: Int,
    var shape: IntArray,
    val dtype: DataType,
    val scale: Float,
    val zeroPoint: Int,
    var buffer: ByteBuffer,
)

/** Decodes raw output tensors into canonical detections. Implementations own
 *  all scratch state; decode() must not allocate. */
interface DetectionDecoder {
    fun decode(
        outputs: List<OutputTensorInfo>,
        meta: LetterboxMeta,
        config: ModelConfig,
        out: DetectionBuffer,
    )
}
