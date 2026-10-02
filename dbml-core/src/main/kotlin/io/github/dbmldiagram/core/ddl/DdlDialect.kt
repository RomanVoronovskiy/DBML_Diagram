package io.github.dbmldiagram.core.ddl

/** SQL dialects available in the diagram toolbar. */
enum class DdlDialect(val displayName: String) {
    POSTGRESQL("PostgreSQL"),
    MYSQL("MySQL"),
    ORACLE("Oracle");

    fun generator(): DdlGenerator = when (this) {
        POSTGRESQL -> PostgreSqlDdlGenerator()
        MYSQL -> MySqlDdlGenerator()
        ORACLE -> OracleDdlGenerator()
    }

    override fun toString(): String = displayName

    companion object {
        fun fromDatabaseType(value: String?): DdlDialect? = when (value?.trim()?.lowercase()) {
            "postgres", "postgresql" -> POSTGRESQL
            "mysql" -> MYSQL
            "oracle", "oracle database" -> ORACLE
            else -> null
        }
    }
}
