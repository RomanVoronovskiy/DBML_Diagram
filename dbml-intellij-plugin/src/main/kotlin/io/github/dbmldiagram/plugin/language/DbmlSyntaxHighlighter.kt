package io.github.dbmldiagram.plugin.language

import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.IElementType
import com.intellij.openapi.vfs.VirtualFile

private val KEYWORD = IElementType("DBML_KEYWORD", DbmlLanguage)
private val TYPE = IElementType("DBML_TYPE", DbmlLanguage)
private val STRING = IElementType("DBML_STRING", DbmlLanguage)
private val COMMENT = IElementType("DBML_COMMENT", DbmlLanguage)
private val BAD = IElementType("DBML_BAD", DbmlLanguage)
private val IDENTIFIER = IElementType("DBML_IDENTIFIER", DbmlLanguage)

private val KEYWORD_KEY = TextAttributesKey.createTextAttributesKey("DBML_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
private val TYPE_KEY = TextAttributesKey.createTextAttributesKey("DBML_TYPE", DefaultLanguageHighlighterColors.CLASS_NAME)
private val STRING_KEY = TextAttributesKey.createTextAttributesKey("DBML_STRING", DefaultLanguageHighlighterColors.STRING)
private val COMMENT_KEY = TextAttributesKey.createTextAttributesKey("DBML_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
private val BAD_KEY = TextAttributesKey.createTextAttributesKey("DBML_BAD", HighlighterColors.BAD_CHARACTER)
private val EMPTY_KEYS = emptyArray<TextAttributesKey>()

class DbmlSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = DbmlSyntaxHighlighter()
}

class DbmlSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = DbmlHighlightingLexer()
    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        KEYWORD -> arrayOf(KEYWORD_KEY)
        TYPE -> arrayOf(TYPE_KEY)
        STRING -> arrayOf(STRING_KEY)
        COMMENT -> arrayOf(COMMENT_KEY)
        BAD -> arrayOf(BAD_KEY)
        else -> EMPTY_KEYS
    }
}

private class DbmlHighlightingLexer : LexerBase() {
    private lateinit var buffer: CharSequence
    private var end = 0
    private var start = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private val keywords = setOf("project", "table", "enum", "ref", "indexes", "note", "as")
    private val types = setOf("uuid", "bigint", "integer", "varchar", "text", "boolean", "double", "real", "decimal", "numeric", "timestamp", "timestamptz")

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer; end = endOffset; start = startOffset; locate()
    }
    override fun getState() = 0
    override fun getTokenType() = tokenType
    override fun getTokenStart() = start
    override fun getTokenEnd() = tokenEnd
    override fun advance() { start = tokenEnd; locate() }
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = end

    private fun locate() {
        if (start >= end) { tokenType = null; tokenEnd = end; return }
        val c = buffer[start]
        when {
            c.isWhitespace() -> { tokenEnd = start + 1; while (tokenEnd < end && buffer[tokenEnd].isWhitespace()) tokenEnd++; tokenType = IDENTIFIER }
            c == '/' && start + 1 < end && buffer[start + 1] == '/' -> { tokenEnd = start + 2; while (tokenEnd < end && buffer[tokenEnd] != '\n') tokenEnd++; tokenType = COMMENT }
            c == '/' && start + 1 < end && buffer[start + 1] == '*' -> { tokenEnd = start + 2; while (tokenEnd + 1 < end && !(buffer[tokenEnd] == '*' && buffer[tokenEnd + 1] == '/')) tokenEnd++; tokenEnd = (tokenEnd + 2).coerceAtMost(end); tokenType = COMMENT }
            c == '\'' || c == '"' || c == '`' -> { val quote = c; tokenEnd = start + 1; while (tokenEnd < end && buffer[tokenEnd] != quote) { if (buffer[tokenEnd] == '\\') tokenEnd++; tokenEnd++ }; tokenEnd = (tokenEnd + 1).coerceAtMost(end); tokenType = STRING }
            c.isLetterOrDigit() || c == '_' -> { tokenEnd = start + 1; while (tokenEnd < end && (buffer[tokenEnd].isLetterOrDigit() || buffer[tokenEnd] == '_')) tokenEnd++; val word = buffer.subSequence(start, tokenEnd).toString().lowercase(); tokenType = when (word) { in keywords -> KEYWORD; in types -> TYPE; else -> IDENTIFIER } }
            c.code < 32 -> { tokenEnd = start + 1; tokenType = BAD }
            else -> { tokenEnd = start + 1; tokenType = IDENTIFIER }
        }
    }
}
