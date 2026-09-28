package io.github.dbmldiagram.plugin.language

import com.intellij.lang.Commenter

class DbmlCommenter : Commenter {
    override fun getLineCommentPrefix() = "//"
    override fun getBlockCommentPrefix() = "/*"
    override fun getBlockCommentSuffix() = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}
