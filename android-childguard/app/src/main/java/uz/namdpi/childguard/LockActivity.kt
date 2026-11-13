package uz.namdpi.childguard

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import uz.namdpi.childguard.databinding.ActivityLockBinding
import java.util.concurrent.Executors

/**
 * Blok ekrani. Hamma narsa ustida ochiladi.
 * Faqat OTA-ONA yuzi aniqlansa yoki to'g'ri PIN kiritilsa yopiladi.
 */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private lateinit var recognizer: FaceRecognizer
    private val exec = Executors.newSingleThreadExecutor()
    private var parentStreak = 0
    @Volatile private var unlocking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverEverything()
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recognizer = FaceRecognizer(this)

        // Orqaga tugmasi ishlamasin
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* bloklangan */ }
        })

        val childName = Prefs.getLockedChildName(this)
        binding.status.text = when {
            !recognizer.isModelLoaded -> getString(R.string.model_missing_pin_only)
            childName.isNotBlank() -> getString(R.string.locked_sub_named, childName)
            else -> getString(R.string.locked_sub)
        }

        binding.pinButton.setOnClickListener {
            val entered = binding.pinInput.text?.toString().orEmpty()
            if (entered == Prefs.getParentPin(this)) {
                unlock()
            } else {
                binding.status.text = getString(R.string.wrong_pin)
                binding.pinInput.text?.clear()
            }
        }

        startCamera()
    }

    private fun showOverEverything() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
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
                        if (!unlocking) checkParent(proxy)
                    } catch (e: Exception) {
                        Log.e(TAG, "analyze error", e)
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

    private fun checkParent(proxy: androidx.camera.core.ImageProxy) {
        if (!recognizer.isModelLoaded) return
        // QAT'IY: faqat ota-ona aniq tanilsa (bola yuzi ochmaydi)
        if (recognizer.isConfidentParent(proxy.toUprightBitmap())) {
            parentStreak++
            // Xatodan saqlanish uchun ketma-ket 3 marta tasdiqlansin
            if (parentStreak >= 3) {
                runOnUiThread { unlock() }
            }
        } else {
            parentStreak = 0
        }
    }

    private fun unlock() {
        if (unlocking) return
        unlocking = true
        Prefs.setLocked(this, false)
        // Kunlik cheklov (B): hisob NOLGA tushmaydi. Faqat qisqa muhlat beramiz,
        // shunda darhol qayta bloklanib ketmaydi (telefonni qaytarish uchun).
        Prefs.setGraceUntil(this, System.currentTimeMillis() + GRACE_MS)
        // Kamerani kuzatuv xizmatiga qaytaramiz (blok ekrani uni olib qo'ygan edi)
        if (Prefs.isGuardEnabled(this)) MonitorService.start(this)
        finish()
    }

    override fun onDestroy() {
        exec.shutdown()
        recognizer.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LockActivity"
        private const val GRACE_MS = 30_000L   // ochilgandan keyingi muhlat
    }
}
