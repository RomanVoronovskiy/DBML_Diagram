package io.github.dbmldiagram.plugin.preview

import io.github.dbmldiagram.core.layout.DiagramPoint
import io.github.dbmldiagram.core.layout.LayeredDiagramLayoutEngine
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.core.renderer.SvgDiagramRenderer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PreviewHtmlTest {
    @Test fun producesInteractiveFixtureWithClickableAnchors() {
        val schema = TolerantDbmlParser().parse("""
            Table orders {
              id uuid [pk]
              user_id uuid
            }
            Table users {
              id uuid [pk]
              email text
            }
            Ref: orders.user_id > users.id
        """.trimIndent()).schema!!
        val layout = LayeredDiagramLayoutEngine(mapOf("orders" to DiagramPoint(48.0, 412.0), "users" to DiagramPoint(520.0, 160.0))).layout(schema)
        val svg = SvgDiagramRenderer().render(schema, layout)
        val html = PreviewHtml.page(svg, null, true,
            "window.savedPayloads.push(payload);", PreviewViewState(1.0, 20.0, 20.0))
            .replace("let scale=", "window.savedPayloads=[];let scale=")
        assertTrue(html.contains(".route-snap{pointer-events:all"))
        assertTrue(html.contains("window.addEventListener('pointerup',finishPointerDrag)"))
        System.getProperty("dbml.preview.fixture")?.let { path ->
            val file = Path.of(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, html)
            val selectedHtml = PreviewHtml.page(svg, null, true, "window.savedPayloads.push(payload);",
                PreviewViewState(1.0, 20.0, 20.0, schema.references.single().routeId()))
                .replace("let scale=", "window.savedPayloads=[];let scale=")
            Files.writeString(file.resolveSibling("preview-selected-fixture.html"), selectedHtml)
        }
    }

    @Test fun escapesSelectedRelationBeforeEmbeddingInScript() {
        val html = PreviewHtml.page(null, null, true, viewState = PreviewViewState(1.0, 20.0, 20.0, "x'</script>\n\\\u2028"))
        assertTrue(html.contains("const initialSelectedRelation='x\\'\\u003c/script>\\n\\\\\\u2028';"))
        assertFalse(html.contains("x'</script>"))
    }
}
