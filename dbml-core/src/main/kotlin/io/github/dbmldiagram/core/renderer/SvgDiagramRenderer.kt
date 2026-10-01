package io.github.dbmldiagram.core.renderer

import io.github.dbmldiagram.core.layout.DiagramLayout
import io.github.dbmldiagram.core.layout.DiagramPoint
import io.github.dbmldiagram.core.layout.TableNode
import io.github.dbmldiagram.core.layout.TableSide
import io.github.dbmldiagram.core.model.DbmlCardinality
import io.github.dbmldiagram.core.model.DbmlReference
import io.github.dbmldiagram.core.model.DbmlSchema

class SvgDiagramRenderer : DiagramRenderer {
    override fun render(schema: DbmlSchema, layout: DiagramLayout): String = buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" width="${layout.width.toInt()}" height="${layout.height.toInt()}" viewBox="0 0 ${layout.width.toInt()} ${layout.height.toInt()}" role="img" aria-label="DBML entity relationship diagram">""")
        append("""<defs><style>.table{fill:var(--dbml-card,#fff);stroke:var(--dbml-border,#9aa0a6);stroke-width:1}.head{fill:var(--dbml-header,#eef1f5)}.title{font:600 14px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:var(--dbml-text,#24292f)}.col{font:12px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:var(--dbml-text,#24292f)}.type{fill:var(--dbml-muted,#65717e)}.badge{font:700 8px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:var(--dbml-accent,#3d65a5);letter-spacing:.15px}.separator{stroke:var(--dbml-edge,#79838e);stroke-width:1}.relation-halo{fill:none;stroke:var(--dbml-bg,#fff);stroke-width:5;stroke-linejoin:round;stroke-linecap:round}.relation{fill:none;stroke:var(--dbml-relation,#568af2);stroke-width:1.8;stroke-linejoin:round}.relation-hit{fill:none;stroke:transparent;stroke-width:14;pointer-events:stroke;cursor:move}.relation-route.selected .relation{stroke-width:2.8}.cardinality{font:700 10px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:var(--dbml-relation,#568af2);stroke:var(--dbml-bg,#fff);stroke-width:3;paint-order:stroke fill}.route-control,.route-endpoint,.route-snap{display:none}.route-controls.selected .route-control,.route-controls.selected .route-endpoint,.route-controls.selected .route-snap{display:block}.route-control{fill:var(--dbml-relation,#568af2);stroke:var(--dbml-bg,#fff);stroke-width:2;cursor:move}.route-endpoint{fill:var(--dbml-bg,#fff);stroke:var(--dbml-relation,#568af2);stroke-width:2;cursor:crosshair}.route-snap{fill:var(--dbml-bg,#fff);stroke:var(--dbml-relation,#568af2);stroke-width:1;opacity:.75;pointer-events:none}.relation-arrow{fill:var(--dbml-relation,#568af2)}</style><marker id="fk-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth"><path class="relation-arrow" d="M0,0 L8,4 L0,8 Z"/></marker></defs>""")

        val foreignKeys = schema.references.mapNotNull { reference ->
            val endpoint = reference.foreignKeyEndpoint() ?: return@mapNotNull null
            val table = schema.tables.firstOrNull {
                it.qualifiedName.equals(endpoint.tableName, true) ||
                    (endpoint.schema == null && it.alias?.equals(endpoint.table, true) == true)
            } ?: return@mapNotNull null
            "${table.qualifiedName}.${endpoint.column}".lowercase()
        }.toSet()

        layout.nodes.forEach { node ->
            val table = schema.tables.first { it.qualifiedName.equals(node.tableId, true) }
            val primaryKeyColumns = (
                table.indexes.firstOrNull { it.primaryKey }?.columns
                    ?: table.columns.filter { it.primaryKey }.map { it.name }
                ).map(String::lowercase).toSet()
            val uniqueColumns = table.indexes.filter { it.unique }.flatMap { it.columns }.map(String::lowercase).toSet()
            append("<g data-table=\"").append(escape(table.qualifiedName))
                .append("\" data-x=\"").append(node.x).append("\" data-y=\"").append(node.y)
                .append("\" data-width=\"").append(node.width).append("\" data-height=\"").append(node.height).append("\">")
            append("<rect class=\"table\" x=\"${node.x}\" y=\"${node.y}\" width=\"${node.width}\" height=\"${node.height}\" rx=\"5\"/>")
            append("<path class=\"head\" d=\"M${node.x + 5},${node.y} H${node.x + node.width - 5} Q${node.x + node.width},${node.y} ${node.x + node.width},${node.y + 5} V${node.y + 38} H${node.x} V${node.y + 5} Q${node.x},${node.y} ${node.x + 5},${node.y}Z\"/>")
            append("<line class=\"separator\" x1=\"${node.x}\" y1=\"${node.y + 38}\" x2=\"${node.x + node.width}\" y2=\"${node.y + 38}\"/>")
            append("<text class=\"title\" x=\"${node.x + 14}\" y=\"${node.y + 25}\">${escape(table.qualifiedName)}</text>")
            table.columns.forEachIndexed { index, column ->
                val y = node.y + 57 + index * 26
                val badges = buildList {
                    val primaryKey = column.name.lowercase() in primaryKeyColumns
                    if (primaryKey) add("PK")
                    if ("${table.qualifiedName}.${column.name}".lowercase() in foreignKeys) add("FK")
                    if (column.unique || column.name.lowercase() in uniqueColumns) add("UNIQ")
                    if (!column.nullable || primaryKey) add("NOT_NULL")
                }.joinToString(" · ")
                append("<text class=\"col\" x=\"${node.x + 12}\" y=\"$y\">${escape(column.name)}</text>")
                append("<text class=\"badge\" text-anchor=\"end\" x=\"${node.x + node.width - 105}\" y=\"$y\">${escape(badges)}</text>")
                append("<text class=\"col type\" text-anchor=\"end\" x=\"${node.x + node.width - 12}\" y=\"$y\">${escape(column.type)}</text>")
            }
            append("</g>")
        }

        val nodes = layout.nodes.associateBy { it.tableId.lowercase() }
        val controls = StringBuilder()
        layout.edges.forEach { edge ->
            val relationId = edge.relation.routeId()
            val escapedId = escape(relationId)
            val points = edge.points.joinToString(" ") { "${it.x},${it.y}" }
            val directed = edge.relation.foreignKeyEndpoint() != null
            append("<g class=\"relation-route\" data-relation-id=\"").append(escapedId)
                .append("\" data-from-table=\"").append(escape(edge.fromTableId))
                .append("\" data-to-table=\"").append(escape(edge.toTableId))
                .append("\" data-from-side=\"").append(edge.fromSide.name.lowercase())
                .append("\" data-to-side=\"").append(edge.toSide.name.lowercase())
                .append("\" data-control-x=\"").append(edge.control.x)
                .append("\" data-control-y=\"").append(edge.control.y).append("\">")
            append("<polyline class=\"relation-halo\" points=\"").append(points).append("\"/>")
            append("<polyline class=\"relation\" points=\"").append(points).append("\"")
            if (directed) append(" marker-end=\"url(#fk-arrow)\"")
            append("><title>").append(escape(relationTitle(edge.relation))).append("</title></polyline>")
            append("<polyline class=\"relation-hit\" points=\"").append(points).append("\"/>")
            val (fromMark, toMark) = if (directed) {
                (if (edge.relation.cardinality == DbmlCardinality.ONE_TO_ONE) "1" else "N") to "1"
            } else {
                "N" to "N"
            }
            appendCardinality(edge.points.first(), edge.fromSide, fromMark, "from")
            appendCardinality(edge.points.last(), edge.toSide, toMark, "to")
            append("</g>")

            val fromNode = nodes.getValue(edge.fromTableId.lowercase())
            val toNode = nodes.getValue(edge.toTableId.lowercase())
            controls.append("<g class=\"route-controls\" data-controls-for=\"").append(escapedId).append("\">")
            appendSnapPoints(controls, fromNode, "from")
            appendSnapPoints(controls, toNode, "to")
            controls.append("<circle class=\"route-endpoint\" data-end=\"from\" cx=\"")
                .append(edge.points.first().x).append("\" cy=\"").append(edge.points.first().y).append("\" r=\"6\"/>")
            controls.append("<circle class=\"route-endpoint\" data-end=\"to\" cx=\"")
                .append(edge.points.last().x).append("\" cy=\"").append(edge.points.last().y).append("\" r=\"6\"/>")
            controls.append("<circle class=\"route-control\" data-role=\"control\" cx=\"")
                .append(edge.control.x).append("\" cy=\"").append(edge.control.y).append("\" r=\"6\"/>")
            controls.append("</g>")
        }
        append(controls)

        if (schema.tables.isEmpty()) append("<text class=\"title\" x=\"40\" y=\"60\">No tables to display</text>")
        append("</svg>")
    }

    private fun StringBuilder.appendCardinality(point: DiagramPoint, side: TableSide, value: String, endpoint: String) {
        val (x, y, anchor) = when (side) {
            TableSide.LEFT -> Triple(point.x - 8, point.y - 6, "end")
            TableSide.RIGHT -> Triple(point.x + 8, point.y - 6, "start")
            TableSide.TOP -> Triple(point.x + 8, point.y - 7, "start")
            TableSide.BOTTOM -> Triple(point.x + 8, point.y + 15, "start")
        }
        append("<text class=\"cardinality\" data-end=\"$endpoint\" text-anchor=\"$anchor\" x=\"$x\" y=\"$y\">${escape(value)}</text>")
    }

    private fun appendSnapPoints(target: StringBuilder, node: TableNode, endpoint: String) {
        TableSide.values().forEach { side ->
            val point = anchor(node, side)
            target.append("<circle class=\"route-snap\" data-end=\"").append(endpoint)
                .append("\" data-side=\"").append(side.name.lowercase())
                .append("\" cx=\"").append(point.x).append("\" cy=\"").append(point.y).append("\" r=\"3.5\"/>")
        }
    }

    private fun anchor(node: TableNode, side: TableSide): DiagramPoint = when (side) {
        TableSide.TOP -> DiagramPoint(node.x + node.width / 2, node.y)
        TableSide.RIGHT -> DiagramPoint(node.x + node.width, node.y + node.height / 2)
        TableSide.BOTTOM -> DiagramPoint(node.x + node.width / 2, node.y + node.height)
        TableSide.LEFT -> DiagramPoint(node.x, node.y + node.height / 2)
    }

    private fun relationTitle(reference: DbmlReference): String {
        val foreignKey = reference.foreignKeyEndpoint()
        val referenced = reference.referencedEndpoint()
        val prefix = reference.name?.let { "$it: " } ?: ""
        return if (foreignKey != null && referenced != null) {
            val cardinality = if (reference.cardinality == DbmlCardinality.ONE_TO_ONE) "1:1" else "N:1"
            "${prefix}FK ${foreignKey.tableName}.${foreignKey.column} → REF ${referenced.tableName}.${referenced.column} ($cardinality)"
        } else {
            "$prefix${reference.from.tableName}.${reference.from.column} ↔ ${reference.to.tableName}.${reference.to.column} (N:N)"
        }
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
