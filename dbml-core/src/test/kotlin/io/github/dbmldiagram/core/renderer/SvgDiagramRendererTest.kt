package io.github.dbmldiagram.core.renderer

import io.github.dbmldiagram.core.layout.LayeredDiagramLayoutEngine
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import kotlin.test.Test
import kotlin.test.assertContains

class SvgDiagramRendererTest {
    @Test fun `renders standalone svg with viewBox`() {
        val schema = TolerantDbmlParser().parse("Table users { id uuid [pk] }").schema!!
        val svg = SvgDiagramRenderer().render(schema, LayeredDiagramLayoutEngine().layout(schema))
        assertContains(svg, "<svg xmlns=\"http://www.w3.org/2000/svg\"")
        assertContains(svg, "viewBox=")
        assertContains(svg, "users")
        assertContains(svg, "PK")
    }
}
