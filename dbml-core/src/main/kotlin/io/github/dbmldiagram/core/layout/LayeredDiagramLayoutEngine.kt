package io.github.dbmldiagram.core.layout

import io.github.dbmldiagram.core.model.DbmlColumnRef
import io.github.dbmldiagram.core.model.DbmlSchema
import io.github.dbmldiagram.core.model.DbmlTable
import kotlin.math.abs
import kotlin.math.max

/** Deterministic, dependency-free layered layout suitable for offline previews. */
class LayeredDiagramLayoutEngine(
    private val manualPositions: Map<String, DiagramPoint> = emptyMap(),
    private val manualRoutes: Map<String, ManualRelationRoute> = emptyMap(),
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
            val defaultSides = defaultSides(from, to)
            val manual = manualRoutes[ref.routeId()]
            val fromSide = manual?.fromSide ?: defaultSides.first
            val toSide = manual?.toSide ?: defaultSides.second
            val start = anchor(from, fromSide)
            val end = anchor(to, toSide)
            val startExit = exit(start, fromSide)
            val endExit = exit(end, toSide)
            val control = manual?.control ?: DiagramPoint(
                (startExit.x + endExit.x) / 2,
                (startExit.y + endExit.y) / 2,
            )
            val points = (listOf(start) + routeLeg(startExit, fromSide, control) +
                routeLeg(endExit, toSide, control).reversed() + end).removeConsecutiveDuplicates()
            RelationEdge(
                ref,
                points,
                fromTable.qualifiedName,
                toTable.qualifiedName,
                fromSide,
                toSide,
                control,
            )
        }
        val routePoints = edges.flatMap { it.points }
        val width = max(640.0, max(positionedNodes.maxOf { it.x + it.width }, routePoints.maxOfOrNull { it.x } ?: 0.0) + margin)
        val height = max(360.0, max(positionedNodes.maxOf { it.y + it.height }, routePoints.maxOfOrNull { it.y } ?: 0.0) + margin)
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

    private fun defaultSides(from: TableNode, to: TableNode): Pair<TableSide, TableSide> {
        val dx = (to.x + to.width / 2) - (from.x + from.width / 2)
        val dy = (to.y + to.height / 2) - (from.y + from.height / 2)
        return if (abs(dx) >= abs(dy)) {
            if (dx >= 0) TableSide.RIGHT to TableSide.LEFT else TableSide.LEFT to TableSide.RIGHT
        } else {
            if (dy >= 0) TableSide.BOTTOM to TableSide.TOP else TableSide.TOP to TableSide.BOTTOM
        }
    }

    private fun anchor(node: TableNode, side: TableSide): DiagramPoint = when (side) {
        TableSide.TOP -> DiagramPoint(node.x + node.width / 2, node.y)
        TableSide.RIGHT -> DiagramPoint(node.x + node.width, node.y + node.height / 2)
        TableSide.BOTTOM -> DiagramPoint(node.x + node.width / 2, node.y + node.height)
        TableSide.LEFT -> DiagramPoint(node.x, node.y + node.height / 2)
    }

    private fun exit(point: DiagramPoint, side: TableSide, distance: Double = 24.0): DiagramPoint = when (side) {
        TableSide.TOP -> point.copy(y = point.y - distance)
        TableSide.RIGHT -> point.copy(x = point.x + distance)
        TableSide.BOTTOM -> point.copy(y = point.y + distance)
        TableSide.LEFT -> point.copy(x = point.x - distance)
    }

    private fun routeLeg(exit: DiagramPoint, side: TableSide, control: DiagramPoint): List<DiagramPoint> = when (side) {
        TableSide.RIGHT -> {
            val safeX = max(exit.x, control.x)
            listOf(exit, DiagramPoint(safeX, exit.y), DiagramPoint(safeX, control.y), control)
        }
        TableSide.LEFT -> {
            val safeX = minOf(exit.x, control.x)
            listOf(exit, DiagramPoint(safeX, exit.y), DiagramPoint(safeX, control.y), control)
        }
        TableSide.BOTTOM -> {
            val safeY = max(exit.y, control.y)
            listOf(exit, DiagramPoint(exit.x, safeY), DiagramPoint(control.x, safeY), control)
        }
        TableSide.TOP -> {
            val safeY = minOf(exit.y, control.y)
            listOf(exit, DiagramPoint(exit.x, safeY), DiagramPoint(control.x, safeY), control)
        }
    }

    private fun List<DiagramPoint>.removeConsecutiveDuplicates(): List<DiagramPoint> =
        fold(mutableListOf()) { result, point ->
            if (result.lastOrNull() != point) result += point
            result
        }
}
