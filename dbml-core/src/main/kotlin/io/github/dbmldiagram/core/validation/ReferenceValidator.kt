package io.github.dbmldiagram.core.validation

import io.github.dbmldiagram.core.model.*

internal fun DbmlTable.uniqueKeys(): List<List<String>> {
    val primaryKey = indexes.firstOrNull { it.primaryKey }?.columns
        ?: columns.filter { it.primaryKey }.map { it.name }
    return (listOf(primaryKey) + columns.filter { it.unique }.map { listOf(it.name) } + indexes.filter { it.unique }.map { it.columns })
        .filter { it.isNotEmpty() }.map { key -> key.map(String::lowercase) }
}

/** A unique subset also makes the complete FK tuple unique (but is not a composite target key). */
fun DbmlTable.isColumnsUnique(columns: List<String>): Boolean {
    val names = columns.map(String::lowercase).toSet()
    return uniqueKeys().any { key -> names.containsAll(key) }
}

fun DbmlTable.hasExactUniqueKey(columns: List<String>): Boolean {
    val names = columns.map(String::lowercase).toSet()
    return names.size == columns.size && uniqueKeys().any { key -> key.size == columns.size && key.toSet() == names }
}

fun DbmlTable.isColumnUnique(column: String): Boolean = isColumnsUnique(listOf(column))

/** Validates complete reference tuples and checks type compatibility in declared column order. */
class ReferenceValidator {
    fun validate(schema: DbmlSchema): List<DbmlParseError> = buildList {
        schema.references.forEach { reference ->
            fun report(message: String) {
                val range = reference.sourceRange ?: DbmlSourceRange(1, 1, 1, 2)
                add(DbmlParseError(message, range.line, range.column, DbmlParseSeverity.ERROR,
                    range.endLine, range.endColumn, reference.routeId()))
            }
            fun resolve(endpoint: DbmlColumnRef): Pair<DbmlTable, List<DbmlColumn>>? {
                val table = schema.tables.firstOrNull {
                    it.qualifiedName.equals(endpoint.tableName, true) ||
                        (endpoint.schema == null && it.alias?.equals(endpoint.table, true) == true)
                }
                if (table == null) {
                    report("Unknown reference table '${endpoint.tableName}'")
                    return null
                }
                val columns = endpoint.columns.mapNotNull { name ->
                    table.columns.firstOrNull { it.name.equals(name, true) }.also {
                        if (it == null) report("Unknown reference column '${endpoint.tableName}.$name'")
                    }
                }
                if (columns.size != endpoint.columns.size) return null
                if (endpoint.columns.map(String::lowercase).distinct().size != endpoint.columns.size) {
                    report("Duplicate columns in relationship endpoint '${endpoint.displayName}'")
                    return null
                }
                return table to columns
            }
            val from = resolve(reference.from)
            val to = resolve(reference.to)
            if (from == null || to == null) return@forEach
            if (from.first == to.first && from.second == to.second) {
                report("A relationship cannot reference the same column on both sides")
                return@forEach
            }
            fun requireKey(endpoint: Pair<DbmlTable, List<DbmlColumn>>) {
                val (table, columns) = endpoint
                if (!table.hasExactUniqueKey(columns.map { it.name })) {
                    if (columns.size == 1) {
                        report("Referenced column '${table.qualifiedName}.${columns.single().name}' must have a single-column PK or UNIQUE constraint; part of a composite key is not unique by itself")
                    } else {
                        report("Referenced columns '${table.qualifiedName}.(${columns.joinToString(", ") { it.name }})' must form a complete PK or UNIQUE key, not just part of a composite key")
                    }
                }
            }
            if (reference.cardinality == DbmlCardinality.MANY_TO_MANY) {
                // <> is a conceptual relationship between entity keys, not a direct FK.
                // The two entities may legitimately have different key types.
                requireKey(from)
                requireKey(to)
                return@forEach
            }
            if (from.second.size != to.second.size) {
                report("Relationship endpoints must have the same number of columns (${from.second.size} and ${to.second.size})")
                return@forEach
            }
            val foreignKey = if (reference.foreignKeyEndpoint() == reference.from) from else to
            val referenced = if (reference.referencedEndpoint() == reference.from) from else to
            requireKey(referenced)
            val uniqueForeignKey = foreignKey.first.isColumnsUnique(foreignKey.second.map { it.name })
            val foreignKeyName = reference.foreignKeyEndpoint()!!.displayName
            when (reference.cardinality) {
                DbmlCardinality.ONE_TO_ONE -> if (!uniqueForeignKey) {
                    report("Invalid 1:1 relationship: foreign-key column(s) '$foreignKeyName' must have a PK or UNIQUE constraint")
                }
                else -> if (uniqueForeignKey) {
                    report("Invalid 1:N relationship: '$foreignKeyName' is unique, so it cannot be the many side. Use '-' for 1:1 or remove its PK/UNIQUE constraint")
                }
            }
            foreignKey.second.zip(referenced.second).forEach { (foreignColumn, referencedColumn) ->
                if (typeFamily(foreignColumn.type) != typeFamily(referencedColumn.type)) {
                    report("Incompatible relationship types: '${foreignKey.first.qualifiedName}.${foreignColumn.name}' (${foreignColumn.type}) and '${referenced.first.qualifiedName}.${referencedColumn.name}' (${referencedColumn.type})")
                }
            }
        }
    }

    private fun typeFamily(type: String): String {
        val normalized = type.trim().lowercase().replace(Regex("\\s+"), " ")
        val base = normalized.substringBefore('(').trim()
        return when (base) {
            "int", "integer", "smallint", "bigint", "tinyint", "int2", "int4", "int8",
            "number", "numeric", "decimal", "float", "double", "double precision", "real" -> "numeric"
            "varchar", "varchar2", "nvarchar", "nvarchar2", "char", "character", "character varying", "text", "string" -> "text"
            "bool", "boolean" -> "boolean"
            "datetime", "timestamp", "timestamp without time zone" -> "timestamp"
            "timestamptz", "timestamp with time zone" -> "timestamptz"
            else -> normalized
        }
    }
}
