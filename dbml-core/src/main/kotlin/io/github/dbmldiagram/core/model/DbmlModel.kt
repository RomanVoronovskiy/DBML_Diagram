package io.github.dbmldiagram.core.model

data class DbmlSchema(
    val project: DbmlProject? = null,
    val tables: List<DbmlTable> = emptyList(),
    val enums: List<DbmlEnum> = emptyList(),
    val references: List<DbmlReference> = emptyList(),
)

data class DbmlProject(val name: String?, val properties: Map<String, String>)

data class DbmlTable(
    val schema: String? = null,
    val name: String,
    val alias: String? = null,
    val note: String? = null,
    val columns: List<DbmlColumn> = emptyList(),
    val indexes: List<DbmlIndex> = emptyList(),
) {
    val qualifiedName: String get() = schema?.let { "$it.$name" } ?: name
}

data class DbmlColumn(
    val name: String,
    val type: String,
    val primaryKey: Boolean = false,
    val nullable: Boolean = true,
    val unique: Boolean = false,
    val increment: Boolean = false,
    val defaultValue: String? = null,
    val note: String? = null,
)

data class DbmlIndex(val columns: List<String>, val unique: Boolean = false, val name: String? = null)
data class DbmlEnum(val name: String, val values: List<String>)

data class DbmlReference(
    val from: DbmlColumnRef,
    val to: DbmlColumnRef,
    val cardinality: DbmlCardinality,
    val name: String? = null,
)

data class DbmlColumnRef(val schema: String? = null, val table: String, val column: String) {
    val tableName: String get() = schema?.let { "$it.$table" } ?: table
}

enum class DbmlCardinality { ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY }

enum class DbmlParseSeverity { WARNING, ERROR }
data class DbmlParseError(val message: String, val line: Int, val column: Int, val severity: DbmlParseSeverity)
data class ParseResult(val schema: DbmlSchema?, val errors: List<DbmlParseError>) {
    val hasErrors: Boolean get() = errors.any { it.severity == DbmlParseSeverity.ERROR }
}
