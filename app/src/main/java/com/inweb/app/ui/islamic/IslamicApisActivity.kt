package com.inweb.app.ui.islamic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.inweb.app.R
import com.inweb.app.islamic.IslamicApiInstaller
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.inweb.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Islamic Developer Kit" screen.
 *
 * Explains what the built-in Islamic APIs are, lets the user
 * one-tap-install them into their web root, and provides shortcut
 * links to each endpoint.
 */
class IslamicApisActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_islamic_apis)
        PageScaffold.setup(this, getString(R.string.islamic_title)) {
            onBackPressedDispatcher.onBackPressed()
        }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)
        prefs = Prefs(this)

        val statusText: TextView = findViewById(R.id.statusText)
        val installBtn: Button   = findViewById(R.id.installBtn)
        val uninstallBtn: Button = findViewById(R.id.uninstallBtn)

        fun refresh() {
            val installed = IslamicApiInstaller.isInstalled(this)
            statusText.text = if (installed) getString(R.string.islamic_installed)
                              else getString(R.string.islamic_not_installed)
            statusText.setTextColor(if (installed) 0xFF10B981.toInt() else 0xFFEF4444.toInt())
            installBtn.text = if (installed) getString(R.string.islamic_reinstall)
                              else getString(R.string.islamic_install)
            uninstallBtn.visibility = if (installed) View.VISIBLE else View.GONE
        }

        installBtn.setOnClickListener {
            installBtn.isEnabled = false
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { IslamicApiInstaller.install(this@IslamicApisActivity) }
                }.let { r ->
                    installBtn.isEnabled = true
                    if (r.isSuccess) {
                        Toast.makeText(this@IslamicApisActivity, R.string.islamic_install_ok, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@IslamicApisActivity, r.exceptionOrNull()?.message ?: "Failed", Toast.LENGTH_LONG).show()
                    }
                    refresh()
                }
            }
        }
        uninstallBtn.setOnClickListener {
            IslamicApiInstaller.uninstall(this)
            Toast.makeText(this, R.string.islamic_uninstalled, Toast.LENGTH_SHORT).show()
            refresh()
        }

        val port = prefs.httpPort
        val base = "http://localhost:$port/api"
        findViewById<Button>(R.id.tryPrayer).setOnClickListener  { open("$base/prayer-times.php?lat=23.8103&lng=90.4125") }
        findViewById<Button>(R.id.tryQibla).setOnClickListener   { open("$base/qibla.php?lat=23.8103&lng=90.4125") }
        findViewById<Button>(R.id.tryHijri).setOnClickListener   { open("$base/hijri-date.php") }
        findViewById<Button>(R.id.tryZakat).setOnClickListener   { open("$base/zakat.php?cash=100000&gold_g=50") }
        findViewById<Button>(R.id.openIndex).setOnClickListener  { open("$base/") }

        refresh()
    }

    private fun open(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Throwable) { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show() }
    }
}
