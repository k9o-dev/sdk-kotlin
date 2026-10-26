package dev.k9o.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** One part of a multipart/form-data body: a text field, or a file when [filename] is set. */
class FormPart(
    val name: String,
    val bytes: ByteArray,
    val filename: String? = null,
    val contentType: String? = null,
)

/**
 * A request handed to the transport. When [parts] is set the body is multipart/form-data built from the parts
 * ([body] is null); otherwise [body] is JSON text, or null for no body.
 */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val parts: List<FormPart>? = null,
)

/** A raw response from the transport. */
class HttpResponse(val status: Int, val body: String)

/** A response whose body is read incrementally (server-sent events). Close it to release the connection. */
class HttpStreamResponse(val status: Int, val reader: java.io.Reader) : java.io.Closeable {
    override fun close() = reader.close()
}

/** Injectable transport. Throw any exception to signal a network failure. */
fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse

    /**
     * Open a response to read incrementally. The default delivers the whole [send] body as one readable text, which
     * is enough for fixed responses; [OkHttpTransport] overrides it to read from the live connection.
     */
    suspend fun stream(request: HttpRequest): HttpStreamResponse {
        val r = send(request)
        return HttpStreamResponse(r.status, java.io.StringReader(r.body))
    }
}

/** Default OkHttp-backed transport. */
class OkHttpTransport(private val client: OkHttpClient = OkHttpClient()) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        client.newCall(build(request)).execute().use { r ->
            return HttpResponse(r.code, r.body?.string() ?: "")
        }
    }

    override suspend fun stream(request: HttpRequest): HttpStreamResponse = withContext(Dispatchers.IO) {
        // A live feed idles between events, so the read timeout is lifted for this call.
        val live = client.newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val r = live.newCall(build(request)).execute()
        val body = r.body
        HttpStreamResponse(
            r.code,
            object : java.io.InputStreamReader(body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0)), Charsets.UTF_8) {
                override fun close() {
                    super.close()
                    r.close()
                }
            },
        )
    }

    private fun build(request: HttpRequest): Request {
        val b = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> b.header(k, v) }
        val body: RequestBody? = when {
            request.parts != null -> MultipartBody.Builder().setType(MultipartBody.FORM).also { m ->
                for (p in request.parts) {
                    val type = p.contentType?.toMediaTypeOrNull()
                    if (p.filename != null) m.addFormDataPart(p.name, p.filename, p.bytes.toRequestBody(type))
                    else m.addFormDataPart(p.name, null, p.bytes.toRequestBody(type))
                }
            }.build()
            request.body != null -> request.body.toRequestBody("application/json".toMediaType())
            request.method in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody()
            else -> null
        }
        return b.method(request.method, body).build()
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
    private val gson = GsonBuilder().serializeNulls().create()

    suspend fun post(path: String, body: JsonObject): Envelope<JsonObject> {
        val env = request("POST", path, body = body)
        val data = env.data.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw internal("Malformed envelope from ${baseUrl + path}: expected {data, meta}", 200)
        return Envelope(data, env.meta)
    }

    /**
     * One call. [query] values are percent-encoded here; a JSON [body] or multipart [parts] is sent as given.
     * `data` is whatever JSON the gateway returned (object, array or null); a 204 gives an empty envelope.
     */
    suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonElement? = null,
        parts: List<FormPart>? = null,
    ): Envelope<JsonElement> {
        val url = buildUrl(path, query)
        val hdrs = headers(parts == null && body != null, "application/json")
        val response = try {
            transport.send(HttpRequest(method, url, hdrs, body?.let { gson.toJson(it) }, parts))
        } catch (e: Exception) {
            throw network(url, e)
        }
        if (response.status == 204) return Envelope(JsonNull.INSTANCE, emptyMap())

        val obj = parseObject(response.body)
        if (response.status in 200..299) {
            if (obj == null || !obj.has("data")) {
                throw internal("Malformed envelope from $url: expected {data, meta}", response.status)
            }
            val meta = obj.get("meta")?.takeIf { it.isJsonObject }?.let { toMap(it.asJsonObject) } ?: emptyMap()
            return Envelope(obj.get("data"), meta)
        }
        throw failure(response.status, obj)
    }

    /** Open a server-sent-events response. Throws the gateway's [KError] on a non-2xx answer. */
    suspend fun openStream(path: String, query: Map<String, String> = emptyMap()): HttpStreamResponse {
        val url = buildUrl(path, query)
        val hdrs = headers(false, "text/event-stream")
        val response = try {
            transport.stream(HttpRequest("GET", url, hdrs, null))
        } catch (e: Exception) {
            throw network(url, e)
        }
        if (response.status in 200..299) return response
        val text = try {
            response.reader.use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        throw failure(response.status, parseObject(text))
    }

    private fun buildUrl(path: String, query: Map<String, String>): String {
        val qs = query.entries.joinToString("&") { (k, v) -> "${formEncode(k)}=${formEncode(v)}" }
        return baseUrl + path + if (qs.isEmpty()) "" else "?$qs"
    }

    private suspend fun headers(json: Boolean, accept: String): Map<String, String> {
        val headers = linkedMapOf("accept" to accept)
        if (json) headers["content-type"] = "application/json"
        if (!projectSlug.isNullOrEmpty()) headers["x-k9o-project-slug"] = projectSlug
        val token = accessToken ?: tokenProvider?.invoke()
        if (!token.isNullOrEmpty()) headers["authorization"] = "Bearer $token"
        return headers
    }

    private fun network(url: String, e: Exception): Exception {
        if (e is kotlin.coroutines.cancellation.CancellationException) return e
        return KError(
            code = "K9O_UPSTREAM_UNAVAILABLE",
            message = "Network error reaching $url: ${e.message ?: e.javaClass.simpleName}",
            context = mapOf("url" to url),
            recommendation = "Check connectivity to the gateway; retry with backoff if transient.",
            cause = e,
        )
    }

    private fun parseObject(text: String): JsonObject? = try {
        JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Exception) {
        null
    }

    private fun failure(status: Int, obj: JsonObject?): KError {
        val code = obj?.get("code")?.takeIf { it.isJsonPrimitive }?.asString
        val message = obj?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
        if (obj != null && code != null && message != null) {
            return KError(
                code = code,
                message = message,
                context = obj.get("context")?.takeIf { it.isJsonObject }?.let { toMap(it.asJsonObject) } ?: emptyMap(),
                recommendation = obj.get("recommendation")?.takeIf { it.isJsonPrimitive }?.asString,
                status = status,
                requestId = obj.get("requestId")?.takeIf { it.isJsonPrimitive }?.asString,
            )
        }
        return internal("Upstream returned $status with non-KError body", status)
    }

    private fun internal(message: String, status: Int) =
        KError(code = "K9O_INTERNAL_ERROR", message = message, status = status)

    @Suppress("UNCHECKED_CAST")
    private fun toMap(o: JsonObject): Map<String, Any?> =
        Gson().fromJson(o, Map::class.java) as Map<String, Any?>
}

/** application/x-www-form-urlencoded encoding of one query component (a space becomes `+`). */
internal fun formEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

/** Percent-encoding of one path segment, as JavaScript's `encodeURIComponent` does it (a space becomes `%20`). */
internal fun encodeSegment(s: String): String {
    val keep = "-_.!~*'()"
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xff).toChar()
        if ((c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in keep) && b >= 0) sb.append(c)
        else sb.append('%').append("%02X".format(b.toInt() and 0xff))
    }
    return sb.toString()
}
