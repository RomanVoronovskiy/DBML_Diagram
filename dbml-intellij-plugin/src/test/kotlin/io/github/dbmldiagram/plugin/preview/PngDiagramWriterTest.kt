package io.github.dbmldiagram.plugin.preview

import io.github.dbmldiagram.core.layout.DiagramPoint
import io.github.dbmldiagram.core.layout.LayeredDiagramLayoutEngine
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.core.renderer.SvgDiagramRenderer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import java.nio.file.Files

class PngDiagramWriterTest {
    @Test fun exportsDiagramWithConstraintsArrowsAndManualPositions() {
        val schema = TolerantDbmlParser().parse("""
            Table users {
              id uuid [pk]
              email text [unique]
            }
            Table orders {
              id uuid [pk]
              user_id uuid
            }
            Ref: orders.user_id > users.id
        """.trimIndent()).schema!!
        val layout = LayeredDiagramLayoutEngine(mapOf("orders" to DiagramPoint(750.0, 440.0))).layout(schema)
        val svg = SvgDiagramRenderer().render(schema, layout)
        val bytes = PngDiagramWriter.encode(svg)
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertNotNull("Export must produce a decodable PNG", image)
        assertEquals(layout.width.toInt(), image.width)
        assertEquals(layout.height.toInt(), image.height)
        assertEquals("Header must retain the SVG fallback color", 0xffeef1f5.toInt(), image.getRGB(770, 455))
        assertEquals("Table background must be white", 0xffffffff.toInt(), image.getRGB(770, 480))
        var bluePixels = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getRGB(x, y) == 0xff568af2.toInt()) bluePixels++
        }
        assertTrue("Arrows and relationship lines must remain visible", bluePixels > 100)
        assertTrue(bytes.size > 1000)
    }

    @Test fun rejectsOversizedOrInvalidDimensionsBeforeRasterization() {
        for (dimensions in listOf("width=\"20000\" height=\"2\"", "height='7000' width='7000'", "width='0' height='20'", "width='NaN' height='20'")) {
            try {
                PngDiagramWriter.encode("<svg xmlns=\"http://www.w3.org/2000/svg\" $dimensions/>")
                fail("Unsafe dimensions were accepted: $dimensions")
            } catch (_: IllegalArgumentException) {
                // Expected: invalid dimensions never reach the memory-intensive transcoder.
            } catch (_: IllegalStateException) {
                // Non-numeric dimensions.
            }
        }
        assertEquals(320 to 200, PngDiagramWriter.dimensions("<svg height='200' width='320'/>") )
    }

    @Test fun writesDecodableFileAndKeepsExistingFileOnFailure() {
        val directory = Files.createTempDirectory("dbml-png-test-")
        val target = directory.resolve("diagram.png")
        try {
            PngDiagramWriter.write("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"20\"><rect width=\"20\" height=\"20\" fill=\"#568af2\"/></svg>", target)
            assertEquals(20, ImageIO.read(target.toFile()).width)
            val original = Files.readAllBytes(target)
            try {
                PngDiagramWriter.write("<svg width='0' height='20'/>", target)
                fail("Invalid export should fail")
            } catch (_: IllegalArgumentException) {
                assertArrayEquals(original, Files.readAllBytes(target))
            }
            Files.list(directory).use { assertEquals(1L, it.count()) }
        } finally {
            Files.deleteIfExists(target)
            Files.deleteIfExists(directory)
        }
    }
}
