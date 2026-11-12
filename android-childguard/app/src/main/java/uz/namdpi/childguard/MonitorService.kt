package uz.namdpi.childguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.Executors

/**
 * Fon xizmat: old kamera orqali vaqti-vaqti bilan (TICK_MS) yuzni tekshiradi.
 * Agar BOLA yuzi aniqlansa — uning ekran vaqti to'planadi.
 * To'plangan vaqt chegaradan oshsa — telefon bloklanadi (LockActivity ochiladi).
 */
class MonitorService : LifecycleService() {

    private lateinit var recognizer: FaceRecognizer
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile private var lastTick = 0L

    override fun onCreate() {
        super.onCreate()
        recognizer = FaceRecognizer(this)
        startForeground(NOTIF_ID, buildNotification())
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Xizmat o'ldirilsa, qayta tiklanadi
        return START_STICKY
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        processFrame(proxy)
                    } catch (e: Exception) {
                        Log.e(TAG, "analyze error", e)
                    } finally {
                        proxy.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "camera start failed", e)
            }
        }, uiExecutor())
    }

    private fun processFrame(proxy: androidx.camera.core.ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastTick < TICK_MS) return   // throttling: TICK_MS da bir marta
        val prevTick = lastTick
        lastTick = now

        if (!Prefs.isGuardEnabled(this)) return
        if (Prefs.isLocked(this)) return        // allaqachon bloklangan

        val bitmap = proxy.toUprightBitmap()
        val result = recognizer.recognize(bitmap)

        // Faqat aniq bir BOLA tanilsa — o'sha bolaning vaqtiga qo'shamiz.
        if (result.kind == FaceRecognizer.Kind.CHILD && result.childId != null) {
            val delta = if (prevTick == 0L) TICK_MS else (now - prevTick).coerceAtMost(TICK_MS * 2)
            Prefs.addChildUsedMillis(this, result.childId, delta)

            val limitMs = Prefs.getLimitMinutes(this) * 60_000L
            if (Prefs.getChildUsedMillis(this, result.childId) >= limitMs) {
                lockNow(result.childId, result.childName ?: "")
            }
        }
        // PARENT / UNKNOWN / NO_FACE bo'lsa hech kimning vaqti qo'shilmaydi.
    }

    private fun lockNow(childId: String, childName: String) {
        Prefs.setLocked(this, true)
        Prefs.setLockedChild(this, childId, childName)
        val i = Intent(this, LockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "ChildGuard himoya",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.guard_running))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        recognizer.close()
        super.onDestroy()
    }

    private fun uiExecutor() = androidx.core.content.ContextCompat.getMainExecutor(this)

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "childguard_monitor"
        private const val NOTIF_ID = 1001
        private const val TICK_MS = 5_000L   // har 5 soniyada bir marta tekshirish

        fun start(ctx: Context) {
            val i = Intent(ctx, MonitorService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }
}
