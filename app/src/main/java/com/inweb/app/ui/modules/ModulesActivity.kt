package com.inweb.app.ui.modules

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.inweb.app.R
import com.inweb.app.runtime.RuntimeModule
import com.inweb.app.runtime.RuntimeModuleManager
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import java.io.File

/*
 * ════════════════════════════════════════════════════════════════
 *  INWEB — Runtime Modules স্ক্রিন
 * ════════════════════════════════════════════════════════════════
 *  ভারী optional বাইনারি (Node.js / Caddy / Cloudflare Tunnel) মূল APK-তে
 *  বান্ডল না করে ছোট runtime module APK হিসেবে এখান থেকেই নামে —
 *  Android 10+ এ ডাউনলোড করা বাইনারি app-data dir থেকে exec করা যায় না,
 *  তাই module-ও ইনস্টলড APK-ই (exec-legal nativeLibraryDir)।
 *
 *  নীতি: **core-এ থাকলে core-ই ব্যবহার হয়** — মডিউল শুধু তখনই দরকার যখন
 *  core APK থেকে সেটা আলাদা করা (--split-modules)।
 */
class ModulesActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_modules)
        PageScaffold.setup(this, getString(R.string.modules_title)) { finish() }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)
        list = findViewById(R.id.moduleList)
        findViewById<View>(R.id.modulesHelp)?.setOnClickListener {
            Toast.makeText(this, R.string.modules_note, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        render()                      // ইনস্টলার থেকে ফেরার পর স্ট্যাটাস রিফ্রেশ
    }

    private fun render() {
        val coreLib = File(applicationInfo.nativeLibraryDir)
        list.removeAllViews()
        for (m in RuntimeModule.entries) {
            val coreHas = m.executables.any { File(coreLib, it).canExecute() }
            val inst = RuntimeModuleManager.installed(this, m)
            val row = layoutInflater.inflate(R.layout.item_module, list, false)
            row.findViewById<TextView>(R.id.moduleTitle).text = m.displayName
            row.findViewById<View>(R.id.moduleDot).setBackgroundResource(
                if (coreHas || inst != null) R.drawable.dot_green else R.drawable.dot_amber)
            row.findViewById<TextView>(R.id.moduleSub).text = when {
                coreHas -> getString(R.string.modules_state_bundled)
                inst != null -> getString(R.string.modules_state_installed, inst.versionName)
                else -> getString(R.string.modules_state_missing, m.approxMb)
            }
            row.setOnClickListener { onRow(m, coreHas, inst != null) }
            row.setOnLongClickListener { openAppDetails(m); true }
            list.addView(row)
        }
    }

    private fun onRow(m: RuntimeModule, coreHas: Boolean, installed: Boolean) {
        when {
            coreHas -> Toast.makeText(this, R.string.modules_nothing_needed, Toast.LENGTH_SHORT).show()
            installed -> {
                Toast.makeText(this, R.string.modules_restart_hint, Toast.LENGTH_LONG).show()
                openAppDetails(m)
            }
            else -> RuntimeModuleManager.downloadAndInstall(this, m)
        }
    }

    private fun openAppDetails(m: RuntimeModule) {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + m.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }
}
