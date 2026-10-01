package io.github.dbmldiagram.core.renderer

import io.github.dbmldiagram.core.layout.DiagramLayout
import io.github.dbmldiagram.core.model.*

class SvgDiagramRenderer : DiagramRenderer {
    override fun render(schema: DbmlSchema, layout: DiagramLayout): String = buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" width="${layout.width.toInt()}" height="${layout.height.toInt()}" viewBox="0 0 ${layout.width.toInt()} ${layout.height.toInt()}" role="img" aria-label="DBML entity relationship diagram">""")
        append("""<defs><style>.table{fill:var(--dbml-card,#fff);stroke:var(--dbml-border,#9aa0a6);stroke-width:1}.head{fill:var(--dbml-header,#eef1f5)}.title{font:600 14px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:var(--dbml-text,#24292f)}.col{font:12px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:var(--dbml-text,#24292f)}.type{fill:var(--dbml-muted,#65717e)}.badge{font:700 8px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:var(--dbml-accent,#3d65a5);letter-spacing:.15px}.edge{fill:none;stroke:var(--dbml-edge,#79838e);stroke-width:1.5}.cardinality{font:11px sans-serif;fill:var(--dbml-muted,#65717e)}</style></defs>""")
        layout.edges.forEach { edge ->
            val points = edge.points.joinToString(" ") { "${it.x},${it.y}" }
            append("<polyline class=\"edge\" points=\"").append(points).append("\"/>")
            val first = edge.points.first(); val last = edge.points.last()
            val (fromMark, toMark) = marks(edge.relation.cardinality)
            append("<text class=\"cardinality\" x=\"${first.x + 5}\" y=\"${first.y - 5}\">${escape(fromMark)}</text>")
            append("<text class=\"cardinality\" x=\"${last.x - 12}\" y=\"${last.y - 5}\">${escape(toMark)}</text>")
        }
        val foreignKeys = schema.references.mapNotNull { reference ->
            val endpoint = when (reference.cardinality) {
                DbmlCardinality.MANY_TO_ONE -> reference.from
                DbmlCardinality.ONE_TO_MANY -> reference.to
                DbmlCardinality.ONE_TO_ONE -> if (reference.inline) reference.from else reference.to
                DbmlCardinality.MANY_TO_MANY -> null
            } ?: return@mapNotNull null
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
            append("<line class=\"edge\" x1=\"${node.x}\" y1=\"${node.y + 38}\" x2=\"${node.x + node.width}\" y2=\"${node.y + 38}\"/>")
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
                append("<text class=\"badge\" x=\"${node.x + 10}\" y=\"$y\">${escape(badges)}</text>")
                append("<text class=\"col\" x=\"${node.x + 148}\" y=\"$y\">${escape(column.name)}</text>")
                append("<text class=\"col type\" text-anchor=\"end\" x=\"${node.x + node.width - 12}\" y=\"$y\">${escape(column.type)}</text>")
            }
            append("</g>")
        }
        if (schema.tables.isEmpty()) append("<text class=\"title\" x=\"40\" y=\"60\">No tables to display</text>")
        append("</svg>")
    }

    private fun marks(cardinality: DbmlCardinality) = when (cardinality) {
        DbmlCardinality.ONE_TO_ONE -> "1" to "1"
        DbmlCardinality.ONE_TO_MANY -> "1" to "*"
        DbmlCardinality.MANY_TO_ONE -> "*" to "1"
        DbmlCardinality.MANY_TO_MANY -> "*" to "*"
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
