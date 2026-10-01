package io.github.dbmldiagram.core.renderer

import io.github.dbmldiagram.core.layout.DiagramLayout
import io.github.dbmldiagram.core.model.*

class SvgDiagramRenderer : DiagramRenderer {
    override fun render(schema: DbmlSchema, layout: DiagramLayout): String = buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" width="${layout.width.toInt()}" height="${layout.height.toInt()}" viewBox="0 0 ${layout.width.toInt()} ${layout.height.toInt()}" role="img" aria-label="DBML entity relationship diagram">""")
        append("""<defs><style>.table{fill:var(--dbml-card,#fff);stroke:var(--dbml-border,#9aa0a6);stroke-width:1}.head{fill:var(--dbml-header,#eef1f5)}.title{font:600 14px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:var(--dbml-text,#24292f)}.col{font:12px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:var(--dbml-text,#24292f)}.type{fill:var(--dbml-muted,#65717e)}.badge{font:700 8px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:var(--dbml-accent,#3d65a5);letter-spacing:.15px}.separator{stroke:var(--dbml-edge,#79838e);stroke-width:1}.relation{fill:none;stroke:var(--dbml-relation,#568af2);stroke-width:1.8}.relation-arrow{fill:var(--dbml-relation,#568af2)}.cardinality{font:700 10px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:var(--dbml-relation,#568af2);stroke:var(--dbml-bg,#fff);stroke-width:3;paint-order:stroke fill}</style><marker id="fk-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth"><path class="relation-arrow" d="M0,0 L8,4 L0,8 Z"/></marker></defs>""")
        layout.edges.forEach { edge ->
            val points = edge.points.joinToString(" ") { "${it.x},${it.y}" }
            val directed = edge.relation.foreignKeyEndpoint() != null
            append("<polyline class=\"relation\" points=\"").append(points).append("\"")
            if (directed) append(" marker-end=\"url(#fk-arrow)\"")
            append("><title>").append(escape(relationTitle(edge.relation))).append("</title></polyline>")
            val first = edge.points.first(); val last = edge.points.last()
            val (fromMark, toMark) = if (directed) {
                (if (edge.relation.cardinality == DbmlCardinality.ONE_TO_ONE) "1" else "N") to "1"
            } else {
                "N" to "N"
            }
            appendCardinality(first, edge.points[1], fromMark)
            appendCardinality(last, edge.points[edge.points.lastIndex - 1], toMark)
        }
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
            append("<g data-table=\"").append(escape(table.qualifiedName)).append("\">")
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
        if (schema.tables.isEmpty()) append("<text class=\"title\" x=\"40\" y=\"60\">No tables to display</text>")
        append("</svg>")
    }

    private fun StringBuilder.appendCardinality(point: io.github.dbmldiagram.core.layout.DiagramPoint, adjacent: io.github.dbmldiagram.core.layout.DiagramPoint, value: String) {
        val pointsRight = adjacent.x >= point.x
        val x = point.x + if (pointsRight) 7 else -7
        val anchor = if (pointsRight) "start" else "end"
        append("<text class=\"cardinality\" text-anchor=\"$anchor\" x=\"$x\" y=\"${point.y - 6}\">${escape(value)}</text>")
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
