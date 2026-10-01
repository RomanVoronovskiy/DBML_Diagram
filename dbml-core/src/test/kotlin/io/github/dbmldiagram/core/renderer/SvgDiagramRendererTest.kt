package io.github.dbmldiagram.core.renderer

import io.github.dbmldiagram.core.layout.LayeredDiagramLayoutEngine
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SvgDiagramRendererTest {
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
        assertEquals(8, Regex("class=\"route-snap\"").findAll(svg).count())
        assertEquals(true, svg.indexOf("data-table=\"orders\"") < svg.indexOf("class=\"relation-route\""))
    }
}
