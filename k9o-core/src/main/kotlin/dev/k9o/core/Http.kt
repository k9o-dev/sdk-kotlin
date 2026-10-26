package dev.k9o.core

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A request handed to the transport. */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

/** A raw response from the transport. */
class HttpResponse(val status: Int, val body: String)

/** Injectable transport. Throw any exception to signal a network failure. */
fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** Default OkHttp-backed transport. */
class OkHttpTransport(private val client: OkHttpClient = OkHttpClient()) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        val b = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> b.header(k, v) }
        b.method(request.method, request.body?.toRequestBody("application/json".toMediaType()))
        client.newCall(b.build()).execute().use { r ->
            return HttpResponse(r.code, r.body?.string() ?: "")
        }
    }
}

/** Successful `{ data, meta }` envelope; `meta` is empty when absent. */
data class Envelope<T>(val data: T, val meta: Map<String, Any?>)

internal class HttpCore(
    val baseUrl: String,
    val projectSlug: String?,
    private val accessToken: String?,
    private val tokenProvider: (suspend () -> String?)?,
    private val transport: HttpTransport,
) {
    private val gson = Gson()

    suspend fun post(path: String, body: JsonObject): Envelope<JsonObject> {
        val url = baseUrl + path
        val headers = linkedMapOf("accept" to "application/json", "content-type" to "application/json")
        if (!projectSlug.isNullOrEmpty()) headers["x-k9o-project-slug"] = projectSlug
        val token = accessToken ?: tokenProvider?.invoke()
        if (!token.isNullOrEmpty()) headers["authorization"] = "Bearer $token"

        val response = try {
            transport.send(HttpRequest("POST", url, headers, gson.toJson(body)))
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            throw KError(
                code = "K9O_UPSTREAM_UNAVAILABLE",
                message = "Network error reaching $url: ${e.message ?: e.javaClass.simpleName}",
                context = mapOf("url" to url),
                recommendation = "Check connectivity to the gateway; retry with backoff if transient.",
                cause = e,
            )
        }

        val parsed: JsonElement? = try {
            JsonParser.parseString(response.body)
        } catch (e: Exception) {
            null
        }
        val obj = parsed?.takeIf { it.isJsonObject }?.asJsonObject

        if (response.status in 200..299) {
            val data = obj?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw internal("Malformed envelope from $url: expected {data, meta}", response.status)
            val meta = obj.get("meta")?.takeIf { it.isJsonObject }?.let { toMap(it.asJsonObject) } ?: emptyMap()
            return Envelope(data, meta)
        }

        val code = obj?.get("code")?.takeIf { it.isJsonPrimitive }?.asString
        val message = obj?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
        if (obj != null && code != null && message != null) {
            throw KError(
                code = code,
                message = message,
                context = obj.get("context")?.takeIf { it.isJsonObject }?.let { toMap(it.asJsonObject) } ?: emptyMap(),
                recommendation = obj.get("recommendation")?.takeIf { it.isJsonPrimitive }?.asString,
                status = response.status,
                requestId = obj.get("requestId")?.takeIf { it.isJsonPrimitive }?.asString,
            )
        }
        throw internal("Upstream returned ${response.status} with non-KError body", response.status)
    }

    private fun internal(message: String, status: Int) =
        KError(code = "K9O_INTERNAL_ERROR", message = message, status = status)

    @Suppress("UNCHECKED_CAST")
    private fun toMap(o: JsonObject): Map<String, Any?> =
        gson.fromJson(o, Map::class.java) as Map<String, Any?>
}
