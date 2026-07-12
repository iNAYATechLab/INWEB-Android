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

    private var step: Int = 0

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Regardless of grant/deny, advance to the final step.
            step = 2; render()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        prefs = Prefs(this)

        stepIcon  = findViewById(R.id.stepIcon)
        stepTitle = findViewById(R.id.stepTitle)
        stepBody  = findViewById(R.id.stepBody)
        nextBtn   = findViewById(R.id.nextBtn)
        skipBtn   = findViewById(R.id.skipBtn)
        dot1      = findViewById(R.id.dot1)
        dot2      = findViewById(R.id.dot2)
        dot3      = findViewById(R.id.dot3)

        skipBtn.setOnClickListener { finishOnboarding() }
        nextBtn.setOnClickListener { advance() }
        render()
    }

    private fun advance() {
        when (step) {
            0 -> { step = 1; render() }
            1 -> {
                if (needsNotificationPermission()) {
                    requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    step = 2; render()
                }
            }
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
                nextBtn.text   = if (needsNotificationPermission())
                    getString(R.string.onboard_grant) else getString(R.string.onboard_next)
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
        prefs.onboarded = true
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
