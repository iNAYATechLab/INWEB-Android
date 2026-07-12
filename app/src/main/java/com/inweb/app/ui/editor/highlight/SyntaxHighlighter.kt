package com.inweb.app.ui.editor.highlight

import android.text.Editable
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import com.inweb.app.ui.editor.themes.EditorTheme

/**
 * Applies syntax colouring to an [Editable] in-place using the given [Language]
 * and [EditorTheme]. Efficient enough for files up to ~500 KB (the editor
 * caps at 2 MB anyway).
 *
 * Strategy:
 *   1. Strip previous ForegroundColorSpans.
 *   2. Walk the rule list. For each rule, find all non-overlapping matches
 *      that don't intersect a *previously coloured* range (earlier rules win).
 *   3. Set a ForegroundColorSpan for each accepted match.
 *
 * The overlap check keeps comments/strings from being re-tokenised as
 * keywords when a keyword text happens to appear inside them.
 */
class SyntaxHighlighter(
    private var language: Language,
    private var theme: EditorTheme
) {

    fun updateLanguage(lang: Language) { language = lang }
    fun updateTheme(t: EditorTheme)    { theme = t }

    /**
     * Recompute all spans for the text in [editable].
     * Call this from onTextChanged (debounced) or whenever theme/language changes.
     */
    fun apply(editable: Editable) {
        // 1. Clear old colour spans (leave selection/other spans intact).
        val old = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        for (s in old) editable.removeSpan(s)

        if (language.rules.isEmpty() || editable.isEmpty()) return

        val text = editable.toString()
        val n = text.length
        // Bitmask: has any earlier rule painted this character?
        val painted = BooleanArray(n)

        for (rule in language.rules) {
            val m = rule.pattern.matcher(text)
            val color = colorFor(rule.kind)
            while (m.find()) {
                val start = m.start()
                val end   = m.end()
                if (start >= end) continue
                if (rangeOverlaps(painted, start, end)) continue
                editable.setSpan(
                    ForegroundColorSpan(color),
                    start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                for (i in start until end) painted[i] = true
            }
        }
    }

    private fun rangeOverlaps(painted: BooleanArray, from: Int, to: Int): Boolean {
        for (i in from until to) if (painted[i]) return true
        return false
    }

    private fun colorFor(kind: TokenKind): Int = when (kind) {
        TokenKind.KEYWORD      -> theme.keyword
        TokenKind.STRING       -> theme.string
        TokenKind.NUMBER       -> theme.number
        TokenKind.COMMENT      -> theme.comment
        TokenKind.OPERATOR     -> theme.operator
        TokenKind.VARIABLE     -> theme.variable
        TokenKind.FUNCTION     -> theme.function
        TokenKind.TAG          -> theme.tag
        TokenKind.ATTRIBUTE    -> theme.attribute
        TokenKind.PROPERTY_KEY -> theme.propertyKey
        TokenKind.ERROR        -> theme.error
        TokenKind.DEFAULT      -> theme.defaultText
    }
}
