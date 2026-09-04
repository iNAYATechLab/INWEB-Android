package com.inweb.app.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.inweb.app.MainActivity
import com.inweb.app.R
import com.inweb.app.util.Prefs

/**
 * Three-step first-run wizard.
 *
 *   1. Welcome — brand splash + tagline
 *   2. Permissions — request POST_NOTIFICATIONS (Android 13+)
 *   3. Ready — quick tour of what the user can do next
 *
 * On completion, [Prefs.onboarded] is flipped to true and MainActivity is
 * launched. If the user already onboarded, MainActivity skips this screen.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var stepIcon: ImageView
    private lateinit var stepTitle: TextView
    private lateinit var stepBody: TextView
    private lateinit var nextBtn: Button
    private lateinit var skipBtn: Button
    private lateinit var dot1: View
    private lateinit var dot2: View
    private lateinit var dot3: View

    /** MainActivity থেকে শুধু পারমিশন ফ্লোটা চালাতে চাইলে (অনবোর্ডিং স্কিপ) */
    private var permissionsOnly: Boolean = false

    private var step: Int = 0

    /* ── 🔐 Sequential grant chain ───────────────────────────────────────
     * Android 6+ ইনস্টল-টাইমে কোনো পারমিশন জিজ্ঞেস করে না (platform rule) —
     * তাই "ইনস্টলের সময়ই চাওয়া" মানে **প্রথম লঞ্চেই** সব দরকারি গ্রান্ট চাওয়া।
     * ক্রম: notifications (runtime) → battery exemption (special access)
     *        → unknown-sources (OTA/module ইনস্টলের জন্য)।
     * deny করলেও অ্যাপ চলে; শুধু সংশ্লিষ্ট ফিচার সীমিত থাকে।
     */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { nextPermission() }

    private val batteryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { nextPermission() }

    private val installSrcLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { nextPermission() }

    /** 0 = notifications, 1 = battery, 2 = unknown sources, 3 = done */
    private var permCursor = 0

    private fun nextPermission() {
        when (permCursor) {
            0 -> {
                permCursor = 1
                if (needsNotificationPermission())
                    requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else nextPermission()
            }
            1 -> {
                permCursor = 2
                if (!com.inweb.app.util.PermissionCenter.ignoringBatteryOptimizations(this)) {
                    val i = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + packageName))
                    try { batteryLauncher.launch(i) } catch (_: Exception) { nextPermission() }
                } else nextPermission()
            }
            2 -> {
                permCursor = 3
                if (!com.inweb.app.util.PermissionCenter.canInstallPackages(this)) {
                    val i = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + packageName))
                    try { installSrcLauncher.launch(i) } catch (_: Exception) { nextPermission() }
                } else nextPermission()
            }
            else -> if (permissionsOnly) finish() else { step = 2; render() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        prefs = Prefs(this)
        permissionsOnly = intent.getBooleanExtra(EXTRA_PERMISSIONS_ONLY, false)
        if (permissionsOnly) { step = 1 }

        stepIcon  = findViewById(R.id.stepIcon)
        stepTitle = findViewById(R.id.stepTitle)
        stepBody  = findViewById(R.id.stepBody)
        nextBtn   = findViewById(R.id.nextBtn)
        skipBtn   = findViewById(R.id.skipBtn)
        dot1      = findViewById(R.id.dot1)
        dot2      = findViewById(R.id.dot2)
        dot3      = findViewById(R.id.dot3)

        skipBtn.setOnClickListener { finishOnboarding() }   // permsOnly হলে finish() করেই ফেরত যায়
        nextBtn.setOnClickListener { advance() }
        render()
    }

    private fun advance() {
        when (step) {
            0 -> { step = 1; render() }
            // 🔐 এক ট্যাপে বাকি সব গ্রান্ট: notifications → battery → unknown sources
            //    (প্রতিটার ফলাফলের পর চেইন নিজেই পেছনে বাড়ায় — দেখুন nextPermission())
            1 -> { permCursor = 0; nextPermission() }
            2 -> finishOnboarding()
        }
    }

    private fun render() {
        // Progress dots.
        val active = ContextCompat.getColor(this, R.color.accent)
        val inactive = ContextCompat.getColor(this, R.color.text_secondary)
        dot1.setBackgroundColor(if (step >= 0) active else inactive)
        dot2.setBackgroundColor(if (step >= 1) active else inactive)
        dot3.setBackgroundColor(if (step >= 2) active else inactive)

        when (step) {
            0 -> {
                stepIcon.setImageResource(R.mipmap.ic_launcher_foreground)
                stepTitle.text = getString(R.string.onboard_welcome_title)
                stepBody.text  = getString(R.string.onboard_welcome_body)
                nextBtn.text   = getString(R.string.onboard_next)
                skipBtn.visibility = View.VISIBLE
            }
            1 -> {
                stepIcon.setImageResource(R.drawable.ic_notif_bell)
                stepTitle.text = getString(R.string.onboard_perm_title)
                stepBody.text  = getString(R.string.onboard_perm_body)
                nextBtn.text   = getString(R.string.onboard_grant_all)
                skipBtn.visibility = View.VISIBLE
            }
            2 -> {
                stepIcon.setImageResource(R.drawable.ic_ready_check)
                stepTitle.text = getString(R.string.onboard_ready_title)
                stepBody.text  = getString(R.string.onboard_ready_body)
                nextBtn.text   = getString(R.string.onboard_finish)
                skipBtn.visibility = View.GONE
            }
        }
    }

    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun finishOnboarding() {
        if (permissionsOnly) { prefs.permsAskedVersionCode = com.inweb.app.BuildConfig.VERSION_CODE; finish(); return }
        prefs.onboarded = true
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        /** শুধু পারমিশন ফ্লো রান করবে (onboarding step 1) */
        const val EXTRA_PERMISSIONS_ONLY = "perms_only"
    }
}
