package uz.namdpi.childguard

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import uz.namdpi.childguard.databinding.ActivityEnrollBinding
import java.util.concurrent.Executors

/**
 * Yuzni ro'yxatdan o'tkazish.
 * Intent orqali EXTRA_ROLE ("parent" yoki "child") keladi; "child" bo'lsa
 * EXTRA_CHILD_ID ham keladi (qaysi bola profili ekani).
 */
class EnrollActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEnrollBinding
    private lateinit var recognizer: FaceRecognizer
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var lastBitmap: Bitmap? = null

    private var isParent = true
    private var childId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnrollBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isParent = intent.getStringExtra(EXTRA_ROLE) != ROLE_CHILD
        childId = intent.getStringExtra(EXTRA_CHILD_ID) ?: ""
        recognizer = FaceRecognizer(this)

        binding.title.text = if (isParent) {
            getString(R.string.enroll_parent)
        } else {
            val name = Profiles.getChild(this, childId)?.name ?: ""
            getString(R.string.enroll_child_named, name)
        }
        updateCount()

        if (!recognizer.isModelLoaded) {
            Toast.makeText(this, R.string.model_missing, Toast.LENGTH_LONG).show()
        }

        binding.captureButton.setOnClickListener { captureSample() }
        binding.clearButton.setOnClickListener {
            if (isParent) Profiles.clearParent(this)
            else Profiles.clearChildSamples(this, childId)
            updateCount()
        }
        binding.doneButton.setOnClickListener { finish() }

        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(binding.preview.surfaceProvider)

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(exec) { proxy ->
                    try {
                        lastBitmap = proxy.toBitmap()
                    } finally {
                        proxy.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "camera failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureSample() {
        val bmp = lastBitmap
        if (bmp == null) {
            Toast.makeText(this, R.string.no_frame, Toast.LENGTH_SHORT).show()
            return
        }
        exec.execute {
            val emb = recognizer.embedLargestFace(bmp)
            runOnUiThread {
                if (emb == null) {
                    Toast.makeText(this, R.string.no_face_try_again, Toast.LENGTH_SHORT).show()
                } else {
                    if (isParent) Profiles.addParentSample(this, emb)
                    else Profiles.addChildSample(this, childId, emb)
                    updateCount()
                }
            }
        }
    }

    private fun updateCount() {
        val n = if (isParent) Profiles.parentCount(this)
        else Profiles.childSampleCount(this, childId)
        binding.count.text = getString(R.string.samples_count, n)
        binding.doneButton.isEnabled = n > 0
    }

    override fun onDestroy() {
        exec.shutdown()
        recognizer.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EnrollActivity"
        const val EXTRA_ROLE = "role"
        const val EXTRA_CHILD_ID = "child_id"
        const val ROLE_PARENT = "parent"
        const val ROLE_CHILD = "child"
    }
}
