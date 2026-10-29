package io.github.dbmldiagram.core.database

import io.github.dbmldiagram.core.ddl.primaryKeyColumns
import io.github.dbmldiagram.core.model.*

/** Additive migration planner. Destructive/ambiguous differences are reported, never inferred as renames. */
class PostgresSchemaDiff {
    fun compare(desired: DbmlSchema, actual: DatabaseSnapshot, defaultSchema: String = "public"): SchemaComparison {
        require(defaultSchema.isNotBlank())
        val changes = mutableListOf<SchemaDifference>()
        val tables = actual.tables.associateBy { it.id }
        val expected = desired.tables.associateBy { DatabaseName(it.schema ?: defaultSchema, it.name) }
        val enums = desired.enums.associate { DatabaseName(defaultSchema, it.name) to it.values }
        val identifiers=buildList {
            add(defaultSchema)
            desired.tables.forEach { table -> table.schema?.let(::add);add(table.name);addAll(table.columns.map { it.name }) }
            addAll(desired.enums.map { it.name })
        }
        if(expected.size!=desired.tables.size || desired.tables.any { table -> table.columns.map { it.name }.distinct().size!=table.columns.size } ||
            enums.size!=desired.enums.size || identifiers.any { it.isEmpty() || '\u0000' in it || it.toByteArray(Charsets.UTF_8).size>63 })
            return SchemaComparison(listOf(SchemaDifference("ambiguous-identifiers",SchemaDifferenceKind.UNSUPPORTED,"DBML identifiers",
                "Duplicate or unsupported PostgreSQL identifiers. Use unique names of at most 63 UTF-8 bytes.")),actual.capturedAtMillis)
        val plannedRelations=actual.relationNames.toMutableSet()
        val constraintNames=actual.tables.associate { table -> table.id to
            (table.keys.map { it.name }+table.checks.map { it.name }+table.foreignKeys.map { it.name }).toMutableSet() }.toMutableMap()
        val uninspected = (expected.keys.map { it.schema } + enums.keys.map { it.schema }).toSet() - actual.inspectedSchemas
        if(uninspected.isNotEmpty()) return SchemaComparison(listOf(SchemaDifference("uninspected",SchemaDifferenceKind.UNSUPPORTED,
            uninspected.joinToString(),"DBML uses schemas not covered by this snapshot. Compare with the database again.")),actual.capturedAtMillis)
        fun id(kind: String, vararg parts: String) = kind + parts.joinToString("") { ":${it.length}:$it" }
        fun schemaId(name: String) = id("schema",name)
        fun tableId(name: DatabaseName) = id("table",name.schema,name.name)
        fun columnId(name: DatabaseName, column: String) = id("column",name.schema,name.name,column)
        fun enumId(name: DatabaseName) = id("enum",name.schema,name.name)
        fun columns(names: List<String>) = names.joinToString(", ", transform = ::pgQuote)
        fun schemaDependency(name: String) = if(name in actual.schemas) emptySet() else setOf(schemaId(name))
        fun tableDependency(name: DatabaseName) = if(name in tables) emptySet() else setOf(tableId(name))
        fun columnDependencies(name: DatabaseName, names: List<String>) = names.mapNotNull { column ->
            val existing=tables[name]?.columns?.firstOrNull { it.name==column }
            if (name !in tables) tableId(name) else if(existing==null || existing.generated) columnId(name,column)
            else id("type",name.toString(),column).takeIf { typeId -> changes.any { it.id==typeId } }
        }.toSet()
        fun report(kind: SchemaDifferenceKind, key: String, objectName: String, message: String, table: DbmlTable? = null,
                   column: DbmlColumn? = null, range: DbmlSourceRange? = null, sql: String? = null,
                   review: Boolean = false, dependencies: Set<String> = emptySet()) {
            changes += SchemaDifference(key,kind,objectName,message,table?.qualifiedName,column?.name,
                range ?: column?.sourceRange ?: table?.sourceRange,sql,review,dependencies)
        }
        (expected.keys.map { it.schema } + enums.keys.map { it.schema }).distinct().filter { it !in actual.schemas }.forEach { name ->
            report(SchemaDifferenceKind.MISSING,schemaId(name),name,"Schema is missing in the database",sql="CREATE SCHEMA ${pgQuote(name)};")
        }
        enums.forEach { (name, values) ->
            val existing = actual.enums[name]
            if(existing == null) report(SchemaDifferenceKind.MISSING,enumId(name),name.toString(),"Enum is missing in the database",
                sql=if(name in actual.typeNames || values.isEmpty() || values.distinct().size!=values.size || values.any { it.toByteArray(Charsets.UTF_8).size>63 }) null else "CREATE TYPE ${name.sql} AS ENUM (${values.joinToString(", ", transform=::pgString)});",
                dependencies=schemaDependency(name.schema))
            else if(existing != values) report(SchemaDifferenceKind.CHANGED,enumId(name),name.toString(),
                "Enum values/order differ. DB: $existing; DBML: $values. Review manually.")
        }
        fun type(column: DbmlColumn, namespace: String): String? {
            val raw = column.type.trim()
            val enum = enums.keys.firstOrNull { it.name == raw && it.schema == defaultSchema }
            if(enum != null) return enum.sql
            val custom = DatabaseName(namespace,raw)
            if(custom in actual.typeNames && raw.matches(Regex("[A-Za-z_][A-Za-z_0-9$]*"))) return custom.sql
            if(!raw.matches(Regex("[A-Za-z_][A-Za-z_0-9]*(?:\\s+[A-Za-z_][A-Za-z_0-9]*)*(?:\\(\\s*\\d+(?:\\s*,\\s*\\d+)?\\s*\\))?(?:\\[\\])?"))) return null
            val base=raw.substringBefore('(').removeSuffix("[]").lowercase()
            if(base !in setOf("int","int2","int4","int8","smallint","integer","bigint","serial","bigserial","smallserial",
                "bool","boolean","numeric","decimal","real","double","double precision","float","float4","float8",
                "text","varchar","character varying","char","character","bpchar","uuid","json","jsonb","xml","bytea",
                "date","timestamp","timestamptz","timestamp with time zone","timestamp without time zone","datetime",
                "time","timetz","time with time zone","time without time zone","interval","inet","cidr","macaddr","macaddr8",
                "bit","bit varying","varbit","money","tsvector","tsquery","int4range","int8range","numrange","tsrange","tstzrange","daterange")) return null
            return when(raw.lowercase()) {
                "int", "int4", "serial" -> "integer"
                "int2", "smallserial" -> "smallint"
                "int8", "bigserial" -> "bigint"
                "bool" -> "boolean"
                "double", "float8" -> "double precision"
                "float", "float4" -> "real"
                "datetime" -> "timestamp"
                else -> raw
            }
        }
        fun typeDependency(column: DbmlColumn): Set<String> = enums.keys.firstOrNull { it.name == column.type }?.let {
            if(it !in actual.enums) setOf(enumId(it)) else emptySet()
        }.orEmpty()
        fun definition(column: DbmlColumn, table: DbmlTable, namespace: String): String? {
            val sqlType = type(column,namespace) ?: return null
            val identity = column.increment || column.type.lowercase() in setOf("serial","bigserial","smallserial")
            if(identity && sqlType !in setOf("smallint","integer","bigint")) return null
            return "${pgQuote(column.name)} $sqlType" +
                (if(identity) " GENERATED BY DEFAULT AS IDENTITY" else column.defaultValue?.let { " DEFAULT $it" }.orEmpty()) +
                if(!column.nullable || column.name in primaryKeyColumns(table)) " NOT NULL" else ""
        }
        val blockedTables = mutableSetOf<DatabaseName>()
        expected.forEach { (name, table) ->
            val database = tables[name]
            if(database == null) {
                val definitions = table.columns.map { definition(it,table,name.schema) }
                val sql = if(name in actual.relationNames || name in actual.typeNames || name in enums || definitions.any { it == null }) null else
                    "CREATE TABLE ${name.sql} (\n${definitions.joinToString(",\n") { "    $it" }}\n);"
                report(SchemaDifferenceKind.MISSING,tableId(name),name.toString(),
                    if(sql == null) "Cannot create table automatically: name is occupied or a column type is unsupported" else "Table is missing in the database",table,
                    sql=sql,dependencies=schemaDependency(name.schema)+table.columns.flatMap(::typeDependency))
                if(sql == null) blockedTables += name
                else plannedRelations+=name
            } else if(!database.ordinary) {
                blockedTables += name
                report(SchemaDifferenceKind.UNSUPPORTED,tableId(name),name.toString(),"Partitioned tables require manual migration review",table)
            } else {
                table.columns.forEach { column ->
                    val existing = database.columns.firstOrNull { it.name == column.name }
                    val path = "$name.${column.name}"
                    if(existing == null) {
                        val definition = definition(column,table,name.schema)
                        report(SchemaDifferenceKind.MISSING,columnId(name,column.name),path,"Column is missing in the database",table,column,
                            sql=definition?.let { "ALTER TABLE ${name.sql} ADD COLUMN $it;" },
                            review=(!column.nullable || column.name in primaryKeyColumns(table)),dependencies=typeDependency(column))
                    } else if(existing.generated) {
                        report(SchemaDifferenceKind.UNSUPPORTED,columnId(name,column.name),path,"Generated column expression cannot be represented by this DBML model",table,column)
                    } else {
                        val sqlType = type(column,name.schema)
                        if(sqlType == null || canonicalType(sqlType) != canonicalType(existing.type))
                            report(SchemaDifferenceKind.CHANGED,id("type",name.toString(),column.name),path,
                                "Type differs. DB: ${existing.type}; DBML: ${column.type}",table,column,
                                sql=sqlType?.let { "ALTER TABLE ${name.sql} ALTER COLUMN ${pgQuote(column.name)} TYPE $it;" },review=true,dependencies=typeDependency(column))
                        val nullable = column.nullable && column.name !in primaryKeyColumns(table)
                        if(nullable != existing.nullable) report(SchemaDifferenceKind.CHANGED,id("nullable",name.toString(),column.name),path,
                            "Nullability differs. DB: ${existing.nullable}; DBML: $nullable",table,column,
                            sql="ALTER TABLE ${name.sql} ALTER COLUMN ${pgQuote(column.name)} ${if(nullable) "DROP" else "SET"} NOT NULL;",review=true)
                        val identity = column.increment || column.type.lowercase() in setOf("serial","bigserial","smallserial")
                        if(identity != existing.identity) report(SchemaDifferenceKind.CHANGED,id("identity",name.toString(),column.name),path,
                            "Identity/sequence setting differs; review sequence ownership and existing values manually",table,column)
                        fun normalizedDefault(value: String?): String? {
                            val literalCast = value?.let { Regex("^('(?:''|[^'])*')::(.+)$").matchEntire(it) }
                            val enumType = enums.keys.firstOrNull { it.name == column.type }
                            return canonicalExpression(if(literalCast != null && enumType != null &&
                                canonicalType(literalCast.groupValues[2]) == canonicalType(enumType.sql)) literalCast.groupValues[1] else value)
                        }
                        if(!identity && !existing.identity && normalizedDefault(column.defaultValue) != normalizedDefault(existing.defaultValue))
                            report(SchemaDifferenceKind.CHANGED,id("default",name.toString(),column.name),path,
                                "Default differs. DB: ${existing.defaultValue ?: "none"}; DBML: ${column.defaultValue ?: "none"}",table,column,
                                sql="ALTER TABLE ${name.sql} ALTER COLUMN ${pgQuote(column.name)} " +
                                    (column.defaultValue?.let { "SET DEFAULT $it" } ?: "DROP DEFAULT") + ";",review=true)
                    }
                }
                database.columns.filter { db -> table.columns.none { it.name == db.name } }.forEach {
                    report(SchemaDifferenceKind.DATABASE_ONLY,id("extra-column",name.toString(),it.name),"$name.${it.name}","Column exists only in the database; no DROP is generated",table)
                }
            }
        }
        val keyChanges = mutableMapOf<Pair<DatabaseName,Set<String>>,String>()
        expected.forEach { (name, table) ->
            if(name in blockedTables) return@forEach
            val database = tables[name]
            val primary = primaryKeyColumns(table)
            val desiredKeys = buildList {
                if(primary.isNotEmpty()) add(Triple(primary,true,"pk_${table.name}_${primary.joinToString("_")}"))
                table.columns.filter { it.unique && it.name !in primary }.forEach { add(Triple(listOf(it.name),false,"uq_${table.name}_${it.name}")) }
                table.indexes.filter { it.unique && !it.primaryKey }.forEach { add(Triple(it.columns,false,it.name ?: "uq_${table.name}_${it.columns.joinToString("_")}")) }
            }.distinctBy { it.first.toSet() to it.second }
            desiredKeys.forEach { (names, pk, requestedName) ->
                if(names.any { column -> table.columns.none { it.name==column } }) {
                    report(SchemaDifferenceKind.UNSUPPORTED,id("unsupported-key",name.toString(),requestedName),"$name.$requestedName",
                        "Expression or unresolved key columns require manual review",table)
                    return@forEach
                }
                val matched = database?.keys?.any { it.ordinary && it.columns.toSet() == names.toSet() && (!pk || it.primary) } == true ||
                    (!pk && database?.indexes?.any { it.simple && it.unique && it.columns.toSet() == names.toSet() } == true)
                if(matched) return@forEach
                val constraintName = shortName(requestedName)
                val key = id(if(pk) "pk" else "unique",name.toString(),names.joinToString("\u0000"))
                keyChanges[name to names.toSet()] = key
                val conflict = (pk && database?.keys?.any { it.primary } == true) || constraintName in constraintNames[name].orEmpty() ||
                    DatabaseName(name.schema,constraintName) in plannedRelations
                if(!conflict) { plannedRelations+=DatabaseName(name.schema,constraintName);constraintNames.getOrPut(name) { mutableSetOf() }+=constraintName }
                report(SchemaDifferenceKind.MISSING,key,"$name (${names.joinToString()})",
                    if(conflict) "Key definition/name conflicts with an existing object; review manually" else "${if(pk) "Primary" else "Unique"} key is missing in the database",table,
                    range=table.indexes.firstOrNull { it.columns == names }?.sourceRange ?: table.columns.firstOrNull { it.name == names.first() }?.sourceRange,
                    sql=if(conflict) null else "ALTER TABLE ${name.sql} ADD CONSTRAINT ${pgQuote(constraintName)} ${if(pk) "PRIMARY KEY" else "UNIQUE"} (${columns(names)});",
                    review=database != null,dependencies=columnDependencies(name,names))
            }
            table.indexes.filter { !it.primaryKey && !it.unique }.forEach { index ->
                if(index.columns.any { column -> table.columns.none { it.name==column } }) {
                    report(SchemaDifferenceKind.UNSUPPORTED,id("unsupported-index",name.toString(),index.columns.joinToString()),name.toString(),
                        "Expression or unresolved index columns require manual review",table,range=index.sourceRange)
                    return@forEach
                }
                if(database?.indexes?.any { it.simple && it.columns == index.columns } == true) return@forEach
                val indexName = shortName(index.name ?: "ix_${table.name}_${index.columns.joinToString("_")}")
                val occupied = DatabaseName(name.schema,indexName) in plannedRelations
                if(!occupied) plannedRelations+=DatabaseName(name.schema,indexName)
                report(SchemaDifferenceKind.MISSING,id("index",name.toString(),index.columns.joinToString("\u0000")),"$name.$indexName",
                    if(occupied) "Index name exists with a different/unsupported definition" else "Index is missing in the database",table,range=index.sourceRange,
                    sql=if(occupied) null else "CREATE INDEX ${pgQuote(indexName)} ON ${name.sql} (${columns(index.columns)});",dependencies=columnDependencies(name,index.columns))
            }
            (table.checks + table.columns.flatMap { it.checks }).forEachIndexed { ordinal, check ->
                val matched = database?.checks?.firstOrNull { canonicalExpression(it.expression) == canonicalExpression(check.expression) }
                if(matched?.validated == true) return@forEachIndexed
                val constraintName = shortName(check.name ?: "ck_${table.name}_${ordinal + 1}")
                val conflict = matched != null || constraintName in constraintNames[name].orEmpty()
                if(!conflict) constraintNames.getOrPut(name) { mutableSetOf() }+=constraintName
                report(SchemaDifferenceKind.MISSING,id("check",name.toString(),check.expression),"$name.$constraintName",
                    if(conflict) "CHECK is different or NOT VALID; review manually" else "CHECK is missing in the database",table,range=check.sourceRange,
                    sql=if(conflict) null else "ALTER TABLE ${name.sql} ADD CONSTRAINT ${pgQuote(constraintName)} CHECK (${check.expression});",
                    review=database != null,dependencies=tableDependency(name)+columnDependencies(name,table.columns.map { it.name }))
            }
            database?.keys?.filter { key -> desiredKeys.none { (cols,pk,_) -> key.columns.toSet()==cols.toSet() && key.primary==pk } }?.forEach { key ->
                report(SchemaDifferenceKind.DATABASE_ONLY,id("extra-key",name.toString(),key.name),"$name.${key.name}","Key exists only in the database; no DROP is generated",table)
            }
            database?.indexes?.filter { db -> database.keys.none { it.name==db.name } &&
                table.indexes.none { it.columns==db.columns && it.unique==db.unique && db.simple } &&
                !(db.unique && db.simple && desiredKeys.any { it.first.toSet()==db.columns.toSet() }) }?.forEach { index ->
                report(SchemaDifferenceKind.DATABASE_ONLY,id("extra-index",name.toString(),index.name),"$name.${index.name}","Index exists only in the database or has an unsupported definition; no DROP is generated",table)
            }
            val expectedChecks=table.checks+table.columns.flatMap { it.checks }
            database?.checks?.filter { db -> expectedChecks.none { it.name==db.name || canonicalExpression(it.expression)==canonicalExpression(db.expression) } }?.forEach { check ->
                report(SchemaDifferenceKind.DATABASE_ONLY,id("extra-check",name.toString(),check.name),"$name.${check.name}","CHECK exists only in the database; no DROP is generated",table)
            }
        }
        desired.references.forEach { ref ->
            val from = ref.foreignKeyEndpoint()
            val to = ref.referencedEndpoint()
            if(from == null || to == null) {
                report(SchemaDifferenceKind.UNSUPPORTED,id("conceptual",ref.routeId()),ref.routeId(),"Conceptual N:N needs an explicit junction table for database comparison",range=ref.sourceRange)
                return@forEach
            }
            fun resolve(endpoint: DbmlColumnRef) = desired.tables.firstOrNull { it.qualifiedName == endpoint.tableName || (endpoint.schema == null && it.alias == endpoint.table) }
            val source = resolve(from) ?: return@forEach
            val target = resolve(to) ?: return@forEach
            val sourceName = DatabaseName(source.schema ?: defaultSchema,source.name)
            val targetName = DatabaseName(target.schema ?: defaultSchema,target.name)
            if(sourceName in blockedTables || targetName in blockedTables) return@forEach
            val database = tables[sourceName]
            val onDelete = ref.onDelete?.lowercase() ?: "no action"
            val onUpdate = ref.onUpdate?.lowercase() ?: "no action"
            if(listOf(onDelete,onUpdate).any { it !in setOf("cascade","restrict","no action","set null","set default") }) {
                report(SchemaDifferenceKind.UNSUPPORTED,id("unsupported-action",ref.routeId()),sourceName.toString(),
                    "Unsupported referential action; use cascade, restrict, no action, set null or set default",source,range=ref.sourceRange)
                return@forEach
            }
            val candidate = database?.foreignKeys?.firstOrNull { it.columns == from.columns && it.target == targetName && it.targetColumns == to.columns }
            if(candidate != null && candidate.ordinary && candidate.onDelete == onDelete && candidate.onUpdate == onUpdate) return@forEach
            val constraintName = shortName(ref.name ?: "fk_${source.name}_${from.columns.joinToString("_")}_${target.name}")
            val conflict = candidate != null || constraintName in constraintNames[sourceName].orEmpty() || database?.foreignKeys?.any { it.columns == from.columns } == true
            if(!conflict) constraintNames.getOrPut(sourceName) { mutableSetOf() }+=constraintName
            val targetKey = keyChanges[targetName to to.columns.toSet()]
            val ownKey = if(ref.cardinality==DbmlCardinality.ONE_TO_ONE) keyChanges[sourceName to from.columns.toSet()] else null
            report(if(conflict) SchemaDifferenceKind.CHANGED else SchemaDifferenceKind.MISSING,id("fk",ref.routeId()),"$sourceName (${from.columns.joinToString()})",
                if(conflict) "Foreign key target/actions/validation differ; review existing constraint manually" else "Foreign key is missing in the database",source,range=ref.sourceRange,
                sql=if(conflict) null else "ALTER TABLE ${sourceName.sql} ADD CONSTRAINT ${pgQuote(constraintName)} FOREIGN KEY (${columns(from.columns)}) REFERENCES ${targetName.sql} (${columns(to.columns)}) ON DELETE ${safeAction(onDelete)} ON UPDATE ${safeAction(onUpdate)};",
                review=database != null,dependencies=columnDependencies(sourceName,from.columns)+columnDependencies(targetName,to.columns)+listOfNotNull(targetKey,ownKey))
        }
        actual.tables.filter { it.id !in expected }.forEach {
            report(SchemaDifferenceKind.DATABASE_ONLY,id("extra-table",it.id.toString()),it.id.toString(),"Table exists only in the database; no DROP is generated")
        }
        actual.tables.filter { it.id in expected }.forEach { database ->
            database.foreignKeys.filter { fk -> desired.references.none { ref ->
                val source=ref.foreignKeyEndpoint()
                val table=source?.let { endpoint -> desired.tables.firstOrNull { it.qualifiedName==endpoint.tableName || it.alias==endpoint.table } }
                table!=null && DatabaseName(table.schema ?: defaultSchema,table.name)==database.id && source.columns==fk.columns
            } }.forEach { fk ->
                report(SchemaDifferenceKind.DATABASE_ONLY,id("extra-fk",database.id.toString(),fk.name),"${database.id}.${fk.name}",
                    "Foreign key exists only in the database; no DROP is generated",expected[database.id])
            }
        }
        return SchemaComparison(changes.distinctBy { it.id },actual.capturedAtMillis)
    }

