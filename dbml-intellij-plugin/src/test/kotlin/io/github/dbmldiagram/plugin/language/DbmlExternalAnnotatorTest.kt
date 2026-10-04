package io.github.dbmldiagram.plugin.language

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DbmlExternalAnnotatorTest : BasePlatformTestCase() {
    fun testInvalidRelationshipHighlightedAtReferenceAndClearsAfterEdit() {
        val source = """
            Table users { id uuid [pk] }
            Table orders { id uuid [pk] }
            Ref: orders.id > users.id
        """.trimIndent()
        val file = myFixture.configureByText("schema.dbml", source)
        assertEquals(DbmlLanguage, file.language)
        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        assertEquals(1, errors.size)
        assertTrue(errors.single().description.contains("Invalid 1:N"))
        assertEquals("orders.id > users.id", source.substring(errors.single().startOffset, errors.single().endOffset))

        myFixture.editor.caretModel.moveToOffset(source.indexOf('>'))
        myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_DELETE)
        myFixture.type('-')
        assertTrue(myFixture.doHighlighting().none { it.severity == HighlightSeverity.ERROR })
    }

    fun testInlineRelationshipHighlightedAndValidManyToManyAccepted() {
        myFixture.configureByText("inline.dbml", """
            Table users { id uuid [pk] }
            Table orders {
              id uuid [pk, ref: > users.id]
            }
        """.trimIndent())
        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        assertEquals(1, errors.size)
        assertEquals("ref: > users.id", myFixture.editor.document.text.substring(errors.single().startOffset, errors.single().endOffset))

        myFixture.configureByText("many.dbml", "Table users { id uuid [pk] }\nTable groups { id int [pk] }\nRef: users.id <> groups.id")
        assertTrue(myFixture.doHighlighting().none { it.severity == HighlightSeverity.ERROR })
    }
}
