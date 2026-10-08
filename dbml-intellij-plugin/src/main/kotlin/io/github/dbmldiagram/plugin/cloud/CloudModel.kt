package io.github.dbmldiagram.plugin.cloud

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.URI
import java.security.MessageDigest

internal const val MAX_CLOUD_BYTES = 8 * 1024 * 1024

internal data class CloudDiagram(val id: String, val name: String, val content: String? = null,
    val updatedAt: String = "", val createdAt: String = "", val status: String = "", val workspaceId: String = "") {
    val url: String get() = "https://dbdiagram.io/d/$id"
}

internal class CloudException(message: String) : RuntimeException(message)

internal interface CloudClient {
    fun login()
    fun logout()
    fun list(): List<CloudDiagram>
    fun get(id: String): CloudDiagram
    fun push(id: String, name: String, content: String)
}

internal object CloudData {
    fun diagramId(input: String): String {
        val value = input.trim()
        val candidate = if (value.startsWith("https://")) {
            val uri = runCatching { URI(value) }.getOrNull() ?: throw CloudException("Invalid diagram URL.")
            if (uri.host != "dbdiagram.io" || uri.userInfo != null || uri.port != -1) throw CloudException("Use a dbdiagram.io diagram URL or its ID.")
            val path = uri.path.removePrefix("/d/").trimEnd('/')
            if (!uri.path.startsWith("/d/") || path.contains('/')) throw CloudException("Use a diagram URL in the form https://dbdiagram.io/d/<id>.")
            path.takeLast(24)
        } else value
        if (!candidate.matches(Regex("[a-fA-F0-9]{24}"))) throw CloudException("A diagram ID must contain 24 hexadecimal characters.")
        return candidate.lowercase()
    }

    fun fingerprint(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.replace("\r\n", "\n").replace('\r', '\n').toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun checkPush(baseline: String, remote: String, local: String) {
        if (baseline.isBlank()) throw CloudException("Pull or link the remote diagram before pushing.")
        val remoteHash = fingerprint(remote)
        if (remoteHash != baseline && remoteHash != fingerprint(local)) {
            throw CloudException("The remote diagram changed since the last sync. Push was stopped. Pull into a separate file and merge your changes before retrying.")
        }
    }

    fun checkContent(content: String) {
        if (content.isBlank()) throw CloudException("An empty local document cannot be pushed. This prevents accidentally clearing a remote schema.")
        if (content.toByteArray(Charsets.UTF_8).size > MAX_CLOUD_BYTES) throw CloudException("DBML exceeds the 8 MiB limit.")
    }

    fun parseDiagram(element: JsonElement, requireContent: Boolean = true): CloudDiagram {
        if (!element.isJsonObject) throw CloudException("Unexpected diagram response.")
        val obj = element.asJsonObject
        fun string(key: String) = obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val id = diagramId(string("_id") ?: string("id") ?: throw CloudException("The server did not return a diagram ID."))
        val content = string("content")
        if (requireContent && content == null) throw CloudException("The server did not return DBML content; the local file was not changed.")
        return CloudDiagram(id, string("name") ?: "Untitled Diagram", if (requireContent) content else null,
            string("updatedAt").orEmpty(), string("createdAt").orEmpty(), string("status").orEmpty(), string("workspaceId").orEmpty())
    }

    fun cliJson(output: String): JsonElement {
        val clean = output.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
        // The official CLI writes progress lines before its --json result.
        var start = 0
        for (line in clean.split('\n')) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                runCatching { JsonParser.parseString(clean.substring(start).trim()) }.getOrNull()?.let { return it }
            }
            start += line.length + 1
        }
        if (clean.contains("No diagrams found.")) return JsonParser.parseString("[]")
        throw CloudException("Unexpected CLI output. Update dbdiagram CLI or check the connection settings.")
    }
}
