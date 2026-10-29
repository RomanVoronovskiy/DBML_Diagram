package io.github.dbmldiagram.plugin.database

import io.github.dbmldiagram.core.database.*
import io.github.dbmldiagram.core.layout.LayeredDiagramLayoutEngine
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.core.renderer.SvgDiagramRenderer
import io.github.dbmldiagram.plugin.preview.PngDiagramWriter
import io.github.dbmldiagram.plugin.preview.PreviewHtml
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream

class DatabaseDiagramTest {
    @Test fun databaseDriftIsEscapedColoredAndExportable() {
        val schema=TolerantDbmlParser().parse("""
            Table users {
              id bigint [pk]
              name varchar(255)
              email varchar(255)
            }
            Table orders {
              id bigint [pk]
              user_id bigint [ref: > users.id]
            }
        """.trimIndent()).schema!!
        val actual=DatabaseSnapshot(setOf("public"),listOf(DatabaseTable(DatabaseName("public","users"),
            listOf(DatabaseColumn("id","bigint",false,null),DatabaseColumn("name","text",true,null)),listOf(DatabaseKey("pk",listOf("id"),true)))))
        val comparison=PostgresSchemaDiff().compare(schema,actual)
        val base=SvgDiagramRenderer().render(schema,LayeredDiagramLayoutEngine().layout(schema))
        val svg=DatabaseDiagramOverlay.decorate(base,comparison)
        assertTrue(svg.contains("data-column=\"name\" data-db-drift=\"changed\""))
        assertTrue(svg.contains("data-column=\"email\" data-db-drift=\"missing\""))
        val malicious=comparison.copy(differences=comparison.differences+SchemaDifference("unsafe",SchemaDifferenceKind.CHANGED,"x","<script>alert(1)</script>","users"))
        val escaped=DatabaseDiagramOverlay.decorate(base,malicious)
        assertFalse(escaped.contains("<script>"))
        assertTrue(escaped.contains("&lt;script&gt;"))
        val png=PngDiagramWriter.encode(svg)
        assertNotNull(ImageIO.read(ByteArrayInputStream(png)))
        System.getProperty("dbml.preview.fixture")?.let {
            val dir=Path.of(it).parent.resolve("database-comparison")
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("drift.svg"),svg)
            Files.write(dir.resolve("drift.png"),png)
            for(dark in listOf(false,true)) Files.writeString(dir.resolve("preview-${if(dark) "dark" else "light"}.html"),
                PreviewHtml.page(svg,"PostgreSQL: ${comparison.differences.size} differences. Blue: missing in DB; amber: changed. Synthetic example.",dark))
        }
    }
}
