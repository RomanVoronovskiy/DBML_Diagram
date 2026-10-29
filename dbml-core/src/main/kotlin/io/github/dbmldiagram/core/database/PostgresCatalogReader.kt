package io.github.dbmldiagram.core.database

import java.sql.Connection
import java.sql.ResultSet

/** Uses a dedicated connection and a consistent, read-only catalog transaction. Never reads table rows. */
class PostgresCatalogReader {
    fun read(connection: Connection, selectedSchemas: Set<String>, checkCanceled: () -> Unit = {}): DatabaseSnapshot {
        require(selectedSchemas.isNotEmpty() && selectedSchemas.size <= 32) { "Select 1 to 32 schemas." }
        require(connection.autoCommit) { "Use a dedicated connection outside a transaction." }
        require(connection.metaData.databaseMajorVersion>=15) { "PostgreSQL 15 or newer is required for schema comparison." }
        val oldReadOnly = connection.isReadOnly
        val oldIsolation = connection.transactionIsolation
        connection.isReadOnly = true
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.execute("SET LOCAL search_path = pg_catalog") }
            var count = 0
            var textSize = 0L
            fun query(sql: String, action: (ResultSet) -> Unit) {
                checkCanceled()
                connection.prepareStatement(sql).use { statement ->
                    statement.queryTimeout = 30
                    statement.fetchSize = 500
                    val array = connection.createArrayOf("text", selectedSchemas.toTypedArray())
                    try {
                        statement.setArray(1, array)
                        statement.executeQuery().use { rows ->
                            while (rows.next()) {
                                checkCanceled()
                                require(++count <= 200_000) { "Catalog snapshot exceeds the object limit; select fewer schemas." }
                                for(column in 1..rows.metaData.columnCount) textSize+=rows.getString(column)?.length ?: 0
                                require(textSize<=8_000_000) { "Catalog snapshot exceeds the metadata text limit; select fewer schemas." }
                                action(rows)
                            }
                        }
                    } finally { array.free() }
                }
            }
            val schemas = linkedSetOf<String>()
            query("SELECT nspname, has_schema_privilege(oid, 'USAGE') AS allowed FROM pg_namespace WHERE nspname = ANY (?)") {
                require(it.getBoolean("allowed")) { "Missing USAGE privilege for selected schema." }
                schemas += it.getString("nspname")
            }
            val relations = linkedSetOf<DatabaseName>()
            val ordinary = linkedMapOf<DatabaseName, Boolean>()
            query("SELECT n.nspname, c.relname, c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname = ANY (?) ORDER BY n.nspname,c.relname") {
                val id = DatabaseName(it.getString(1), it.getString(2))
                relations += id
                if (it.getString(3) in setOf("r", "p")) ordinary[id] = it.getString(3) == "r"
            }
            val columns = mutableMapOf<DatabaseName, MutableList<DatabaseColumn>>()
            query("""SELECT n.nspname,c.relname,a.attname,format_type(a.atttypid,a.atttypmod) AS type,
                a.attnotnull,a.attidentity,a.attgenerated,pg_get_expr(d.adbin,d.adrelid) AS expression
                FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
                WHERE n.nspname = ANY (?) AND c.relkind IN ('r','p') AND a.attnum>0 AND NOT a.attisdropped
                ORDER BY n.nspname,c.relname,a.attnum""") {
                val expression = it.getString("expression")
                val identity = it.getString("attidentity").isNotEmpty() || expression?.startsWith("nextval(") == true
                columns.getOrPut(DatabaseName(it.getString(1), it.getString(2))) { mutableListOf() } +=
                    DatabaseColumn(it.getString("attname"), it.getString("type"), !it.getBoolean("attnotnull"),
                        if (identity) null else expression, identity, it.getString("attgenerated").isNotEmpty())
            }
            val keys = mutableMapOf<DatabaseName, MutableList<DatabaseKey>>()
            val checks = mutableMapOf<DatabaseName, MutableList<DatabaseCheck>>()
            val foreignKeys = mutableMapOf<DatabaseName, MutableList<DatabaseForeignKey>>()
            fun strings(rows: ResultSet, field: String): List<String> {
                val array = rows.getArray(field) ?: return emptyList()
                try { return (array.array as Array<*>).map { it.toString() } } finally { array.free() }
            }
            query("""SELECT n.nspname,t.relname,c.conname,c.contype,c.convalidated,
                ARRAY(SELECT a.attname FROM unnest(c.conkey) WITH ORDINALITY AS k(num,ord)
                  JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.num ORDER BY k.ord) AS cols,
                rn.nspname AS refschema,rt.relname AS reftable,
                ARRAY(SELECT a.attname FROM unnest(c.confkey) WITH ORDINALITY AS k(num,ord)
                  JOIN pg_attribute a ON a.attrelid=rt.oid AND a.attnum=k.num ORDER BY k.ord) AS refcols,
                c.confdeltype,c.confupdtype,c.confmatchtype,c.condeferrable,pg_get_expr(c.conbin,c.conrelid) AS expression
                FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                LEFT JOIN pg_class rt ON rt.oid=c.confrelid LEFT JOIN pg_namespace rn ON rn.oid=rt.relnamespace
                WHERE n.nspname = ANY (?) AND t.relkind IN ('r','p') ORDER BY n.nspname,t.relname,c.conname""") {
                val id = DatabaseName(it.getString(1), it.getString(2))
                val name = it.getString("conname")
                when (it.getString("contype")) {
                    "p", "u" -> keys.getOrPut(id) { mutableListOf() } += DatabaseKey(name, strings(it,"cols"), it.getString("contype") == "p",!it.getBoolean("condeferrable"))
                    "c" -> checks.getOrPut(id) { mutableListOf() } += DatabaseCheck(name, it.getString("expression"), it.getBoolean("convalidated"))
                    "f" -> foreignKeys.getOrPut(id) { mutableListOf() } += DatabaseForeignKey(name, strings(it,"cols"),
                        DatabaseName(it.getString("refschema"), it.getString("reftable")), strings(it,"refcols"),
                        action(it.getString("confdeltype")), action(it.getString("confupdtype")),
                        it.getBoolean("convalidated") && !it.getBoolean("condeferrable") && it.getString("confmatchtype") == "s")
                }
            }
            val indexes = mutableMapOf<DatabaseName, MutableList<DatabaseIndex>>()
            query("""SELECT n.nspname,t.relname,i.relname AS name,x.indisunique,
                (x.indexprs IS NULL AND x.indpred IS NULL AND x.indisvalid AND x.indimmediate AND am.amname='btree'
                 AND x.indnatts=x.indnkeyatts AND NOT x.indnullsnotdistinct
                 AND NOT EXISTS (SELECT 1 FROM unnest(x.indoption) o WHERE o<>0)
                 AND NOT EXISTS (SELECT 1 FROM unnest(x.indclass) o JOIN pg_opclass op ON op.oid=o WHERE NOT op.opcdefault)) AS simple,
                ARRAY(SELECT a.attname FROM unnest(x.indkey) WITH ORDINALITY AS k(num,ord)
                  JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.num WHERE k.ord<=x.indnkeyatts ORDER BY k.ord) AS cols
                FROM pg_index x JOIN pg_class t ON t.oid=x.indrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                JOIN pg_class i ON i.oid=x.indexrelid JOIN pg_am am ON am.oid=i.relam
                WHERE n.nspname = ANY (?) AND t.relkind IN ('r','p') ORDER BY n.nspname,t.relname,i.relname""") {
                indexes.getOrPut(DatabaseName(it.getString(1),it.getString(2))) { mutableListOf() } +=
                    DatabaseIndex(it.getString("name"), strings(it,"cols"), it.getBoolean("indisunique"), it.getBoolean("simple"))
            }
            val typeNames = linkedSetOf<DatabaseName>()
            val enums = linkedMapOf<DatabaseName, MutableList<String>>()
            query("""SELECT n.nspname,t.typname,e.enumlabel FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
                LEFT JOIN pg_enum e ON e.enumtypid=t.oid WHERE n.nspname = ANY (?) ORDER BY n.nspname,t.typname,e.enumsortorder""") {
                val id = DatabaseName(it.getString(1),it.getString(2))
                typeNames += id
                it.getString(3)?.let { label -> enums.getOrPut(id) { mutableListOf() } += label }
            }
            return DatabaseSnapshot(schemas, ordinary.map { (id, plain) -> DatabaseTable(id, columns[id].orEmpty(),
                keys[id].orEmpty(), indexes[id].orEmpty(), foreignKeys[id].orEmpty(), checks[id].orEmpty(), plain) }, enums, relations, typeNames,
                inspectedSchemas=selectedSchemas)
        } finally {
            connection.rollback()
            connection.autoCommit = true
            connection.transactionIsolation = oldIsolation
            connection.isReadOnly = oldReadOnly
        }
    }

    private fun action(code: String) = when (code) { "c" -> "cascade"; "n" -> "set null"; "d" -> "set default"; "r" -> "restrict"; else -> "no action" }
}
