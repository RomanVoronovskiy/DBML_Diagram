package io.github.dbmldiagram.core

import io.github.dbmldiagram.core.ddl.*
import io.github.dbmldiagram.core.layout.*
import io.github.dbmldiagram.core.model.*
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.core.renderer.SvgDiagramRenderer
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.test.*

class TableGroupTest {
    private val parser = TolerantDbmlParser()
    private val source = """
        TableGroup "Identity" [color: #3b82f6, note: 'Accounts'] { users profiles }
        TableGroup Sales [color: #0a8] { orders }
        Table users { id uuid [pk] }
        Table profiles {
          id uuid [pk]
          user_id uuid [unique, ref: - users.id]
        }
        Table orders {
          id uuid [pk]
          user_id uuid [ref: > users.id]
        }
        Table audit { id uuid [pk] }
    """.trimIndent()
    private fun schema(text: String = source): DbmlSchema {
        val result = parser.parse(text)
        assertFalse(result.hasErrors, result.errors.toString())
        return result.schema!!
    }

    @Test fun `parses forward members and colors without unsupported warnings`() {
        val result = parser.parse(source)
        assertEquals(emptyList(), result.errors)
        val groups = result.schema!!.tableGroups
        assertEquals(listOf("Identity", "Sales"), groups.map { it.name })
        assertEquals(listOf("users", "profiles"), groups.first().tables.map { it.tableName })
        assertEquals("Accounts", groups.first().note)
        assertEquals("#00aa88", TableGroupGeometry.color(groups.last()))
    }

    @Test fun `resolves qualified and quoted names and aliases`() {
        val value = schema("""
            TableGroup "Domain & team" { app."user accounts" O }
            Table app."user accounts" { id int [pk] }
            Table app.orders as O { id int [pk] }
        """.trimIndent())
        val layout = LayeredDiagramLayoutEngine().layout(value)
        assertEquals(listOf("app.user accounts", "app.orders"), layout.groups.single().tableIds)
    }

    @Test fun `supports multiline settings and block and inline notes`() {
        val value = schema("""
            Table users { id int }
            TableGroup "Group A" [
              color: #ABC,
              note: 'header'
            ] {
              users
              Note {
                '''Owner's group with {braces}
                and a second line'''
              }
            }
        """.trimIndent())
        assertEquals("#aabbcc", TableGroupGeometry.color(value.tableGroups.single()))
        assertContains(value.tableGroups.single().note!!, "Owner's group with {braces}")
        assertContains(value.tableGroups.single().note!!, "second line")
        assertEquals("Body note", schema("Table users { id int }\nTableGroup A { users\nNote: 'Body note' }").tableGroups.single().note)
    }

    @Test fun `unknown and empty members warn with source lines without blocking render`() {
        val result = parser.parse("Table users { id int }\nTableGroup A { missing }\nTableGroup B { }")
        assertTrue(result.canRender)
        assertEquals(3, result.errors.size)
        assertTrue(result.errors.all { it.severity == DbmlParseSeverity.WARNING && it.line in 2..3 })
        assertTrue(LayeredDiagramLayoutEngine().layout(result.schema!!).groups.isEmpty())
    }

    @Test fun `duplicates are drawn only once and shared ownership is reported`() {
        val result = parser.parse("Table users { id int }\nTableGroup A { users USERS }\nTableGroup B { users }")
        assertFalse(result.hasErrors)
        assertEquals(2, result.errors.size)
        val layout = LayeredDiagramLayoutEngine().layout(result.schema!!)
        assertEquals(1, layout.nodes.size)
        assertEquals(listOf(1, 1), layout.groups.map { it.tableIds.size })
    }

    @Test fun `duplicate group names are errors`() {
        assertTrue(parser.parse("TableGroup A {}\nTableGroup a {}").errors.any {
            it.severity == DbmlParseSeverity.ERROR && it.message.contains("Duplicate TableGroup")
        })
    }

    @Test fun `invalid paint values use safe deterministic fallback colors`() {
        for (color in listOf("red", "#12", "#12345678", "'url(https://example.invalid/paint)'", "'\" onload=\"x'")) {
            val result = parser.parse("Table users { id int }\nTableGroup A [color: $color] { users }")
            assertFalse(result.hasErrors)
            assertTrue(result.errors.any { "Invalid TableGroup color" in it.message })
            assertEquals(TableGroupGeometry.color(DbmlTableGroup("A", emptyList())), TableGroupGeometry.color(result.schema!!.tableGroups.single()))
        }
    }

