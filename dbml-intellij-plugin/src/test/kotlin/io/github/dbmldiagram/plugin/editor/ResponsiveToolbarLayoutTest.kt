package io.github.dbmldiagram.plugin.editor

import org.junit.Assert.*
import org.junit.Test
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JPanel

class ResponsiveToolbarLayoutTest {
    @Test fun pngExportIsNotClippedInNarrowPanels() {
        val toolbar = JPanel(ResponsiveToolbarLayout())
        val labels = listOf("Fit", "−", "100%", "+", "Reset layout", "Refresh", "DDL dialect", "PostgreSQL", "Export DDL", "Export SVG", "Export PNG")
        labels.forEach { toolbar.add(JButton(it).apply { preferredSize = Dimension(100, 30) }) }
        for (width in listOf(320, 480, 800, 1200)) {
            toolbar.setSize(width, 1)
            val height = toolbar.preferredSize.height
            toolbar.setSize(width, height)
            toolbar.doLayout()
            val png = toolbar.components.last()
            assertTrue("PNG button clipped at $width px", png.y >= 0 && png.y + png.height <= height)
            assertTrue("PNG button horizontally clipped at $width px", png.x >= 0 && png.x + png.width <= width)
        }
    }
}
