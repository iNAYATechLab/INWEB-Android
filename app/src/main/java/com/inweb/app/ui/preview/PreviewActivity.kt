package com.inweb.app.ui.preview

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.inweb.app.R
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.util.Prefs

/**
 * Built-in browser that talks to the local server without leaving INWEB.
 *
 * Design goals:
 *   - Feel like Chrome — URL bar, back/forward/reload/home, indeterminate
 *     progress at the top.
 *   - **Never** navigate off the local server unless the user explicitly
 *     confirms (we don't want the app to become a general-purpose browser
 *     that could accidentally load hostile content).
 *   - Support desktop User-Agent toggle (useful for testing responsive
 *     layouts).
 *   - JavaScript on by default, cookies + DOM storage enabled — exactly
 *     what a PHP developer expects when previewing WordPress / Laravel.
 *
 * Launch parameters:
 *   – EXTRA_URL: optional starting URL. Defaults to http://localhost:PORT/
 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var backBtn: ImageButton
    private lateinit var fwdBtn: ImageButton
    private lateinit var reloadBtn: ImageButton
    private lateinit var homeBtn: ImageButton
    private lateinit var moreBtn: ImageButton
    private lateinit var statusText: TextView

    private var desktopUa: Boolean = false
    private val defaultUa: String by lazy { WebSettings.getDefaultUserAgent(this) }
    private val desktopUaString =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)
        prefs = Prefs(this)

        setupUrlBar()
        setupWebView()

        val startUrl = intent.getStringExtra(EXTRA_URL) ?: homeUrl()
        loadUrl(startUrl)
    }

    /* ---------------------------------------------------------------- */
    /*  URL bar + toolbar buttons                                       */
    /* ---------------------------------------------------------------- */

    private fun setupUrlBar() {
        urlBar     = findViewById(R.id.urlBar)
        progress   = findViewById(R.id.progressBar)
        backBtn    = findViewById(R.id.backBtn)
        fwdBtn     = findViewById(R.id.fwdBtn)
        reloadBtn  = findViewById(R.id.reloadBtn)
        homeBtn    = findViewById(R.id.homeBtn)
        moreBtn    = findViewById(R.id.moreBtn)
        statusText = findViewById(R.id.statusText)

        findViewById<ImageButton>(R.id.closeBtn).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        backBtn.setOnClickListener   { if (webView.canGoBack())    webView.goBack() }
        fwdBtn.setOnClickListener    { if (webView.canGoForward()) webView.goForward() }
        reloadBtn.setOnClickListener { webView.reload() }
        homeBtn.setOnClickListener   { loadUrl(homeUrl()) }
        moreBtn.setOnClickListener   { showMoreSheet() }

        // Pressing enter on the URL bar navigates.
        urlBar.setOnEditorActionListener { _, _, _ ->
            loadUrl(normaliseUrl(urlBar.text.toString()))
            true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled           = true
            domStorageEnabled           = true
            allowFileAccess             = false      // safety: no file://
            allowContentAccess          = false
            loadWithOverviewMode        = true
            useWideViewPort             = true
            builtInZoomControls         = true
            displayZoomControls         = false
            setSupportZoom(true)
            textZoom                    = 100
            mediaPlaybackRequiresUserGesture = false
            userAgentString             = defaultUa
            // Fine to enable — this is *your own* local server.
            mixedContentMode            = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode                   = WebSettings.LOAD_DEFAULT
        }

        // Cookies: enable + persist so login sessions survive.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // Local host → allow inline.
                if (isLocalUrl(url)) { view.loadUrl(url); return true }
                // External URL → confirm before leaving INWEB.
                confirmExternalNavigation(url)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                urlBar.setText(url ?: "")
                updateNavButtons()
                statusText.text = getString(R.string.preview_loading)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                urlBar.setText(url ?: "")
                updateNavButtons()
                statusText.text = view?.title.orEmpty().take(80)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) statusText.text = title.take(80)
            }
        }

        // Nicer default: pinch-to-zoom uses whole viewport.
        webView.isVerticalScrollBarEnabled   = true
        webView.isHorizontalScrollBarEnabled = false
    }

    /* ---------------------------------------------------------------- */
    /*  Navigation helpers                                              */
    /* ---------------------------------------------------------------- */

    private fun homeUrl(): String = "http://localhost:${prefs.httpPort}/"

    /** Turn "foo/bar" or "127.0.0.1" into a full http:// URL. */
    private fun normaliseUrl(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return homeUrl()
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        if (s.startsWith("/")) return "http://localhost:${prefs.httpPort}$s"
        // Bare hostname / path — assume http://
        return "http://$s"
    }

    private fun loadUrl(url: String) {
        webView.loadUrl(url)
        urlBar.setText(url)
    }

    private fun updateNavButtons() {
        backBtn.alpha = if (webView.canGoBack())    1f else 0.35f
        fwdBtn.alpha  = if (webView.canGoForward()) 1f else 0.35f
        backBtn.isEnabled = webView.canGoBack()
        fwdBtn.isEnabled  = webView.canGoForward()
    }

    private fun isLocalUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host ?: return false
        return host == "localhost" || host == "127.0.0.1" ||
               host.startsWith("192.168.") || host.startsWith("10.") ||
               host.startsWith("172.")
    }

    private fun confirmExternalNavigation(url: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.preview_external_title)
            .setMessage(getString(R.string.preview_external_msg, url))
            .setPositiveButton(R.string.preview_external_open) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /* ---------------------------------------------------------------- */
    /*  Overflow menu                                                    */
    /* ---------------------------------------------------------------- */

    private fun showMoreSheet() {
        val items = arrayOf(
            getString(R.string.preview_action_copy_url),
            getString(R.string.preview_action_open_ext),
            getString(R.string.preview_action_share),
            getString(if (desktopUa) R.string.preview_action_mobile_ua
                      else R.string.preview_action_desktop_ua),
            getString(R.string.preview_action_clear_cache),
            getString(R.string.preview_action_view_source),
        )
        AlertDialog.Builder(this)
            .setTitle(webView.title ?: webView.url ?: getString(R.string.preview_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> copyUrl()
                    1 -> openInSystemBrowser()
                    2 -> shareUrl()
                    3 -> toggleDesktopUa()
                    4 -> clearCache()
                    5 -> viewSource()
                }
            }.show()
    }

    private fun copyUrl() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("URL", webView.url ?: ""))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun openInSystemBrowser() {
        val url = webView.url ?: return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Throwable) { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show() }
    }

    private fun shareUrl() {
        val url = webView.url ?: return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(i, getString(R.string.share_url)))
    }

    private fun toggleDesktopUa() {
        desktopUa = !desktopUa
        webView.settings.userAgentString = if (desktopUa) desktopUaString else defaultUa
        webView.settings.useWideViewPort = desktopUa
        webView.settings.loadWithOverviewMode = desktopUa
        webView.reload()
        Toast.makeText(
            this,
            if (desktopUa) R.string.preview_desktop_ua_on
                      else R.string.preview_mobile_ua_on,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun clearCache() {
        webView.clearCache(true)
        webView.clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
        Toast.makeText(this, R.string.preview_cache_cleared, Toast.LENGTH_SHORT).show()
        webView.reload()
    }

    private fun viewSource() {
        // Chrome-style "view-source:" URL — WebView supports it directly.
        val url = webView.url ?: return
        loadUrl("view-source:$url")
    }

    /* ---------------------------------------------------------------- */
    /*  System back = WebView back until history exhausted              */
    /* ---------------------------------------------------------------- */

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onPause() {
        super.onPause(); webView.onPause()
    }
    override fun onResume() {
        super.onResume(); webView.onResume()
    }
    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"

        /** Open the preview showing the given URL (or the home URL if null). */
        fun open(context: android.content.Context, url: String? = null) {
            val i = Intent(context, PreviewActivity::class.java)
            if (url != null) i.putExtra(EXTRA_URL, url)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(i)
        }
    }
}