    @Test fun `group blocks never overlap in auto layout and leave ungrouped tables outside`() {
        val value = schema()
        val layout = LayeredDiagramLayoutEngine().layout(value)
        assertEquals(2, layout.groups.size)
        assertEquals(4, layout.nodes.size)
        val (a, b) = layout.groups
        assertTrue(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y)
        val ungrouped = layout.nodes.single { it.tableId == "audit" }
        layout.groups.forEach { g ->
            assertTrue(ungrouped.x >= g.x + g.width || ungrouped.x + ungrouped.width <= g.x ||
                ungrouped.y >= g.y + g.height || ungrouped.y + ungrouped.height <= g.y)
        }
        assertEquals(layout, LayeredDiagramLayoutEngine().layout(value))
        assertFramesContainMembers(layout)
    }

    @Test fun `manual positions win and frame bounds include the restored positions`() {
        val layout = LayeredDiagramLayoutEngine(mapOf("users" to DiagramPoint(1800.0, 1400.0), "profiles" to DiagramPoint(-1.0, -1.0))).layout(schema())
        val users = layout.nodes.single { it.tableId == "users" }
        assertEquals(1800.0, users.x)
        assertEquals(1400.0, users.y)
        assertEquals(48.0, layout.nodes.single { it.tableId == "profiles" }.x)
        assertFramesContainMembers(layout)
    }

    @Test fun `long names enlarge frames without clipping labels or canvas`() {
        val value = schema("Table users { id int }\nTableGroup \"${"W".repeat(200)}\" { users }")
        val layout = LayeredDiagramLayoutEngine().layout(value)
        assertTrue(layout.groups.single().width > layout.nodes.single().width + 48)
        assertEquals(70, TableGroupGeometry.label(value.tableGroups.single().name).length)
        assertFramesContainMembers(layout)
    }

    @Test fun `SVG frames precede tables and contain title count and safe color`() {
        val value = schema()
        val svg = SvgDiagramRenderer().render(value, LayeredDiagramLayoutEngine().layout(value))
        assertTrue(svg.indexOf("class=\"table-group\"") < svg.indexOf("data-table=\"users\""))
        assertContains(svg, "data-table-group=\"Identity\"")
        assertContains(svg, "stroke=\"#00aa88\"")
        assertContains(svg, ">2 tables</text>")
        assertContains(svg, ">1 table</text>")
        assertContains(svg, "<title>Identity&#10;Accounts</title>")
        assertContains(svg, "data-tables=\"[&quot;users&quot;,&quot;profiles&quot;]\"")
    }

    @Test fun `SVG escapes names and JSON attributes and blocks unsafe direct model colors`() {
        val id = "users\"\\<&\n"
        val table = schema("Table users { id int }").tables.single().copy(name = id)
        val value = DbmlSchema(tables = listOf(table), tableGroups = listOf(
            DbmlTableGroup("</script><script>alert(1)</script>", listOf(DbmlTableRef(id)), "url(https://invalid)", "<img onerror='x'>")
        ))
        val svg = SvgDiagramRenderer().render(value, LayeredDiagramLayoutEngine().layout(value))
        assertFalse(svg.contains("<script>"))
        assertFalse(svg.contains("url(https://invalid)"))
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(svg)))
        val group = (0 until document.getElementsByTagName("g").length).map {
            document.getElementsByTagName("g").item(it) as org.w3c.dom.Element
        }.single { it.hasAttribute("data-table-group") }
        // Newlines in XML attributes are normalized; JSON must escape them, not embed raw controls.
        assertEquals("[\"users\\\"\\\\<&\\u000a\"]", group.getAttribute("data-tables"))
        val card = (0 until document.getElementsByTagName("g").length).map {
            document.getElementsByTagName("g").item(it) as org.w3c.dom.Element
        }.single { it.hasAttribute("data-table") }
        assertEquals(id, card.getAttribute("data-table"))
    }

    @Test fun `groups do not change DDL output in any supported dialect`() {
        val value = schema()
        for (generator in listOf(PostgreSqlDdlGenerator(), MySqlDdlGenerator(), OracleDdlGenerator())) {
            assertEquals(generator.generate(value.copy(tableGroups = emptyList())), generator.generate(value))
        }
    }

    private fun assertFramesContainMembers(layout: DiagramLayout) {
        layout.groups.forEach { g ->
            assertTrue(g.x >= 0 && g.y >= 0)
            assertTrue(g.x + g.width < layout.width && g.y + g.height < layout.height)
            layout.nodes.filter { it.tableId in g.tableIds }.forEach { n ->
                assertTrue(n.x >= g.x + 24 && n.y >= g.y + 40)
                assertTrue(n.x + n.width <= g.x + g.width - 24 && n.y + n.height <= g.y + g.height - 24)
            }
        }
    }
}
