package uz.namdpi.childguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Yuzni tanib oluvchi.
 * 1) ML Kit yordamida rasmdan eng katta yuzni topadi va kesib oladi.
 * 2) TFLite model (MobileFaceNet) yuzni son vektoriga (embedding) aylantiradi.
 * 3) Saqlangan ota-ona va har bir BOLA namunalari bilan solishtiradi.
 *
 * Model float32 ham, kvantlangan (int8/uint8) ham bo'lishi mumkin — kirish/chiqish
 * turi modeldan o'qib olinadi va mos ravishda tayyorlanadi.
 *
 * assets/ ichida "mobile_face_net.tflite" bo'lishi kerak (README'ga qarang).
 */
class FaceRecognizer(context: Context) {

    enum class Kind { PARENT, CHILD, UNKNOWN, NO_FACE }

    data class RecResult(
        val kind: Kind,
        val childId: String? = null,
        val childName: String? = null
    )

    private val appCtx = context.applicationContext
    private var interpreter: Interpreter? = null

    private var inputSize = 112
    private var embeddingSize = 192
    private var inputType = DataType.FLOAT32
    private var outputType = DataType.FLOAT32
    private var inScale = 1f
    private var inZero = 0
    private var outScale = 1f
    private var outZero = 0

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.15f)
            .build()
    )

    val isModelLoaded: Boolean get() = interpreter != null

    init {
        try {
            val model = loadModelFile(MODEL_NAME)
            val itp = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })

            val inT = itp.getInputTensor(0)
            val inShape = inT.shape()                 // [1, H, W, 3]
            if (inShape.size == 4) inputSize = inShape[1]
            inputType = inT.dataType()
            inT.quantizationParams()?.let { inScale = it.scale; inZero = it.zeroPoint }
            if (inScale == 0f) inScale = 1f

            val outT = itp.getOutputTensor(0)
            val outShape = outT.shape()               // [1, N]
            embeddingSize = outShape[outShape.size - 1]
            outputType = outT.dataType()
            outT.quantizationParams()?.let { outScale = it.scale; outZero = it.zeroPoint }
            if (outScale == 0f) outScale = 1f

            interpreter = itp
        } catch (e: Exception) {
            interpreter = null
        }
    }

    private fun loadModelFile(name: String): MappedByteBuffer {
        val afd = appCtx.assets.openFd(name)
        val channel = afd.createInputStream().channel
        return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    /** Rasmdan eng katta yuzni topib, uning embedding'ini qaytaradi (yuz yo'q bo'lsa null). */
    fun embedLargestFace(bitmap: Bitmap): FloatArray? {
        val itp = interpreter ?: return null
        val rect = detectLargestFaceRect(bitmap) ?: return null
        val face = cropSafely(bitmap, rect)
        val scaled = Bitmap.createScaledBitmap(face, inputSize, inputSize, true)

        val input = buildInputBuffer(scaled)
        val outBytes = embeddingSize * if (outputType == DataType.FLOAT32) 4 else 1
        val output = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())

        itp.run(input, output)
        output.rewind()

        val emb = FloatArray(embeddingSize)
        if (outputType == DataType.FLOAT32) {
            for (i in 0 until embeddingSize) emb[i] = output.float
        } else {
            val signed = outputType == DataType.INT8
            for (i in 0 until embeddingSize) {
                val raw = output.get().toInt()
                val q = if (signed) raw else (raw and 0xFF)
                emb[i] = (q - outZero) * outScale
            }
        }
        return l2normalize(emb)
    }

    /** Rasmni ota-ona yoki aniq bir bola sifatida tasniflaydi. */
    fun recognize(bitmap: Bitmap): RecResult {
        if (interpreter == null) return RecResult(Kind.UNKNOWN)
        val emb = embedLargestFace(bitmap) ?: return RecResult(Kind.NO_FACE)

        val parents = Profiles.loadParent(appCtx)
        val children = Profiles.listChildren(appCtx)
        if (parents.isEmpty() && children.all { it.embeddings.isEmpty() }) {
            return RecResult(Kind.UNKNOWN)
        }

        val threshold = Prefs.getMatchThreshold(appCtx)
        val pDist = minDistance(emb, parents)

        var bestChildDist = Float.MAX_VALUE
        var bestChild: ChildProfile? = null
        for (c in children) {
            val d = minDistance(emb, c.embeddings)
            if (d < bestChildDist) { bestChildDist = d; bestChild = c }
        }

        if (pDist > threshold && bestChildDist > threshold) return RecResult(Kind.UNKNOWN)
        return if (pDist <= bestChildDist) RecResult(Kind.PARENT)
        else RecResult(Kind.CHILD, bestChild?.id, bestChild?.name)
    }

    private fun detectLargestFaceRect(bitmap: Bitmap): Rect? {
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            com.google.android.gms.tasks.Tasks.await(detector.process(image))
                .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?.boundingBox
        } catch (e: Exception) {
            null
        }
    }

    private fun cropSafely(src: Bitmap, r: Rect): Bitmap {
        val left = r.left.coerceIn(0, src.width - 1)
        val top = r.top.coerceIn(0, src.height - 1)
        val right = r.right.coerceIn(left + 1, src.width)
        val bottom = r.bottom.coerceIn(top + 1, src.height)
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }

    /** Pikselarni [-1,1] domeniga keltiradi; kerak bo'lsa model kvantiga moslaydi. */
    private fun buildInputBuffer(bmp: Bitmap): ByteBuffer {
        val count = inputSize * inputSize
        val pixels = IntArray(count)
        bmp.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val isFloat = inputType == DataType.FLOAT32
        val bytesPerCh = if (isFloat) 4 else 1
        val buf = ByteBuffer.allocateDirect(count * 3 * bytesPerCh).order(ByteOrder.nativeOrder())
        val signed = inputType == DataType.INT8

        for (p in pixels) {
            val r = (p shr 16 and 0xFF)
            val g = (p shr 8 and 0xFF)
            val b = (p and 0xFF)
            putChannel(buf, r, isFloat, signed)
            putChannel(buf, g, isFloat, signed)
            putChannel(buf, b, isFloat, signed)
        }
        buf.rewind()
        return buf
    }

    private fun putChannel(buf: ByteBuffer, c: Int, isFloat: Boolean, signed: Boolean) {
        val f = (c - 127.5f) / 128f   // [-1, 1]
        if (isFloat) {
            buf.putFloat(f)
        } else {
            val q = (f / inScale + inZero).roundToInt()
            val clamped = if (signed) q.coerceIn(-128, 127) else q.coerceIn(0, 255)
            buf.put(clamped.toByte())
        }
    }

    private fun l2normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum).coerceAtLeast(1e-10f)
        return FloatArray(v.size) { v[it] / norm }
    }

    /** Eng yaqin namunagacha bo'lgan Evklid masofasi. Namuna yo'q bo'lsa MAX. */
    private fun minDistance(emb: FloatArray, samples: List<FloatArray>): Float {
        var best = Float.MAX_VALUE
        for (s in samples) {
            if (s.size != emb.size) continue
            var sum = 0f
            for (i in emb.indices) {
                val d = emb[i] - s[i]
                sum += d * d
            }
            val dist = sqrt(sum)
            if (dist < best) best = dist
        }
        return best
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        detector.close()
    }

    companion object {
        const val MODEL_NAME = "mobile_face_net.tflite"
    }
}
