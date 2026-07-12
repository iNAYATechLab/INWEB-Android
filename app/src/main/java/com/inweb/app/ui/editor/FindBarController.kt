package com.inweb.app.ui.editor

import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Controls the Find & Replace toolbar overlay above the editor.
 *
 * - Highlights every occurrence in yellow with [BackgroundColorSpan]
 * - Prev / Next buttons cycle through matches
 * - Replace / Replace-All buttons update the source text
 * - "0 of 0" counter updates live
 */
class FindBarController(
    private val root: View,
    private val editor: EditText,
    private val findInput: EditText,
    private val replaceInput: EditText,
    private val counter: TextView,
    private val prevBtn: View,
    private val nextBtn: View,
    private val replaceBtn: View,
    private val replaceAllBtn: View,
    private val closeBtn: View
) {

    private var matches: List<IntRange> = emptyList()
    private var currentIndex: Int = -1

    private val highlightColor = 0x66FFEB3B          // translucent yellow
    private val activeHighlight = 0xAAFFEB3B.toInt() // stronger yellow

    init {
        prevBtn.setOnClickListener      { moveTo(currentIndex - 1) }
        nextBtn.setOnClickListener      { moveTo(currentIndex + 1) }
        replaceBtn.setOnClickListener   { replaceCurrent() }
        replaceAllBtn.setOnClickListener{ replaceAll() }
        closeBtn.setOnClickListener     { hide() }

        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { rescan() }
        })
    }

    fun show() {
        root.visibility = View.VISIBLE
        findInput.requestFocus()
        // If the user has selected text, pre-populate the find field with it.
        val start = editor.selectionStart
        val end   = editor.selectionEnd
        if (end > start) {
            val selected = editor.text.substring(start, end)
            if (selected.length in 1..80) findInput.setText(selected)
        }
        rescan()
    }

    fun hide() {
        root.visibility = View.GONE
        clearHighlights()
        matches = emptyList()
        currentIndex = -1
    }

    fun isVisible(): Boolean = root.visibility == View.VISIBLE

    /* --------------------------------------------------------------- */
    /*  Core                                                            */
    /* --------------------------------------------------------------- */

    private fun rescan() {
        clearHighlights()
        val needle = findInput.text.toString()
        if (needle.isEmpty()) {
            matches = emptyList(); currentIndex = -1
            updateCounter(); return
        }
        val haystack = editor.text.toString()
        val list = mutableListOf<IntRange>()
        var idx = 0
        while (idx <= haystack.length - needle.length) {
            val found = haystack.indexOf(needle, idx, ignoreCase = false)
            if (found < 0) break
            list += found until (found + needle.length)
            idx = found + needle.length
        }
        matches = list
        currentIndex = if (matches.isEmpty()) -1 else 0
        applyHighlights()
        updateCounter()
        if (currentIndex >= 0) scrollToMatch(currentIndex)
    }

    private fun applyHighlights() {
        val editable = editor.text ?: return
        matches.forEachIndexed { i, range ->
            val color = if (i == currentIndex) activeHighlight else highlightColor
            editable.setSpan(
                BackgroundColorSpan(color),
                range.first, range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun clearHighlights() {
        val editable = editor.text ?: return
        val spans = editable.getSpans(0, editable.length, BackgroundColorSpan::class.java)
        for (s in spans) editable.removeSpan(s)
    }

    private fun moveTo(index: Int) {
        if (matches.isEmpty()) return
        currentIndex = ((index % matches.size) + matches.size) % matches.size
        clearHighlights()
        applyHighlights()
        scrollToMatch(currentIndex)
        updateCounter()
    }

    private fun scrollToMatch(i: Int) {
        val range = matches[i]
        editor.setSelection(range.first, range.last + 1)
        editor.requestFocus()
    }

    private fun replaceCurrent() {
        if (currentIndex < 0 || matches.isEmpty()) return
        val range = matches[currentIndex]
        val replacement = replaceInput.text.toString()
        editor.text?.replace(range.first, range.last + 1, replacement)
        rescan()
    }

    private fun replaceAll() {
        if (matches.isEmpty()) return
        val needle = findInput.text.toString()
        val replacement = replaceInput.text.toString()
        if (needle.isEmpty()) return
        val text = editor.text?.toString() ?: return
        val replaced = text.replace(needle, replacement)
        val count = matches.size
        editor.setText(replaced)
        Toast.makeText(root.context, "Replaced $count occurrences", Toast.LENGTH_SHORT).show()
        rescan()
    }

    private fun updateCounter() {
        counter.text = if (matches.isEmpty()) "0 / 0"
                       else "${currentIndex + 1} / ${matches.size}"
    }
}
