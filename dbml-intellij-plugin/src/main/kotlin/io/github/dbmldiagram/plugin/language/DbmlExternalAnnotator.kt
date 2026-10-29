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
import io.github.dbmldiagram.core.database.DatabaseSnapshot
import io.github.dbmldiagram.core.database.PostgresSchemaDiff
import io.github.dbmldiagram.plugin.database.DatabaseComparisonService

data class DbmlAnnotationInput(val text: String,val snapshot: DatabaseSnapshot?=null,val defaultSchema: String="public")

class DbmlExternalAnnotator : ExternalAnnotator<DbmlAnnotationInput, List<DbmlParseError>>() {
    override fun collectInformation(file: PsiFile): DbmlAnnotationInput? {
        if(file.language!=DbmlLanguage) return null
        val session=file.virtualFile?.let { DatabaseComparisonService.get(file.project).session(it) }
        return DbmlAnnotationInput(file.text,session?.snapshot,session?.defaultSchema ?: "public")
    }

    override fun doAnnotate(collectedInfo: DbmlAnnotationInput): List<DbmlParseError> {
        val parsed=TolerantDbmlParser().parse(collectedInfo.text)
        val snapshot=collectedInfo.snapshot
        if(parsed.hasErrors || parsed.schema==null || snapshot==null) return parsed.errors
        val differences=PostgresSchemaDiff().compare(parsed.schema!!,snapshot,collectedInfo.defaultSchema).differences
        return parsed.errors + differences.map { difference ->
            val range=difference.range
            DbmlParseError("Database drift: ${difference.objectName}: ${difference.message}",range?.line ?: 1,range?.column ?: 1,
                DbmlParseSeverity.WARNING,range?.endLine ?: 1,range?.endColumn ?: 2)
        }
    }

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
