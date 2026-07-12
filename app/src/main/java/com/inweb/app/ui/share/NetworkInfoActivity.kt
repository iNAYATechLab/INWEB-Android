package com.inweb.app.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.inweb.app.Constants
import com.inweb.app.R
import com.inweb.app.net.NetworkUtils
import com.inweb.app.net.QrGenerator
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.inweb.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * "Share your server" screen.
 *
 * Shows:
 *   – the URL(s) the user can hit (localhost + LAN)
 *   – Wi-Fi SSID and interface transport (WIFI / ETHERNET / HOTSPOT)
 *   – a big scannable QR code encoding the LAN URL
 *
 * Users can tap the URL to open it, tap Copy to copy, or tap Share to send
 * the URL to messaging apps.
 */
class NetworkInfoActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var qrImage: ImageView
    private lateinit var qrLabel: TextView
    private lateinit var localUrlText: TextView
    private lateinit var lanUrlText: TextView
    private lateinit var lanUrlHint: TextView
    private lateinit var ssidText: TextView
    private lateinit var transportText: TextView
    private lateinit var bindWarning: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_info)
        PageScaffold.setup(this, getString(R.string.share_title)) {
            onBackPressedDispatcher.onBackPressed()
        }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.SHARE)

        prefs         = Prefs(this)
        qrImage       = findViewById(R.id.qrImage)
        qrLabel       = findViewById(R.id.qrLabel)
        localUrlText  = findViewById(R.id.localUrlText)
        lanUrlText    = findViewById(R.id.lanUrlText)
        lanUrlHint    = findViewById(R.id.lanUrlHint)
        ssidText      = findViewById(R.id.ssidText)
        transportText = findViewById(R.id.transportText)
        bindWarning   = findViewById(R.id.bindWarning)

        findViewById<View>(R.id.copyBtn).setOnClickListener  { copyBestUrl() }
        findViewById<View>(R.id.shareBtn).setOnClickListener { shareBestUrl() }
        findViewById<View>(R.id.shareQrBtn).setOnClickListener { shareQrImage() }
        findViewById<View>(R.id.openTunnelBtn).setOnClickListener {
            startActivity(Intent(this, com.inweb.app.ui.tunnel.TunnelActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /* ------------------------------------------------------------- */
    /*  Rendering                                                      */
    /* ------------------------------------------------------------- */

    private fun refresh() {
        val port = prefs.httpPort
        val bindLan = prefs.bindLan
        val info = NetworkUtils.snapshot(this)

        // Localhost URL — always works from the phone itself.
        localUrlText.text = "http://localhost:$port"
        localUrlText.setOnClickListener { openInBrowser("http://localhost:$port") }

        // LAN URL — only meaningful if we found an IPv4 AND we're bound to LAN.
        val lanUrl = info.ipv4?.let { "http://$it:$port" }
        when {
            lanUrl == null -> {
                lanUrlText.text = "—"
                lanUrlHint.text = getString(R.string.no_network)
                lanUrlText.isClickable = false
            }
            !bindLan -> {
                lanUrlText.text = lanUrl
                lanUrlHint.text = getString(R.string.bind_localhost_hint)
                lanUrlText.isClickable = false
            }
            else -> {
                lanUrlText.text = lanUrl
                lanUrlHint.text = getString(R.string.lan_open_hint)
                lanUrlText.setOnClickListener { openInBrowser(lanUrl) }
            }
        }

        // Wi-Fi label.
        val transportLabel = when (info.transport) {
            NetworkUtils.Transport.WIFI     -> "Wi-Fi"
            NetworkUtils.Transport.ETHERNET -> "Ethernet"
            NetworkUtils.Transport.CELLULAR -> "Cellular (mobile data)"
            NetworkUtils.Transport.HOTSPOT  -> "Hotspot"
            NetworkUtils.Transport.OTHER    -> "Other"
            NetworkUtils.Transport.NONE     -> "Not connected"
        }
        val hotspot = if (NetworkUtils.isActingAsHotspot()) " · sharing as hotspot" else ""
        transportText.text = transportLabel + hotspot
        ssidText.text = info.ssid ?: getString(R.string.ssid_unavailable)

        // Bind-mode warning.
        bindWarning.visibility = if (!bindLan && info.ipv4 != null) View.VISIBLE else View.GONE

        // Build QR: always encode the *best* URL — LAN if available & bound to LAN,
        // else fall back to localhost (still useful if you scan on the same device).
        val qrUrl = if (bindLan && lanUrl != null) lanUrl else "http://localhost:$port"
        qrLabel.text = qrUrl
        generateQr(qrUrl)
    }

    private fun generateQr(text: String) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                runCatching { QrGenerator.generate(text, sizePx = 800) }.getOrNull()
            }
            if (bmp != null) qrImage.setImageBitmap(bmp)
        }
    }

    /* ------------------------------------------------------------- */
    /*  Actions                                                        */
    /* ------------------------------------------------------------- */

    private fun bestUrl(): String {
        val port = prefs.httpPort
        val lan  = NetworkUtils.snapshot(this).ipv4
        return if (prefs.bindLan && lan != null) "http://$lan:$port" else "http://localhost:$port"
    }

    private fun openInBrowser(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Throwable) { toast(getString(R.string.no_browser)) }
    }

    private fun copyBestUrl() {
        val url = bestUrl()
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("INWEB URL", url))
        toast(getString(R.string.copied_url, url))
    }

    private fun shareBestUrl() {
        val url = bestUrl()
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, "INWEB server")
        }
        startActivity(Intent.createChooser(i, getString(R.string.share_url)))
    }

    /** Save the QR bitmap to cache and share via FileProvider. */
    private fun shareQrImage() {
        val drawable = qrImage.drawable ?: return
        val bmp = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { saveBitmapToCache(bmp) } ?: return@launch
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, bestUrl())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, getString(R.string.share_qr)))
        }
    }

    private fun saveBitmapToCache(bmp: Bitmap): Uri? = try {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "inweb_qr.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    } catch (_: Throwable) { null }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
