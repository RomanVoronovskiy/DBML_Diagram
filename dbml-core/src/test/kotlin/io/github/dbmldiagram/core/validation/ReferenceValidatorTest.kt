package io.github.dbmldiagram.core.validation

import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import kotlin.test.*

class ReferenceValidatorTest {
    private val parser = TolerantDbmlParser()

    private fun schema(fromSettings: String, toSettings: String, relation: String, fromType: String = "uuid", toType: String = "uuid") = """
        Table users as u {
          id $toType $toSettings
        }
        Table orders as o {
          user_id $fromType $fromSettings
        }
        $relation
    """.trimIndent()

    @Test fun `rejects PK to PK marked many to one and reverse one to many`() {
        for (relation in listOf("Ref: orders.user_id > users.id", "Ref: users.id < orders.user_id")) {
            val result = parser.parse(schema("[pk]", "[pk]", relation))
            val error = result.errors.single()
            assertContains(error.message, "Invalid 1:N")
            assertEquals(7, error.line)
            assertEquals(6, error.column)
            assertEquals(7, error.endLine)
            assertEquals(relation.length + 1, error.endColumn)
            assertEquals(result.schema!!.references.single().routeId(), error.referenceId)
            assertTrue(result.canRender)
        }
    }

    @Test fun `unique many side is also rejected`() {
        val result = parser.parse(schema("[unique]", "[pk]", "Ref: orders.user_id > users.id"))
        assertContains(result.errors.single().message, "Invalid 1:N")
    }

    @Test fun `allows ordinary many to one and reverse form`() {
        for (relation in listOf("Ref: orders.user_id > users.id", "Ref: users.id < orders.user_id")) {
            val result = parser.parse(schema("", "[pk]", relation))
            assertFalse(result.hasErrors, result.errors.toString())
        }
    }

    @Test fun `allows shared primary key one to one in standalone and inline forms`() {
        val standalone = parser.parse(schema("[pk]", "[pk]", "Ref: users.id - orders.user_id"))
        val inline = parser.parse(schema("[pk, ref: - users.id]", "[pk]", ""))
        assertFalse(standalone.hasErrors, standalone.errors.toString())
        assertFalse(inline.hasErrors, inline.errors.toString())
    }

    @Test fun `one to one requires unique FK and referenced key`() {
        val result = parser.parse(schema("", "", "Ref: users.id - orders.user_id"))
        assertEquals(2, result.errors.size)
        assertTrue(result.errors.any { "Invalid 1:1" in it.message })
        assertTrue(result.errors.any { "Referenced column" in it.message })
    }

    @Test fun `inline invalid references point to the ref setting`() {
        val source = schema("[pk, ref: > users.id]", "[pk]", "")
        val error = parser.parse(source).errors.single()
        val line = source.lines()[error.line - 1]
        assertEquals("ref: > users.id", line.substring(error.column - 1, error.endColumn - 1))
        assertEquals(5, error.line)
    }

    @Test fun `validates every inline reference on a column`() {
        val result = parser.parse("""
            Table users { id uuid [pk] }
            Table orders { id uuid [pk] }
            Table items {
              order_id uuid [ref: > orders.id, ref: > users.missing]
            }
        """.trimIndent())
        assertEquals(2, result.schema!!.references.size)
        val error = result.errors.single()
        assertContains(error.message, "users.missing")
        val line = "  order_id uuid [ref: > orders.id, ref: > users.missing]"
        assertEquals("ref: > users.missing", line.substring(error.column - 1, error.endColumn - 1))
    }

    @Test fun `named multiline reference location and aliases`() {
        val result = parser.parse(schema("[pk]", "[pk]", """
            Ref bad {
              o.user_id
              > u.id
            }
        """.trimIndent()))
        val error = result.errors.single()
        assertContains(error.message, "Invalid 1:N")
        assertEquals(8, error.line)
        assertEquals(9, error.endLine)
    }

    @Test fun `single column unique indexes count but composite indexes do not`() {
        val source = """
            Table users {
              id uuid
              tenant_id uuid
              indexes { (id, tenant_id) [pk] }
            }
            Table orders { user_id uuid }
            Ref: orders.user_id > users.id
        """.trimIndent()
        assertContains(parser.parse(source).errors.single().message, "composite key")
        val valid = source.replace("(id, tenant_id) [pk]", "id [unique]")
        assertFalse(parser.parse(valid).hasErrors)
        val invalidMany = valid.replace("Table orders { user_id uuid }", "Table orders { user_id uuid\n indexes { user_id [unique] }\n}")
        assertContains(parser.parse(invalidMany).errors.single().message, "Invalid 1:N")
    }

    @Test fun `part of composite FK primary key can still be many side`() {
        val source = """
            Table users { id uuid [pk] }
            Table orders {
              user_id uuid
              item_id uuid
              indexes { (user_id, item_id) [pk] }
            }
            Ref: orders.user_id > users.id
        """.trimIndent()
        assertFalse(parser.parse(source).hasErrors)
        val columnPrimaryKeys = source.replace("user_id uuid", "user_id uuid [pk]")
            .replace("item_id uuid", "item_id uuid [pk]").replace("indexes { (user_id, item_id) [pk] }", "")
        assertFalse(parser.parse(columnPrimaryKeys).hasErrors)
    }

    @Test fun `many to many accepts entity keys even with different types`() {
        val result = parser.parse(schema("[pk]", "[pk]", "Ref: users.id <> orders.user_id", "int", "uuid"))
        assertFalse(result.hasErrors, result.errors.toString())
        val invalid = parser.parse(schema("", "[pk]", "Ref: users.id <> orders.user_id"))
        assertContains(invalid.errors.single().message, "Referenced column")
    }

    @Test fun `checks physical FK type compatibility while accepting aliases`() {
        val source = schema("", "[pk]", "Ref: o.user_id > u.id", "uuid", "int")
        assertContains(parser.parse(source).errors.single().message, "Incompatible relationship types")
        assertFalse(parser.parse(schema("", "[pk]", "Ref: o.user_id > u.id", "integer", "int")).hasErrors)
    }

    @Test fun `rejects a column referencing itself but accepts a hierarchical self relation`() {
        val result = parser.parse("Table users { id uuid [pk] }\nRef: users.id - users.id")
        assertContains(result.errors.single().message, "same column")
        val valid = parser.parse("Table users {\n id uuid [pk]\n parent_id uuid\n}\nRef: users.parent_id > users.id")
        assertFalse(valid.hasErrors)
    }
}
