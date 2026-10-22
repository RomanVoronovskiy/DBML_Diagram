package io.github.dbmldiagram.plugin.preview

import io.github.dbmldiagram.core.layout.DiagramPoint
import io.github.dbmldiagram.core.layout.LayeredDiagramLayoutEngine
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.core.renderer.SvgDiagramRenderer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Only the committed synthetic example is turned into shareable preview artifacts. */
class TableGroupsPreviewTest {
    @Test fun exportGroupFramesAndInteractiveThemeFixtures() {
        val source = Files.readString(Path.of(System.getProperty("dbml.groups.example")))
        val result = TolerantDbmlParser().parse(source)
        assertEquals(emptyList<Any>(), result.errors)
        val schema = result.schema!!
        val layout = LayeredDiagramLayoutEngine().layout(schema)
        assertEquals(3, layout.groups.size)
        assertEquals(6, layout.nodes.size)
        val svg = SvgDiagramRenderer().render(schema, layout)
        val bytes = PngDiagramWriter.encode(svg)
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(layout.width.toInt(), image.width)
        assertEquals(layout.height.toInt(), image.height)
        for (frame in layout.groups) {
            // Sample the middle of a clear frame border, not a table, text, or arrow.
            val actual = java.awt.Color(image.getRGB((frame.x + frame.width - 40).toInt(), frame.y.toInt()), true)
            val expected = java.awt.Color.decode(frame.color)
            assertTrue("Frame border must remain colored in PNG: $actual / $expected", actual.alpha > 100)
            // The stroke uses 85% opacity; allow the white PNG matte beneath it.
            assertTrue("Red channel: $actual / $expected", kotlin.math.abs(actual.red - expected.red) < 45)
            assertTrue("Green channel: $actual / $expected", kotlin.math.abs(actual.green - expected.green) < 45)
            assertTrue("Blue channel: $actual / $expected", kotlin.math.abs(actual.blue - expected.blue) < 45)
        }
        System.getProperty("dbml.preview.fixture")?.let { fixture ->
            val directory = Path.of(fixture).parent.resolve("table-groups")
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("table-groups.svg"), svg)
            Files.write(directory.resolve("table-groups.png"), bytes)
            for (dark in listOf(false, true)) {
                val html = PreviewHtml.page(svg, null, dark, "window.savedPayloads.push(payload);")
                    .replace("let scale=", "window.savedPayloads=[];let scale=")
                Files.writeString(directory.resolve("preview-${if (dark) "dark" else "light"}.html"), html)
            }
            val moved = LayeredDiagramLayoutEngine(mapOf("customers" to DiagramPoint(1500.0, 1100.0))).layout(schema)
            Files.writeString(directory.resolve("preview-restored.html"), PreviewHtml.page(SvgDiagramRenderer().render(schema, moved), null, true))
        }
    }
}
