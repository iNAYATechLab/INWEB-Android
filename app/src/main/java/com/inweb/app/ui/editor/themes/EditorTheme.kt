package com.inweb.app.ui.editor.themes

import android.graphics.Color

/**
 * A code-editor color theme. All colours are ARGB ints so they can be
 * applied directly to SpannableStringBuilder / Paint.
 */
data class EditorTheme(
    val id: String,
    val displayName: String,

    // Chrome
    val background: Int,
    val gutterBg:   Int,
    val gutterFg:   Int,
    val cursor:     Int,
    val selection:  Int,
    val currentLine: Int,

    // Token colours
    val defaultText: Int,
    val keyword:     Int,   // if, else, function, class, ...
    val string:      Int,   // "hello", 'world'
    val number:      Int,   // 42, 3.14
    val comment:     Int,   // // # /* */
    val operator:    Int,   // + - * / = < >
    val variable:    Int,   // $foo (PHP)
    val function:    Int,   // foo() calls
    val tag:         Int,   // <div>
    val attribute:   Int,   // class="foo"
    val propertyKey: Int,   // JSON keys, CSS properties
    val error:       Int,   // syntax errors
) {
    companion object {
        val INWEB_DARK = EditorTheme(
            id = "inweb_dark", displayName = "INWEB Dark",
            background = 0xFF0B1410.toInt(),
            gutterBg   = 0xFF132821.toInt(),
            gutterFg   = 0xFF4B7C6D.toInt(),
            cursor     = 0xFF14B8A6.toInt(),
            selection  = 0x5514B8A6.toInt(),
            currentLine= 0x1A14B8A6.toInt(),
            defaultText= 0xFFE7F0EC.toInt(),
            keyword    = 0xFF14B8A6.toInt(),
            string     = 0xFFB6F0B6.toInt(),
            number     = 0xFFFCD34D.toInt(),
            comment    = 0xFF4B7C6D.toInt(),
            operator   = 0xFFE7F0EC.toInt(),
            variable   = 0xFFF59E0B.toInt(),
            function   = 0xFF7DD3FC.toInt(),
            tag        = 0xFF14B8A6.toInt(),
            attribute  = 0xFFF59E0B.toInt(),
            propertyKey= 0xFF7DD3FC.toInt(),
            error      = 0xFFEF4444.toInt(),
        )

        val DRACULA = EditorTheme(
            id = "dracula", displayName = "Dracula",
            background = 0xFF282A36.toInt(),
            gutterBg   = 0xFF21222C.toInt(),
            gutterFg   = 0xFF6272A4.toInt(),
            cursor     = 0xFFF8F8F2.toInt(),
            selection  = 0x55BD93F9.toInt(),
            currentLine= 0x1AFFFFFF.toInt(),
            defaultText= 0xFFF8F8F2.toInt(),
            keyword    = 0xFFFF79C6.toInt(),
            string     = 0xFFF1FA8C.toInt(),
            number     = 0xFFBD93F9.toInt(),
            comment    = 0xFF6272A4.toInt(),
            operator   = 0xFFFF79C6.toInt(),
            variable   = 0xFF8BE9FD.toInt(),
            function   = 0xFF50FA7B.toInt(),
            tag        = 0xFFFF79C6.toInt(),
            attribute  = 0xFF50FA7B.toInt(),
            propertyKey= 0xFF8BE9FD.toInt(),
            error      = 0xFFFF5555.toInt(),
        )

        val MONOKAI = EditorTheme(
            id = "monokai", displayName = "Monokai",
            background = 0xFF272822.toInt(),
            gutterBg   = 0xFF1E1F1C.toInt(),
            gutterFg   = 0xFF75715E.toInt(),
            cursor     = 0xFFF8F8F0.toInt(),
            selection  = 0x5549483E.toInt(),
            currentLine= 0x1AFFFFFF.toInt(),
            defaultText= 0xFFF8F8F2.toInt(),
            keyword    = 0xFFF92672.toInt(),
            string     = 0xFFE6DB74.toInt(),
            number     = 0xFFAE81FF.toInt(),
            comment    = 0xFF75715E.toInt(),
            operator   = 0xFFF92672.toInt(),
            variable   = 0xFF66D9EF.toInt(),
            function   = 0xFFA6E22E.toInt(),
            tag        = 0xFFF92672.toInt(),
            attribute  = 0xFFA6E22E.toInt(),
            propertyKey= 0xFF66D9EF.toInt(),
            error      = 0xFFFF5555.toInt(),
        )

        val SOLARIZED_DARK = EditorTheme(
            id = "solarized_dark", displayName = "Solarized Dark",
            background = 0xFF002B36.toInt(),
            gutterBg   = 0xFF073642.toInt(),
            gutterFg   = 0xFF586E75.toInt(),
            cursor     = 0xFF93A1A1.toInt(),
            selection  = 0x55268BD2.toInt(),
            currentLine= 0x1AFFFFFF.toInt(),
            defaultText= 0xFF839496.toInt(),
            keyword    = 0xFF859900.toInt(),
            string     = 0xFF2AA198.toInt(),
            number     = 0xFFD33682.toInt(),
            comment    = 0xFF586E75.toInt(),
            operator   = 0xFF93A1A1.toInt(),
            variable   = 0xFF268BD2.toInt(),
            function   = 0xFF268BD2.toInt(),
            tag        = 0xFF268BD2.toInt(),
            attribute  = 0xFFB58900.toInt(),
            propertyKey= 0xFFB58900.toInt(),
            error      = 0xFFDC322F.toInt(),
        )

        val ALL = listOf(INWEB_DARK, DRACULA, MONOKAI, SOLARIZED_DARK)

        fun byId(id: String?): EditorTheme = ALL.firstOrNull { it.id == id } ?: INWEB_DARK
    }
}
