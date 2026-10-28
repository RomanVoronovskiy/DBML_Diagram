package io.github.dbmldiagram.plugin.importer

import io.github.dbmldiagram.core.model.DbmlCardinality
import io.github.dbmldiagram.core.model.DbmlColumnRef
import io.github.dbmldiagram.core.parser.TolerantDbmlParser
import io.github.dbmldiagram.core.validation.isColumnsUnique
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class SqlImportDialect(val flag: String, val label: String) {
    POSTGRESQL("--postgres", "PostgreSQL"), MYSQL("--mysql", "MySQL"), ORACLE("--oracle", "Oracle");
    override fun toString() = label
}

internal data class SqlImportPaths(val node: String, val entry: String) {
    fun command(source: Path, target: Path, dialect: SqlImportDialect): List<String> {
        require(Files.isRegularFile(Path.of(node)) && Files.isRegularFile(Path.of(entry)) && entry.endsWith(".js")) {
            "Select Node.js and @dbml/cli/bin/sql2dbml.js. Install with: npm install -g @dbml/cli"
        }
        return listOf(node, entry, source.toString(), dialect.flag, "-o", target.toString())
    }

    companion object {
        fun detect(nodeHint: String = ""): SqlImportPaths {
            val dirs = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).filter(String::isNotBlank).map(Path::of)
            val nodeName = if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node"
            val node = nodeHint.takeIf { it.isNotBlank() && Files.isRegularFile(Path.of(it)) }
                ?: dirs.map { it.resolve(nodeName) }.firstOrNull(Files::isRegularFile)?.toString().orEmpty()
            val roots = dirs + listOfNotNull(node.takeIf(String::isNotBlank)?.let { Path.of(it).parent },
                Path.of(System.getProperty("user.home"), "AppData", "Roaming", "npm"))
            val candidates = roots.flatMap { dir -> listOf(dir.resolve("node_modules/@dbml/cli/bin/sql2dbml.js"),
                dir.resolve("../lib/node_modules/@dbml/cli/bin/sql2dbml.js"), dir.resolve("sql2dbml")) }
            val entry = candidates.firstOrNull { Files.isRegularFile(it) &&
                runCatching { it.toRealPath().toString().endsWith("sql2dbml.js") }.getOrDefault(false) }
            return SqlImportPaths(node, entry?.toRealPath()?.toString().orEmpty())
        }
    }
}

/** Local conversion only: SQL is a temporary text input, never executed against a database. */
internal object SqlImportService {
    const val MAX_BYTES = 8 * 1024 * 1024

    fun convert(sql: String, paths: SqlImportPaths, dialect: SqlImportDialect, checkCanceled: () -> Unit = {}): String {
        require(sql.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "SQL exceeds the 8 MiB import limit." }
        require(sql.isNotBlank()) { "The SQL file is empty." }
        checkCanceled()
        val directory = Files.createTempDirectory("dbml-sql-import-")
        try {
            val source = directory.resolve("input.sql")
            val target = directory.resolve("output.dbml")
            Files.writeString(source, sql)
            val process = ProcessBuilder(paths.command(source, target, dialect)).directory(directory.toFile()).redirectErrorStream(true)
                .apply { environment()["NO_COLOR"] = "1" }.start()
            process.outputStream.close()
            val overflow = AtomicBoolean(false)
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dbml-sql-import-output").apply { isDaemon = true } }
            val output = executor.submit<String> {
                val bytes = ByteArrayOutputStream()
                process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count <= 1024 * 1024) bytes.write(buffer, 0, count) else overflow.set(true)
                    }
                }
                bytes.toString(Charsets.UTF_8)
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    checkCanceled()
                    require(!overflow.get()) { "SQL converter output exceeded the limit." }
                    require(System.nanoTime() < deadline) { "SQL conversion timed out after 120 seconds." }
                }
                checkCanceled()
                val message = output.get(5, TimeUnit.SECONDS)
                require(!overflow.get()) { "SQL converter output exceeded the limit." }
                require(process.exitValue() == 0) { "SQL conversion failed. Check the selected dialect and SQL syntax.\n${message.take(4000)}" }
                require(Files.isRegularFile(target) && Files.size(target) in 1..MAX_BYTES.toLong()) { "Converter did not produce a nonempty DBML file within the 8 MiB limit." }
                return normalizeCardinality(Files.readString(target))
            } finally {
                if (process.isAlive) {
                    process.descendants().use { it.forEach { child -> child.destroyForcibly() } }
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
                output.cancel(true)
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
                process.inputStream.close()
                process.errorStream.close()
            }
        } finally {
            cleanup(directory)
        }
    }

    private fun cleanup(directory: Path) {
        // Close the walk before deleting its root. Windows can retain the terminated worker's
        // current-directory handle briefly, so allow a short bounded cleanup retry.
        var cleanupError: java.io.IOException? = null
        repeat(10) {
            if (!Files.exists(directory)) return
            try {
                Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).toList() }.forEach(Files::deleteIfExists)
                return
            } catch (error: java.io.IOException) {
                cleanupError = error
                Thread.sleep(50)
            }
        }
        if (Files.exists(directory)) throw cleanupError ?: java.io.IOException("Cannot remove SQL import temporary directory.")
    }

    /** The official converter emits 1:N for unique FKs too; preserve ownership while displaying 1:1. */
    internal fun normalizeCardinality(dbml: String): String {
        val schema = TolerantDbmlParser().parse(dbml).schema ?: return dbml
        val lines = dbml.lines().toMutableList()
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun endpoint(ref: DbmlColumnRef) = listOfNotNull(ref.schema, ref.table).joinToString(".", transform = ::quote) + "." +
            if (ref.columns.size == 1) quote(ref.column) else ref.columns.joinToString(", ", "(", ")", transform = ::quote)
        schema.references.forEach { ref ->
            if (ref.cardinality != DbmlCardinality.MANY_TO_ONE && ref.cardinality != DbmlCardinality.ONE_TO_MANY) return@forEach
            val foreign = ref.foreignKeyEndpoint() ?: return@forEach
            val target = ref.referencedEndpoint() ?: return@forEach
            val table = schema.tables.firstOrNull { it.qualifiedName.equals(foreign.tableName, true) } ?: return@forEach
            if (!table.isColumnsUnique(foreign.columns)) return@forEach
            val range = ref.sourceRange ?: return@forEach
            // Only canonical, standalone, single-line converter references can be replaced safely.
            val index = range.line - 1
            if (ref.inline || range.line != range.endLine || !lines[index].trimStart().startsWith("Ref", true)) return@forEach
            val actions = listOfNotNull(ref.onDelete?.let { "delete: $it" }, ref.onUpdate?.let { "update: $it" })
            lines[index] = "Ref" + ref.name?.let { " ${quote(it)}" }.orEmpty() + ": ${endpoint(target)} - ${endpoint(foreign)}" +
                if (actions.isEmpty()) "" else actions.joinToString(", ", " [", "]")
        }
        return lines.joinToString("\n")
    }
}
