package io.github.dbmldiagram.core.layout

import io.github.dbmldiagram.core.model.DbmlReference
import io.github.dbmldiagram.core.model.DbmlSchema
import io.github.dbmldiagram.core.model.DbmlTableGroup

data class DiagramLayout(val nodes: List<TableNode>, val edges: List<RelationEdge>, val width: Double, val height: Double,
                         val groups: List<TableGroupNode> = emptyList(),
                         val originX: Double = 0.0, val originY: Double = 0.0,
                         val notes: List<NoteNode> = emptyList())

data class NoteNode(val id: String, val title: String, val text: String, val lines: List<String>, val color: String,
                    val x: Double, val y: Double, val width: Double, val height: Double)

data class TableGroupNode(val group: DbmlTableGroup, val tableIds: List<String>, val x: Double, val y: Double,
                          val width: Double, val height: Double, val color: String)
data class TableNode(val tableId: String, val x: Double, val y: Double, val width: Double, val height: Double)
data class DiagramPoint(val x: Double, val y: Double)
enum class TableSide { TOP, RIGHT, BOTTOM, LEFT }
data class ManualRelationRoute(val fromSide: TableSide, val toSide: TableSide, val control: DiagramPoint)
data class RelationEdge(
    val relation: DbmlReference,
    val points: List<DiagramPoint>,
    val fromTableId: String,
    val toTableId: String,
    val fromSide: TableSide,
    val toSide: TableSide,
    val control: DiagramPoint,
    val fromColumnOffset: Double,
    val toColumnOffset: Double,
)

/** Text baseline is 57px; the visual row center is 5px above it. */
fun columnRowOffset(index: Int): Double = 52.0 + index * 26.0

fun relationshipAnchor(node: TableNode, side: TableSide, columnOffset: Double): DiagramPoint = when (side) {
    TableSide.TOP -> DiagramPoint(node.x + node.width / 2, node.y)
    TableSide.RIGHT -> DiagramPoint(node.x + node.width, node.y + columnOffset)
    TableSide.BOTTOM -> DiagramPoint(node.x + node.width / 2, node.y + node.height)
    TableSide.LEFT -> DiagramPoint(node.x, node.y + columnOffset)
}

fun interface DiagramLayoutEngine {
    fun layout(schema: DbmlSchema): DiagramLayout
}
