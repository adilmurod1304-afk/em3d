package uz.namdpi.childguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import uz.namdpi.childguard.databinding.ActivityMainBinding

/**
 * Boshqaruv paneli: ruxsatlar, ota-ona va bir nechta bola yuzlarini
 * ro'yxatga olish, vaqt/PIN sozlash, himoyani yoqish/o'chirish.
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
        binding.btnEnrollParent.setOnClickListener { enrollParent() }
        binding.btnAddChild.setOnClickListener { addChildDialog() }

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

    // ---------- Ro'yxatga olish ----------

    private fun enrollParent() {
        if (!hasCamera()) { requestPerms(); return }
        startActivity(Intent(this, EnrollActivity::class.java)
            .putExtra(EnrollActivity.EXTRA_ROLE, EnrollActivity.ROLE_PARENT))
    }

    private fun enrollChild(childId: String) {
        if (!hasCamera()) { requestPerms(); return }
        startActivity(Intent(this, EnrollActivity::class.java)
            .putExtra(EnrollActivity.EXTRA_ROLE, EnrollActivity.ROLE_CHILD)
            .putExtra(EnrollActivity.EXTRA_CHILD_ID, childId))
    }

    private fun addChildDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = getString(R.string.add_child_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_child_title)
            .setView(input)
            .setPositiveButton(R.string.btn_add) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                val id = Profiles.addChild(this, name)
                refresh()
                enrollChild(id) // darhol yuz qo'shishga o'tamiz
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveChild(child: ChildProfile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_child_title)
            .setMessage(getString(R.string.remove_child_msg, child.name))
            .setPositiveButton(R.string.btn_remove) { _, _ ->
                Profiles.removeChild(this, child.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- Ruxsatlar ----------

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

    private fun canStartGuard(): Boolean = hasCamera() && Profiles.isReady(this)

    // ---------- Ko'rinishni yangilash ----------

    private fun refresh() {
        binding.limitInput.setText(Prefs.getLimitMinutes(this).toString())
        binding.guardSwitch.isChecked = Prefs.isGuardEnabled(this)

        val camOk = hasCamera()
        val overlayOk = Settings.canDrawOverlays(this)
        val pCount = Profiles.parentCount(this)
        val children = Profiles.listChildren(this)
        val ready = canStartGuard()

        binding.readyBanner.text =
            if (ready) getString(R.string.ready_ok) else getString(R.string.ready_setup)
        binding.readyBanner.setTextColor(
            ContextCompat.getColor(this, if (ready) R.color.cg_success else R.color.cg_primary)
        )

        binding.statusText.text = buildString {
            append(mark(camOk)); append("  "); append(getString(R.string.perm_camera)); append('\n')
            append(mark(overlayOk)); append("  "); append(getString(R.string.perm_overlay)); append('\n')
            append(mark(pCount > 0)); append("  ")
            append(getString(R.string.parent_faces, pCount)); append('\n')
            append(mark(children.any { it.embeddings.isNotEmpty() })); append("  ")
            append(getString(R.string.children_total, children.size))
        }

        binding.btnEnrollParent.isEnabled = camOk
        binding.btnAddChild.isEnabled = camOk

        buildChildrenList(children)
    }

    private fun buildChildrenList(children: List<ChildProfile>) {
        val container = binding.childrenContainer
        container.removeAllViews()
        val limit = Prefs.getLimitMinutes(this)
        val inflater = layoutInflater

        if (children.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.no_children_yet)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.cg_muted))
                textSize = 13f
                setPadding(0, dp(8), 0, dp(4))
            })
            return
        }

        for (child in children) {
            val row = inflater.inflate(R.layout.item_child, container, false)
            val usedMin = (Prefs.getChildUsedMillis(this, child.id) / 60000L).toInt()

            row.findViewById<TextView>(R.id.childName).text = child.name
            row.findViewById<TextView>(R.id.childInfo).text =
                getString(R.string.child_info, child.embeddings.size, usedMin, limit)
            row.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEnroll)
                .setOnClickListener { enrollChild(child.id) }
            row.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRemove)
                .setOnClickListener { confirmRemoveChild(child) }

            container.addView(row)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun mark(ok: Boolean) = if (ok) "✅" else "◻️"

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
