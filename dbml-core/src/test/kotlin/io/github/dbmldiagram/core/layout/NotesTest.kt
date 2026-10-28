package io.github.dbmldiagram.core.layout

import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.core.renderer.SvgDiagramRenderer
import kotlin.test.*

class NotesTest {
    @Test fun `sticky and project notes render above left of tables and move independently`() {
        val text = """
            Project learning {
              Note: 'Project description'
            }
            Note reminder [color: #cef] { 'Review <script> & "quoted" keys' }
            Note second [color: none] {
              '''First line
              Second line'''
            }
            Table users { id int [pk] }
        """.trimIndent()
        val result = TolerantDbmlParser().parse(text)
        assertEquals(emptyList(), result.errors)
        val schema = result.schema!!
        assertEquals(2, schema.notes.size)
        val auto = LayeredDiagramLayoutEngine().layout(schema)
        assertEquals(3, auto.notes.size)
        assertTrue(auto.notes.all { it.x + it.width < auto.nodes.single().x })
        assertEquals(auto.nodes.single().y, auto.notes.first().y)
        val tableMoved = LayeredDiagramLayoutEngine(mapOf("users" to DiagramPoint(-900.0, -500.0))).layout(schema)
        assertEquals(auto.notes, tableMoved.notes)
        val moved = LayeredDiagramLayoutEngine(manualNotes = mapOf("note:reminder" to DiagramPoint(-1200.0, -600.0))).layout(schema)
        assertEquals(auto.nodes, moved.nodes)
        assertTrue(moved.originX < -1200 && moved.originY < -600)
        val svg = SvgDiagramRenderer().render(schema, moved)
        assertContains(svg, "data-note=\"note:reminder\"")
        assertContains(svg, "&lt;script&gt; &amp; &quot;quoted&quot;")
        assertFalse(svg.contains("<script>"))
    }

    @Test fun `note only schema exports with bounded wrapped text`() {
        val result = TolerantDbmlParser().parse("Note only { '" + "long ".repeat(1000) + "' }")
        assertFalse(result.hasErrors)
        val layout = LayeredDiagramLayoutEngine().layout(result.schema!!)
        assertEquals(1, layout.notes.size)
        assertTrue(layout.notes.single().lines.size <= 40)
        assertTrue(layout.width >= layout.notes.single().width)
        assertFalse(SvgDiagramRenderer().render(result.schema!!, layout).contains("No tables to display"))
    }

    @Test fun `malformed notes report errors without losing later tables`() {
        val result = TolerantDbmlParser().parse("Note bad { unquoted }\nTable next { id int }")
        assertTrue(result.hasErrors)
        assertTrue(result.schema!!.tables.any { it.name == "next" })
        val duplicate = TolerantDbmlParser().parse("Note a { 'one' }\nNote a { 'two' }")
        assertTrue(duplicate.hasErrors)
    }

    @Test fun `dark notes have contrasting text and wrapping never splits emoji`() {
        val result = TolerantDbmlParser().parse("Note dark [color: #123] { '" + "a".repeat(37) + "😀 review' }")
        val layout = LayeredDiagramLayoutEngine().layout(result.schema!!)
        val lines = layout.notes.single().lines
        assertTrue(lines.none { it.lastOrNull()?.isHighSurrogate() == true || it.firstOrNull()?.isLowSurrogate() == true })
        assertContains(SvgDiagramRenderer().render(result.schema!!, layout), "style=\"fill:#fff\"")
    }
}
