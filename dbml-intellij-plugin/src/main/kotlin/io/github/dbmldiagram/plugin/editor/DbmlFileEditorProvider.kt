package io.github.dbmldiagram.plugin.editor

import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class DbmlFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file.extension.equals("dbml", ignoreCase = true)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val preview = DbmlPreviewEditor(project, file)
        return TextEditorWithPreview(textEditor, preview, "DBML Diagram", TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
    }

    override fun getEditorTypeId() = "dbml-diagram-editor"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
