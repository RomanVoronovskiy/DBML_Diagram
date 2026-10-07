package io.github.dbmldiagram.core.ddl

import io.github.dbmldiagram.core.model.DbmlColumnRef
import io.github.dbmldiagram.core.model.DbmlSchema
import io.github.dbmldiagram.core.model.DbmlTable
import io.github.dbmldiagram.core.validation.isColumnUnique

internal fun primaryKeyColumns(table: DbmlTable): List<String> =
    table.indexes.firstOrNull { it.primaryKey }?.columns
        ?: table.columns.filter { it.primaryKey }.map { it.name }

internal fun isUnique(table: DbmlTable, column: String): Boolean =
    table.isColumnUnique(column)

internal fun resolveTable(schema: DbmlSchema, reference: DbmlColumnRef): DbmlTable? =
    schema.tables.firstOrNull {
        it.qualifiedName.equals(reference.tableName, true) ||
            (reference.schema == null && it.alias?.equals(reference.table, true) == true)
    }

internal fun normalizedAction(value: String): String = when (value.trim().replace('_', ' ').lowercase()) {
    "cascade" -> "CASCADE"
    "restrict" -> "RESTRICT"
    "set null" -> "SET NULL"
    "set default" -> "SET DEFAULT"
    "no action" -> "NO ACTION"
    else -> value.trim().uppercase()
}

internal fun safeName(prefix: String, table: String, parts: List<String>, maxLength: Int): String {
    val raw = (listOf(prefix, table) + parts).joinToString("_")
        .lowercase()
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
    return raw.take(maxLength)
}

internal fun sqlString(value: String): String = "'${value.replace("'", "''")}'"

internal fun display(reference: DbmlColumnRef): String = "${reference.tableName}.${reference.column}"
