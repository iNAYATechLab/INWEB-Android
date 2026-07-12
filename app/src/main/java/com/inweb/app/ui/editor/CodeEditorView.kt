package com.inweb.app.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText
import com.inweb.app.ui.editor.themes.EditorTheme

/**
 * A monospace [AppCompatEditText] that draws a left-gutter with line numbers
 * and highlights the current line. Works in tandem with [SyntaxHighlighter]
 * (which is applied via a TextWatcher from the host activity).
 *
 * Deliberately kept as a single lightweight subclass — no extra libraries —
 * so INWEB stays under a few MB.
 */
class CodeEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyle) {

    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val gutterBgPaint = Paint()
    private val currentLinePaint = Paint()

    private var gutterWidthPx: Int = 0
    private var gutterPadPx: Int = dp(6)
    private var gutterFgColor: Int = 0xFF888888.toInt()
    private var gutterBgColor: Int = 0xFF222222.toInt()
    private var currentLineColor: Int = 0x11FFFFFF

    private val tmpRect = Rect()

    init {
        typeface = Typeface.MONOSPACE
        setLineSpacing(0f, 1.15f)
        includeFontPadding = false
        // Padding-left will be updated dynamically as line count changes.
        setPadding(gutterWidthPx + gutterPadPx * 2, paddingTop, paddingRight, paddingBottom)
    }

    /** Applies theme chrome (background, gutter colours). Called from the activity. */
    fun applyTheme(theme: EditorTheme) {
        setBackgroundColor(theme.background)
        setTextColor(theme.defaultText)
        highlightColor = theme.selection
        gutterBgColor = theme.gutterBg
        gutterFgColor = theme.gutterFg
        currentLineColor = theme.currentLine
        gutterPaint.color = gutterFgColor
        gutterPaint.textSize = textSize * 0.85f
        gutterBgPaint.color = gutterBgColor
        currentLinePaint.color = currentLineColor
        recomputeGutterWidth()
        invalidate()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, after: Int) {
        super.onTextChanged(text, start, before, after)
        recomputeGutterWidth()
    }

    override fun onDraw(canvas: Canvas) {
        val layout: Layout = layout ?: run { super.onDraw(canvas); return }
        val lineCount = layout.lineCount

        // 1) Highlight the caret's current line.
        val selLine = if (selectionStart >= 0) layout.getLineForOffset(selectionStart) else -1
        if (selLine >= 0) {
            val top = layout.getLineTop(selLine) + paddingTop
            val bottom = layout.getLineBottom(selLine) + paddingTop
            canvas.drawRect(
                scrollX.toFloat(), top.toFloat(),
                (scrollX + width).toFloat(), bottom.toFloat(),
                currentLinePaint
            )
        }

        // 2) Gutter background stripe.
        canvas.drawRect(
            scrollX.toFloat(), 0f,
            (scrollX + gutterWidthPx + gutterPadPx * 2).toFloat(),
            (scrollY + height).toFloat(),
            gutterBgPaint
        )

        // 3) Line numbers (only for visible range for perf).
        val fm = gutterPaint.fontMetricsInt
        val visibleTop = scrollY
        val visibleBottom = scrollY + height
        val topLine = layout.getLineForVertical(visibleTop - paddingTop).coerceAtLeast(0)
        val bottomLine = layout.getLineForVertical(visibleBottom - paddingTop).coerceAtMost(lineCount - 1)

        // We show 1-based line numbers, but Layout counts *wrapped* lines. For
        // a monospace editor with no line wrap this is 1:1. To handle wrap
        // gracefully, we only print a number on the *first* wrapped line of
        // each source line (identified by lineStart == paragraph start).
        for (line in topLine..bottomLine) {
            val lineStart = layout.getLineStart(line)
            val isFirstOfParagraph = lineStart == 0 || text!![lineStart - 1] == '\n'
            if (!isFirstOfParagraph) continue

            val sourceLine = countNewlines(text!!, 0, lineStart) + 1
            val label = sourceLine.toString()
            val yBaseline = layout.getLineBaseline(line) + paddingTop
            val xRight = scrollX + gutterWidthPx + gutterPadPx
            val textWidth = gutterPaint.measureText(label)
            canvas.drawText(label, xRight - textWidth, yBaseline.toFloat(), gutterPaint)
        }

        super.onDraw(canvas)
    }

    /* --------------------------------------------------------------- */

    private fun recomputeGutterWidth() {
        val lines = (text?.count { it == '\n' } ?: 0) + 1
        val digits = lines.toString().length.coerceAtLeast(2)
        val sample = "0".repeat(digits)
        val w = gutterPaint.measureText(sample).toInt()
        if (w != gutterWidthPx) {
            gutterWidthPx = w
            setPadding(gutterWidthPx + gutterPadPx * 2, paddingTop, paddingRight, paddingBottom)
        }
    }

    private fun countNewlines(cs: CharSequence, from: Int, to: Int): Int {
        var n = 0
        var i = from
        while (i < to) { if (cs[i] == '\n') n++; i++ }
        return n
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
