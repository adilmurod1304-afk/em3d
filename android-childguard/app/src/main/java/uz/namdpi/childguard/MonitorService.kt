package uz.namdpi.childguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.Executors

/**
 * Fon xizmat: old kamera orqali kim foydalanayotganини aniqlaydi.
 *  - Ota-ona yoki vaqti HALI BOR bola → ruxsat (blok yopiladi).
 *  - Vaqti TUGAGAN bola → o'sha paytда overlay blok chiqadi (YouTube ustида ham).
 * Har bir bolaning vaqti alohida; blok butun qurilma uchun emas, HOZIR kim
 * foydalanayotganига qarab ko'rsatiladi/yopiladi.
 */
class MonitorService : LifecycleService() {

    private lateinit var recognizer: FaceRecognizer
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    private var overlayView: View? = null
    private var overlayPreview: PreviewView? = null

    @Volatile private var blocked = false
    private var permitStreak = 0
    @Volatile private var lastTick = 0L

    override fun onCreate() {
        super.onCreate()
        recognizer = FaceRecognizer(this)
        startForeground(NOTIF_ID, buildNotification())
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (cameraProvider != null) startCamera()   // kamerani qaytarib ulash
        return START_STICKY
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                if (analysis == null) {
                    analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build().also { a ->
                            a.setAnalyzer(analysisExecutor) { proxy ->
                                try {
                                    processFrame(proxy)
                                } catch (e: Exception) {
                                    Log.e(TAG, "analyze error", e)
                                } finally {
                                    proxy.close()
                                }
                            }
                        }
                }
                bindCamera(if (overlayView != null) overlayPreview else null)
            } catch (e: Exception) {
                Log.e(TAG, "camera start failed", e)
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    /** Kamerani ulaydi: tahlil + (kerak bo'lsa) blok ekrani uchun jonli ko'rinish. */
    private fun bindCamera(previewView: PreviewView?) {
        val provider = cameraProvider ?: return
        val a = analysis ?: return
        try {
            provider.unbindAll()
            if (previewView != null) {
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, a, preview)
            } else {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, a)
            }
        } catch (e: Exception) {
            Log.e(TAG, "bindCamera failed", e)
        }
    }

    private fun processFrame(proxy: androidx.camera.core.ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastTick < TICK_MS) return
        val prevTick = lastTick
        lastTick = now

        if (!Prefs.isGuardEnabled(this)) { main.post { hideOverlay() }; return }
        if (now < Prefs.getGraceUntil(this)) { main.post { hideOverlay() }; return }

        val bitmap = proxy.toUprightBitmap()
        val result = recognizer.monitorClassify(bitmap)   // eng yaqin g'alaba qiladi

        when (result.kind) {
            FaceRecognizer.Kind.CHILD -> {
                val id = result.childId ?: return
                val delta = if (prevTick == 0L) TICK_MS else (now - prevTick).coerceAtMost(TICK_MS * 2)
                Prefs.addChildUsedMillis(this, id, delta)

                val usedMs = Prefs.getChildUsedMillis(this, id)
                val limit = Prefs.getLimitMinutes(this)
                updateNotification(
                    getString(R.string.guard_status, result.childName ?: "?", (usedMs / 60_000L).toInt(), limit)
                )
                if (usedMs >= limit * 60_000L) {
                    // Shu bola vaqtini tugatgan → blok
                    permitStreak = 0
                    Prefs.setLockedChild(this, id, result.childName ?: "")
                    if (!blocked) main.post { showOverlay() }
                } else {
                    permit()   // vaqti bor bola → ruxsat
                }
            }
            FaceRecognizer.Kind.PARENT -> permit()   // ota-ona → ruxsat
            else -> { /* NO_FACE / UNKNOWN: holatni o'zgartirmaymiz */ }
        }
    }

    /** Ruxsat berilgan foydalanuvchi (ota-ona yoki vaqti bor bola) — blokni yopadi. */
    private fun permit() {
        if (!blocked) return
        permitStreak++
        if (permitStreak >= 2) {   // xatodan saqlanish uchun 2 ta kadr
            permitStreak = 0
            main.post { hideOverlay() }
        }
    }

    // ---------- Overlay blok oynasi ----------

    private fun showOverlay() {
        if (overlayView != null) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val themed = ContextThemeWrapper(this, R.style.Theme_ChildGuard)
            val v = LayoutInflater.from(themed).inflate(R.layout.overlay_lock, null)

            val name = Prefs.getLockedChildName(this)
            v.findViewById<TextView>(R.id.overlayStatus).text =
                if (name.isNotBlank()) getString(R.string.locked_sub_named, name)
                else getString(R.string.locked_sub)

            val pinField = v.findViewById<EditText>(R.id.overlayPin)
            val msg = v.findViewById<TextView>(R.id.overlayMsg)
            v.findViewById<Button>(R.id.overlayUnlock).setOnClickListener {
                if (pinField.text?.toString().orEmpty() == Prefs.getParentPin(this)) {
                    pinUnlock()
                } else {
                    msg.text = getString(R.string.wrong_pin)
                    pinField.text?.clear()
                }
            }

            v.isFocusableInTouchMode = true
            v.setOnKeyListener { _, code, _ -> code == KeyEvent.KEYCODE_BACK }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            wm.addView(v, lp)

            overlayView = v
            overlayPreview = v.findViewById<PreviewView>(R.id.overlayPreview)
            blocked = true
            Prefs.setLocked(this, true)
            bindCamera(overlayPreview)   // blok ekranida jonli kamera
        } catch (e: Exception) {
            Log.e(TAG, "overlay failed — fallback activity", e)
            try {
                startActivity(
                    Intent(this, LockActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                )
            } catch (_: Exception) { }
        }
    }

    private fun hideOverlay() {
        blocked = false
        Prefs.setLocked(this, false)
        val v = overlayView ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) { }
        overlayView = null
        overlayPreview = null
        bindCamera(null)
    }

    private fun pinUnlock() {
        Prefs.setGraceUntil(this, System.currentTimeMillis() + GRACE_MS)
        permitStreak = 0
        hideOverlay()
    }

    // ---------- Bildirishnoma ----------

    private fun updateNotification(text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ChildGuard himoya", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.guard_running))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        main.post { hideOverlay() }
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        recognizer.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "childguard_monitor"
        private const val NOTIF_ID = 1001
        private const val TICK_MS = 3_000L
        private const val GRACE_MS = 30_000L

        fun start(ctx: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                ctx, Intent(ctx, MonitorService::class.java)
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }
}
