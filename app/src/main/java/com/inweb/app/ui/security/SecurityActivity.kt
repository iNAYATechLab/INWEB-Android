package com.inweb.app.ui.security

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.inweb.app.R
import com.inweb.app.security.SecurityConfig
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.inweb.app.util.Prefs

/**
 * Three-in-one security screen:
 *   1. Basic Auth (user, password, protected paths)
 *   2. IP Firewall (open / whitelist / blacklist mode)
 *   3. Rate limiting (RPS + burst)
 *
 * All changes are stored via [Prefs.security] and take effect on next
 * server restart (AssetInstaller regenerates nginx.conf every start).
 */
class SecurityActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var current: SecurityConfig = SecurityConfig()

    // Basic Auth
    private lateinit var baSwitch:    Switch
    private lateinit var baUserRow:   View
    private lateinit var baUserValue: TextView
    private lateinit var baPassRow:   View
    private lateinit var baPassValue: TextView
    private lateinit var baPathsRow:  View
    private lateinit var baPathsValue:TextView
    private lateinit var baStatus:    TextView

    // IP firewall
    private lateinit var ipModeRow:   View
    private lateinit var ipModeValue: TextView
    private lateinit var ipAllowRow:  View
    private lateinit var ipAllowValue:TextView
    private lateinit var ipBlockRow:  View
    private lateinit var ipBlockValue:TextView

    // Rate limit
    private lateinit var rlSwitch:    Switch
    private lateinit var rlRpsRow:    View
    private lateinit var rlRpsValue:  TextView
    private lateinit var rlBurstRow:  View
    private lateinit var rlBurstValue:TextView

    private lateinit var restartHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_security)
        PageScaffold.setup(this, getString(R.string.security_title)) {
            onBackPressedDispatcher.onBackPressed()
        }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)

        prefs = Prefs(this)
        current = prefs.security

        bindViews()
        renderAll()
    }

    private fun bindViews() {
        baSwitch     = findViewById(R.id.baSwitch)
        baUserRow    = findViewById(R.id.baUserRow)
        baUserValue  = findViewById(R.id.baUserValue)
        baPassRow    = findViewById(R.id.baPassRow)
        baPassValue  = findViewById(R.id.baPassValue)
        baPathsRow   = findViewById(R.id.baPathsRow)
        baPathsValue = findViewById(R.id.baPathsValue)
        baStatus     = findViewById(R.id.baStatus)

        ipModeRow    = findViewById(R.id.ipModeRow)
        ipModeValue  = findViewById(R.id.ipModeValue)
        ipAllowRow   = findViewById(R.id.ipAllowRow)
        ipAllowValue = findViewById(R.id.ipAllowValue)
        ipBlockRow   = findViewById(R.id.ipBlockRow)
        ipBlockValue = findViewById(R.id.ipBlockValue)

        rlSwitch     = findViewById(R.id.rlSwitch)
        rlRpsRow     = findViewById(R.id.rlRpsRow)
        rlRpsValue   = findViewById(R.id.rlRpsValue)
        rlBurstRow   = findViewById(R.id.rlBurstRow)
        rlBurstValue = findViewById(R.id.rlBurstValue)

        restartHint  = findViewById(R.id.restartHint)

        // Basic Auth
        baSwitch.setOnCheckedChangeListener { _, checked -> update { it.copy(basicAuthEnabled = checked) } }
        baUserRow.setOnClickListener  { promptText(getString(R.string.sec_ba_user_dialog),
            current.basicAuthUser, InputType.TYPE_CLASS_TEXT) { update { c -> c.copy(basicAuthUser = it.ifBlank { "admin" }) } } }
        baPassRow.setOnClickListener  { promptPassword() }
        baPathsRow.setOnClickListener { promptMultiline(
            getString(R.string.sec_ba_paths_dialog),
            getString(R.string.sec_ba_paths_hint),
            current.basicAuthPaths.joinToString("\n")
        ) { update { c -> c.copy(basicAuthPaths = it) } } }

        // IP firewall
        ipModeRow.setOnClickListener  { pickIpMode() }
        ipAllowRow.setOnClickListener { promptMultiline(
            getString(R.string.sec_ip_allow_dialog),
            getString(R.string.sec_ip_list_hint),
            current.ipAllowList.joinToString("\n")
        ) { update { c -> c.copy(ipAllowList = it) } } }
        ipBlockRow.setOnClickListener { promptMultiline(
            getString(R.string.sec_ip_block_dialog),
            getString(R.string.sec_ip_list_hint),
            current.ipBlockList.joinToString("\n")
        ) { update { c -> c.copy(ipBlockList = it) } } }

        // Rate limit
        rlSwitch.setOnCheckedChangeListener { _, checked -> update { it.copy(rateLimitEnabled = checked) } }
        rlRpsRow.setOnClickListener   { promptNumber(
            getString(R.string.sec_rl_rps_dialog), current.rateLimitRps, 1, 1_000
        ) { update { c -> c.copy(rateLimitRps = it) } } }
        rlBurstRow.setOnClickListener { promptNumber(
            getString(R.string.sec_rl_burst_dialog), current.rateLimitBurst, 1, 10_000
        ) { update { c -> c.copy(rateLimitBurst = it) } } }
    }

    /* ---------------------------------------------------------- */
    /*  State + rendering                                          */
    /* ---------------------------------------------------------- */

    private fun update(mutator: (SecurityConfig) -> SecurityConfig) {
        current = mutator(current)
        prefs.security = current
        renderAll()
        restartHint.visibility = View.VISIBLE
    }

    private fun renderAll() {
        baSwitch.isChecked = current.basicAuthEnabled
        baUserValue.text   = current.basicAuthUser
        baPassValue.text   = if (current.basicAuthPass.isEmpty())
            getString(R.string.sec_ba_pass_not_set) else "••••••••"
        baPathsValue.text  = current.basicAuthPaths.ifEmpty { listOf(getString(R.string.sec_ba_paths_whole_site)) }
            .joinToString(" · ").take(60)

        baStatus.text = when {
            !current.basicAuthEnabled          -> getString(R.string.sec_ba_status_off)
            current.basicAuthPass.isEmpty()    -> getString(R.string.sec_ba_status_no_pass)
            else                               -> getString(R.string.sec_ba_status_on)
        }
        baStatus.setTextColor(
            if (current.basicAuthEnabled && current.basicAuthPass.isNotEmpty())
                0xFF10B981.toInt() else 0xFF9AB5AA.toInt()
        )

        ipModeValue.text = current.ipMode.displayName
        ipAllowValue.text = current.ipAllowList.joinToString(" · ").ifEmpty {
            getString(R.string.sec_ip_list_empty)
        }.take(60)
        ipBlockValue.text = current.ipBlockList.joinToString(" · ").ifEmpty {
            getString(R.string.sec_ip_list_empty)
        }.take(60)

        rlSwitch.isChecked = current.rateLimitEnabled
        rlRpsValue.text    = current.rateLimitRps.toString()
        rlBurstValue.text  = current.rateLimitBurst.toString()
    }

    /* ---------------------------------------------------------- */
    /*  Prompts                                                    */
    /* ---------------------------------------------------------- */

    private fun promptText(title: String, current: String, type: Int, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = type
            setText(current); setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title).setView(input)
            .setPositiveButton(R.string.save) { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptPassword() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sec_ba_pass_dialog)
            .setMessage(R.string.sec_ba_pass_hint)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val v = input.text.toString()
                if (v.length < 4) {
                    Toast.makeText(this, R.string.sec_ba_pass_short, Toast.LENGTH_SHORT).show()
                } else {
                    update { it.copy(basicAuthPass = v) }
                }
            }
            .setNeutralButton(R.string.sec_ba_pass_clear) { _, _ ->
                update { it.copy(basicAuthPass = "") }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun promptMultiline(title: String, hint: String, current: String, onOk: (List<String>) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setHint(hint); setText(current); minLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle(title).setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val parsed = input.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                onOk(parsed)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun promptNumber(title: String, currentValue: Int, min: Int, max: Int, onOk: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentValue.toString()); setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title).setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n == null || n !in min..max) {
                    Toast.makeText(this,
                        getString(R.string.sec_number_range, min, max), Toast.LENGTH_SHORT).show()
                } else onOk(n)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun pickIpMode() {
        val modes = SecurityConfig.IpMode.entries
        val labels = modes.map { it.displayName }.toTypedArray()
        val currentIdx = modes.indexOf(current.ipMode)
        AlertDialog.Builder(this)
            .setTitle(R.string.sec_ip_mode_dialog)
            .setSingleChoiceItems(labels, currentIdx) { d, which ->
                update { it.copy(ipMode = modes[which]) }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
