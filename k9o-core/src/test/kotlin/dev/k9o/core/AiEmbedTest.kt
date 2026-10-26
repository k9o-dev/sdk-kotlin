package dev.k9o.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

private const val OK =
    """{"data":{"embeddings":[[0.1,0.2]],"model":"nomic-embed-text-v1.5","dimensions":2},"meta":{}}"""

class StubTransport(private val status: Int = 200, private val body: String = OK, private val fail: Exception? = null) :
    HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        fail?.let { throw it }
        return HttpResponse(status, body)
    }
}

class AiEmbedTest {
    private fun core(t: StubTransport, slug: String? = "member-portal") =
        K9OCore("https://acme.api.k9o.io/", slug, accessToken = "tok_member", transport = t)

    @Test
    fun `posts to project embeddings url with bearer and returns vectors`() = runBlocking {
        val t = StubTransport()
        val r = core(t).ai.embed("hello")
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals("https://acme.api.k9o.io/@member-portal/ai/embeddings", req.url)
        assertEquals("Bearer tok_member", req.headers["authorization"])
        assertEquals(listOf(listOf(0.1, 0.2)), r.data.embeddings)
        assertEquals(2, r.data.dimensions)
        assertTrue(r.meta.isEmpty())
    }

    @Test
    fun `string input stays a string`() = runBlocking {
        val t = StubTransport()
        core(t).ai.embed("hello")
        val body = JsonParser.parseString(t.requests.single().body).asJsonObject
        assertTrue(body.get("input").isJsonPrimitive)
        assertEquals("hello", body.get("input").asString)
    }

    @Test
    fun `list input with model sent as given`() = runBlocking {
        val t = StubTransport()
        core(t).ai.embed(listOf("search_document: a", "search_document: b"), model = "nomic-embed-text-v1.5")
        val body = JsonParser.parseString(t.requests.single().body).asJsonObject
        assertEquals("nomic-embed-text-v1.5", body.get("model").asString)
        assertEquals(listOf("search_document: a", "search_document: b"), body.getAsJsonArray("input").map { it.asString })
    }

    @Test
    fun `model omitted when not given`() = runBlocking {
        val t = StubTransport()
        core(t).ai.embed(listOf("x"))
        val body = JsonParser.parseString(t.requests.single().body).asJsonObject
        assertFalse(body.has("model"))
    }

    @Test
    fun `missing slug refused before any request`() = runBlocking {
        val t = StubTransport()
        val e = assertFailsWith<KError> { core(t, slug = null).ai.embed("x") }
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", e.code)
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun `4xx flat body surfaces as KError without the input text`() = runBlocking {
        val secret = "the-secret-document-body"
        val t = StubTransport(
            400,
            """{"code":"K9O_MODEL_NOT_DECLARED","message":"model is not a declared embedding model","context":{"declared":["nomic-embed-text-v1.5"]},"recommendation":null}""",
        )
        val e = assertFailsWith<KError> { core(t).ai.embed(secret, model = "other") }
        assertEquals("K9O_MODEL_NOT_DECLARED", e.code)
        assertEquals(400, e.status)
        assertEquals(null, e.recommendation)
        assertFalse(e.toString().contains(secret))
        assertFalse(e.message!!.contains(secret))
        assertFalse(e.context.toString().contains(secret))
    }

    @Test
    fun `network failure maps to upstream unavailable`() = runBlocking {
        val t = StubTransport(fail = IOException("connection refused"))
        val e = assertFailsWith<KError> { core(t).ai.embed("x") }
        assertEquals("K9O_UPSTREAM_UNAVAILABLE", e.code)
    }

    @Test
    fun `non-json error body maps to internal error`() = runBlocking {
        val t = StubTransport(502, "<html>bad gateway</html>")
        val e = assertFailsWith<KError> { core(t).ai.embed("x") }
        assertEquals("K9O_INTERNAL_ERROR", e.code)
    }
}
