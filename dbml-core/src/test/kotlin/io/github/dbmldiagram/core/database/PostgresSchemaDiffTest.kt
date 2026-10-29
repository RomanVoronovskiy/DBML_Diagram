package io.github.dbmldiagram.core.database

import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import kotlin.test.*

class PostgresSchemaDiffTest {
    @Test fun `additive plan preserves dependency order and source locations`() {
        val result = TolerantDbmlParser().parse("""
            Table users {
              id bigint [pk]
              email varchar(255) [unique]
            }
            Table orders {
              id bigint [pk]
              user_id bigint [ref: > users.id]
            }
        """.trimIndent())
        assertFalse(result.hasErrors)
        val diff = PostgresSchemaDiff().compare(result.schema!!,DatabaseSnapshot(emptySet(),emptyList(),inspectedSchemas=setOf("public")))
        val fk = diff.differences.single { it.id.startsWith("fk:") }
        val sql = diff.migration(setOf(fk.id))
        assertTrue(sql.indexOf("CREATE SCHEMA") < sql.indexOf("CREATE TABLE"))
        assertTrue(sql.indexOf("PRIMARY KEY") < sql.indexOf("FOREIGN KEY"))
        assertContains(sql,"REFERENCES \"public\".\"users\"")
        assertEquals(7,fk.range!!.line)
        assertFailsWith<IllegalArgumentException> { diff.migration(setOf("unknown")) }
    }

    @Test fun `existing structural keys and aliases are not recreated and extra columns are non destructive`() {
        val schema = TolerantDbmlParser().parse("""
            Table users {
              id int [pk]
              email varchar(255) [unique]
            }
        """.trimIndent()).schema!!
        val database = DatabaseTable(DatabaseName("public","users"),listOf(
            DatabaseColumn("id","integer",false,null),DatabaseColumn("email","character varying(255)",true,null),
            DatabaseColumn("legacy","text",true,null)),listOf(DatabaseKey("other_pk",listOf("id"),true)),
            listOf(DatabaseIndex("old_email_name",listOf("email"),true,true)))
        val diff = PostgresSchemaDiff().compare(schema,DatabaseSnapshot(setOf("public"),listOf(database)))
        assertEquals(1,diff.differences.size)
        assertEquals(SchemaDifferenceKind.DATABASE_ONLY,diff.differences.single().kind)
        assertNull(diff.differences.single().sql)
    }

    @Test fun `partial unique index cannot satisfy complete uniqueness`() {
        val schema = TolerantDbmlParser().parse("Table users { email text [unique] }").schema!!
        val database = DatabaseTable(DatabaseName("public","users"),listOf(DatabaseColumn("email","text",true,null)),
            indexes=listOf(DatabaseIndex("partial",listOf("email"),true,false)))
        val diff = PostgresSchemaDiff().compare(schema,DatabaseSnapshot(setOf("public"),listOf(database)))
        assertTrue(diff.differences.any { it.sql?.contains(" UNIQUE ") == true && it.requiresReview })
    }

    @Test fun `type null and default drift are review only while generated columns are manual`() {
        val schema = TolerantDbmlParser().parse("""
            Table users {
              name varchar(255) [not null, default: 'A B']
              total int
            }
        """.trimIndent()).schema!!
        val database = DatabaseTable(DatabaseName("public","users"),listOf(DatabaseColumn("name","text",true,"'old'::text"),
            DatabaseColumn("total","integer",true,null,generated=true)))
        val diff = PostgresSchemaDiff().compare(schema,DatabaseSnapshot(setOf("public"),listOf(database)))
        assertEquals(4,diff.differences.size)
        assertTrue(diff.differences.filter { it.sql != null }.all { it.requiresReview })
        assertTrue(diff.differences.any { it.kind == SchemaDifferenceKind.UNSUPPORTED && it.sql == null })
        assertFalse(diff.differences.any { it.sql?.contains("DROP TABLE") == true })
    }

    @Test fun `expression normalization preserves literals and logical token boundaries`() {
        assertEquals(PostgresSchemaDiff.canonicalExpression("now()"),PostgresSchemaDiff.canonicalExpression("CURRENT_TIMESTAMP"))
        assertEquals(PostgresSchemaDiff.canonicalExpression("'A B'"),PostgresSchemaDiff.canonicalExpression("'A B'::text"))
        assertNotEquals(PostgresSchemaDiff.canonicalExpression("'ABC'"),PostgresSchemaDiff.canonicalExpression("'abc'"))
        assertNotEquals(PostgresSchemaDiff.canonicalExpression("x AND y"),PostgresSchemaDiff.canonicalExpression("xandy"))
        assertEquals(PostgresSchemaDiff.canonicalExpression("amount >= 0"),PostgresSchemaDiff.canonicalExpression("((amount >= 0))"))
        assertNotEquals(PostgresSchemaDiff.canonicalType("\"A\".\"Status\""),PostgresSchemaDiff.canonicalType("a.status"))
        assertEquals(PostgresSchemaDiff.canonicalType("int[]"),PostgresSchemaDiff.canonicalType("integer[]"))
    }

    @Test fun `uninspected namespaces and conflicting planned index names never produce guesses`() {
        val schema=TolerantDbmlParser().parse("Table billing.users { id int [pk] }").schema!!
        val unknown=PostgresSchemaDiff().compare(schema,DatabaseSnapshot(setOf("public"),emptyList()))
        assertTrue(unknown.differences.all { it.sql==null })
        val duplicate=TolerantDbmlParser().parse("""
            Table one {
              id int
              indexes { id [name: 'shared_index'] }
            }
            Table two {
              id int
              indexes { id [name: 'shared_index'] }
            }
        """.trimIndent()).schema!!
        val diff=PostgresSchemaDiff().compare(duplicate,DatabaseSnapshot(setOf("public"),emptyList()))
        assertTrue(diff.differences.any { it.message.startsWith("Index name exists") && it.sql==null })
        val ids=diff.differences.filter { it.sql!=null }.map { it.id }.toSet()
        assertEquals(1,Regex("CREATE INDEX").findAll(diff.migration(ids)).count())
    }

    @Test fun `foreign key selection includes pending target type changes before constraints`() {
        val schema=TolerantDbmlParser().parse("""
            Table users { id int [pk] }
            Table orders { user_id int [ref: > users.id] }
        """.trimIndent()).schema!!
        val snapshot=DatabaseSnapshot(setOf("public"),listOf(DatabaseTable(DatabaseName("public","users"),
            listOf(DatabaseColumn("id","text",false,null)),listOf(DatabaseKey("pk",listOf("id"),true)))))
        val diff=PostgresSchemaDiff().compare(schema,snapshot)
        val fk=diff.differences.single { it.id.startsWith("fk:") }
        val sql=diff.migration(setOf(fk.id))
        assertTrue(sql.indexOf(" TYPE integer") < sql.indexOf("FOREIGN KEY"))
        assertContains(sql,"-- REVIEW:")
    }
}
