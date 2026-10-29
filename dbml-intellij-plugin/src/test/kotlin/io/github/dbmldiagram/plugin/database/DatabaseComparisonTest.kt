package io.github.dbmldiagram.plugin.database

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dbmldiagram.core.database.*
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.plugin.language.DbmlAnnotationInput
import io.github.dbmldiagram.plugin.language.DbmlExternalAnnotator

class DatabaseComparisonTest : BasePlatformTestCase() {
    private val source="""
        Table users {
          id bigint [pk]
          email varchar(255)
        }
    """.trimIndent()
    fun testDatabaseWarningsHighlightActualColumnAndClearAfterRefreshAndDisconnect() {
        val file=myFixture.configureByText("database-drift.dbml",source).virtualFile
        val service=DatabaseComparisonService.get(project)
        val existing=DatabaseTable(DatabaseName("public","users"),listOf(DatabaseColumn("id","bigint",false,null)),listOf(DatabaseKey("db_pk",listOf("id"),true)))
        try {
            service.publish(file,DatabaseComparisonSession(DatabaseSnapshot(setOf("public"),listOf(existing)),"public","synthetic"))
            val warnings=myFixture.doHighlighting().filter { it.severity==HighlightSeverity.WARNING && it.description.startsWith("Database drift:") }
            assertEquals(1,warnings.size)
            assertEquals("email varchar(255)",source.substring(warnings.single().startOffset,warnings.single().endOffset))
            service.publish(file,DatabaseComparisonSession(DatabaseSnapshot(setOf("public"),listOf(existing.copy(columns=existing.columns+DatabaseColumn("email","character varying(255)",true,null)))),"public","synthetic"))
            assertTrue(myFixture.doHighlighting().none { it.description?.startsWith("Database drift:")==true })
            service.disconnect(file)
            assertNull(service.session(file))
            assertTrue(myFixture.doHighlighting().none { it.description?.startsWith("Database drift:")==true })
        } finally { service.disconnect(file) }
    }
    fun testCachedSnapshotRecomparesEditedDbmlWithoutDatabaseCalls() {
        val snapshot=DatabaseSnapshot(setOf("public"),listOf(DatabaseTable(DatabaseName("public","users"),
            listOf(DatabaseColumn("id","bigint",false,null)),listOf(DatabaseKey("pk",listOf("id"),true)))))
        val annotator=DbmlExternalAnnotator()
        val warning=annotator.doAnnotate(DbmlAnnotationInput(source,snapshot))
        assertEquals(1,warning.size)
        assertEquals(3,warning.single().line)
        val fixed="Table users { id bigint [pk] }"
        assertEquals(emptyList<Any>(),annotator.doAnnotate(DbmlAnnotationInput(fixed,snapshot)))
        val noSnapshot=annotator.doAnnotate(DbmlAnnotationInput(source))
        assertEquals(emptyList<Any>(),noSnapshot)
    }
    fun testMigrationSelectionExcludesReviewOperationsAndCannotSelectDatabaseOnlyItems() {
        val schema=TolerantDbmlParser().parse(source).schema!!
        val actual=DatabaseSnapshot(setOf("public"),listOf(DatabaseTable(DatabaseName("public","users"),
            listOf(DatabaseColumn("id","integer",false,null),DatabaseColumn("legacy","text",true,null)),listOf(DatabaseKey("pk",listOf("id"),true)))))
        val comparison=PostgresSchemaDiff().compare(schema,actual)
        val model=MigrationSelection(comparison)
        val sql=model.sql()
        assertTrue(sql.contains("ADD COLUMN \"email\""))
        assertFalse(sql.contains(" TYPE "))
        val extra=comparison.differences.indexOfFirst { it.kind==SchemaDifferenceKind.DATABASE_ONLY }
        assertFalse(model.isCellEditable(extra,0))
        model.setValueAt(true,extra,0)
        assertFalse(model.getValueAt(extra,0) as Boolean)
        val type=comparison.differences.indexOfFirst { it.message.startsWith("Type differs") }
        model.setValueAt(true,type,0)
        assertTrue(model.sql().contains(" TYPE bigint"))
    }
}
