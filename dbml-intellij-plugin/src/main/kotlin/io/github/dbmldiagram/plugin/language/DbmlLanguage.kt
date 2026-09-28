package io.github.dbmldiagram.plugin.language

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object DbmlLanguage : Language("DBML")

class DbmlFileType : LanguageFileType(DbmlLanguage) {
    override fun getName() = "DBML"
    override fun getDescription() = "Database Markup Language"
    override fun getDefaultExtension() = "dbml"
    override fun getIcon(): Icon = IconLoader.getIcon("/icons/dbml.svg", DbmlFileType::class.java)
}
