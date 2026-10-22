package io.github.dbmldiagram.core.parser

import io.github.dbmldiagram.core.ddl.*
import io.github.dbmldiagram.core.layout.LayeredDiagramLayoutEngine
import io.github.dbmldiagram.core.layout.columnRowOffset
import io.github.dbmldiagram.core.model.*
import io.github.dbmldiagram.core.renderer.SvgDiagramRenderer
import kotlin.test.*

class ComplexSchemaTest {
    private val parser = TolerantDbmlParser()
    private val source = """
        Project example { database_type: 'PostgreSQL' }
        Table app.parents as p {
          id uuid
          tenant text
          indexes { (id, tenant) [pk] }
          checks {
            `tenant <> '<&>'` [name: 'ck_parent_tenant']
          }
        }
        Table app.children as c {
          id uuid [pk]
          parent_id uuid
          tenant_ref text
          quantity integer [check: `quantity >= 0`, check: `quantity <= 100`]
          checks {
            `quantity IS NULL OR
             quantity BETWEEN 0 AND 100` [name: 'ck_quantity']
          }
        }
        Ref fk_child_parent: c.(parent_id, tenant_ref) > p.(id, tenant) [delete: cascade, update: no action]
    """.trimIndent()

    private fun valid(text: String = source): DbmlSchema {
        val result = parser.parse(text)
        assertFalse(result.hasErrors, result.errors.toString())
        assertTrue(result.canRender)
        return result.schema!!
    }

    @Test fun `CHECK blocks and repeated inline checks are retained without phantom columns`() {
        val schema = valid()
        assertEquals(2, schema.tables.size)
        assertEquals(listOf("id", "parent_id", "tenant_ref", "quantity"), schema.tables.last().columns.map { it.name })
        assertEquals("ck_quantity", schema.tables.last().checks.single().name)
        assertContains(schema.tables.last().checks.single().expression, "\n")
        assertEquals(listOf("quantity >= 0", "quantity <= 100"), schema.tables.last().columns.last().checks.map { it.expression })
        assertEquals("tenant <> '<&>'", schema.tables.first().checks.single().expression)
    }

    @Test fun `composite endpoints preserve order aliases actions and full source range`() {
        val reference = valid().references.single()
        assertEquals(listOf("parent_id", "tenant_ref"), reference.from.columns)
        assertEquals(listOf("id", "tenant"), reference.to.columns)
        assertEquals("c", reference.from.table)
        assertNull(reference.from.schema)
        assertEquals("cascade", reference.onDelete)
        assertEquals("no action", reference.onUpdate)
        assertEquals(source.lines().lastIndex + 1, reference.sourceRange!!.line)
        assertEquals(source.lines().lastIndex + 1, reference.sourceRange.endLine)
    }

    @Test fun `schema qualified and quoted composite names are not split on identifier punctuation`() {
        val schema = valid("""
            Table "core.data"."parent.group" {
              "a.b" uuid
              "c,d" text
              indexes { ("a.b", "c,d") [pk] }
            }
            Table child {
              a uuid
              b text
            }
            Ref: child.(a, b) > "core.data"."parent.group".("a.b", "c,d")
        """.trimIndent())
        assertEquals("core.data", schema.references.single().to.schema)
        assertEquals("parent.group", schema.references.single().to.table)
        assertEquals(listOf("a.b", "c,d"), schema.references.single().to.columns)
    }

    @Test fun `named multiline reference blocks support composite endpoints`() {
        val text = source.substringBefore("Ref fk_child_parent:") + """
            Ref fk_child_parent {
              c.(parent_id, tenant_ref)
                > p.(id, tenant) [delete: cascade]
            }
        """.trimIndent()
        val reference = valid(text).references.single()
        assertEquals("fk_child_parent", reference.name)
        assertEquals("cascade", reference.onDelete)
        assertTrue(reference.sourceRange!!.endLine > reference.sourceRange.line)
    }

    @Test fun `unequal tuple lengths are relationship errors rather than bogus table names`() {
        val result = parser.parse(source.replace("p.(id, tenant)", "p.id"))
        assertEquals(1, result.errors.size)
        assertContains(result.errors.single().message, "same number of columns")
        assertNotNull(result.errors.single().referenceId)
        assertTrue(result.canRender)
    }

    @Test fun `validates missing and incompatible nonfirst columns`() {
        val missing = parser.parse(source.replace("p.(id, tenant)", "p.(id, missing)"))
        assertContains(missing.errors.single().message, "p.missing")
        val mismatch = parser.parse(source.replace("tenant_ref text", "tenant_ref uuid"))
        assertContains(mismatch.errors.single().message, "Incompatible relationship types")
        assertContains(mismatch.errors.single().message, "tenant_ref")
        assertTrue(mismatch.canRender)
    }

