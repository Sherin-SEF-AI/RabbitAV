package com.deepmost.rabbitav.core.inference

import android.os.Build
import com.deepmost.rabbitav.core.inference.decode.DetectionDecoder
import com.deepmost.rabbitav.core.inference.decode.OutputTensorInfo
import com.deepmost.rabbitav.core.inference.decode.SsdDecoder
import com.deepmost.rabbitav.core.inference.decode.YoloV8Decoder
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import timber.log.Timber

/** Thrown when a requested delegate cannot be constructed on this device. */
class DelegateUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Owns one Interpreter + delegate + all pre-allocated IO buffers. Confined to
 * the inference thread after [prepare]; zero allocations in [run].
 */
class InferenceEngine(
    private val modelFile: File,
    val config: ModelConfig,
    val delegateKind: DelegateKind,
    private val numThreads: Int = 4,
) : AutoCloseable {

    private var interpreter: Interpreter? = null
    private var delegate: Delegate? = null

    var inputWidth: Int = config.input.width; private set
    var inputHeight: Int = config.input.height; private set
    lateinit var inputBuffer: ByteBuffer; private set
    var inputDataType: DataType = DataType.UINT8; private set

    /** 256-entry quantization lookup: sRGB byte -> model input representation. */
    private lateinit var lutQuant: ByteArray
    private lateinit var lutFloat: FloatArray
    private val padValue = 114 // YOLO letterbox gray convention

    private val outputs = mutableListOf<OutputTensorInfo>()
    private lateinit var decoder: DetectionDecoder
    private val frame = DetectionFrame()

    fun prepare() {
        val mapped = mapModel(modelFile)
        val options = Interpreter.Options()
        when (delegateKind) {
            DelegateKind.XNNPACK -> {
                options.setNumThreads(numThreads)
                options.setUseXNNPACK(true)
            }
            DelegateKind.GPU -> {
                val compat = CompatibilityList()
                if (!compat.isDelegateSupportedOnThisDevice) {
                    throw DelegateUnavailableException("GPU delegate not supported on this device")
                }
                delegate = try {
                    GpuDelegate(compat.bestOptionsForThisDevice)
                } catch (t: Throwable) {
                    throw DelegateUnavailableException("GPU delegate init failed", t)
                }
                options.addDelegate(delegate)
            }
            DelegateKind.NNAPI -> {
                // NNAPI exists from API 27 and is deprecated (and often stubbed
                // to a slow reference impl) from Android 15; skip it there.
                if (Build.VERSION.SDK_INT < 27 || Build.VERSION.SDK_INT >= 35) {
                    throw DelegateUnavailableException("NNAPI skipped on API ${Build.VERSION.SDK_INT}")
                }
                delegate = try {
                    NnApiDelegate()
                } catch (t: Throwable) {
                    throw DelegateUnavailableException("NNAPI delegate init failed", t)
                }
                options.addDelegate(delegate)
            }
        }

        val interp = try {
            Interpreter(mapped, options)
        } catch (t: Throwable) {
            closeDelegate()
            throw DelegateUnavailableException("interpreter init failed on $delegateKind", t)
        }
        interpreter = interp

        val inTensor = interp.getInputTensor(0)
        val shape = inTensor.shape() // [1, h, w, 3]
        require(shape.size == 4 && shape[3] == 3) { "unexpected input shape ${shape.contentToString()}" }
        inputHeight = shape[1]
        inputWidth = shape[2]
        inputDataType = inTensor.dataType()
        buildLut(inTensor)
        allocateInput()
        refreshOutputs()

        decoder = when (config.decode.family) {
            ModelConfig.FAMILY_SSD -> SsdDecoder()
            else -> YoloV8Decoder(config.classes.size, maxCandidates())
        }
        Timber.tag(TAG).i(
            "engine ready: %s %dx%d in=%s delegate=%s outputs=%s",
            config.name, inputWidth, inputHeight, inputDataType, delegateKind,
            outputs.joinToString { it.shape.contentToString() }
        )
    }

    private fun maxCandidates(): Int {
        val s = outputs.firstOrNull()?.shape ?: return 1
        return s.maxOrNull() ?: 1
    }

    private fun mapModel(file: File): MappedByteBuffer =
        FileInputStream(file).use { fis ->
            fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }

    private fun buildLut(tensor: org.tensorflow.lite.Tensor) {
        when (inputDataType) {
            DataType.FLOAT32 -> {
                lutFloat = FloatArray(256) { it / 255f }
                lutQuant = ByteArray(0)
            }
            DataType.UINT8, DataType.INT8 -> {
                val q = tensor.quantizationParams()
                val scale = if (q.scale > 0f) q.scale else 1f / 255f
                val zp = q.zeroPoint
                val lo = if (inputDataType == DataType.INT8) -128 else 0
                val hi = if (inputDataType == DataType.INT8) 127 else 255
                lutQuant = ByteArray(256) { p ->
                    (((p / 255f) / scale) + zp).roundToInt().coerceIn(lo, hi).toByte()
                }
                lutFloat = FloatArray(0)
            }
            else -> throw IllegalStateException("unsupported input dtype $inputDataType")
        }
    }

    private fun allocateInput() {
        val bpp = if (inputDataType == DataType.FLOAT32) 4 else 1
        inputBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * bpp)
            .order(ByteOrder.nativeOrder())
        prefillPadding()
    }

    /** Fills the whole input with letterbox gray once; the content region is
     *  overwritten every frame, padding stays valid forever. */
    fun prefillPadding() {
        inputBuffer.clear()
        if (inputDataType == DataType.FLOAT32) {
            val v = padValue / 255f
            val f = inputBuffer.asFloatBuffer()
            for (i in 0 until f.capacity()) f.put(i, v)
        } else {
            val v = lutQuant[padValue]
            for (i in 0 until inputBuffer.capacity()) inputBuffer.put(i, v)
        }
    }

    /** Writes one RGB pixel (bytes 0..255) at content position; used by the
     *  preprocessor's tight loop through [lutQuant]/[lutFloat] accessors. */
    val quantLut: ByteArray get() = lutQuant
    val floatLut: FloatArray get() = lutFloat
    val isFloatInput: Boolean get() = inputDataType == DataType.FLOAT32

    private fun refreshOutputs() {
        val interp = interpreter ?: return
        outputs.clear()
        // Old map entries hold the PREVIOUS buffers (position at end-of-write);
        // without this clear, run() sees size==size, keeps them, and the next
        // tensor copy throws BufferOverflowException (caught on-device at L2).
        outputMap.clear()
        for (i in 0 until interp.outputTensorCount) {
            val t = interp.getOutputTensor(i)
            val q = t.quantizationParams()
            outputs += OutputTensorInfo(
                index = i,
                shape = t.shape().copyOf(),
                dtype = t.dataType(),
                scale = if (q.scale > 0f) q.scale else 1f,
                zeroPoint = q.zeroPoint,
                buffer = ByteBuffer.allocateDirect(t.numBytes()).order(ByteOrder.nativeOrder()),
            )
        }
    }

    /**
     * Shrinks the input resolution (governor L2). Only valid for resizable
     * (fully convolutional) models; decoder capacity was sized at the initial,
     * larger resolution so shrinking is always safe.
     */
    fun resizeInput(w: Int, h: Int): Boolean {
        val interp = interpreter ?: return false
        if (!config.input.resizable) return false
        if (w > config.input.width || h > config.input.height) return false
        if (w == inputWidth && h == inputHeight) return true
        return try {
            interp.resizeInput(0, intArrayOf(1, h, w, 3))
            interp.allocateTensors()
            inputWidth = w
            inputHeight = h
            allocateInput()
            refreshOutputs()
            Timber.tag(TAG).i("input resized to %dx%d", w, h)
            true
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "resizeInput(%d,%d) failed; keeping %dx%d", w, h, inputWidth, inputHeight)
            false
        }
    }

    private val inputArray = arrayOfNulls<Any>(1)
    private val outputMap = HashMap<Int, Any>()

    /** Runs one inference on whatever is in [inputBuffer]. */
    fun run(timestampNs: Long, meta: LetterboxMeta): DetectionFrame {
        val interp = interpreter ?: throw IllegalStateException("engine not prepared")
        inputBuffer.rewind()
        if (outputMap.size != outputs.size) {
            outputMap.clear()
            for (o in outputs) outputMap[o.index] = o.buffer
        }
        for (o in outputs) o.buffer.rewind()
        inputArray[0] = inputBuffer

        val t0 = System.nanoTime()
        interp.runForMultipleInputsOutputs(inputArray, outputMap)
        val latencyMs = (System.nanoTime() - t0) / 1e6f

        // Shapes can change after resizeInput; keep decoder's view current.
        for (o in outputs) o.shape = interp.getOutputTensor(o.index).shape()

        decoder.decode(outputs, meta, config, frame.detections)
        frame.timestampNs = timestampNs
        frame.latencyMs = latencyMs
        frame.delegate = delegateKind
        return frame
    }

    private fun closeDelegate() {
        when (val d = delegate) {
            is GpuDelegate -> d.close()
            is NnApiDelegate -> d.close()
            else -> Unit
        }
        delegate = null
    }

    override fun close() {
        try {
            interpreter?.close()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "interpreter close failed")
        }
        interpreter = null
        closeDelegate()
    }

    companion object {
        private const val TAG = "RAV-Infer"
    }
}
