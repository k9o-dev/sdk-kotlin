package dev.k9o.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val INF_OK =
    """{"data":{"modelId":"llama-3.1-8b","content":"Hello there","finishReason":"stop","usage":{"promptTokens":3,"completionTokens":2,"totalTokens":5},"usageType":"chat"},"meta":{}}"""

class AiInferenceTest {
    private fun core(t: StubTransport, slug: String? = "member-portal") =
        K9OCore("https://acme.api.k9o.io/", slug, accessToken = "tok_member", transport = t)

    @Test
    fun `posts flat body to generate path and parses response`() = runBlocking {
        val t = StubTransport(body = INF_OK)
        val r = core(t).ai.generate("hi", model = "llama-3.1-8b", maxTokens = 10, temperature = 0.2)
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals("https://acme.api.k9o.io/@member-portal/ai/generate", req.url)
        assertEquals("Bearer tok_member", req.headers["authorization"])
        val body = JsonParser.parseString(req.body).asJsonObject
        assertEquals("hi", body.get("prompt").asString)
        assertEquals(10, body.get("maxTokens").asInt)
        assertEquals(0.2, body.get("temperature").asDouble)
        assertEquals("llama-3.1-8b", body.get("model").asString)
        assertEquals(4, body.size())
        assertEquals("llama-3.1-8b", r.data.modelId)
        assertEquals("Hello there", r.data.content)
        assertEquals("stop", r.data.finishReason)
        assertEquals(InferenceUsage(3, 2, 5), r.data.usage)
    }

    @Test
    fun `optional params omitted`() = runBlocking {
        val t = StubTransport(body = INF_OK)
        core(t).ai.generate("p")
        val body = JsonParser.parseString(t.requests.single().body).asJsonObject
        assertEquals(1, body.size())
    }

    @Test
    fun `missing slug refused before any request`() = runBlocking {
        val t = StubTransport(body = INF_OK)
        val e = assertFailsWith<KError> { core(t, slug = null).ai.generate("p") }
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", e.code)
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun `501 not implemented surfaces as KError without the prompt`() = runBlocking {
        val secret = "the-secret-prompt"
        val t = StubTransport(
            501,
            """{"code":"K9O_NOT_IMPLEMENTED","message":"external provider not built","context":{},"recommendation":null}""",
        )
        val e = assertFailsWith<KError> { core(t).ai.generate(secret, model = "gpt-4o") }
        assertEquals("K9O_NOT_IMPLEMENTED", e.code)
        assertEquals(501, e.status)
        assertFalse(e.toString().contains(secret))
    }

    @Test
    fun `400 no default model surfaces as KError and sends no model`() = runBlocking {
        val t = StubTransport(
            400,
            """{"code":"K9O_INVALID_FIELD_VALUE","message":"Name a model: this org has no default LLM","context":{"field":"model"},"recommendation":null}""",
        )
        val e = assertFailsWith<KError> { core(t).ai.generate("hi") }
        assertEquals("K9O_INVALID_FIELD_VALUE", e.code)
        assertEquals(400, e.status)
        assertFalse(JsonParser.parseString(t.requests.single().body).asJsonObject.has("model"))
    }
}
