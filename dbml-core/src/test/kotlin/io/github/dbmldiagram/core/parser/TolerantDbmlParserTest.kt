package io.github.dbmldiagram.core.parser

import io.github.dbmldiagram.core.model.DbmlCardinality
import io.github.dbmldiagram.core.model.DbmlParseSeverity
import kotlin.test.*

class TolerantDbmlParserTest {
    private val parser = TolerantDbmlParser()

    @Test fun `simple and multiple tables`() {
        val result = parser.parse("""
            Table users { id uuid }
            Table orders { id bigint }
        """.trimIndent())
        assertFalse(result.hasErrors, result.errors.toString())
        assertEquals(listOf("users", "orders"), result.schema!!.tables.map { it.name })
    }

    @Test fun `column settings default and notes`() {
        val schema = parser.parse("""
            Table users [note: 'People'] {
              id integer [pk, increment]
              email varchar(255) [not null, unique, note: 'Login']
              active boolean [default: true]
              nickname text [null]
            }
        """.trimIndent()).schema!!
        val table = schema.tables.single()
        assertEquals("People", table.note)
        assertTrue(table.columns[0].primaryKey)
        assertTrue(table.columns[0].increment)
        assertEquals("varchar(255)", table.columns[1].type)
        assertFalse(table.columns[1].nullable)
        assertTrue(table.columns[1].unique)
        assertEquals("Login", table.columns[1].note)
        assertEquals("true", table.columns[2].defaultValue)
        assertTrue(table.columns[3].nullable)
    }

    @Test fun `indexes and composite indexes`() {
        val table = parser.parse("""
            Table orders {
              id uuid
              user_id uuid
              status text
              indexes {
                id [unique]
                (user_id, status) [name: 'by_user_status']
              }
            }
        """.trimIndent()).schema!!.tables.single()
        assertEquals(listOf("id"), table.indexes[0].columns)
        assertTrue(table.indexes[0].unique)
        assertEquals(listOf("user_id", "status"), table.indexes[1].columns)
        assertEquals("by_user_status", table.indexes[1].name)
    }

    @Test fun `all reference cardinalities and direction`() {
        val result = parser.parse("""
            Table users { id uuid [pk] }
            Table orders { user_id uuid }
            Table profile { user_id uuid [unique] }
            Table groups { id uuid [pk] }
            Ref: orders.user_id > users.id
            Ref: users.id < orders.user_id
            Ref: users.id - profile.user_id
            Ref: users.id <> groups.id
        """.trimIndent())
        assertEquals(listOf(DbmlCardinality.MANY_TO_ONE, DbmlCardinality.ONE_TO_MANY, DbmlCardinality.ONE_TO_ONE, DbmlCardinality.MANY_TO_MANY), result.schema!!.references.map { it.cardinality })
        assertFalse(result.hasErrors, result.errors.toString())
    }

    @Test fun `enums project schema qualified names comments and inline refs`() {
        val result = parser.parse("""
            // heading
            Project shop { database_type: 'PostgreSQL' }
            Enum order_status { CREATED
              PAID
            }
            /* table comment */
            Table auth.users { id uuid [pk] }
            Table sales.orders {
              user_id uuid [ref: > auth.users.id]
              status order_status
            }
        """.trimIndent())
        assertFalse(result.hasErrors, result.errors.toString())
        assertEquals("shop", result.schema!!.project!!.name)
        assertEquals("auth", result.schema.tables[0].schema)
        assertEquals(listOf("CREATED", "PAID"), result.schema.enums.single().values)
        assertEquals("auth", result.schema.references.single().to.schema)
    }

    @Test fun `named reference block`() {
        val result = parser.parse("""
            Table a { id uuid }
            Table b { a_id uuid }
            Ref fk_b_a { b.a_id > a.id }
        """.trimIndent())
        assertEquals("fk_b_a", result.schema!!.references.single().name)
    }

    @Test fun `reference delete and update settings do not become endpoint identifiers`() {
        val result = parser.parse("""
            Table dataset { dataset_id uuid [pk] }
            Table wall_contour { dataset_id uuid [not null] }
            Ref: wall_contour.dataset_id > dataset.dataset_id [delete: cascade, update: no action]
        """.trimIndent())
        assertFalse(result.hasErrors, result.errors.toString())
        val reference = result.schema!!.references.single()
        assertEquals("wall_contour", reference.from.table)
        assertEquals("dataset_id", reference.to.column)
        assertEquals("cascade", reference.onDelete)
        assertEquals("no action", reference.onUpdate)
    }

    @Test fun `preserves SQL string and expression defaults`() {
        val columns = parser.parse("""
            Table users {
              name text [default: 'guest']
              created_at timestamptz [default: `now()`]
            }
        """.trimIndent()).schema!!.tables.single().columns
        assertEquals("'guest'", columns[0].defaultValue)
        assertEquals("now()", columns[1].defaultValue)
    }

    @Test fun `composite primary key index is retained`() {
        val index = parser.parse("""
            Table memberships {
              user_id uuid
              group_id uuid
              indexes { (user_id, group_id) [pk] }
            }
        """.trimIndent()).schema!!.tables.single().indexes.single()
        assertTrue(index.primaryKey)
        assertEquals(listOf("user_id", "group_id"), index.columns)
    }

    @Test fun `malformed and partially typed DBML return errors instead of throwing`() {
        val malformed = parser.parse("Table users {\n id uu")
        assertNotNull(malformed.schema)
        assertTrue(malformed.errors.any { it.severity == DbmlParseSeverity.ERROR })
        assertEquals("users", malformed.schema!!.tables.single().name)
    }

    @Test fun `duplicate table and duplicate column are warnings`() {
        val result = parser.parse("""
            Table users { id uuid
              id bigint
            }
            Table users { other text }
        """.trimIndent())
        assertTrue(result.errors.count { it.severity == DbmlParseSeverity.WARNING } >= 2)
        assertFalse(result.hasErrors)
    }

    @Test fun `unknown references are errors on their source line`() {
        val result = parser.parse("""
            Table users { id uuid }
            Ref: missing.user_id > users.missing_id
        """.trimIndent())
        assertEquals(2, result.errors.count { it.severity == DbmlParseSeverity.ERROR })
        assertTrue(result.errors.all { it.line == 2 && it.referenceId != null })
        assertTrue(result.canRender)
    }

    @Test fun `large generated schema`() {
        val source = buildString {
            repeat(100) { table ->
                appendLine("Table t$table {")
                repeat(12) { column -> appendLine("  c$column varchar(255) [not null${if (column == 0) ", pk" else ""}]") }
                appendLine("}")
                if (table > 0) appendLine("Ref: t$table.c1 > t${table - 1}.c0")
            }
        }
        val result = parser.parse(source)
        assertFalse(result.hasErrors, result.errors.take(3).toString())
        assertEquals(100, result.schema!!.tables.size)
        assertEquals(1200, result.schema.tables.sumOf { it.columns.size })
        assertEquals(99, result.schema.references.size)
    }
}
