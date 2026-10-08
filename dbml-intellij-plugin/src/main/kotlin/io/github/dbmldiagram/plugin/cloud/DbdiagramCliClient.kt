package io.github.dbmldiagram.plugin.cloud

import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class CliPaths(val node: String, val entry: String) {
    fun command(args: List<String>): List<String> {
        if (!Files.isRegularFile(Path.of(node)) || !Files.isRegularFile(Path.of(entry)) || !entry.endsWith(".js")) {
            throw CloudException("Configure Node.js and the dbdiagram CLI .js entry in Connection settings. Install CLI with: npm install -g dbdiagram")
        }
        return listOf(node, entry) + args // No shell parsing, even on Windows.
    }
    companion object {
        fun detect(): CliPaths {
            val dirs = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it) }
            val windows = System.getProperty("os.name").startsWith("Windows")
            val nodeName = if (windows) "node.exe" else "node"
            val candidates = dirs + Path.of(System.getProperty("user.home"), "AppData", "Roaming", "npm")
            val entries = candidates.flatMap { dir -> listOf(dir.resolve("node_modules/dbdiagram/dist/index.js"), dir.resolve("../lib/node_modules/dbdiagram/dist/index.js"), dir.resolve("dbdiagram")) }
            val entry = entries.firstOrNull { p -> Files.isRegularFile(p) && (p.toString().endsWith(".js") || runCatching { p.toRealPath().toString().endsWith("/dbdiagram/dist/index.js") }.getOrDefault(false)) }
            val node = dirs.map { it.resolve(nodeName) }.firstOrNull { Files.isRegularFile(it) }
            return CliPaths(node?.toString().orEmpty(), entry?.toRealPath()?.toString().orEmpty())
        }
    }
}

internal fun interface CliRunner { fun run(args: List<String>, cwd: Path, login: Boolean): String }

internal class DbdiagramCliClient(private val runner: CliRunner, private val workspace: String = "") : CloudClient {
    override fun login() = inTemp { runner.run(listOf("auth", "login"), it, true); Unit }
    override fun logout() = inTemp { runner.run(listOf("auth", "logout"), it, false); Unit }
    override fun list(): List<CloudDiagram> = inTemp { dir ->
        val args = listOf("list", "--json") + if (workspace.isBlank()) emptyList() else listOf("--workspace", workspace)
        val json = CloudData.cliJson(runner.run(args, dir, false))
        if (!json.isJsonArray) throw CloudException("Unexpected CLI diagram list response.")
        json.asJsonArray.map { CloudData.parseDiagram(it, false) }
    }
    override fun get(id: String): CloudDiagram = inTemp { dir ->
        val validId = CloudData.diagramId(id)
        val target = dir.resolve("schema.dbml")
        val output = runner.run(listOf("pull", "--diagram-id", validId, "--out-file", target.toString()), dir, false)
        // The official CLI skips writing empty remote diagrams; a fresh directory has no stale output.
        val content = if (Files.exists(target)) {
            if (Files.size(target) > MAX_CLOUD_BYTES) throw CloudException("Remote DBML exceeds the 8 MiB safety limit.")
            Files.readString(target)
        } else if (output.contains("Pulled content is empty.")) "" else throw CloudException("CLI did not produce a DBML file. The local document was not changed.")
        CloudDiagram(validId, validId, content)
    }
    override fun push(id: String, name: String, content: String) = inTemp { dir ->
        CloudData.checkContent(content)
        val validId = CloudData.diagramId(id)
        val source = dir.resolve("schema.dbml")
        Files.writeString(source, content)
        val result = CloudData.cliJson(runner.run(listOf("push", source.toString(), "--diagram-id", validId, "--json"), dir, false))
        if (!result.isJsonObject || result.asJsonObject.get("diagramId")?.asString != validId) {
            throw CloudException("Push returned an unexpected diagram ID. Check the remote diagram before retrying.")
        }
    }
    private fun <T> inTemp(action: (Path) -> T): T {
        val dir = Files.createTempDirectory("dbml-dbdiagram-")
        try { return action(dir) } finally {
            // Only our private temporary directory is touched. Never run init in the user's repository.
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    companion object {
        fun connect(paths: CliPaths, workspace: String, checkCanceled: () -> Unit) = DbdiagramCliClient(CliRunner { args, dir, login ->
            checkCanceled()
            val process = ProcessBuilder(paths.command(args)).directory(dir.toFile()).redirectErrorStream(true).apply {
                environment()["NO_COLOR"] = "1"
                // Use official hosts and the CLI's browser-login credentials, not inherited project tokens/URL overrides.
                listOf("DBDIAGRAM_TOKEN", "PORTAL_LOGIN_URL", "PORTAL_API_URL", "DBDIAGRAM_BASE_URL", "DBDOCS_BASE_URL", "TRACKING_COLLECTOR_URL", "TRACKING_APP_ID").forEach { environment().remove(it) }
            }.start()
            process.outputStream.close()
            val overflow = AtomicBoolean(false)
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dbdiagram-output").apply { isDaemon = true } }
            val output = executor.submit<String> {
                val bytes = ByteArrayOutputStream()
                process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count <= MAX_CLOUD_BYTES) bytes.write(buffer, 0, count) else overflow.set(true)
                    }
                }
                bytes.toString(Charsets.UTF_8)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(if (login) 300 else 90)
            try {
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    checkCanceled()
                    if (overflow.get()) throw CloudException("CLI response exceeded the safety limit.")
                    if (System.nanoTime() > deadline) throw CloudException("dbdiagram timed out. Check login or connectivity before retrying a push.")
                }
                checkCanceled()
                val text = output.get(5, TimeUnit.SECONDS)
                if (overflow.get()) throw CloudException("CLI response exceeded the safety limit.")
                if (process.exitValue() != 0) {
                    val code = runCatching { CloudData.cliJson(text).asJsonObject.get("errorCode")?.asString }.getOrNull().orEmpty()
                    throw CloudException("dbdiagram CLI failed${if (code.matches(Regex("[a-z_]{1,64}"))) " ($code)" else ""}. Check login, permissions, CLI version, and DBML syntax.")
                }
                text
            } finally {
                if (process.isAlive) {
                    // Leave the external login browser alone, but stop export/parse workers on cancellation.
                    if (!login) process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
                output.cancel(true); executor.shutdownNow()
            }
        }, workspace)
    }
}
