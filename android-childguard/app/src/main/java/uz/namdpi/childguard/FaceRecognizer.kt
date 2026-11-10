package uz.namdpi.childguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Yuzni tanib oluvchi.
 * 1) ML Kit yordamida rasmdan eng katta yuzni topadi va kesib oladi.
 * 2) TFLite model (masalan MobileFaceNet) yordamida yuzni son vektoriga (embedding) aylantiradi.
 * 3) Saqlangan ota-ona / bola namunalari bilan solishtiradi.
 *
 * MUHIM: assets/ ichida "mobile_face_net.tflite" fayli bo'lishi kerak (README'ga qarang).
 * Model bo'lmasa, recognize() hamma narsani UNKNOWN qaytaradi.
 */
class FaceRecognizer(context: Context) {

    enum class Label { PARENT, CHILD, UNKNOWN, NO_FACE }

    private val appCtx = context.applicationContext
    private var interpreter: Interpreter? = null
    private var inputSize = 112          // MobileFaceNet uchun odatda 112x112
    private var embeddingSize = 192      // modelga qarab o'zgaradi (aniqlanadi)

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
            val opts = Interpreter.Options().apply { setNumThreads(2) }
            val itp = Interpreter(model, opts)
            // Chiqish o'lchamini modeldan o'qiymiz
            val outShape = itp.getOutputTensor(0).shape() // [1, N]
            embeddingSize = outShape[outShape.size - 1]
            val inShape = itp.getInputTensor(0).shape()    // [1, H, W, 3]
            if (inShape.size == 4) inputSize = inShape[1]
            interpreter = itp
        } catch (e: Exception) {
            interpreter = null
        }
    }

    private fun loadModelFile(name: String): MappedByteBuffer {
        val afd = appCtx.assets.openFd(name)
        val input = afd.createInputStream()
        val channel = input.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    /** Rasmdan eng katta yuzni topib, uning embedding'ini qaytaradi (yuz yo'q bo'lsa null). */
    fun embedLargestFace(bitmap: Bitmap): FloatArray? {
        val itp = interpreter ?: return null
        val rect = detectLargestFaceRect(bitmap) ?: return null
        val face = cropSafely(bitmap, rect)
        val scaled = Bitmap.createScaledBitmap(face, inputSize, inputSize, true)
        val input = bitmapToBuffer(scaled)
        val output = Array(1) { FloatArray(embeddingSize) }
        itp.run(input, output)
        return l2normalize(output[0])
    }

    /** Rasmni ota-ona yoki bola sifatida tasniflaydi. */
    fun recognize(bitmap: Bitmap): Label {
        if (interpreter == null) return Label.UNKNOWN
        val emb = embedLargestFace(bitmap) ?: return Label.NO_FACE

        val parents = EmbeddingStore.load(appCtx, EmbeddingStore.ROLE_PARENT)
        val children = EmbeddingStore.load(appCtx, EmbeddingStore.ROLE_CHILD)
        if (parents.isEmpty() && children.isEmpty()) return Label.UNKNOWN

        val pDist = minDistance(emb, parents)
        val cDist = minDistance(emb, children)
        val threshold = Prefs.getMatchThreshold(appCtx)

        // Ikkisidan ham uzoq bo'lsa — noma'lum
        if (pDist > threshold && cDist > threshold) return Label.UNKNOWN
        return if (pDist <= cDist) Label.PARENT else Label.CHILD
    }

    private fun detectLargestFaceRect(bitmap: Bitmap): Rect? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val task = detector.process(image)
        return try {
            // Bu funksiyalar fon oqimda chaqiriladi (xizmat/ishchi ip)
            com.google.android.gms.tasks.Tasks.await(task)
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

    private fun bitmapToBuffer(bmp: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bmp.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (p in pixels) {
            val r = (p shr 16 and 0xFF)
            val g = (p shr 8 and 0xFF)
            val b = (p and 0xFF)
            // [-1, 1] oralig'iga normallashtirish (MobileFaceNet uslubi)
            buffer.putFloat((r - 127.5f) / 128f)
            buffer.putFloat((g - 127.5f) / 128f)
            buffer.putFloat((b - 127.5f) / 128f)
        }
        buffer.rewind()
        return buffer
    }

    private fun l2normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum).coerceAtLeast(1e-10f)
        return FloatArray(v.size) { v[it] / norm }
    }

    /** Eng yaqin namunagacha bo'lgan Evklid masofasi (normallashtirilgan vektorlar uchun). */
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
