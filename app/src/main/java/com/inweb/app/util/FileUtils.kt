package com.inweb.app.util

import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {

    /** Text file extensions the built-in editor knows how to open. */
    private val EDITABLE_EXT = setOf(
        "php", "html", "htm", "css", "js", "mjs", "json", "xml",
        "txt", "md", "conf", "ini", "yml", "yaml", "sh", "log",
        "sql", "csv", "svg", "env", "gitignore", "htaccess"
    )

    fun isEditable(file: File): Boolean {
        if (file.length() > MAX_EDIT_BYTES) return false
        val ext = file.extension.lowercase(Locale.ROOT)
        // Extension match, or extension-less small file that is probably text.
        return ext in EDITABLE_EXT || (ext.isEmpty() && file.length() < 64 * 1024)
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var idx = 0
        while (value >= 1024 && idx < units.lastIndex) {
            value /= 1024; idx++
        }
        return "${DecimalFormat("#.##").format(value)} ${units[idx]}"
    }

    fun humanTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    fun mimeType(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "html", "htm" -> "text/html"
        "css"         -> "text/css"
        "js", "mjs"   -> "application/javascript"
        "json"        -> "application/json"
        "png"         -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif"         -> "image/gif"
        "svg"         -> "image/svg+xml"
        "pdf"         -> "application/pdf"
        "php", "txt", "md", "conf", "ini", "log", "xml", "yml", "yaml", "sh", "sql" -> "text/plain"
        else          -> "application/octet-stream"
    }

    /** Prevent path-escape attacks like `../../etc/passwd`. */
    fun isInside(child: File, parent: File): Boolean {
        val childPath  = child.canonicalPath
        val parentPath = parent.canonicalPath
        return childPath == parentPath || childPath.startsWith(parentPath + File.separator)
    }

    const val MAX_EDIT_BYTES = 2L * 1024 * 1024   // 2 MB
}
