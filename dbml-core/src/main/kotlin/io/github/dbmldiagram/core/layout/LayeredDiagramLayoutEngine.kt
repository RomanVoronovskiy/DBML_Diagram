package io.github.dbmldiagram.core.layout

import io.github.dbmldiagram.core.model.DbmlColumnRef
import io.github.dbmldiagram.core.model.DbmlSchema
import io.github.dbmldiagram.core.model.DbmlTable
import kotlin.math.max

/** Deterministic, dependency-free layered layout suitable for offline previews. */
class LayeredDiagramLayoutEngine(
    private val manualPositions: Map<String, DiagramPoint> = emptyMap(),
) : DiagramLayoutEngine {
    override fun layout(schema: DbmlSchema): DiagramLayout {
        if (schema.tables.isEmpty()) return DiagramLayout(emptyList(), emptyList(), 640.0, 360.0)
        val tablesByName = schema.tables.associateBy { it.qualifiedName.lowercase() }
        val incoming = schema.tables.associate { it.qualifiedName.lowercase() to 0 }.toMutableMap()
        val outgoing = mutableMapOf<String, MutableList<String>>()
        schema.references.forEach { ref ->
            val source = resolveTable(schema, ref.foreignKeyEndpoint() ?: ref.from)
            val target = resolveTable(schema, ref.referencedEndpoint() ?: ref.to)
            val from = source?.qualifiedName?.lowercase() ?: return@forEach
            val to = target?.qualifiedName?.lowercase() ?: return@forEach
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
        val positionsByTable = manualPositions.entries.associate { it.key.lowercase() to it.value }
        val positionedNodes = nodes.map { node ->
            positionsByTable[node.tableId.lowercase()]?.let { position ->
                node.copy(x = position.x.coerceAtLeast(margin), y = position.y.coerceAtLeast(margin))
            } ?: node
        }
        val nodeMap = positionedNodes.associateBy { it.tableId.lowercase() }
        val edges = schema.references.mapNotNull { ref ->
            val fromRef = ref.foreignKeyEndpoint() ?: ref.from
            val toRef = ref.referencedEndpoint() ?: ref.to
            val fromTable = resolveTable(schema, fromRef) ?: return@mapNotNull null
            val toTable = resolveTable(schema, toRef) ?: return@mapNotNull null
            val from = nodeMap[fromTable.qualifiedName.lowercase()] ?: return@mapNotNull null
            val to = nodeMap[toTable.qualifiedName.lowercase()] ?: return@mapNotNull null
            val fromRow = fromTable.columns.indexOfFirst { it.name.equals(fromRef.column, true) }
            val toRow = toTable.columns.indexOfFirst { it.name.equals(toRef.column, true) }
            val fromY = from.y + if (fromRow >= 0) 48.0 + fromRow * 26.0 + 13.0 else from.height / 2
            val toY = to.y + if (toRow >= 0) 48.0 + toRow * 26.0 + 13.0 else to.height / 2
            val leftToRight = from.x <= to.x
            val p1 = DiagramPoint(if (leftToRight) from.x + from.width else from.x, fromY)
            val p4 = DiagramPoint(if (leftToRight) to.x else to.x + to.width, toY)
            val middleX = (p1.x + p4.x) / 2
            RelationEdge(ref, listOf(p1, DiagramPoint(middleX, p1.y), DiagramPoint(middleX, p4.y), p4))
        }
        val width = max(640.0, positionedNodes.maxOf { it.x + it.width } + margin)
        val height = max(360.0, positionedNodes.maxOf { it.y + it.height } + margin)
        return DiagramLayout(positionedNodes, edges, width, height)
    }

    private fun tableWidth(name: String, columns: List<Pair<String, String>>): Double {
        val longest = max(name.length + 8, columns.maxOfOrNull { (column, type) -> column.length + type.length + 12 } ?: 0)
        return (longest * 7.4 + 170).coerceIn(360.0, 600.0)
    }

    private fun resolveTable(schema: DbmlSchema, reference: DbmlColumnRef): DbmlTable? =
        schema.tables.firstOrNull {
            it.qualifiedName.equals(reference.tableName, true) ||
                (reference.schema == null && it.alias?.equals(reference.table, true) == true)
        }
}
