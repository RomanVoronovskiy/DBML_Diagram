package io.github.dbmldiagram.plugin.editor

import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DbmlFileEditorProviderTest : BasePlatformTestCase() {
    fun testDbmlFileOpensWithTextAndPreviewEditorAndDisposes() {
        val file = myFixture.configureByText("schema.dbml", "Table users {\n  id uuid [pk]\n}").virtualFile
        val provider = DbmlFileEditorProvider()
        assertTrue(provider.accept(project, file))

        val editor = provider.createEditor(project, file)
        try {
            assertInstanceOf(editor, TextEditorWithPreview::class.java)
            assertTrue(editor.isValid)
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun testProviderRejectsOtherExtensions() {
        val file = myFixture.configureByText("schema.sql", "select 1").virtualFile
        assertFalse(DbmlFileEditorProvider().accept(project, file))
    }
}
