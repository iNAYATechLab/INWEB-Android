package com.inweb.app.ui.tunnel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.inweb.app.AssetInstaller
import com.inweb.app.R
import com.inweb.app.net.QrGenerator
import com.inweb.app.tunnel.TunnelManager
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.inweb.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Share to the whole internet" screen. Runs a Cloudflare or ngrok tunnel
 * process that forwards a public HTTPS URL to your local server.
 *
 * Screen shows:
 *   – Provider picker (Cloudflare / ngrok)
 *   – Start/Stop button
 *   – Public URL card with tap-to-copy, share, QR
 *   – Live status label
 */
class TunnelActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var tunnelManager: TunnelManager? = null

    private lateinit var providerCf: RadioButton
    private lateinit var providerNgrok: RadioButton
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var urlCard: View
    private lateinit var copyBtn: View
    private lateinit var shareBtn: View
    private lateinit var openBtn: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tunnel)
        PageScaffold.setup(this, getString(R.string.tunnel_title)) {
            onBackPressedDispatcher.onBackPressed()
        }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.SHARE)
        prefs = Prefs(this)

        providerCf    = findViewById(R.id.providerCf)
        providerNgrok = findViewById(R.id.providerNgrok)
        startBtn      = findViewById(R.id.startBtn)
        stopBtn       = findViewById(R.id.stopBtn)
        statusText    = findViewById(R.id.statusText)
        urlText       = findViewById(R.id.urlText)
        qrImage       = findViewById(R.id.qrImage)
        urlCard       = findViewById(R.id.urlCard)
        copyBtn       = findViewById(R.id.copyBtn)
        shareBtn      = findViewById(R.id.shareBtn)
        openBtn       = findViewById(R.id.openBtn)

        providerCf.isChecked = true
        renderStopped()

        startBtn.setOnClickListener { start() }
        stopBtn.setOnClickListener  { stop() }

        // Lifecycle-scoped observer of the tunnel state.
        lifecycleScope.launch {
            // We wire this up after start() so tunnelManager is non-null; use
            // a small polling wrapper here for simplicity.
            while (true) {
                tunnelManager?.state?.collectLatest { renderState(it) }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    override fun onDestroy() {
        // Leave the tunnel running if user backs out — like a background service.
        super.onDestroy()
    }

    /* ------------------------------------------------------------- */

    private fun start() {
        val provider = if (providerNgrok.isChecked)
            TunnelManager.Provider.NGROK else TunnelManager.Provider.CLOUDFLARE
        val localUrl = "http://localhost:${prefs.httpPort}"

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val layout = AssetInstaller.install(this@TunnelActivity)
                    val mgr = tunnelManager ?: TunnelManager(this@TunnelActivity, layout)
                        .also { tunnelManager = it }
                    mgr.start(provider, localUrl)
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TunnelActivity, it.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stop() {
        tunnelManager?.stop()
        renderStopped()
    }

    /* ------------------------------------------------------------- */

    private fun renderState(s: TunnelManager.State) = runOnUiThread {
        when (s) {
            TunnelManager.State.Stopped -> renderStopped()
            TunnelManager.State.Starting -> {
                statusText.text = getString(R.string.tunnel_starting)
                statusText.setTextColor(0xFFF59E0B.toInt())
                startBtn.isEnabled = false
                stopBtn.isEnabled  = true
                urlCard.visibility = View.GONE
            }
            is TunnelManager.State.Running -> {
                statusText.text = getString(R.string.tunnel_running)
                statusText.setTextColor(0xFF10B981.toInt())
                startBtn.isEnabled = false
                stopBtn.isEnabled  = true
                urlText.text = s.url
                urlCard.visibility = View.VISIBLE
                generateQr(s.url)
                copyBtn.setOnClickListener  { copy(s.url) }
                shareBtn.setOnClickListener { share(s.url) }
                openBtn.setOnClickListener  { open(s.url) }
            }
            is TunnelManager.State.Error -> {
                statusText.text = "Error: ${s.message}"
                statusText.setTextColor(0xFFEF4444.toInt())
                startBtn.isEnabled = true
                stopBtn.isEnabled  = false
                urlCard.visibility = View.GONE
            }
        }
    }

    private fun renderStopped() {
        statusText.text = getString(R.string.tunnel_stopped)
        statusText.setTextColor(0xFFEF4444.toInt())
        startBtn.isEnabled = true
        stopBtn.isEnabled  = false
        urlCard.visibility = View.GONE
    }

    private fun generateQr(url: String) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                runCatching { QrGenerator.generate(url, sizePx = 600) }.getOrNull()
            }
            if (bmp != null) qrImage.setImageBitmap(bmp)
        }
    }

    private fun copy(url: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("INWEB public URL", url))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun share(url: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(i, getString(R.string.share_url)))
    }

    private fun open(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Throwable) { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show() }
    }
}
