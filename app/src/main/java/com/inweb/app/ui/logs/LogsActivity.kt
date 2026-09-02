package com.inweb.app.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.inweb.app.Constants
import com.inweb.app.R
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Live tail viewer for Nginx access.log and error.log.
 *
 * Poll-based (500 ms) — simple, works reliably on Android's FS which lacks
 * inotify guarantees for app storage. Only reads *new* bytes each poll so
 * memory stays bounded.
 */
class LogsActivity : AppCompatActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var console: TextView
    private lateinit var status: TextView
    private lateinit var tabs: TabLayout

    private var tailJob: Job? = null
    private var currentFile: File? = null
    private var lastOffset: Long = 0
    private var autoScroll: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)
        PageScaffold.setup(this, getString(R.string.nav_logs)) {
            onBackPressedDispatcher.onBackPressed()
        }
        PageScaffold.setActionIcon(this, R.drawable.ic_arrow_down) {
            autoScroll = true; scrollToBottom()
        }
        PageScaffold.setSecondaryActionIcon(this, R.drawable.ic_more) {
            showLogMenu()
        }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.LOGS)

        scroll  = findViewById(R.id.scroll)
        console = findViewById(R.id.console)
        status  = findViewById(R.id.status)
        tabs    = findViewById(R.id.tabs)

        console.typeface = Typeface.MONOSPACE

        val logsDir = File(filesDir, "${Constants.ASSET_ROOT}/logs")
        logsDir.mkdirs()

        // Service process logs (written by ServerManager/MysqlManager) FIRST —
        // these carry the real reason a server fails to start.
        tabs.addTab(tabs.newTab().setText("nginx").setTag(File(logsDir, "nginx.log")))
        tabs.addTab(tabs.newTab().setText("php-fpm").setTag(File(logsDir, "php-fpm.log")))
        tabs.addTab(tabs.newTab().setText("mariadbd").setTag(File(logsDir, "mariadbd.log")))
        tabs.addTab(tabs.newTab().setText("access.log").setTag(File(logsDir, "access.log")))
        tabs.addTab(tabs.newTab().setText("error.log").setTag(File(logsDir, "error.log")))
        tabs.addTab(tabs.newTab().setText("php-fpm.error.log").setTag(File(logsDir, "php-fpm.error.log")))

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { switchTo(tab.tag as File) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) { switchTo(tab.tag as File) }
        })

        // Detect user scroll — pause auto-scroll if they scroll up.
        scroll.viewTreeObserver.addOnScrollChangedListener {
            val diff = console.height - (scroll.height + scroll.scrollY)
            autoScroll = diff < 60
        }

        switchTo(tabs.getTabAt(0)!!.tag as File)
    }

    private fun showLogMenu() {
        val items = arrayOf(
            getString(R.string.log_action_clear),
            getString(R.string.log_action_truncate),
            getString(R.string.log_action_copy)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(currentFile?.name ?: "Log")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> clearScreen()
                    1 -> truncateCurrentFile()
                    2 -> copyToClipboard()
                }
            }.show()
    }

    /* ------------------------------------------------------------- */

    private fun switchTo(f: File) {
        currentFile = f
        lastOffset = 0
        console.text = ""
        status.text = "Watching ${f.name}…"
        startTail()
    }

    private fun startTail() {
        tailJob?.cancel()
        val f = currentFile ?: return
        tailJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    pollOnce(f)
                    delay(500)
                }
            }
        }
    }

    private suspend fun pollOnce(f: File) {
        val chunk = withContext(Dispatchers.IO) {
            if (!f.exists()) return@withContext null
            val len = f.length()
            if (len < lastOffset) lastOffset = 0   // file was truncated / rotated
            if (len == lastOffset) return@withContext ""
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(lastOffset)
                val bytes = ByteArray((len - lastOffset).toInt().coerceAtMost(64 * 1024))
                val n = raf.read(bytes)
                lastOffset += n
                String(bytes, 0, n)
            }
        }
        when {
            chunk == null -> status.text = "${f.name} does not exist yet — start the server."
            chunk.isEmpty() -> Unit
            else -> {
                console.append(chunk)
                // Cap the buffer so extremely long sessions don't OOM.
                if (console.length() > MAX_CONSOLE_CHARS) {
                    val trimmed = console.text.substring(console.length() - MAX_CONSOLE_CHARS / 2)
                    console.text = "…[trimmed]…\n$trimmed"
                }
                status.text = "${f.name} · ${lastOffset} bytes"
                if (autoScroll) scrollToBottom()
            }
        }
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun clearScreen() {
        console.text = ""
        Toast.makeText(this, "Cleared (file is untouched)", Toast.LENGTH_SHORT).show()
    }

    private fun truncateCurrentFile() {
        val f = currentFile ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { RandomAccessFile(f, "rw").use { it.setLength(0) } }
            withContext(Dispatchers.Main) {
                lastOffset = 0
                console.text = ""
                Toast.makeText(this@LogsActivity, "${f.name} truncated", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyToClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", console.text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MAX_CONSOLE_CHARS = 200_000
    }
}
