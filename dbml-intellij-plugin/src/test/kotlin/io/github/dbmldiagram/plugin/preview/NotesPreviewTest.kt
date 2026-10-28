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

class NotesPreviewTest {
    @Test fun notesExportToPngAndCreateInteractiveFixtures() {
        val result = TolerantDbmlParser().parse("""
            Project education {
              Note: 'Education schema: students, courses and lessons.'
            }
            Note reminder [color: #cef] { 'Review course deletion rules before migration.' }
            Note checklist {
              '''Review constraints
              Check indexes
              Export SQL'''
            }
            Table courses { id bigint [pk] }
            Table lessons {
              id bigint [pk]
              course_id bigint [ref: > courses.id]
            }
            TableGroup Education [color: #10b981] { courses lessons }
        """.trimIndent())
        assertFalse(result.errors.toString(), result.hasErrors)
        val schema = result.schema!!
        val layout = LayeredDiagramLayoutEngine(mapOf("courses" to DiagramPoint(150.0, 60.0), "lessons" to DiagramPoint(150.0, 460.0))).layout(schema)
        val svg = SvgDiagramRenderer().render(schema, layout)
        val png = PngDiagramWriter.encode(svg)
        val image = ImageIO.read(ByteArrayInputStream(png))
        val note = layout.notes.first()
        val pixel = java.awt.Color(image.getRGB((note.x - layout.originX + 5).toInt(), (note.y - layout.originY + 5).toInt()), true)
        assertTrue(pixel.alpha > 200 && pixel.red > 200)
        System.getProperty("dbml.preview.fixture")?.let { fixture ->
            val dir = Path.of(fixture).parent.resolve("notes")
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("notes.svg"), svg)
            Files.write(dir.resolve("notes.png"), png)
            for (dark in listOf(false,true)) {
                Files.writeString(dir.resolve("preview-${if(dark) "dark" else "light"}.html"),
                    PreviewHtml.page(svg, null, dark, "window.savedPayloads.push(payload);", PreviewViewState(.85, 360.0, 150.0))
                        .replace("let scale=", "window.savedPayloads=[];let scale="))
            }
        }
    }
}
