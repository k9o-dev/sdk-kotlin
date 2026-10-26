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
}

/** Result of [Ai.embed]: one vector per input string, in input order. */
data class EmbedResult(val embeddings: List<List<Double>>, val model: String, val dimensions: Int)

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