    @Test fun `composite referenced key must be complete not merely contain an individually unique column`() {
        val noTupleKey = source.replace("id uuid\n", "id uuid [pk]\n").replace("indexes { (id, tenant) [pk] }", "")
        assertContains(parser.parse(noTupleKey).errors.single().message, "complete PK or UNIQUE key")
        valid(noTupleKey.replace("tenant text", "tenant text\n  indexes { (id, tenant) [unique] }"))
    }

    @Test fun `validates uniqueness of the entire foreign key including unique subsets`() {
        val uniqueTuple = source.replace("parent_id uuid", "parent_id uuid\n  indexes { (parent_id, tenant_ref) [unique] }")
        assertContains(parser.parse(uniqueTuple).errors.single().message, "Invalid 1:N")
        val uniqueSubset = source.replace("parent_id uuid", "parent_id uuid [unique]")
        assertContains(parser.parse(uniqueSubset).errors.single().message, "Invalid 1:N")
        valid(uniqueTuple.replace("c.(parent_id, tenant_ref) > p.(id, tenant)", "p.(id, tenant) - c.(parent_id, tenant_ref)"))
        valid(uniqueSubset.replace("c.(parent_id, tenant_ref) > p.(id, tenant)", "p.(id, tenant) - c.(parent_id, tenant_ref)"))
        assertContains(parser.parse(source.replace("c.(parent_id, tenant_ref) > p.(id, tenant)", "p.(id, tenant) - c.(parent_id, tenant_ref)")).errors.single().message, "Invalid 1:1")
    }

    @Test fun `reordered candidate keys preserve positional type mapping`() {
        val reordered = source.replace("c.(parent_id, tenant_ref) > p.(id, tenant)", "c.(tenant_ref, parent_id) > p.(tenant, id)")
        val schema = valid(reordered)
        val ddl = MySqlDdlGenerator().generate(schema)
        assertContains(ddl, "UNIQUE INDEX `uq_fk1_parents_tenant_id` ON `app`.`parents` (`tenant`, `id`)")
        assertContains(ddl, "FOREIGN KEY (`tenant_ref`, `parent_id`) REFERENCES `app`.`parents` (`tenant`, `id`)")
    }

    @Test fun `duplicate tuple members are rejected`() {
        val result = parser.parse(source.replace("c.(parent_id, tenant_ref)", "c.(parent_id, parent_id)"))
        assertContains(result.errors.single().message, "Duplicate columns")
    }

    @Test fun `malformed tuple syntax reports a syntax error`() {
        for (tuple in listOf("c.()", "c.(parent_id,)", "c.(parent_id,,tenant_ref)", "c.(parent_id tenant_ref)", "c.parent_id extra")) {
            val result = parser.parse(source.replace("c.(parent_id, tenant_ref)", tuple))
            assertTrue(result.hasErrors, tuple)
            assertFalse(result.canRender, tuple)
            assertContains(result.errors.single().message, "Invalid reference endpoint")
        }
    }

    @Test fun `inline references to composite tuples cannot silently truncate columns`() {
        val result = parser.parse("""
            Table target {
              a uuid
              b uuid
              indexes { (a, b) [pk] }
            }
            Table child { x uuid [ref: > target.(a, b)] }
        """.trimIndent())
        assertEquals(listOf("a", "b"), result.schema!!.references.single().to.columns)
        assertContains(result.errors.single().message, "same number of columns")
    }

    @Test fun `rejects empty invalid duplicate and unterminated checks with actual source lines`() {
        for (expression in listOf("``", "'x > 0'", "x > 0")) {
            val result = parser.parse("Table t {\n x int\n checks {\n $expression\n }\n}")
            assertTrue(result.hasErrors)
            assertEquals(4, result.errors.first().line)
            assertContains(result.errors.first().message, "CHECK requires")
        }
        val duplicate = parser.parse("Table t {\n x int\n checks {\n `x > 0` [name: 'ck']\n `x < 10` [name: 'ck']\n }\n}")
        assertContains(duplicate.errors.single().message, "Duplicate CHECK")
        assertEquals(5, duplicate.errors.single().line)
        assertTrue(parser.parse("Table t { checks { `x > 0`").hasErrors)
        assertContains(parser.parse("Table t { x int [check wrong `x > 0`] }").errors.single().message, "Expected ':' after check")
    }

    @Test fun `CHECK and index blocks may be interleaved and close on the same line`() {
        val schema = valid("""
            Table t {
              x int
              checks
              { `x > 0` [name: 'positive'] }
              indexes { x [unique] }
              checks { `x < 100` }
            }
            Table next { id int [pk] }
        """.trimIndent())
        assertEquals(2, schema.tables.first().checks.size)
        assertEquals(listOf("x"), schema.tables.first().columns.map { it.name })
        assertEquals(1, schema.tables.first().indexes.size)
        assertEquals(2, schema.tables.size)
    }

