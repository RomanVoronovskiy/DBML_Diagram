package io.github.dbmldiagram.core.ddl

import io.github.dbmldiagram.core.model.DbmlColumnRef
import io.github.dbmldiagram.core.model.DbmlColumn
import io.github.dbmldiagram.core.model.DbmlSchema
import io.github.dbmldiagram.core.model.DbmlTable
import io.github.dbmldiagram.core.validation.isColumnsUnique

internal fun primaryKeyColumns(table: DbmlTable): List<String> =
    table.indexes.firstOrNull { it.primaryKey }?.columns
        ?: table.columns.filter { it.primaryKey }.map { it.name }

internal fun isUnique(table: DbmlTable, columns: List<String>): Boolean = table.isColumnsUnique(columns)

internal fun resolveColumns(table: DbmlTable?, reference: DbmlColumnRef): List<DbmlColumn>? {
    if (table == null || reference.columns.map(String::lowercase).distinct().size != reference.columns.size) return null
    return reference.columns.map { name -> table.columns.firstOrNull { it.name.equals(name, true) } ?: return null }
}

internal fun checkDefinitions(table: DbmlTable, quote: (String) -> String, maxNameLength: Int): List<String> {
    val checks = table.checks + table.columns.flatMap { it.checks }
    val used = checks.mapNotNull { it.name?.lowercase() }.toMutableSet()
    var ordinal = 0
    return checks.map { check ->
        require(check.expression.isNotBlank()) { "CHECK expression must not be empty" }
        val constraintName = check.name ?: run {
            var generated: String
            do { generated = safeName("chk${++ordinal}", table.name, emptyList(), maxNameLength) }
            while (!used.add(generated.lowercase()))
            generated
        }
        "    CONSTRAINT ${quote(constraintName)} CHECK (${check.expression})"
    }
}

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

internal fun display(reference: DbmlColumnRef): String = reference.displayName
