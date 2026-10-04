package io.github.dbmldiagram.core.renderer

import io.github.dbmldiagram.core.layout.LayeredDiagramLayoutEngine
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SvgDiagramRendererTest {
    @Test fun `marks invalid relationship and columns without a valid FK badge or arrow`() {
        val result = TolerantDbmlParser().parse("""
            Table users { id uuid [pk] }
            Table orders { id uuid [pk] }
            Ref: orders.id > users.id
        """.trimIndent())
        val schema = result.schema!!
        val svg = SvgDiagramRenderer().render(schema, LayeredDiagramLayoutEngine().layout(schema))
        assertContains(svg, "class=\"relation-route invalid\"")
        assertContains(svg, "class=\"col invalid\"")
        assertContains(svg, "Invalid relationship")
        assertContains(svg, "ERROR: Invalid 1:N")
        assertFalse(svg.contains("marker-end=\"url(#fk-arrow)\""))
        assertFalse(svg.contains("PK · FK"))
    }

    @Test fun `composite unique index does not mark each column unique`() {
        val schema = TolerantDbmlParser().parse("""
            Table memberships {
              user_id uuid
              group_id uuid
              indexes { (user_id, group_id) [unique] }
            }
        """.trimIndent()).schema!!
        val svg = SvgDiagramRenderer().render(schema, LayeredDiagramLayoutEngine().layout(schema))
        assertFalse(svg.contains("UNIQ"))
    }
    @Test fun `renders standalone svg with viewBox`() {
        val schema = TolerantDbmlParser().parse("Table users { id uuid [pk] }").schema!!
        val svg = SvgDiagramRenderer().render(schema, LayeredDiagramLayoutEngine().layout(schema))
        assertContains(svg, "<svg xmlns=\"http://www.w3.org/2000/svg\"")
        assertContains(svg, "viewBox=")
        assertContains(svg, "users")
        assertContains(svg, "PK")
        assertContains(svg, "data-table=\"users\" data-x=")
    }

    @Test fun `renders explicit constraints and marks only foreign key side`() {
        val schema = TolerantDbmlParser().parse("""
            Table users {
              id uuid [pk]
              email text [not null, unique]
            }
            Table orders {
              id uuid
              user_id uuid [not null]
              indexes { id [unique] }
            }
            Ref: orders.user_id > users.id
        """.trimIndent()).schema!!

        val svg = SvgDiagramRenderer().render(schema, LayeredDiagramLayoutEngine().layout(schema))

        assertContains(svg, "PK · NOT_NULL")
        assertContains(svg, "UNIQ · NOT_NULL")
        assertContains(svg, "FK · NOT_NULL")
        assertEquals(1, Regex(">FK · NOT_NULL<").findAll(svg).count())
        assertContains(svg, "marker-end=\"url(#fk-arrow)\"")
        assertContains(svg, "<title>FK orders.user_id → REF users.id (N:1)</title>")
        assertContains(svg, ">N</text>")
        assertContains(svg, ">1</text>")
        assertContains(svg, "class=\"relation-halo\"")
        assertContains(svg, "class=\"relation-hit\"")
        assertContains(svg, "Drag route left/right or up/down")
        assertContains(svg, "class=\"route-axis route-axis-x\"")
        assertContains(svg, "class=\"route-axis route-axis-y\"")
        assertContains(svg, "data-from-side=\"")
        assertContains(svg, "data-from-column-offset=\"78.0\" data-to-column-offset=\"52.0\"")
        val layout = LayeredDiagramLayoutEngine().layout(schema)
        val orders = layout.nodes.single { it.tableId == "orders" }
        assertContains(svg, "data-end=\"from\" data-side=\"left\" cx=\"${orders.x}\" cy=\"${orders.y + 78.0}\"")
        assertEquals(8, Regex("class=\"route-snap\"").findAll(svg).count())
        assertEquals(true, svg.indexOf("data-table=\"orders\"") < svg.indexOf("class=\"relation-route\""))
    }
}
