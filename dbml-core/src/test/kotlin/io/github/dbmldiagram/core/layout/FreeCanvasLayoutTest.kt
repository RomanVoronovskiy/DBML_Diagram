package io.github.dbmldiagram.core.layout

import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.core.renderer.SvgDiagramRenderer
import kotlin.test.*

class FreeCanvasLayoutTest {
    private val schema = TolerantDbmlParser().parse("""
        Table users { id uuid [pk] }
        Table orders { user_id uuid [ref: > users.id] }
        TableGroup Accounts { users orders }
    """.trimIndent()).schema!!
    private val positions = mapOf("users" to DiagramPoint(-700.0, -350.0), "orders" to DiagramPoint(-100.0, -100.0))

    @Test fun `negative positions are preserved and group and route geometry fit export viewBox`() {
        val layout = LayeredDiagramLayoutEngine(positions).layout(schema)
        assertEquals(-700.0, layout.nodes.single { it.tableId == "users" }.x)
        assertTrue(layout.originX < -700 && layout.originY < -350)
        layout.groups.forEach { assertTrue(it.x >= layout.originX && it.y >= layout.originY) }
        layout.edges.flatMap { it.points }.forEach {
            assertTrue(it.x >= layout.originX && it.y >= layout.originY)
            assertTrue(it.x < layout.originX + layout.width && it.y < layout.originY + layout.height)
        }
        val svg = SvgDiagramRenderer().render(schema, layout)
        assertContains(svg, "viewBox=\"${layout.originX} ${layout.originY}")
        assertContains(svg, "data-x=\"-700.0\"")
    }

    @Test fun `moving the whole scene changes export origin but not image size or relative routes`() {
        val route = ManualRelationRoute(TableSide.LEFT, TableSide.RIGHT, DiagramPoint(-250.0, -430.0))
        val id = schema.references.single().routeId()
        val original = LayeredDiagramLayoutEngine(positions, mapOf(id to route)).layout(schema)
        for (delta in listOf(DiagramPoint(-10_000.0, -20_000.0), DiagramPoint(20_000.0, 30_000.0))) {
            val translated = LayeredDiagramLayoutEngine(positions.mapValues { (_, p) -> DiagramPoint(p.x + delta.x, p.y + delta.y) },
                mapOf(id to route.copy(control = DiagramPoint(route.control.x + delta.x, route.control.y + delta.y)))).layout(schema)
            assertEquals(original.width, translated.width)
            assertEquals(original.height, translated.height)
            assertEquals(original.originX + delta.x, translated.originX)
            assertEquals(original.originY + delta.y, translated.originY)
            assertEquals(original.edges.single().points.map { DiagramPoint(it.x + delta.x, it.y + delta.y) }, translated.edges.single().points)
        }
    }

    @Test fun `distant positive scene does not export all the empty space back to zero`() {
        val layout = LayeredDiagramLayoutEngine(positions.mapValues { (_, p) -> DiagramPoint(p.x + 100_000, p.y + 100_000) }).layout(schema)
        assertTrue(layout.originX > 90_000 && layout.originY > 90_000)
        assertTrue(layout.width < 2000 && layout.height < 1000)
    }
}