    private fun shortName(value: String): String {
        if(value.toByteArray(Charsets.UTF_8).size <= 63) return value
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        var prefix = value.take(40)
        while(prefix.toByteArray(Charsets.UTF_8).size > 48) prefix = prefix.dropLast(1)
        return prefix + "_" + digest
    }
    private fun safeAction(value: String): String = value.takeIf { it in setOf("cascade","restrict","no action","set null","set default") }?.uppercase()
        ?: error("Unsupported referential action")

    companion object {
        fun canonicalType(value: String): String {
            if(value.trim().endsWith("[]")) return canonicalType(value.trim().removeSuffix("[]"))+"[]"
            var text = Regex("\"(?:\"\"|[^\"])*\"|[^\"]+").findAll(value.trim()).joinToString("") {
                val token = it.value
                if(token.startsWith("\"")) {
                    val name = token.drop(1).dropLast(1)
                    if(name.matches(Regex("[a-z_][a-z_0-9$]*"))) name else token
                } else token.lowercase()
            }.replace(Regex("\\s+")," ")
            text = text.replace(Regex("^(varchar)(?=\\(|$)"),"character varying")
                .replace(Regex("^(int4|int)(?=$)"),"integer").replace(Regex("^int8$"),"bigint").replace(Regex("^int2$"),"smallint")
                .replace(Regex("^bool$"),"boolean").replace(Regex("^float8$"),"double precision").replace(Regex("^float4$"),"real")
            if(text == "timestamp") text = "timestamp without time zone"
            if(text == "timestamptz") text = "timestamp with time zone"
            if(text == "decimal" || text.startsWith("decimal(")) text = "numeric" + text.removePrefix("decimal")
            return text.replace(Regex("\\s*([(),])\\s*"),"$1")
        }

        /** Conservative token comparison: preserve string literals and operator/identifier boundaries. */
        fun canonicalExpression(value: String?): String? {
            if(value == null) return null
            val tokens = Regex("'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|[A-Za-z_][A-Za-z_0-9$]*|\\d+(?:\\.\\d+)?|::|>=|<=|<>|!=|\\S")
                .findAll(value).map { val s=it.value; if(s.startsWith("'") || s.startsWith("\"")) s else s.lowercase() }.toList().toMutableList()
            while(tokens.firstOrNull() == "(" && tokens.lastOrNull() == ")") {
                var depth=0
                if(tokens.dropLast(1).any { if(it == "(") depth++ else if(it == ")") depth--; depth == 0 }) break
                tokens.removeAt(tokens.lastIndex); tokens.removeAt(0)
            }
            var index = 0
            while(index + 2 < tokens.size) {
                if(tokens[index] == "(" && tokens[index+2] == ")" &&
                    (tokens[index+1].matches(Regex("\\d+(?:\\.\\d+)?")) || tokens[index+1].startsWith("'"))) {
                    tokens.removeAt(index+2); tokens.removeAt(index)
                } else index++
            }
            index=1
            while(index + 1 < tokens.size) {
                if(tokens[index] == "::" && tokens[index-1].matches(Regex("\\d+(?:\\.\\d+)?")) &&
                    tokens[index+1] in setOf("numeric","integer","bigint","smallint") && tokens.getOrNull(index+2) != "(") {
                    tokens.removeAt(index+1); tokens.removeAt(index)
                } else index++
            }
            if(tokens.size >= 3 && tokens[0].startsWith("'") && tokens[1] == "::" &&
                tokens.drop(2).joinToString(" ") in setOf("text","character varying","varchar","bpchar")) return tokens[0]
            val normalized = tokens.joinToString(" ")
            return when(normalized) { "null", "" -> null; "current_timestamp", "now ( )" -> "now()"; else -> normalized }
        }
    }
}
