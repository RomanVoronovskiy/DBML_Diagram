package io.github.dbmldiagram.plugin.cloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.ExecutionException
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream

/** Apply the limit during reception, not after an unbounded allocation. */
internal class LimitedBodySubscriber(private val limit: Int = MAX_CLOUD_BYTES) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val bytes = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null
    override fun getBody(): CompletionStage<ByteArray> = result
    override fun onSubscribe(value: Flow.Subscription) { subscription = value; value.request(1) }
    override fun onNext(items: List<ByteBuffer>) {
        if (result.isDone) return
        for (buffer in items) {
            if (buffer.remaining() > limit - bytes.size()) {
                subscription?.cancel()
                result.completeExceptionally(CloudException("Remote response exceeds the 8 MiB safety limit."))
                return
            }
            val chunk = ByteArray(buffer.remaining()); buffer.get(chunk); bytes.write(chunk)
        }
        subscription?.request(1)
    }
    override fun onError(error: Throwable) { result.completeExceptionally(error) }
    override fun onComplete() { result.complete(bytes.toByteArray()) }
}

internal data class ApiResponse(val status: Int, val body: String)
internal fun interface ApiTransport { fun request(method: String, path: String, body: String?): ApiResponse }

/** Only public API endpoints are used. Never send credentials to redirected URLs. */
internal class DbdiagramApiClient(private val transport: ApiTransport) : CloudClient {
    override fun login() = Unit // Workspace token authentication is validated by the subsequent list request.
    override fun logout() = Unit // Credential removal belongs to PasswordSafe, not the remote API.
    override fun list(): List<CloudDiagram> {
        val json = json("GET", "/diagrams")
        if (!json.isJsonArray) throw CloudException("Unexpected diagram list response.")
        return json.asJsonArray.map { CloudData.parseDiagram(it, false) }
    }
    override fun get(id: String): CloudDiagram {
        val validId = CloudData.diagramId(id)
        return CloudData.parseDiagram(json("GET", "/diagrams/$validId")).also {
            if (it.id != validId) throw CloudException("The server returned a different diagram ID. Nothing was synchronized.")
        }
    }
    override fun push(id: String, name: String, content: String) {
        CloudData.checkContent(content)
        val body = JsonObject().apply { addProperty("name", name); addProperty("content", content) }
        // Omitting visualization properties retains the website's layout and settings.
        json("PUT", "/diagrams/${CloudData.diagramId(id)}", body.toString())
    }
    private fun json(method: String, path: String, body: String? = null): com.google.gson.JsonElement {
        val response = transport.request(method, path, body)
        if (response.status !in 200..299) throw CloudException(when (response.status) {
            401 -> "dbdiagram rejected the token. Reconnect with a valid workspace API token."
            403 -> "Access denied. Check workspace permissions and the paid-plan API requirement."
            404 -> "Diagram not found or not available to this workspace token."
            429 -> "dbdiagram rate limit reached. Wait and retry; no automatic push retry was made."
            400 -> "dbdiagram rejected the request. Check the DBML syntax and diagram ID."
            else -> "dbdiagram request failed (HTTP ${response.status}). No automatic push retry was made."
        })
        return runCatching { JsonParser.parseString(response.body) }.getOrElse { throw CloudException("Invalid JSON response from dbdiagram.") }
    }

    companion object {
        fun connect(token: String, checkCanceled: () -> Unit): DbdiagramApiClient {
            if (token.isBlank() || token.any { it.isISOControl() }) throw CloudException("Enter a valid workspace API token.")
            val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER)
            ProxySelector.getDefault()?.let(builder::proxy)
            val http = builder.build()
            return DbdiagramApiClient(ApiTransport { method, path, body ->
                checkCanceled()
                val request = HttpRequest.newBuilder(URI("https://api.dbdiagram.io/v1$path"))
                    .timeout(Duration.ofSeconds(45)).header("dbdiagram-access-token", token).header("Accept", "application/json")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it, Charsets.UTF_8) } ?: HttpRequest.BodyPublishers.noBody()).build()
                val pending = http.sendAsync(request, HttpResponse.BodyHandler { LimitedBodySubscriber() })
                try {
                    while (true) {
                        checkCanceled()
                        try {
                            val response = pending.get(100, TimeUnit.MILLISECONDS)
                            if (response.body().size > MAX_CLOUD_BYTES) throw CloudException("Remote response exceeds the 8 MiB safety limit.")
                            return@ApiTransport ApiResponse(response.statusCode(), response.body().toString(Charsets.UTF_8))
                        } catch (_: TimeoutException) { /* Check cancellation while waiting. */ }
                        catch (e: ExecutionException) {
                            val cause = e.cause
                            if (cause is CloudException) throw cause
                            throw CloudException("dbdiagram network request failed. Check connectivity; if pushing, inspect the remote before retrying.")
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                } finally { pending.cancel(true) }
            })
        }
    }
}
