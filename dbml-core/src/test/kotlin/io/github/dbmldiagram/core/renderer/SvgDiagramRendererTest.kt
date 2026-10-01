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
    }
}
