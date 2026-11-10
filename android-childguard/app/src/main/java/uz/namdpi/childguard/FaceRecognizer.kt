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
 * 2) TFLite model (masalan MobileFaceNet) yuzni son vektoriga aylantiradi.
 * 3) Saqlangan ota-ona va har bir BOLA namunalari bilan solishtiradi —
 *    kim ekanini (ota-ona yoki aniq bola) aniqlaydi.
 *
 * MUHIM: assets/ ichida "mobile_face_net.tflite" bo'lishi kerak (README'ga qarang).
 */
class FaceRecognizer(context: Context) {

    enum class Kind { PARENT, CHILD, UNKNOWN, NO_FACE }

    /** Tanish natijasi. CHILD bo'lsa childId/childName to'ldiriladi. */
    data class RecResult(
        val kind: Kind,
        val childId: String? = null,
        val childName: String? = null
    )

    private val appCtx = context.applicationContext
    private var interpreter: Interpreter? = null
    private var inputSize = 112
    private var embeddingSize = 192

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
            val outShape = itp.getOutputTensor(0).shape()
            embeddingSize = outShape[outShape.size - 1]
            val inShape = itp.getInputTensor(0).shape()
            if (inShape.size == 4) inputSize = inShape[1]
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
        val output = Array(1) { FloatArray(embeddingSize) }
        itp.run(bitmapToBuffer(scaled), output)
        return l2normalize(output[0])
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
            if (d < bestChildDist) {
                bestChildDist = d
                bestChild = c
            }
        }

        if (pDist > threshold && bestChildDist > threshold) return RecResult(Kind.UNKNOWN)

        return if (pDist <= bestChildDist) {
            RecResult(Kind.PARENT)
        } else {
            RecResult(Kind.CHILD, bestChild?.id, bestChild?.name)
        }
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

    private fun bitmapToBuffer(bmp: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bmp.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (p in pixels) {
            val r = (p shr 16 and 0xFF)
            val g = (p shr 8 and 0xFF)
            val b = (p and 0xFF)
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
