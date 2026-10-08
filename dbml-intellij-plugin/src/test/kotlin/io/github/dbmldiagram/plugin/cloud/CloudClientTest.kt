package io.github.dbmldiagram.plugin.cloud

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Flow
import java.util.concurrent.ExecutionException

class CloudClientTest {
    private val id = "6ab985d75869425612aa7738"
    private fun rejected(action: () -> Unit): CloudException {
        try { action(); fail("Expected rejection") } catch (e: CloudException) { return e }
        error("unreachable")
    }
    @Test fun `ID accepts official URL and rejects other hosts and unsafe input`() {
        assertEquals(id, CloudData.diagramId(id.uppercase()))
        assertEquals(id, CloudData.diagramId(" https://dbdiagram.io/d/Project-DB-$id?view=full "))
        listOf("https://evil.example/d/$id", "https://dbdiagram.io.evil.example/d/$id", "https://user@dbdiagram.io/d/$id",
            "https://dbdiagram.io:443/d/$id", "https://dbdiagram.io/d/$id/other", "http://dbdiagram.io/d/$id", "x; rm", "abcd").forEach { value -> rejected { CloudData.diagramId(value) } }
    }
    @Test fun `fingerprint normalizes line endings and protects remote changes`() {
        assertEquals(CloudData.fingerprint("a\nb\n"), CloudData.fingerprint("a\r\nb\r\n"))
        CloudData.checkPush(CloudData.fingerprint("remote"), "remote", "local")
        CloudData.checkPush(CloudData.fingerprint("old"), "already pushed", "already pushed")
        rejected { CloudData.checkPush("", "remote", "local") }
        assertTrue(rejected { CloudData.checkPush(CloudData.fingerprint("old"), "someone else's edit", "local") }.message!!.contains("remote diagram changed"))
    }
    @Test fun `empty or oversized push is rejected`() {
        rejected { CloudData.checkContent(" \n\t") }
        rejected { CloudData.checkContent("я".repeat(MAX_CLOUD_BYTES / 2 + 1)) }
        CloudData.checkContent("Table users { id int [pk] }")
    }
    @Test fun `API sends only DBML and name keeping website visualization untouched`() {
        val calls = mutableListOf<Triple<String, String, String?>>()
        val client = DbdiagramApiClient(ApiTransport { method, path, body ->
            calls += Triple(method, path, body)
            ApiResponse(200, if (path == "/diagrams") "[{\"_id\":\"$id\",\"name\":\"Project DB\",\"content\":\"old\",\"createdAt\":\"2026-01-01\"}]" else "{\"_id\":\"$id\",\"name\":\"Project DB\",\"content\":\"old\"}")
        })
        assertNull(client.list().single().content)
        assertEquals("2026-01-01", client.list().single().createdAt)
        assertEquals("old", client.get(id).content)
        val source = "Table \"Таблица\" { id int [pk] }\n// backslash \\ and quote \""
        client.push(id, "Name \"quoted\"", source)
        val put = calls.last()
        assertEquals("PUT", put.first); assertEquals("/diagrams/$id", put.second)
        val json = JsonParser.parseString(put.third).asJsonObject
        assertEquals(setOf("name", "content"), json.keySet())
        assertEquals(source, json["content"].asString)
        assertEquals("Name \"quoted\"", json["name"].asString)
    }
    @Test fun `API failures do not reveal server bodies or tokens`() {
        for (code in listOf(400, 401, 403, 404, 429, 500, 302)) {
            val client = DbdiagramApiClient(ApiTransport { _, _, _ -> ApiResponse(code, "secret-token-from-server") })
            assertFalse(rejected { client.get(id) }.message!!.contains("secret-token"))
        }
    }
    @Test fun `missing or invalid content is not treated as empty schema`() {
        listOf("{}", "{\"_id\":\"$id\"}", "{\"_id\":\"$id\",\"content\":null}", "not json").forEach { body ->
            rejected { DbdiagramApiClient(ApiTransport { _, _, _ -> ApiResponse(200, body) }).get(id) }
        }
        val client = DbdiagramApiClient(ApiTransport { _, _, _ -> ApiResponse(200, "{\"_id\":\"$id\",\"content\":\"\"}") })
        assertEquals("", client.get(id).content)
    }
    @Test fun `invalid IDs never reach the API transport`() {
        val client = DbdiagramApiClient(ApiTransport { _, _, _ -> fail("Network call on invalid ID"); ApiResponse(200, "{}") })
        rejected { client.get("not-an-id") }
        rejected { client.push("not-an-id", "name", "DBML") }
    }
    @Test fun `different response ID cannot accidentally relink local document`() {
        val client = DbdiagramApiClient(ApiTransport { _, _, _ -> ApiResponse(200, "{\"_id\":\"6ab985d75869425612aa7739\",\"content\":\"wrong schema\"}") })
        rejected { client.get(id) }
    }
    @Test fun `CLI parses progress ANSI and empty lists`() {
        val client = DbdiagramCliClient(CliRunner { args, _, _ ->
            assertEquals(listOf("list", "--json", "--workspace", id), args)
            "\u001B[32mFetching diagrams...\u001B[0m\n[{\"id\":\"$id\",\"name\":\"Orders\",\"updatedAt\":\"October 1st 2026\"}]\n"
        }, id)
        assertEquals("Orders", client.list().single().name)
        assertTrue(DbdiagramCliClient(CliRunner { _, _, _ -> "Fetching diagrams...\nNo diagrams found." }).list().isEmpty())
        rejected { CloudData.cliJson("Fetching diagrams...\nfailed") }
    }
    @Test fun `CLI pull uses isolated explicit file and cleans temporary visualization`() {
        var directory: Path? = null
        val client = DbdiagramCliClient(CliRunner { args, cwd, login ->
            directory = cwd; assertFalse(login)
            assertEquals(listOf("pull", "--diagram-id", id, "--out-file", cwd.resolve("schema.dbml").toString()), args)
            Files.writeString(cwd.resolve("schema.dbml"), "Table users { id int [pk] }")
            Files.writeString(cwd.resolve("schema.dbml.viz.json"), "website layout")
            "Pulled successfully"
        })
        assertEquals("Table users { id int [pk] }", client.get(id).content)
        assertFalse(Files.exists(directory!!))
    }
    @Test fun `CLI missing output is rejected except official empty diagram result`() {
        rejected { DbdiagramCliClient(CliRunner { _, _, _ -> "Success but no output" }).get(id) }
        assertEquals("", DbdiagramCliClient(CliRunner { _, _, _ -> "Pulled content is empty. Skipping write to avoid overwriting local files." }).get(id).content)
    }
    @Test fun `CLI push neither initializes project nor creates or renames diagram`() {
        var directory: Path? = null
        val client = DbdiagramCliClient(CliRunner { args, cwd, _ ->
            directory = cwd
            assertEquals(listOf("push", cwd.resolve("schema.dbml").toString(), "--diagram-id", id, "--json"), args)
            assertEquals("local DBML", Files.readString(cwd.resolve("schema.dbml")))
            assertEquals(1L, Files.list(cwd).use { it.count() })
            "Pushing...\n{\"diagramId\":\"$id\",\"url\":\"https://dbdiagram.io/d/$id\"}"
        })
        client.push(id, "preserve remote name", "local DBML")
        assertFalse(Files.exists(directory!!))
    }
    @Test fun `CLI temporary directory is cleaned on failure`() {
        var directory: Path? = null
        rejected { DbdiagramCliClient(CliRunner { _, cwd, _ -> directory = cwd; throw CloudException("failed") }).get(id) }
        assertFalse(Files.exists(directory!!))
    }
    @Test fun `canceled CLI operation does not start even an invalid executable`() {
        class AlreadyCanceled : RuntimeException()
        try {
            DbdiagramCliClient.connect(CliPaths("missing-node", "missing-entry.js"), "") { throw AlreadyCanceled() }.list()
            fail("Canceled command was started")
        } catch (_: AlreadyCanceled) { /* Check runs before launch or path validation. */ }
    }
    @Test fun `canceled API operation does not start a network request`() {
        class AlreadyCanceled : RuntimeException()
        try {
            DbdiagramApiClient.connect("test-token") { throw AlreadyCanceled() }.list()
            fail("Canceled request was started")
        } catch (_: AlreadyCanceled) { /* Check runs before sendAsync. */ }
    }
    @Test fun `CLI auth passes browser flag and correct commands`() {
        val calls = mutableListOf<Pair<List<String>, Boolean>>()
        val client = DbdiagramCliClient(CliRunner { args, _, login -> calls += args to login; "Done" })
        client.login(); client.logout()
        assertEquals(listOf(listOf("auth", "login") to true, listOf("auth", "logout") to false), calls)
    }
    @Test fun `CLI process arguments remain literal even with spaces and shell characters`() {
        val dir = Files.createTempDirectory("cli path spaces ")
        try {
            val node = Files.createFile(dir.resolve("node executable")); val entry = Files.createFile(dir.resolve("dbdiagram entry.js"))
            assertEquals(listOf(node.toString(), entry.toString(), "a & b; $"), CliPaths(node.toString(), entry.toString()).command(listOf("a & b; $")))
        } finally { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun `HTTP subscriber stops receiving before oversized allocation`() {
        var canceled = false
        val body = LimitedBodySubscriber(4)
        body.onSubscribe(object : Flow.Subscription { override fun request(n: Long) = Unit; override fun cancel() { canceled = true } })
        body.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
        body.onNext(listOf(ByteBuffer.wrap(byteArrayOf(4, 5))))
        assertTrue(canceled)
        try { body.body.toCompletableFuture().get(); fail("Oversized body accepted") } catch (e: ExecutionException) { assertTrue(e.cause is CloudException) }
    }
    @Test fun `HTTP subscriber preserves UTF8 bytes across chunks`() {
        val body = LimitedBodySubscriber(100)
        body.onSubscribe(object : Flow.Subscription { override fun request(n: Long) = Unit; override fun cancel() = Unit })
        val bytes = "таблица".toByteArray(Charsets.UTF_8)
        body.onNext(listOf(ByteBuffer.wrap(bytes.copyOfRange(0, 1)), ByteBuffer.wrap(bytes.copyOfRange(1, bytes.size))))
        body.onComplete()
        assertEquals("таблица", body.body.toCompletableFuture().get().toString(Charsets.UTF_8))
    }
}
