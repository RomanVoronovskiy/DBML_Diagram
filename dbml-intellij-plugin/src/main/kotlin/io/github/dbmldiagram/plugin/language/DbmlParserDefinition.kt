package io.github.dbmldiagram.plugin.language

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/** A flat PSI file enables the daemon's DBML annotator; semantic parsing lives in dbml-core. */
class DbmlParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = DbmlSyntaxHighlighter().highlightingLexer
    override fun createParser(project: Project?) = PsiParser { root, builder ->
        val file = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        file.done(root)
        builder.treeBuilt
    }
    override fun getFileNodeType() = FILE
    override fun getWhitespaceTokens() = TokenSet.EMPTY
    override fun getCommentTokens() = TokenSet.EMPTY
    override fun getStringLiteralElements() = TokenSet.EMPTY
    override fun createElement(node: ASTNode) = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider) = object : PsiFileBase(viewProvider, DbmlLanguage) {
        override fun getFileType() = viewProvider.virtualFile.fileType
    }

    companion object {
        private val FILE = IFileElementType(DbmlLanguage)
    }
}
