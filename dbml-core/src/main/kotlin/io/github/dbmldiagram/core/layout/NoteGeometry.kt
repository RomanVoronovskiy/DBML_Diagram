package io.github.dbmldiagram.core.layout

import io.github.dbmldiagram.core.model.DbmlSchema

/** Stable note IDs live in a separate position namespace from table IDs. */
object NoteGeometry {
    fun layout(schema: DbmlSchema, tables: List<TableNode>, positions: Map<String, DiagramPoint>): List<NoteNode> {
        val items = buildList {
            schema.project?.let { project ->
                project.properties.entries.firstOrNull { it.key.equals("note", true) }?.value?.let {
                    add(Triple("project", project.name ?: "Project", it) to "#f4d03f")
                }
            }
            schema.notes.forEach { add(Triple("note:${it.name}", it.name, it.text) to (it.color ?: "#f4d03f")) }
        }
        val left = (tables.minOfOrNull { it.x } ?: 408.0) - 360.0
        var top = tables.minOfOrNull { it.y } ?: 48.0
        return items.map { (item, color) ->
            val lines = wrap(item.third)
            val height = 54.0 + lines.size * 18.0
            val point = positions[item.first]?.takeIf { it.x.isFinite() && it.y.isFinite() } ?: DiagramPoint(left, top)
            NoteNode(item.first, item.second, item.third, lines, color, point.x, point.y, 312.0, height)
                .also { top += height + 24.0 }
        }
    }

    private fun wrap(text: String): List<String> {
        // SVG remains bounded for long documentation. Full content is available in the tooltip/editor.
        val lines = mutableListOf<String>()
        for (paragraph in text.lineSequence()) {
            var rest = paragraph
            while (rest.length > 38 && lines.size < 40) {
                var split = rest.lastIndexOf(' ', 38).takeIf { it >= 12 } ?: 38
                if (rest[split - 1].isHighSurrogate() && rest[split].isLowSurrogate()) split--
                lines += rest.take(split)
                rest = rest.drop(split).trimStart()
            }
            if (lines.size >= 40) return lines.take(39) + "… (full text in tooltip)"
            lines += rest
        }
        return lines.ifEmpty { listOf("") }
    }
}
