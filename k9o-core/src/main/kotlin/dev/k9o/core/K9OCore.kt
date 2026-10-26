package dev.k9o.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Kilimanjaro project-plane core client.
 *
 * @param baseUrl e.g. `https://acme.api.k9o.io` (required)
 * @param projectSlug the project the client is bound to; operations needing one refuse client-side without it
 * @param accessToken static Bearer token; wins over [tokenProvider]
 * @param tokenProvider called per request when no static [accessToken] is set
 * @param transport injectable HTTP transport (defaults to OkHttp)
 */
class K9OCore(
    baseUrl: String,
    projectSlug: String? = null,
    accessToken: String? = null,
    tokenProvider: (suspend () -> String?)? = null,
    transport: HttpTransport = OkHttpTransport(),
) {
    private val http = HttpCore(baseUrl.trimEnd('/'), projectSlug, accessToken, tokenProvider, transport)

    val ai: Ai = Ai(http)

    /** Buckets and objects. */
    val storage: Storage = Storage(http)

    /** User data streams (publish, replay, subscribe). */
    val streams: Streams = Streams(http)

    private val querier = Querier(http)

    /** Start a data call on one collection: `from("posts").select().eq("status", "open").execute()`. */
    fun from(collection: String): FromBuilder = FromBuilder(http, collection)

    /**
     * `POST /@<projectSlug>/query` with no parameters. Read-only: one SELECT statement per call; writes are refused
     * with `K9O_RAW_SQL_READ_ONLY` (mutate through [from]). Row-level security applies. At most 100000 characters of
     * SQL and 1000 parameters; the caller needs the project-editor role. `data` is the list of rows.
     */
    suspend fun query(sql: String): Envelope<List<JsonObject>> = querier.query(sql, null, null)

    /** Positional parameters, bound to `$1, $2, ...`; the SQL is sent as written. Values: String, Number, Boolean or null. */
    suspend fun query(sql: String, params: List<Any?>): Envelope<List<JsonObject>> = querier.query(sql, params, null)

    /**
     * Named parameters: `:name` placeholders in the SQL are rewritten to `$n` here and the values become the
     * positional list. A placeholder with no key throws `K9O_INVALID_FIELD_VALUE` before any request.
     */
    suspend fun query(sql: String, params: Map<String, Any?>): Envelope<List<JsonObject>> = querier.query(sql, null, params)

    /**
     * Full-text search over one text column of [collection], best matches first. Sugar over
     * `from(collection).select().textSearch(column, q, language, type)`; [limit] and [cursor] page the result.
     */
    suspend fun search(
        collection: String,
        column: String,
        q: String,
        language: String? = null,
        type: TextSearchType? = null,
        limit: Int? = null,
        cursor: String? = null,
    ): Envelope<List<JsonObject>> = querier.search(collection, column, q, language, type, limit, cursor)
}

/** Result of [Ai.embed]: one vector per input string, in input order. */
data class EmbedResult(val embeddings: List<List<Double>>, val model: String, val dimensions: Int)

/** Token counts the model server reported for an inference call. */
data class InferenceUsage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int)

/** Result of [Ai.generate]; [content] is the generated text. */
data class InferenceResult(
    val modelId: String,
    val content: String,
    val finishReason: String?,
    val usage: InferenceUsage,
    val usageType: String?,
)

class Ai internal constructor(private val http: HttpCore) {
    /**
     * `POST /@<projectSlug>/ai/embeddings` — embed one string or a list of strings.
     *
     * The gateway refuses an end user's token; this is callable with a project member's or an
     * extension's token only. The input is sent exactly as given (the server owns bounds) and is
     * never included in an error. [model] is omitted from the request when null.
     *
     * Throws [KError] `K9O_MISSING_PROJECT_CONTEXT` (before any request) if no projectSlug is bound.
     */
    suspend fun embed(input: String, model: String? = null): Envelope<EmbedResult> =
        send(model) { it.addProperty("input", input) }

    suspend fun embed(input: List<String>, model: String? = null): Envelope<EmbedResult> =
        send(model) { o -> o.add("input", JsonArray().also { a -> input.forEach(a::add) }) }

    /**
     * `POST /@<projectSlug>/ai/generate` — run one inference call.
     *
     * [model] is optional: when null the org's default LLM is used, and the gateway answers 400
     * `K9O_INVALID_FIELD_VALUE` if the org has none. [maxTokens] (1 to 32000) and
     * [temperature] (0 to 2) are sent flat in the body; every null field is omitted. Callable with a
     * project author's or admin's token or an extension's token. Models served by an external provider answer 501
     * `K9O_NOT_IMPLEMENTED` until that provider is built. The prompt is never included in an error.
     *
     * Throws [KError] `K9O_MISSING_PROJECT_CONTEXT` (before any request) if no projectSlug is bound.
     */
    suspend fun generate(
        prompt: String,
        model: String? = null,
        maxTokens: Int? = null,
        temperature: Double? = null,
    ): Envelope<InferenceResult> {
        val slug = requireSlug()
        val body = JsonObject()
        body.addProperty("prompt", prompt)
        if (model != null) body.addProperty("model", model)
        if (maxTokens != null) body.addProperty("maxTokens", maxTokens)
        if (temperature != null) body.addProperty("temperature", temperature)
        val env = http.post("/@$slug/ai/generate", body)
        val d = env.data
        val u = d.getAsJsonObject("usage")
        fun str(k: String) = d.get(k)?.takeIf { !it.isJsonNull }?.asString
        return Envelope(
            InferenceResult(
                d.get("modelId").asString,
                d.get("content").asString,
                str("finishReason"),
                InferenceUsage(u.get("promptTokens").asInt, u.get("completionTokens").asInt, u.get("totalTokens").asInt),
                str("usageType"),
            ),
            env.meta,
        )
    }

    private fun requireSlug(): String {
        val slug = http.projectSlug
        if (slug.isNullOrEmpty()) {
            throw KError(
                code = "K9O_MISSING_PROJECT_CONTEXT",
                message = "ai operations require projectSlug bound on the client",
                recommendation = "Construct K9OCore with projectSlug.",
            )
        }
        return slug
    }

    private suspend fun send(model: String?, setInput: (JsonObject) -> Unit): Envelope<EmbedResult> {
        val slug = http.projectSlug
        if (slug.isNullOrEmpty()) {
            throw KError(
                code = "K9O_MISSING_PROJECT_CONTEXT",
                message = "ai operations require projectSlug bound on the client",
                recommendation = "Construct K9OCore with projectSlug.",
            )
        }
        val body = JsonObject()
        if (model != null) body.addProperty("model", model)
        setInput(body)
        val env = http.post("/@$slug/ai/embeddings", body)
        val d = env.data
        val vectors = d.getAsJsonArray("embeddings").map { v -> v.asJsonArray.map { it.asDouble } }
        return Envelope(
            EmbedResult(vectors, d.get("model").asString, d.get("dimensions").asInt),
            env.meta,
        )
    }
}
