package io.github.dbmldiagram.core.database

/** Escaped, transient snapshot diagnostics; the underlying schema and persisted layout stay unchanged. */
object DatabaseDiagramOverlay {
    fun decorate(svg: String,comparison: SchemaComparison): String {
        val byTable=comparison.differences.filter { it.tableId!=null }.groupBy { escape(it.tableId!!) }
        val decorated=Regex("<g data-table=\"([^\"]*)\"[^>]*>.*?</g>",RegexOption.DOT_MATCHES_ALL).replace(svg) { match ->
            val issues=byTable[match.groupValues[1]] ?: return@replace match.value
            val missing=issues.any { it.kind==SchemaDifferenceKind.MISSING }
            val status=if(missing) "missing" else "changed"
            var table=match.value.replaceFirst(">"," data-db-drift=\"$status\"><title>"+escape(issues.joinToString("\n") { "DB: ${it.message}" })+"</title>")
            issues.mapNotNull { it.column }.distinct().forEach { column ->
                val columnStatus=if(issues.any { it.column==column && it.kind==SchemaDifferenceKind.MISSING }) "missing" else "changed"
                table=table.replace("data-column=\"${escape(column)}\"","data-column=\"${escape(column)}\" data-db-drift=\"$columnStatus\"")
            }
            table
        }
        return decorated.replace("</svg>","""<defs><style>
            g[data-db-drift="missing"] > .table{stroke:#3b82f6;stroke-width:2;stroke-dasharray:6 3}
            g[data-db-drift="changed"] > .table{stroke:#e8a332;stroke-width:2}
            text[data-db-drift="missing"]{fill:#3b82f6;text-decoration:underline}
            text[data-db-drift="changed"]{fill:#e8a332;text-decoration:underline}
            g[data-db-drift] > .table.invalid{stroke:#e05252;stroke-dasharray:none}
            text.col.invalid[data-db-drift]{fill:#e05252}
            </style></defs></svg>""")
    }
    private fun escape(value: String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
        .replace("\n","&#10;").replace("\r","&#13;").replace("\t","&#9;")
}
