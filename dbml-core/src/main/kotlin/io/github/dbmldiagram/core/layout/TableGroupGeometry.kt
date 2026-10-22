package io.github.dbmldiagram.core.layout

import io.github.dbmldiagram.core.model.DbmlTableGroup

/** Shared geometry contract with PreviewHtml.updateTableGroups. Frames never route as obstacles. */
object TableGroupGeometry {
    const val PADDING = 24.0
    const val HEADER = 40.0
    private val palette = listOf("#3b82f6", "#10b981", "#f59e0b", "#8b5cf6", "#ec4899", "#06b6d4")

    fun label(name: String): String = if (name.length <= 72) name else name.take(69) + "…"
    fun minimumWidth(group: DbmlTableGroup, count: Int): Double = maxOf(240.0, label(group.qualifiedName).length * 14.0 + 108.0 + count.toString().length * 7)

    fun color(group: DbmlTableGroup): String {
        val raw = group.color?.takeIf { Regex("#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?").matches(it) }?.lowercase()
        return if (raw == null) palette[(group.qualifiedName.hashCode().ushr(1)) % palette.size]
        else if (raw.length == 4) "#" + raw.drop(1).flatMap { listOf(it, it) }.joinToString("") else raw
    }

    fun frame(group: DbmlTableGroup, nodes: List<TableNode>): TableGroupNode? {
        if (nodes.isEmpty()) return null
        val x = nodes.minOf { it.x } - PADDING
        val y = nodes.minOf { it.y } - HEADER
        val width = maxOf(minimumWidth(group, nodes.size), nodes.maxOf { it.x + it.width } - x + PADDING)
        val height = nodes.maxOf { it.y + it.height } - y + PADDING
        return TableGroupNode(group, nodes.map { it.tableId }, x, y, width, height, color(group))
    }
}
