package io.github.dbmldiagram.plugin.language

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.dbmldiagram.core.model.DbmlParseError
import io.github.dbmldiagram.core.model.DbmlParseSeverity
import io.github.dbmldiagram.core.parser.TolerantDbmlParser

class DbmlExternalAnnotator : ExternalAnnotator<String, List<DbmlParseError>>() {
    override fun collectInformation(file: PsiFile): String? =
        if (file.language == DbmlLanguage) file.text else null

    override fun doAnnotate(collectedInfo: String): List<DbmlParseError> =
        TolerantDbmlParser().parse(collectedInfo).errors

    override fun apply(file: PsiFile, annotationResult: List<DbmlParseError>, holder: AnnotationHolder) {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        annotationResult.forEach { error ->
            val range = range(document, error) ?: return@forEach
            val severity = if (error.severity == DbmlParseSeverity.ERROR) HighlightSeverity.ERROR else HighlightSeverity.WARNING
            holder.newAnnotation(severity, error.message).range(range).create()
        }
    }

    private fun range(document: Document, error: DbmlParseError): TextRange? {
        if (document.textLength == 0) return null
        fun offset(line: Int, column: Int): Int {
            val index = (line - 1).coerceIn(0, document.lineCount - 1)
            return (document.getLineStartOffset(index) + (column - 1).coerceAtLeast(0))
                .coerceAtMost(document.getLineEndOffset(index))
        }
        val start = offset(error.line, error.column).coerceAtMost(document.textLength - 1)
        val end = offset(error.endLine, error.endColumn).coerceIn(start + 1, document.textLength)
        return TextRange(start, end)
    }
}
