package uz.namdpi.childguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import uz.namdpi.childguard.databinding.ActivityMainBinding

/**
 * Boshqaruv paneli: ruxsatlar, yuzlarni ro'yxatga olish, vaqt chegarasi,
 * PIN va himoyani yoqish/o'chirish.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPerms.setOnClickListener { requestPerms() }
        binding.btnOverlay.setOnClickListener { requestOverlay() }

        binding.btnEnrollParent.setOnClickListener { enroll(EmbeddingStore.ROLE_PARENT) }
        binding.btnEnrollChild.setOnClickListener { enroll(EmbeddingStore.ROLE_CHILD) }

        binding.btnSaveLimit.setOnClickListener {
            val m = binding.limitInput.text?.toString()?.toIntOrNull()
            if (m == null || m <= 0) {
                toast(getString(R.string.enter_valid_minutes))
            } else {
                Prefs.setLimitMinutes(this, m)
                toast(getString(R.string.saved))
            }
        }

        binding.btnSavePin.setOnClickListener {
            val pin = binding.pinInput.text?.toString().orEmpty()
            if (pin.length < 4) {
                toast(getString(R.string.pin_min_len))
            } else {
                Prefs.setParentPin(this, pin)
                binding.pinInput.text?.clear()
                toast(getString(R.string.saved))
            }
        }

        binding.guardSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!canStartGuard()) {
                    binding.guardSwitch.isChecked = false
                    toast(getString(R.string.finish_setup_first))
                    return@setOnCheckedChangeListener
                }
                Prefs.setGuardEnabled(this, true)
                Prefs.setLocked(this, false)
                MonitorService.start(this)
            } else {
                Prefs.setGuardEnabled(this, false)
                MonitorService.stop(this)
            }
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun enroll(role: String) {
        if (!hasCamera()) { requestPerms(); return }
        startActivity(Intent(this, EnrollActivity::class.java)
            .putExtra(EnrollActivity.EXTRA_ROLE, role))
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private fun requestOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun canStartGuard(): Boolean =
        hasCamera() && EmbeddingStore.isEnrolled(this)

    private fun refresh() {
        binding.limitInput.setText(Prefs.getLimitMinutes(this).toString())

        val camOk = hasCamera()
        val overlayOk = Settings.canDrawOverlays(this)
        val pCount = EmbeddingStore.count(this, EmbeddingStore.ROLE_PARENT)
        val cCount = EmbeddingStore.count(this, EmbeddingStore.ROLE_CHILD)

        binding.statusText.text = buildString {
            append(mark(camOk)); append(" "); append(getString(R.string.perm_camera)); append('\n')
            append(mark(overlayOk)); append(" "); append(getString(R.string.perm_overlay)); append('\n')
            append(mark(pCount > 0)); append(" ")
            append(getString(R.string.parent_faces, pCount)); append('\n')
            append(mark(cCount > 0)); append(" ")
            append(getString(R.string.child_faces, cCount))
        }

        binding.guardSwitch.isChecked = Prefs.isGuardEnabled(this)
        binding.btnEnrollParent.isEnabled = camOk
        binding.btnEnrollChild.isEnabled = camOk
    }

    private fun mark(ok: Boolean) = if (ok) "✅" else "⬜"

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
