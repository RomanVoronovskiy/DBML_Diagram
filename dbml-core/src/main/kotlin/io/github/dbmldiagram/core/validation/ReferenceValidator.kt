package io.github.dbmldiagram.core.validation

import io.github.dbmldiagram.core.model.*

fun DbmlTable.isColumnUnique(column: String): Boolean {
    val primaryKey = indexes.firstOrNull { it.primaryKey }?.columns
        ?: columns.filter { it.primaryKey }.map { it.name }
    return (primaryKey.size == 1 && primaryKey.single().equals(column, true)) ||
        columns.any { it.name.equals(column, true) && it.unique } ||
        indexes.any { it.unique && it.columns.size == 1 && it.columns.single().equals(column, true) }
}

/** Validates single-column relations against the constraints actually declared in DBML. */
class ReferenceValidator {
    fun validate(schema: DbmlSchema): List<DbmlParseError> = buildList {
        schema.references.forEach { reference ->
            fun report(message: String) {
                val range = reference.sourceRange ?: DbmlSourceRange(1, 1, 1, 2)
                add(DbmlParseError(message, range.line, range.column, DbmlParseSeverity.ERROR,
                    range.endLine, range.endColumn, reference.routeId()))
            }
            fun resolve(endpoint: DbmlColumnRef): Pair<DbmlTable, DbmlColumn>? {
                val table = schema.tables.firstOrNull {
                    it.qualifiedName.equals(endpoint.tableName, true) ||
                        (endpoint.schema == null && it.alias?.equals(endpoint.table, true) == true)
                }
                if (table == null) {
                    report("Unknown reference table '${endpoint.tableName}'")
                    return null
                }
                val column = table.columns.firstOrNull { it.name.equals(endpoint.column, true) }
                if (column == null) {
                    report("Unknown reference column '${endpoint.tableName}.${endpoint.column}'")
                    return null
                }
                return table to column
            }
            val from = resolve(reference.from)
            val to = resolve(reference.to)
            if (from == null || to == null) return@forEach
            if (from.first == to.first && from.second == to.second) {
                report("A relationship cannot reference the same column on both sides")
                return@forEach
            }
            fun requireKey(endpoint: Pair<DbmlTable, DbmlColumn>) {
                val (table, column) = endpoint
                if (!table.isColumnUnique(column.name)) {
                    report("Referenced column '${table.qualifiedName}.${column.name}' must have a single-column PK or UNIQUE constraint; part of a composite key is not unique by itself")
                }
            }
            if (reference.cardinality == DbmlCardinality.MANY_TO_MANY) {
                // <> is a conceptual relationship between entity keys, not a direct FK.
                // The two entities may legitimately have different key types.
                requireKey(from)
                requireKey(to)
                return@forEach
            }
            val foreignKey = if (reference.foreignKeyEndpoint() == reference.from) from else to
            val referenced = if (reference.referencedEndpoint() == reference.from) from else to
            requireKey(referenced)
            val uniqueForeignKey = foreignKey.first.isColumnUnique(foreignKey.second.name)
            val foreignKeyName = "${foreignKey.first.qualifiedName}.${foreignKey.second.name}"
            when (reference.cardinality) {
                DbmlCardinality.ONE_TO_ONE -> if (!uniqueForeignKey) {
                    report("Invalid 1:1 relationship: foreign-key column '$foreignKeyName' must have a single-column PK or UNIQUE constraint")
                }
                else -> if (uniqueForeignKey) {
                    report("Invalid 1:N relationship: '$foreignKeyName' is unique, so it cannot be the many side. Use '-' for 1:1 or remove its single-column PK/UNIQUE constraint")
                }
            }
            val foreignType = typeFamily(foreignKey.second.type)
            val referencedType = typeFamily(referenced.second.type)
            if (foreignType != referencedType) {
                report("Incompatible relationship types: '$foreignKeyName' (${foreignKey.second.type}) and '${referenced.first.qualifiedName}.${referenced.second.name}' (${referenced.second.type})")
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
