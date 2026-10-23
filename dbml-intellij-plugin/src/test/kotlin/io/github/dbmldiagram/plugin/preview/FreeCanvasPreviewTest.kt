package io.github.dbmldiagram.plugin.preview

import io.github.dbmldiagram.core.layout.*
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.core.renderer.SvgDiagramRenderer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class FreeCanvasPreviewTest {
    @Test fun negativeWorldCoordinatesExportAndGenerateRealInteractiveFixtures() {
        val result = TolerantDbmlParser().parse("""
            Table users {
              id uuid [pk]
              email text [unique]
            }
            Table orders {
              id uuid [pk]
              user_id uuid [ref: > users.id]
              product_id uuid [ref: > products.id]
            }
            Table products {
              id uuid [pk]
              name text
            }
            Table audit { id uuid [pk] }
            TableGroup Accounts [color: #3b82f6] { users orders }
            TableGroup Catalog [color: #10b981] { products }
        """.trimIndent())
        assertFalse(result.errors.toString(), result.hasErrors)
        val schema = result.schema!!
        val layout = LayeredDiagramLayoutEngine(mapOf("users" to DiagramPoint(-700.0, -350.0),
            "orders" to DiagramPoint(-150.0, -100.0), "products" to DiagramPoint(450.0, -300.0), "audit" to DiagramPoint(-200.0, 400.0))).layout(schema)
        val svg = SvgDiagramRenderer().render(schema, layout)
        val bytes = PngDiagramWriter.encode(svg)
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(layout.width.toInt(), image.width)
        assertEquals(layout.height.toInt(), image.height)
        assertTrue(image.width < 2000 && image.height < 1500)
        val users = layout.nodes.single { it.tableId == "users" }
        assertTrue(java.awt.Color(image.getRGB((users.x - layout.originX + 10).toInt(),
            (users.y - layout.originY + 10).toInt()), true).alpha > 200)
        val frame = layout.groups.first()
        val tint = java.awt.Color(image.getRGB((frame.x - layout.originX + 12).toInt(),
            (frame.y - layout.originY + 60).toInt()), true)
        assertTrue("Group backgrounds must remain translucent in exported PNG", tint.alpha in 1..40)
        System.getProperty("dbml.preview.fixture")?.let { fixture ->
            val directory = Path.of(fixture).parent.resolve("free-canvas")
            Files.createDirectories(directory)
            Files.write(directory.resolve("negative-world.png"), bytes)
            Files.writeString(directory.resolve("negative-world.svg"), svg)
            for (dark in listOf(false, true)) {
                val html = PreviewHtml.page(svg, null, dark, "window.savedPayloads.push(payload);", PreviewViewState(.7, 600.0, 500.0))
                    .replace("let scale=", "window.savedPayloads=[];let scale=")
                Files.writeString(directory.resolve("preview-${if (dark) "dark" else "light"}.html"), html)
            }
            val restored = PreviewHtml.page(svg, null, true, "window.savedPayloads.push(payload);",
                PreviewViewState(.6, 1000.0, 700.0, grid = false, snap = true))
                .replace("let scale=", "window.savedPayloads=[];let scale=")
            Files.writeString(directory.resolve("preview-restored.html"), restored)
        }
    }
}
