package com.inweb.app.ui.framework

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.inweb.app.R
import com.inweb.app.framework.FrameworkInstaller
import com.inweb.app.framework.FrameworkTemplate
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.inweb.app.util.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Framework gallery. Users pick a template card; INWEB downloads, unpacks,
 * and configures it in the background with a live progress bar.
 *
 * A small warning is shown if the chosen template needs a database but
 * MariaDB isn't enabled / running.
 */
class FrameworksActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var prefs: Prefs
    private var installJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frameworks)
        PageScaffold.setup(this, getString(R.string.frameworks_title)) {
            onBackPressedDispatcher.onBackPressed()
        }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)

        prefs = Prefs(this)
        container = findViewById(R.id.container)

        renderCards()
    }

    private fun renderCards() {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (tpl in FrameworkTemplate.ALL) {
            val row = inflater.inflate(R.layout.item_framework, container, false)
            row.findViewById<TextView>(R.id.title).text     = tpl.displayName
            row.findViewById<TextView>(R.id.tagline).text   = tpl.tagline
            row.findViewById<TextView>(R.id.meta).text      = buildMeta(tpl)
            row.findViewById<ImageView>(R.id.icon).setImageResource(tpl.iconRes)
            row.findViewById<View>(R.id.installBtn).setOnClickListener { confirmInstall(tpl) }
            container.addView(row)
        }
    }

    private fun buildMeta(tpl: FrameworkTemplate): String {
        val bits = mutableListOf("~${tpl.downloadSizeMB} MB")
        if (tpl.needsDatabase) bits += "needs MariaDB"
        if (tpl.requiresPhp)   bits += "needs PHP"
        return bits.joinToString(" · ")
    }

    /* ---------------------------------------------------------- */
    /*  Confirm + install                                          */
    /* ---------------------------------------------------------- */

    private fun confirmInstall(tpl: FrameworkTemplate) {
        val warning = buildString {
            if (tpl.needsDatabase && !prefs.mysqlEnabled) {
                append("⚠️ ${tpl.displayName} needs MariaDB, but it's disabled in Settings.\n\n")
            }
            append("This will download about ~${tpl.downloadSizeMB} MB and install into\n")
            append("www/${tpl.targetSubdir}/\n\n")
            append("Any existing files in that folder will be replaced.")
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fw_install_confirm_title, tpl.displayName))
            .setMessage(warning)
            .setPositiveButton(R.string.fw_install_btn) { _, _ -> beginInstall(tpl) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun beginInstall(tpl: FrameworkTemplate) {
        val dialog = LayoutInflater.from(this).inflate(R.layout.dialog_install_progress, null)
        val bar        = dialog.findViewById<ProgressBar>(R.id.progressBar)
        val labelStep  = dialog.findViewById<TextView>(R.id.stepLabel)
        val labelPct   = dialog.findViewById<TextView>(R.id.pctLabel)

        labelStep.text = "Preparing…"
        bar.progress = 0
        val alert = AlertDialog.Builder(this)
            .setTitle(getString(R.string.fw_installing, tpl.displayName))
            .setView(dialog)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> installJob?.cancel() }
            .show()

        installJob = lifecycleScope.launch {
            val installer = FrameworkInstaller(this@FrameworksActivity)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    installer.install(tpl) { step, pct, msg ->
                        // UI updates must hop to Main.
                        this@FrameworksActivity.runOnUiThread {
                            bar.progress = pct
                            labelStep.text = msg
                            labelPct.text  = "$pct%"
                        }
                    }
                }
            }
            alert.dismiss()
            result.onSuccess { showSuccess(tpl) }
                  .onFailure { showFailure(tpl, it) }
        }
    }

    private fun showSuccess(tpl: FrameworkTemplate) {
        val port = prefs.httpPort
        val url = "http://localhost:$port/${tpl.targetSubdir}/"
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fw_done_title, tpl.displayName))
            .setMessage(getString(R.string.fw_done_msg, url))
            .setPositiveButton(R.string.fw_open) { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (_: Throwable) { toast(getString(R.string.no_browser)) }
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun showFailure(tpl: FrameworkTemplate, t: Throwable) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fw_failed_title, tpl.displayName))
            .setMessage(t.message ?: "Unknown error")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
