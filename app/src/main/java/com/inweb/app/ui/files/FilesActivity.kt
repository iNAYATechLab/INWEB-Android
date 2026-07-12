package com.inweb.app.ui.files

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.inweb.app.Constants
import com.inweb.app.R
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.inweb.app.ui.editor.EditorActivity
import com.inweb.app.util.FileUtils
import java.io.File

/**
 * Simple, safe file manager rooted at the app's public web-root directory.
 * Users can navigate, create, rename, delete, share and edit files.
 * Path traversal outside the web root is prevented via [FileUtils.isInside].
 */
class FilesActivity : AppCompatActivity() {

    private lateinit var rootDir: File
    private lateinit var currentDir: File
    private lateinit var pathText: TextView
    private lateinit var emptyText: TextView
    private lateinit var recycler: RecyclerView
    private val adapter = FileAdapter(::onEntryClick, ::onEntryLongClick)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        PageScaffold.setup(this, getString(R.string.btn_files)) {
            onBackPressedDispatcher.onBackPressed()
        }
        PageScaffold.setActionIcon(this, R.drawable.ic_home) {
            currentDir = rootDir; refresh()
        }
        PageScaffold.setSecondaryActionIcon(this, R.drawable.ic_refresh) { refresh() }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)

        pathText  = findViewById(R.id.pathText)
        emptyText = findViewById(R.id.emptyText)
        recycler  = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val extRoot = getExternalFilesDir(null) ?: filesDir
        rootDir = File(extRoot, Constants.WWW_DIR).apply { mkdirs() }
        currentDir = rootDir

        findViewById<View>(R.id.fabNew).setOnClickListener { showNewMenu() }
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentDir.canonicalPath != rootDir.canonicalPath) {
            currentDir = currentDir.parentFile ?: rootDir
            refresh()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /* --------------------------------------------------------------- */
    /* Listing                                                          */
    /* --------------------------------------------------------------- */

    private fun refresh() {
        pathText.text = relativeLabel(currentDir)

        val files = currentDir.listFiles()?.toList().orEmpty()
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })

        adapter.submit(files)
        emptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun relativeLabel(f: File): String {
        val rel = f.canonicalPath.removePrefix(rootDir.canonicalPath).ifEmpty { "/" }
        return "www$rel"
    }

    /* --------------------------------------------------------------- */
    /* Actions                                                          */
    /* --------------------------------------------------------------- */

    private fun onEntryClick(f: File) {
        if (!FileUtils.isInside(f, rootDir)) return
        when {
            f.isDirectory     -> { currentDir = f; refresh() }
            FileUtils.isEditable(f) -> openInEditor(f)
            else              -> openWithSystem(f)
        }
    }

    private fun onEntryLongClick(f: File): Boolean {
        val options = buildList {
            if (f.isFile && FileUtils.isEditable(f)) add("Edit")
            if (f.isFile && FileUtils.isEditable(f)) add("Live Code + Preview")
            if (f.isFile) add("Preview in browser")
            add("Rename")
            add("Delete")
            if (f.isFile) add("Share")
            if (f.isFile) add("Open with…")
        }
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(options.toTypedArray()) { _, i ->
                when (options[i]) {
                    "Edit"                 -> openInEditor(f)
                    "Live Code + Preview"  -> com.inweb.app.ui.livecode.LiveCodeActivity.open(this, f)
                    "Preview in browser"   -> previewFile(f)
                    "Rename"               -> promptRename(f)
                    "Delete"               -> confirmDelete(f)
                    "Share"                -> shareFile(f)
                    "Open with…"           -> openWithSystem(f)
                }
            }.show()
        return true
    }

    private fun previewFile(f: File) {
        val rel = f.canonicalPath.removePrefix(rootDir.canonicalPath).replace('\\', '/')
        val safeRel = if (rel.startsWith("/")) rel else "/$rel"
        val port = com.inweb.app.util.Prefs(this).httpPort
        com.inweb.app.ui.preview.PreviewActivity.open(this, "http://localhost:$port$safeRel")
    }

    private fun showNewMenu() {
        val options = arrayOf("New file", "New folder")
        AlertDialog.Builder(this)
            .setTitle("Create in ${relativeLabel(currentDir)}")
            .setItems(options) { _, i ->
                if (i == 0) promptNew(false) else promptNew(true)
            }.show()
    }

    private fun promptNew(isDir: Boolean) {
        val input = EditText(this).apply {
            hint = if (isDir) "folder name" else "file name (e.g. hello.php)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(if (isDir) "New folder" else "New file")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.contains('/') || name.contains('\\')) {
                    toast("Invalid name"); return@setPositiveButton
                }
                val target = File(currentDir, name)
                if (!FileUtils.isInside(target, rootDir)) { toast("Illegal path"); return@setPositiveButton }
                if (target.exists()) { toast("Already exists"); return@setPositiveButton }

                val ok = if (isDir) target.mkdirs() else runCatching { target.createNewFile() }.getOrDefault(false)
                if (!ok) { toast("Create failed"); return@setPositiveButton }

                refresh()
                if (!isDir && FileUtils.isEditable(target)) openInEditor(target)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptRename(f: File) {
        val input = EditText(this).apply { setText(f.name) }
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.contains('/')) { toast("Invalid name"); return@setPositiveButton }
                val target = File(f.parentFile, name)
                if (!FileUtils.isInside(target, rootDir)) { toast("Illegal path"); return@setPositiveButton }
                if (target.exists()) { toast("Already exists"); return@setPositiveButton }
                if (!f.renameTo(target)) toast("Rename failed") else refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${f.name}?")
            .setMessage(if (f.isDirectory) "This will delete the folder and everything inside." else "This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                if (!ok) toast("Delete failed") else refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openInEditor(f: File) {
        startActivity(Intent(this, EditorActivity::class.java)
            .putExtra(EditorActivity.EXTRA_PATH, f.absolutePath))
    }

    private fun openWithSystem(f: File) {
        try {
            val authority = "$packageName.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, f)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, FileUtils.mimeType(f))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Open with"))
        } catch (t: Throwable) {
            toast("No app can open this file")
        }
    }

    private fun shareFile(f: File) {
        try {
            val authority = "$packageName.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = FileUtils.mimeType(f)
                putExtra(Intent.EXTRA_STREAM, uri as Uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Share ${f.name}"))
        } catch (t: Throwable) { toast("Share failed") }
    }

    override fun onResume() {
        super.onResume()
        refresh()  // catch changes made externally or by editor
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/* ------------------------------------------------------------------ */
/* Adapter                                                             */
/* ------------------------------------------------------------------ */

private class FileAdapter(
    private val onClick: (File) -> Unit,
    private val onLongClick: (File) -> Boolean
) : RecyclerView.Adapter<FileAdapter.VH>() {

    private val items = mutableListOf<File>()

    fun submit(list: List<File>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val icon: ImageView = v.findViewById(R.id.icon)
        private val name: TextView  = v.findViewById(R.id.name)
        private val meta: TextView  = v.findViewById(R.id.meta)
        fun bind(f: File) {
            name.text = f.name
            if (f.isDirectory) {
                icon.setImageResource(R.drawable.ic_folder)
                val kids = f.list()?.size ?: 0
                meta.text = "$kids items · ${FileUtils.humanTime(f.lastModified())}"
            } else {
                icon.setImageResource(R.drawable.ic_file)
                meta.text = "${FileUtils.humanSize(f.length())} · ${FileUtils.humanTime(f.lastModified())}"
            }
            itemView.setOnClickListener { onClick(f) }
            itemView.setOnLongClickListener { onLongClick(f) }
        }
    }
}
