package com.inweb.app.ui.editor.highlight

import java.util.Locale
import java.util.regex.Pattern

/**
 * Describes how to lex a source file into coloured tokens.
 *
 * Each [rule] is a compiled regex + a [TokenKind] the highlighter maps to
 * a colour from [com.inweb.app.ui.editor.themes.EditorTheme].
 *
 * Order matters — earlier rules win (put comments/strings before keywords).
 */
enum class TokenKind {
    KEYWORD, STRING, NUMBER, COMMENT, OPERATOR, VARIABLE,
    FUNCTION, TAG, ATTRIBUTE, PROPERTY_KEY, ERROR, DEFAULT
}

data class Rule(val pattern: Pattern, val kind: TokenKind)

data class Language(val id: String, val displayName: String, val rules: List<Rule>) {

    companion object {

        /** Detect language from file extension. Falls back to PLAIN. */
        fun fromFilename(name: String): Language {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (ext) {
                "php", "phtml"            -> PHP
                "html", "htm"             -> HTML
                "css"                     -> CSS
                "js", "mjs"               -> JAVASCRIPT
                "json"                    -> JSON
                "sql"                     -> SQL
                "sh", "bash"              -> SHELL
                "xml", "svg"              -> XML
                "yaml", "yml"             -> YAML
                "md", "markdown"          -> MARKDOWN
                "conf", "ini", "env"      -> INI
                else                      -> PLAIN
            }
        }

        /* ------------------------------------------------------------ */
        /*  Language grammars                                           */
        /* ------------------------------------------------------------ */

        private fun compile(regex: String, kind: TokenKind, flags: Int = 0) =
            Rule(Pattern.compile(regex, flags), kind)

        val PLAIN = Language("plain", "Plain text", emptyList())

        val PHP: Language = Language("php", "PHP", listOf(
            // Comments must come before everything.
            compile("""/\*[\s\S]*?\*/""", TokenKind.COMMENT),
            compile("""//[^\n]*""",       TokenKind.COMMENT),
            compile("""#[^\n]*""",        TokenKind.COMMENT),
            // Strings
            compile("""'(?:\\.|[^'\\])*'""", TokenKind.STRING),
            compile("""\"(?:\\.|[^\"\\])*\"""", TokenKind.STRING),
            // Variables
            compile("""\$[A-Za-z_][A-Za-z0-9_]*""", TokenKind.VARIABLE),
            // Numbers
            compile("""\b\d+(?:\.\d+)?\b""", TokenKind.NUMBER),
            // Keywords
            compile("""\b(?:abstract|and|array|as|break|callable|case|catch|class|clone|const|continue|declare|default|do|echo|else|elseif|empty|enddeclare|endfor|endforeach|endif|endswitch|endwhile|extends|final|finally|fn|for|foreach|function|global|goto|if|implements|include|include_once|instanceof|insteadof|interface|isset|list|match|namespace|new|null|or|print|private|protected|public|require|require_once|return|self|static|switch|this|throw|trait|true|false|try|unset|use|var|while|xor|yield)\b""", TokenKind.KEYWORD),
            // Function calls (identifier followed by paren)
            compile("""\b([A-Za-z_][A-Za-z0-9_]*)(?=\s*\()""", TokenKind.FUNCTION),
            // Operators
            compile("""[+\-*/%=<>!&|^~?:.]""", TokenKind.OPERATOR),
        ))

        val HTML: Language = Language("html", "HTML", listOf(
            compile("""<!--[\s\S]*?-->""", TokenKind.COMMENT),
            compile("""\"(?:[^\"\\]|\\.)*\"""", TokenKind.STRING),
            compile("""'(?:[^'\\]|\\.)*'""", TokenKind.STRING),
            // Tag names
            compile("""</?[a-zA-Z][a-zA-Z0-9\-]*""", TokenKind.TAG),
            compile(""">""", TokenKind.TAG),
            // Attribute names (word followed by =)
            compile("""[a-zA-Z\-]+(?==)""", TokenKind.ATTRIBUTE),
        ))

        val CSS: Language = Language("css", "CSS", listOf(
            compile("""/\*[\s\S]*?\*/""", TokenKind.COMMENT),
            compile("""\"(?:[^\"\\]|\\.)*\"""", TokenKind.STRING),
            compile("""'(?:[^'\\]|\\.)*'""", TokenKind.STRING),
            // Selectors starting with . or #
            compile("""[.#][A-Za-z_][A-Za-z0-9_\-]*""", TokenKind.KEYWORD),
            // Property names (word followed by colon at line start)
            compile("""[a-zA-Z\-]+(?=\s*:)""", TokenKind.PROPERTY_KEY),
            // Values with units
            compile("""\b\d+(?:\.\d+)?(?:px|em|rem|%|vh|vw|s|ms)?\b""", TokenKind.NUMBER),
            // Hex colors
            compile("""#[0-9a-fA-F]{3,8}\b""", TokenKind.NUMBER),
            compile("""[{};:,]""", TokenKind.OPERATOR),
        ))

        val JAVASCRIPT: Language = Language("js", "JavaScript", listOf(
            compile("""/\*[\s\S]*?\*/""", TokenKind.COMMENT),
            compile("""//[^\n]*""",       TokenKind.COMMENT),
            compile("""'(?:\\.|[^'\\])*'""", TokenKind.STRING),
            compile("""\"(?:\\.|[^\"\\])*\"""", TokenKind.STRING),
            compile("""`(?:\\.|[^`\\])*`""", TokenKind.STRING),
            compile("""\b\d+(?:\.\d+)?\b""", TokenKind.NUMBER),
            compile("""\b(?:async|await|break|case|catch|class|const|continue|debugger|default|delete|do|else|export|extends|false|finally|for|from|function|if|import|in|instanceof|let|new|null|of|return|static|super|switch|this|throw|true|try|typeof|undefined|var|void|while|with|yield)\b""", TokenKind.KEYWORD),
            compile("""\b([A-Za-z_$][A-Za-z0-9_$]*)(?=\s*\()""", TokenKind.FUNCTION),
            compile("""[+\-*/%=<>!&|^~?:.]""", TokenKind.OPERATOR),
        ))

        val JSON: Language = Language("json", "JSON", listOf(
            compile("""\"(?:[^\"\\]|\\.)*\"(?=\s*:)""", TokenKind.PROPERTY_KEY),
            compile("""\"(?:[^\"\\]|\\.)*\"""", TokenKind.STRING),
            compile("""\b\d+(?:\.\d+)?(?:[eE][+\-]?\d+)?\b""", TokenKind.NUMBER),
            compile("""\b(?:true|false|null)\b""", TokenKind.KEYWORD),
            compile("""[{}\[\],:]""", TokenKind.OPERATOR),
        ))

        val SQL: Language = Language("sql", "SQL", listOf(
            compile("""--[^\n]*""", TokenKind.COMMENT),
            compile("""/\*[\s\S]*?\*/""", TokenKind.COMMENT),
            compile("""'(?:[^'\\]|\\.)*'""", TokenKind.STRING),
            compile("""\"(?:[^\"\\]|\\.)*\"""", TokenKind.STRING),
            compile("""\b\d+(?:\.\d+)?\b""", TokenKind.NUMBER),
            compile("""\b(?:SELECT|FROM|WHERE|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|TABLE|DATABASE|DROP|ALTER|ADD|COLUMN|INDEX|PRIMARY|KEY|FOREIGN|REFERENCES|JOIN|LEFT|RIGHT|INNER|OUTER|ON|AS|AND|OR|NOT|NULL|IS|IN|BETWEEN|LIKE|ORDER|BY|GROUP|HAVING|LIMIT|OFFSET|UNION|DISTINCT|COUNT|SUM|AVG|MIN|MAX|CASE|WHEN|THEN|ELSE|END|IF|EXISTS|BEGIN|COMMIT|ROLLBACK|TRANSACTION|GRANT|REVOKE|USE|SHOW|DESCRIBE|EXPLAIN|TRUE|FALSE|VARCHAR|INT|INTEGER|BIGINT|TEXT|BLOB|TIMESTAMP|DATETIME|DATE|TIME|FLOAT|DOUBLE|DECIMAL|BOOLEAN|AUTO_INCREMENT|DEFAULT|UNIQUE|CHARACTER|COLLATE)\b""", TokenKind.KEYWORD, Pattern.CASE_INSENSITIVE),
            compile("""[+\-*/%=<>!&|^~?:.,;()]""", TokenKind.OPERATOR),
        ))

        val SHELL: Language = Language("sh", "Shell", listOf(
            compile("""#[^\n]*""", TokenKind.COMMENT),
            compile("""'(?:[^'\\]|\\.)*'""", TokenKind.STRING),
            compile("""\"(?:[^\"\\]|\\.)*\"""", TokenKind.STRING),
            compile("""\$\{?[A-Za-z_][A-Za-z0-9_]*\}?""", TokenKind.VARIABLE),
            compile("""\b(?:if|then|else|elif|fi|for|do|done|while|case|esac|function|return|export|source|echo|read|test|true|false)\b""", TokenKind.KEYWORD),
            compile("""\b\d+\b""", TokenKind.NUMBER),
        ))

        val XML: Language = Language("xml", "XML", listOf(
            compile("""<!--[\s\S]*?-->""", TokenKind.COMMENT),
            compile("""<!\[CDATA\[[\s\S]*?]]>""", TokenKind.STRING),
            compile("""\"(?:[^\"\\]|\\.)*\"""", TokenKind.STRING),
            compile("""'(?:[^'\\]|\\.)*'""", TokenKind.STRING),
            compile("""</?[a-zA-Z][a-zA-Z0-9\-:]*""", TokenKind.TAG),
            compile(""">|/>""", TokenKind.TAG),
            compile("""[a-zA-Z\-:]+(?==)""", TokenKind.ATTRIBUTE),
        ))

        val YAML: Language = Language("yaml", "YAML", listOf(
            compile("""#[^\n]*""", TokenKind.COMMENT),
            compile("""'(?:[^'\\]|\\.)*'""", TokenKind.STRING),
            compile("""\"(?:[^\"\\]|\\.)*\"""", TokenKind.STRING),
            compile("""^\s*[A-Za-z_][A-Za-z0-9_\-]*(?=\s*:)""", TokenKind.PROPERTY_KEY, Pattern.MULTILINE),
            compile("""\b\d+(?:\.\d+)?\b""", TokenKind.NUMBER),
            compile("""\b(?:true|false|null|yes|no)\b""", TokenKind.KEYWORD),
        ))

        val MARKDOWN: Language = Language("md", "Markdown", listOf(
            compile("""^#{1,6}\s.*$""", TokenKind.KEYWORD, Pattern.MULTILINE),
            compile("""\*\*[^*]+\*\*""", TokenKind.FUNCTION),
            compile("""\*[^*]+\*""",     TokenKind.STRING),
            compile("""`[^`]+`""",       TokenKind.STRING),
            compile("""```[\s\S]*?```""",TokenKind.STRING),
            compile("""\[[^\]]+\]\([^)]+\)""", TokenKind.TAG),
        ))

        val INI: Language = Language("ini", "INI / Config", listOf(
            compile("""[#;][^\n]*""", TokenKind.COMMENT),
            compile("""^\s*\[[^\]]+\]""", TokenKind.KEYWORD, Pattern.MULTILINE),
            compile("""^\s*[A-Za-z_][A-Za-z0-9_.\-]*(?=\s*=)""", TokenKind.PROPERTY_KEY, Pattern.MULTILINE),
            compile("""'(?:[^'\\]|\\.)*'""", TokenKind.STRING),
            compile("""\"(?:[^\"\\]|\\.)*\"""", TokenKind.STRING),
            compile("""\b\d+(?:\.\d+)?\b""", TokenKind.NUMBER),
        ))
    }
}
