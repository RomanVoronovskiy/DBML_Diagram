package io.github.dbmldiagram.core.layout

import io.github.dbmldiagram.core.model.DbmlSchema
import kotlin.math.max

/** Deterministic, dependency-free layered layout suitable for offline previews. */
class LayeredDiagramLayoutEngine : DiagramLayoutEngine {
    override fun layout(schema: DbmlSchema): DiagramLayout {
        if (schema.tables.isEmpty()) return DiagramLayout(emptyList(), emptyList(), 640.0, 360.0)
        val tablesByName = schema.tables.associateBy { it.qualifiedName.lowercase() }
        val incoming = schema.tables.associate { it.qualifiedName.lowercase() to 0 }.toMutableMap()
        val outgoing = mutableMapOf<String, MutableList<String>>()
        schema.references.forEach { ref ->
            val from = ref.from.tableName.lowercase(); val to = ref.to.tableName.lowercase()
            if (from in tablesByName && to in tablesByName && from != to) {
                outgoing.getOrPut(to) { mutableListOf() } += from
                incoming[from] = (incoming[from] ?: 0) + 1
            }
        }
        val levels = mutableMapOf<String, Int>()
        val queue = ArrayDeque(incoming.filterValues { it == 0 }.keys)
        queue.forEach { levels[it] = 0 }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in outgoing[current].orEmpty()) {
                levels[next] = max(levels[next] ?: 0, (levels[current] ?: 0) + 1)
                incoming[next] = (incoming[next] ?: 1) - 1
                if (incoming[next] == 0) queue += next
            }
        }
        schema.tables.forEachIndexed { index, table -> levels.putIfAbsent(table.qualifiedName.lowercase(), index % 4) }

        val byLevel = schema.tables.groupBy { levels[it.qualifiedName.lowercase()] ?: 0 }.toSortedMap()
        val nodes = mutableListOf<TableNode>()
        val horizontalGap = 150.0; val verticalGap = 70.0; val margin = 48.0
        var x = margin
        byLevel.forEach { (_, tables) ->
            val widths = tables.map { tableWidth(it.name, it.columns.map { c -> c.name to c.type }) }
            val columnWidth = widths.maxOrNull() ?: 260.0
            var y = margin
            tables.forEachIndexed { index, table ->
                val height = 48.0 + table.columns.size * 26.0
                nodes += TableNode(table.qualifiedName, x, y, widths[index], height)
                y += height + verticalGap
            }
            x += columnWidth + horizontalGap
        }
        val nodeMap = nodes.associateBy { it.tableId.lowercase() }
        val edges = schema.references.mapNotNull { ref ->
            val from = nodeMap[ref.from.tableName.lowercase()] ?: return@mapNotNull null
            val to = nodeMap[ref.to.tableName.lowercase()] ?: return@mapNotNull null
            val fromRow = schema.tables.firstOrNull { it.qualifiedName.equals(from.tableId, true) }?.columns?.indexOfFirst { it.name.equals(ref.from.column, true) } ?: -1
            val toRow = schema.tables.firstOrNull { it.qualifiedName.equals(to.tableId, true) }?.columns?.indexOfFirst { it.name.equals(ref.to.column, true) } ?: -1
            val fromY = from.y + if (fromRow >= 0) 48.0 + fromRow * 26.0 + 13.0 else from.height / 2
            val toY = to.y + if (toRow >= 0) 48.0 + toRow * 26.0 + 13.0 else to.height / 2
            val leftToRight = from.x <= to.x
            val p1 = DiagramPoint(if (leftToRight) from.x + from.width else from.x, fromY)
            val p4 = DiagramPoint(if (leftToRight) to.x else to.x + to.width, toY)
            val middleX = (p1.x + p4.x) / 2
            RelationEdge(ref, listOf(p1, DiagramPoint(middleX, p1.y), DiagramPoint(middleX, p4.y), p4))
        }
        val width = max(640.0, nodes.maxOf { it.x + it.width } + margin)
        val height = max(360.0, nodes.maxOf { it.y + it.height } + margin)
        return DiagramLayout(nodes, edges, width, height)
    }

    private fun tableWidth(name: String, columns: List<Pair<String, String>>): Double {
        val longest = max(name.length + 8, columns.maxOfOrNull { (column, type) -> column.length + type.length + 12 } ?: 0)
        return (longest * 7.4 + 32).coerceIn(240.0, 480.0)
    }
}
