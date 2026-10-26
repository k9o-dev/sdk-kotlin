package dev.k9o.kibo.vault

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val JSON = "application/json; charset=utf-8".toMediaType()

/**
 * Internal OkHttp transport for [VaultClient] (OkHttp + Gson + `Dispatchers.IO`; `{data, meta}`
 * envelope unwrap on 2xx; wire-`KError` rehydration on non-2xx), for GET/POST/PUT/DELETE. A body-less
 * `204 No Content` is handled, and `delete` accepts any 2xx without reading the body.
 *
 * URL grammar: `/@<projectSlug>/vault/secrets[/<name>]`. `<name>` is percent-encoded as a SINGLE path
 * segment (an internal `/` becomes `%2F`) since it addresses one `:name` route param, not a nested path.
 */
internal class VaultTransport(
    orgBaseUrl: String,
    private val projectSlug: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val baseUrl = orgBaseUrl.trimEnd('/')

    internal val gson: Gson = GsonBuilder()
        .registerTypeAdapter(SecretVisibility::class.java, SecretVisibilityAdapter())
        // Base64 `value` payloads routinely end in padding characters (and may contain '+'/'/'); Gson's
        // default HTML-safe escaping would otherwise rewrite those as unicode escapes on the wire.
        // Still valid JSON either way, but disabling it keeps the wire body a plain, unsurprising JSON
        // document.
        .disableHtmlEscaping()
        .create()

    /** `GET /@<projectSlug>/vault/secrets[?prefix=&cursor=&limit=]`. */
    fun secretsUrl(prefix: String?, cursor: String?, limit: Int?): String =
        buildUrl("vault/secrets", listOf("prefix" to prefix, "cursor" to cursor, "limit" to limit?.toString()))

    /** `.../vault/secrets/<name>[?op=<op>][&region=<region>]`; `op` is only set for
     *  revealChallenge/rotate/move, `region` only when the caller gave one. */
    fun secretUrl(name: String, region: String?, op: String? = null): String =
        buildUrl(
            "vault/secrets/${encodeURIComponent(name)}",
            listOf("op" to op, "region" to region),
        )

    private fun buildUrl(path: String, query: List<Pair<String, String?>>): String {
        val base = "$baseUrl/@$projectSlug/$path"
        val present = query.filter { it.second != null }
        if (present.isEmpty()) return base
        val qs = present.joinToString("&") { (k, v) -> "$k=${encodeURIComponent(v!!)}" }
        return "$base?$qs"
    }

    suspend inline fun <reified R : Any> get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): R = withContext(Dispatchers.IO) {
        execute(baseRequest(url, headers).get().build())
    }

    /** POST with no body — `revealChallenge` (without a reason) / `rotate`. */
    suspend inline fun <reified R : Any> post(url: String): R = withContext(Dispatchers.IO) {
        execute(baseRequest(url, emptyMap()).post(ByteArray(0).toRequestBody(null)).build())
    }

    /** POST with a JSON body — `create` / `move` / `revealChallenge` (with a reason). */
    suspend inline fun <reified B, reified R : Any> post(url: String, body: B): R = withContext(Dispatchers.IO) {
        execute(baseRequest(url, emptyMap()).post(gson.toJson(body).toRequestBody(JSON)).build())
    }

    /** PUT with a JSON body — `update`. */
    suspend inline fun <reified B, reified R : Any> put(url: String, body: B): R = withContext(Dispatchers.IO) {
        execute(baseRequest(url, emptyMap()).put(gson.toJson(body).toRequestBody(JSON)).build())
    }

    /** DELETE, no body — `delete`. Expect `R = Unit` (see [execute]'s 204 handling). */
    suspend inline fun <reified R : Any> delete(url: String): R = withContext(Dispatchers.IO) {
        execute(baseRequest(url, emptyMap()).delete().build())
    }

    fun baseRequest(url: String, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "k9o-kibo-vault-kotlin")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder
    }

    @Suppress("UNCHECKED_CAST")
    suspend inline fun <reified R : Any> execute(request: Request): R {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw VaultError.Network(e)
        }

        // `delete` expects Unit: any 2xx is success and the body (if any) is not read.
        if (R::class == Unit::class && response.isSuccessful) {
            response.close()
            return Unit as R
        }

        // A body-less 204 for a call that expects a body is a contract mismatch.
        if (response.code == 204) {
            if (R::class == Unit::class) return Unit as R
            throw VaultError.Decoding(
                IllegalStateException("Expected a response body for ${R::class.simpleName} but got 204 No Content"),
            )
        }

        val bodyString = response.body?.string()
        if (!response.isSuccessful) {
            throw vaultErrorFromResponse(response.code, bodyString, gson)
        }
        return try {
            val envelopeType = object : TypeToken<Envelope<R>>() {}.type
            val envelope = gson.fromJson<Envelope<R>>(bodyString, envelopeType)
            envelope.data
        } catch (e: Exception) {
            throw VaultError.Decoding(e)
        }
    }
}

internal data class Envelope<T>(val data: T, val meta: Map<String, Any?>?)

/**
 * Percent-encoding of a single path segment or query value, matching JavaScript's `encodeURIComponent`
 * for the character set actually used in this domain (secret names, regions, prefixes, cursors) —
 * critically, `/` is percent-encoded to `%2F` rather than left as a path delimiter, since a hierarchical
 * secret `name` (e.g. `db/pw`) addresses ONE `:name` route segment, not a nested path.
 */
internal fun encodeURIComponent(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

// MARK: — Wire error envelope

// The gateway emits a KError at the ROOT of the error body: { code, message, context?, recommendation? }
// — NOT wrapped in { error: … }.
private data class WireKError(
    val code: String?,
    val message: String?,
    val context: Map<String, Any?>?,
    val recommendation: String?,
)

internal fun vaultErrorFromResponse(statusCode: Int, body: String?, gson: Gson): VaultError {
    val wire = body?.let {
        try {
            gson.fromJson(it, WireKError::class.java)
        } catch (_: JsonSyntaxException) {
            null
        }
    }
    return if (wire?.code != null && wire.message != null) {
        VaultError.Api(
            code = wire.code,
            statusCode = statusCode,
            detail = wire.message,
            context = wire.context ?: emptyMap(),
            recommendation = wire.recommendation,
        )
    } else {
        VaultError.Api(
            code = "K9O_INTERNAL_ERROR",
            statusCode = statusCode,
            detail = "Upstream returned $statusCode with a non-KError body: ${body ?: "<no body>"}",
        )
    }
}
