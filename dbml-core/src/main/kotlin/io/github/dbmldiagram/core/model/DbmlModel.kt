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

data class DbmlIndex(
    val columns: List<String>,
    val unique: Boolean = false,
    val primaryKey: Boolean = false,
    val name: String? = null,
)
data class DbmlEnum(val name: String, val values: List<String>)

data class DbmlReference(
    val from: DbmlColumnRef,
    val to: DbmlColumnRef,
    val cardinality: DbmlCardinality,
    val name: String? = null,
    val onDelete: String? = null,
    val onUpdate: String? = null,
    val inline: Boolean = false,
) {
    /** Stable key used for persisted visual routing settings. */
    fun routeId(): String = listOf(
        name.orEmpty(),
        from.tableName,
        from.column,
        cardinality.name,
        to.tableName,
        to.column,
    ).joinToString("|")

    /** Column that owns the physical foreign-key constraint, when the relation maps to one. */
    fun foreignKeyEndpoint(): DbmlColumnRef? = when (cardinality) {
        DbmlCardinality.MANY_TO_ONE -> from
        DbmlCardinality.ONE_TO_MANY -> to
        DbmlCardinality.ONE_TO_ONE -> if (inline) from else to
        DbmlCardinality.MANY_TO_MANY -> null
    }

    /** Column referenced by the physical foreign key, when the relation maps to one. */
    fun referencedEndpoint(): DbmlColumnRef? = when (cardinality) {
        DbmlCardinality.MANY_TO_ONE -> to
        DbmlCardinality.ONE_TO_MANY -> from
        DbmlCardinality.ONE_TO_ONE -> if (inline) to else from
        DbmlCardinality.MANY_TO_MANY -> null
    }
}

data class DbmlColumnRef(val schema: String? = null, val table: String, val column: String) {
    val tableName: String get() = schema?.let { "$it.$table" } ?: table
}

enum class DbmlCardinality { ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY }

enum class DbmlParseSeverity { WARNING, ERROR }
data class DbmlParseError(val message: String, val line: Int, val column: Int, val severity: DbmlParseSeverity)
data class ParseResult(val schema: DbmlSchema?, val errors: List<DbmlParseError>) {
    val hasErrors: Boolean get() = errors.any { it.severity == DbmlParseSeverity.ERROR }
}
