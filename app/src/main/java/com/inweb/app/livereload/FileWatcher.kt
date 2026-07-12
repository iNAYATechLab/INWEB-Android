package com.inweb.app.livereload

import android.os.FileObserver
import android.util.Log
import java.io.File

/**
 * Recursively watches a directory tree for file changes and invokes
 * [onChange] whenever a relevant file is modified.
 *
 * Android's `FileObserver` doesn't recurse — we walk the tree at startup
 * and mount an observer on every subdirectory. When a new directory is
 * created we mount a fresh observer on it.
 *
 * Uses debouncing (200 ms) so a burst of writes from a save operation
 * only triggers one reload.
 */
class FileWatcher(
    private val root: File,
    private val onChange: (String) -> Unit
) {
    private val observers = mutableListOf<FileObserver>()
    private var lastEventMs = 0L
    private var lastPath: String = ""
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null

    fun start() {
        if (!root.exists()) {
            Log.w(TAG, "Root doesn't exist yet: ${root.absolutePath}")
            return
        }
        mountRecursive(root)
        Log.i(TAG, "Watching ${observers.size} directories under ${root.absolutePath}")
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        pendingRunnable?.let { handler.removeCallbacks(it) }
    }

    /* ---------------------------------------------------------------- */

    private fun mountRecursive(dir: File) {
        if (!dir.isDirectory) return
        observers += mkObserver(dir).also { it.startWatching() }
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory && !isIgnored(child)) mountRecursive(child)
        }
    }

    @Suppress("DEPRECATION")
    private fun mkObserver(dir: File): FileObserver =
        object : FileObserver(dir.absolutePath, EVENTS_MASK) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                val fullPath = File(dir, path)

                when (event and ALL_EVENTS) {
                    CREATE, MOVED_TO -> {
                        if (fullPath.isDirectory && !isIgnored(fullPath)) mountRecursive(fullPath)
                    }
                    DELETE_SELF, MOVE_SELF -> {
                        // Directory gone → best-effort re-mount whole tree.
                    }
                }

                if (fullPath.isFile && !isIgnored(fullPath)) {
                    debouncedFire(fullPath.absolutePath)
                }
            }
        }

    private fun debouncedFire(path: String) {
        lastEventMs = System.currentTimeMillis()
        lastPath = path
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = Runnable {
            val since = System.currentTimeMillis() - lastEventMs
            if (since >= DEBOUNCE_MS) {
                onChange(lastPath)
            }
        }
        handler.postDelayed(pendingRunnable!!, DEBOUNCE_MS)
    }

    /** Skip editor swap files, hidden files, log files, etc. */
    private fun isIgnored(f: File): Boolean {
        val name = f.name
        return name.startsWith(".") ||
               name.endsWith(".swp") || name.endsWith(".swo") ||
               name.endsWith("~")    || name.startsWith("#") ||
               name == "node_modules" || name == ".git" ||
               name.endsWith(".log")
    }

    companion object {
        private const val TAG = "FileWatcher"
        private const val DEBOUNCE_MS = 200L
        private const val EVENTS_MASK =
            FileObserver.MODIFY or
            FileObserver.CREATE or
            FileObserver.DELETE or
            FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or
            FileObserver.CLOSE_WRITE
    }
}
