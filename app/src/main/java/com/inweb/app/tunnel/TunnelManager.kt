package com.inweb.app.tunnel

import android.content.Context
import android.util.Log
import com.inweb.app.AssetInstaller
import com.inweb.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * Wraps a `cloudflared tunnel --url http://localhost:<port>` child process
 * and exposes its state (public URL, error, etc.) as a [StateFlow] the UI
 * can render.
 *
 * The Cloudflare "TryCloudflare" tunnel is *anonymous* — no account needed;
 * every run gets a fresh `*.trycloudflare.com` URL. Perfect for demos.
 *
 * The user drops the `cloudflared` binary (or a wrapper script) into
 * assets/server_env/tunnel/. If it's missing we surface a friendly message
 * rather than crashing.
 */
class TunnelManager(
    private val context: Context,
    private val layout: AssetInstaller.Layout
) {

    sealed class State {
        data object Stopped : State()
        data object Starting : State()
        data class Running(val url: String, val startedAt: Long) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state

    private var process: Process? = null
    private var pumper: Job? = null
    private val ioScope = CoroutineScope(Dispatchers.IO)

    /** True iff a tunnel process is currently alive. */
    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * Launches `cloudflared` (or `ngrok` when [provider] = NGROK).
     * @param localUrl e.g. "http://localhost:8080" — the URL to forward.
     */
    @Throws(Exception::class)
    fun start(provider: Provider, localUrl: String) {
        if (isRunning) return
        _state.value = State.Starting

        val bin = when (provider) {
            Provider.CLOUDFLARE -> File(layout.libDir, "libexec_cloudflared.so")
            Provider.NGROK      -> File(layout.prefixDir, "tunnel/ngrok")
        }
        if (!bin.exists() || !bin.canExecute()) {
            val msg = "${bin.name} not found or not executable at ${bin.absolutePath}. " +
                      "Drop the binary into assets/server_env/tunnel/ before running."
            _state.value = State.Error(msg)
            throw IllegalStateException(msg)
        }

        val cmd = when (provider) {
            Provider.CLOUDFLARE -> listOf(
                bin.absolutePath, "tunnel", "--no-autoupdate", "--url", localUrl
            )
            Provider.NGROK -> listOf(
                bin.absolutePath, "http", localUrl.removePrefix("http://").removePrefix("https://")
            )
        }

        Log.i(TAG, "Starting tunnel: ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd)
            .directory(layout.prefixDir)
            .redirectErrorStream(true)

        val env = pb.environment()
        env["PATH"] = "${layout.binDir.absolutePath}:${env["PATH"] ?: "/system/bin"}"
        env["HOME"] = layout.prefixDir.absolutePath

        val proc = pb.start()
        process = proc

        pumper = ioScope.launch {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                    var line: String? = r.readLine()
                    while (line != null) {
                        Log.i("$TAG/${provider.name}", line!!)
                        // Extract public URL from either provider's output.
                        val url = extractUrl(line!!, provider)
                        if (url != null && _state.value !is State.Running) {
                            _state.value = State.Running(url, System.currentTimeMillis())
                        }
                        line = r.readLine()
                    }
                }
            } catch (_: Throwable) { /* stream closed */ }

            // Process exited before/after reporting URL.
            if (_state.value !is State.Error) {
                _state.value = State.Stopped
            }
        }
    }

    fun stop() {
        val p = process ?: return
        try {
            if (p.isAlive) p.destroy()
            val start = System.currentTimeMillis()
            while (p.isAlive && System.currentTimeMillis() - start < 3000) Thread.sleep(50)
            if (p.isAlive) p.destroyForcibly()
        } catch (t: Throwable) { Log.w(TAG, "Error stopping tunnel", t) }
        process = null
        pumper?.cancel()
        pumper = null
        _state.value = State.Stopped
    }

    /* ------------------------------------------------------------- */

    private fun extractUrl(line: String, provider: Provider): String? {
        val m = when (provider) {
            // cloudflared prints:
            //   |  https://foo-bar.trycloudflare.com                       |
            Provider.CLOUDFLARE -> CF_PATTERN.matcher(line)
            // ngrok prints (v3): "url=https://xxxx.ngrok-free.app"
            Provider.NGROK -> NGROK_PATTERN.matcher(line)
        }
        return if (m.find()) m.group(0) else null
    }

    enum class Provider(val displayName: String) {
        CLOUDFLARE("Cloudflare Tunnel"),
        NGROK("ngrok")
    }

    companion object {
        private const val TAG = "TunnelManager"
        private val CF_PATTERN    = Pattern.compile("""https://[a-z0-9-]+\.trycloudflare\.com""")
        private val NGROK_PATTERN = Pattern.compile("""https://[a-zA-Z0-9-]+\.ngrok(?:-free)?\.app""")
    }
}
