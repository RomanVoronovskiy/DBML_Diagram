package io.github.dbmldiagram.core.layout

import io.github.dbmldiagram.core.model.DbmlReference
import io.github.dbmldiagram.core.model.DbmlSchema

data class DiagramLayout(val nodes: List<TableNode>, val edges: List<RelationEdge>, val width: Double, val height: Double)
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
)

fun interface DiagramLayoutEngine {
    fun layout(schema: DbmlSchema): DiagramLayout
}
