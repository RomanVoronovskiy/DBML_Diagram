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
    @Test fun exportsChecksAndCompositeForeignKeyWithoutPhantomColumns() {
        val result = TolerantDbmlParser().parse("""
            Table departments {
              id uuid [pk]
              tenant uuid [pk]
            }
            Table tasks {
              id uuid [pk]
              department_id uuid [not null]
              tenant uuid [not null]
              planned integer [check: `planned >= 0`]
              completed integer
              checks {
                `completed IS NULL OR completed BETWEEN 0 AND planned` [name: 'ck_completed']
              }
            }
            Ref: tasks.(department_id, tenant) > departments.(id, tenant)
        """.trimIndent())
        assertFalse(result.errors.toString(), result.hasErrors)
        val schema = result.schema!!
        assertEquals(5, schema.tables.last().columns.size)
        val layout = LayeredDiagramLayoutEngine().layout(schema)
        val svg = SvgDiagramRenderer().render(schema, layout)
        assertTrue(svg.contains("CHECK (2)"))
        assertTrue(svg.contains("FK tasks.(department_id, tenant)"))
        val bytes = PngDiagramWriter.encode(svg)
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertNotNull(image)
        assertEquals(layout.width.toInt(), image.width)
        assertEquals(layout.height.toInt(), image.height)
        System.getProperty("dbml.preview.fixture")?.let { fixture ->
            val target = java.nio.file.Path.of(fixture).parent.resolve("constraints-preview.png")
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }
    }

    @Test fun rejectsDtdAndEntitiesBeforeXmlParsing() {
        for (prefix in listOf("<!DOCTYPE svg SYSTEM 'file:///must-not-read'>", "<!DOCTYPE svg [<!ENTITY secret SYSTEM 'file:///must-not-read'>]>", "<!ENTITY secret 'value'>")) {
            try {
                PngDiagramWriter.encode(prefix + "<svg xmlns='http://www.w3.org/2000/svg' width='20' height='20'/>")
                fail("DTD/entity input was accepted")
            } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("entity declarations")) }
        }
    }
    @Test fun refusesExternalImageResources() {
        try {
            PngDiagramWriter.encode("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="20" height="20"><image width="20" height="20" xlink:href="file:///must-not-read.png"/></svg>""")
            fail("External image should not be loaded")
        } catch (e: Exception) {
            val causes = generateSequence<Throwable>(e) { it.cause }.take(8).toList()
            assertTrue("Failure must be a resource security denial, not a failed file read: $e", causes.any { it is SecurityException || it.message.orEmpty().contains("external resource", true) })
        }
    }
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
