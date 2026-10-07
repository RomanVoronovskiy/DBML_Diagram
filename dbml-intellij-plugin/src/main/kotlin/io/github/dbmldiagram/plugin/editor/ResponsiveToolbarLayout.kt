package io.github.dbmldiagram.plugin.editor

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/** FlowLayout wraps visually but reports a single-row height, clipping export buttons. */
internal class ResponsiveToolbarLayout : FlowLayout(LEFT, 4, 3) {
    override fun preferredLayoutSize(target: Container): Dimension = synchronized(target.treeLock) {
        val insets = target.insets
        val available = (target.width.takeIf { it > 0 } ?: target.parent?.width?.takeIf { it > 0 }
            ?: Int.MAX_VALUE) - insets.left - insets.right - hgap * 2
        var rowWidth = 0
        var rowHeight = 0
        var width = 0
        var height = 0
        for (component in target.components.filter { it.isVisible }) {
            val size = component.preferredSize
            val gap = if (rowWidth == 0) 0 else hgap
            if (rowWidth > 0 && rowWidth + gap + size.width > available) {
                width = maxOf(width, rowWidth)
                height += rowHeight + vgap
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += (if (rowWidth == 0) 0 else hgap) + size.width
            rowHeight = maxOf(rowHeight, size.height)
        }
        Dimension(maxOf(width, rowWidth) + insets.left + insets.right + hgap * 2,
            height + rowHeight + insets.top + insets.bottom + vgap * 2)
    }
}