    @Test fun `all dialects export CHECK expressions and a single ordered composite FK`() {
        val schema = valid()
        for (generator in listOf(PostgreSqlDdlGenerator(), MySqlDdlGenerator(), OracleDdlGenerator())) {
            val ddl = generator.generate(schema)
            val q: (String) -> String = if (generator is MySqlDdlGenerator) ({ "`$it`" }) else ({ "\"$it\"" })
            assertContains(ddl, "CONSTRAINT ${q("ck_parent_tenant")} CHECK (tenant <> '<&>')")
            assertContains(ddl, "CONSTRAINT ${q("ck_quantity")} CHECK (quantity IS NULL OR\n     quantity BETWEEN 0 AND 100)")
            assertContains(ddl, "CHECK (quantity >= 0)")
            assertContains(ddl, "CHECK (quantity <= 100)")
            assertContains(ddl, "FOREIGN KEY (${q("parent_id")}, ${q("tenant_ref")}) REFERENCES ${q("app")}.${q("parents")} (${q("id")}, ${q("tenant")}) ON DELETE CASCADE")
            assertEquals(1, Regex("FOREIGN KEY").findAll(ddl).count())
            assertFalse("Skipped unresolved" in ddl)
        }
    }

    @Test fun `Oracle exports composite unique indexes as candidate key constraints`() {
        val schema = valid(source.replace("indexes { (id, tenant) [pk] }", "indexes { (id, tenant) [unique, name: 'uq_parent'] }"))
        val ddl = OracleDdlGenerator().generate(schema)
        assertContains(ddl, "CONSTRAINT \"uq_parent\" UNIQUE (\"id\", \"tenant\")")
        assertFalse("CREATE UNIQUE INDEX" in ddl)
    }

    @Test fun `unnamed checks have distinct bounded names even for long table names`() {
        val schema = valid("Table ${"long_name_".repeat(8)} {\n x int [check: `x > 0`, check: `x < 100`]\n checks { `x <> 10` }\n}")
        val ddl = OracleDdlGenerator().generate(schema)
        val names = Regex("CONSTRAINT \"([^\"]+)\" CHECK").findAll(ddl).map { it.groupValues[1] }.toList()
        assertEquals(3, names.distinct().size)
        assertTrue(names.all { it.length <= 30 })
    }

    @Test fun `diagram marks every FK column and shows CHECK constraints and tuple tooltip`() {
        val schema = valid()
        val layout = LayeredDiagramLayoutEngine().layout(schema)
        val svg = SvgDiagramRenderer().render(schema, layout)
        assertEquals(2, layout.nodes.size)
        assertEquals(1, layout.edges.size)
        assertEquals(columnRowOffset(1), layout.edges.single().fromColumnOffset)
        assertEquals(columnRowOffset(0), layout.edges.single().toColumnOffset)
        assertEquals(2, Regex(">FK</text>").findAll(svg).count())
        assertContains(svg, "FK c.(parent_id, tenant_ref) → REF p.(id, tenant) (N:1)")
        assertContains(svg, "CHECK (3)<title>")
        assertContains(svg, "CHECK (1)<title>ck_parent_tenant: tenant &lt;&gt; &apos;&lt;&amp;&gt;&apos;")
        assertFalse("class=\"relation-route invalid\"" in svg)
        assertEquals(1, Regex("marker-end=").findAll(svg).count())
    }

    @Test fun `invalid composite relationships mark all involved columns`() {
        val result = parser.parse(source.replace("tenant_ref text", "tenant_ref uuid"))
        val schema = result.schema!!
        val svg = SvgDiagramRenderer().render(schema, LayeredDiagramLayoutEngine().layout(schema))
        assertEquals(4, Regex("class=\"col invalid\"").findAll(svg).count())
        assertContains(svg, "class=\"relation-route invalid\"")
        assertFalse("marker-end=" in svg)
    }

    @Test fun `single column route identifiers stay compatible and composite tuples are distinct`() {
        val single = DbmlReference(DbmlColumnRef(table = "orders", column = "user_id"), DbmlColumnRef(table = "users", column = "id"), DbmlCardinality.MANY_TO_ONE)
        assertEquals("|orders|user_id|MANY_TO_ONE|users|id", single.routeId())
        val first = single.copy(from = DbmlColumnRef(table = "orders", column = "a,b", additionalColumns = listOf("c")))
        val second = single.copy(from = DbmlColumnRef(table = "orders", column = "a", additionalColumns = listOf("b,c")))
        assertNotEquals(first.routeId(), second.routeId())
        val relation = valid().references.single()
        assertNotEquals(relation.routeId(), relation.copy(from = relation.from.copy(additionalColumns = listOf("different"))).routeId())
    }
}
