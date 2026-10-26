package dev.k9o.core

import com.google.gson.JsonParser
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val REC =
    """{"id":"s1","slug":"orders","projectId":"p1","displayName":"Orders","subjectPrefix":"p1.orders","region":"de01","retentionSeconds":86400,"retentionClass":"standard","maxBytes":1073741824,"status":"active","createdAt":"2026-01-01T00:00:00Z","createdBy":null}"""

/** Serves one scripted SSE body per connection, then repeats the last. */
private class SseTransport(private val bodies: List<String>, private val status: Int = 200, private val errorBody: String = "") :
    HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun send(request: HttpRequest): HttpResponse = HttpResponse(status, errorBody)
    override suspend fun stream(request: HttpRequest): HttpStreamResponse {
        requests += request
        if (status !in 200..299) return HttpStreamResponse(status, StringReader(errorBody))
        return HttpStreamResponse(status, StringReader(bodies[minOf(requests.size - 1, bodies.size - 1)]))
    }
}

private fun frame(seq: String, data: String) =
    "id: $seq\ndata: {\"sequence\":\"$seq\",\"subject\":\"p1.orders.new\",\"data\":$data,\"publishedAt\":\"2026-01-01T00:00:00Z\"}\n\n"

class StreamsTest {
    private fun core(t: HttpTransport, slug: String? = "member-portal") =
        K9OCore("https://acme.api.k9o.io", slug, accessToken = "tok", transport = t)

    private val root = "https://acme.api.k9o.io/@member-portal/data-streams/user"

    @Test fun `create, list, get and delete use the user stream routes`() = runBlocking {
        val t = StubTransport(body = """{"data":$REC,"meta":{}}""")
        val c = core(t).streams
        val created = c.create("Orders", 1_073_741_824L, retentionSeconds = 7200)
        c.get("or ders")
        c.delete("orders")
        assertEquals("POST", t.requests[0].method)
        assertEquals(root, t.requests[0].url)
        assertEquals(
            JsonParser.parseString("""{"displayName":"Orders","maxBytes":1073741824,"retentionSeconds":7200}"""),
            JsonParser.parseString(t.requests[0].body),
        )
        assertEquals("$root/or%20ders", t.requests[1].url)
        assertEquals("DELETE", t.requests[2].method)
        assertEquals("orders", created.data.slug)
        assertEquals(1_073_741_824L, created.data.maxBytes)

        val t2 = StubTransport(body = """{"data":[$REC],"meta":{}}""")
        val l = core(t2).streams.list()
        assertEquals("GET", t2.requests.single().method)
        assertEquals(root, t2.requests.single().url)
        assertEquals("Orders", l.data.single().displayName)
    }

    @Test fun `create omits retentionSeconds when not given`() = runBlocking {
        val t = StubTransport(body = """{"data":$REC,"meta":{}}""")
        core(t).streams.create("Orders", 1L)
        assertTrue(!JsonParser.parseString(t.requests.single().body).asJsonObject.has("retentionSeconds"))
    }

    @Test fun `publish sends data and subject exactly, and keeps falsy data`() = runBlocking {
        val t = StubTransport(202, """{"data":{"streamSlug":"orders","subject":"p1.orders.new","bytes":7,"accepted":true},"meta":{}}""")
        val r = core(t).streams.publish("orders", mapOf("n" to 1), subject = "new")
        assertEquals("$root/orders/publish", t.requests[0].url)
        assertEquals(JsonParser.parseString("""{"data":{"n":1},"subject":"new"}"""), JsonParser.parseString(t.requests[0].body))
        assertEquals(PublishResult("orders", "p1.orders.new", 7, true), r.data)
        core(t).streams.publish("orders", com.google.gson.JsonNull.INSTANCE)
        assertEquals(JsonParser.parseString("""{"data":null}"""), JsonParser.parseString(t.requests[1].body))
        core(t).streams.publish("orders", "")
        assertEquals(JsonParser.parseString("""{"data":""}"""), JsonParser.parseString(t.requests[2].body))
    }

    @Test fun `503 unavailable maps to a typed KError`() = runBlocking {
        val t = StubTransport(503, """{"code":"K9O_STREAM_UNAVAILABLE","message":"no stream plane","context":{},"recommendation":null}""")
        val e = assertFailsWith<KError> { core(t).streams.publish("orders", "x") }
        assertEquals("K9O_STREAM_UNAVAILABLE", e.code)
        assertEquals(503, e.status)
    }

    @Test fun `replay sends only the given range fields and parses the page`() = runBlocking {
        val t = StubTransport(
            body = """{"data":{"streamSlug":"orders","events":[{"sequence":"5","subject":"p1.orders.new","data":{"a":1},"publishedAt":"2026-01-01T00:00:00Z"}],"nextCursor":"5","hasMore":true},"meta":{}}""",
        )
        val r = core(t).streams.replay("orders", fromSequence = "4", limit = 1)
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals("$root/orders/replay", req.url)
        assertEquals(JsonParser.parseString("""{"fromSequence":"4","limit":1}"""), JsonParser.parseString(req.body))
        assertEquals("5", r.data.events.single().sequence)
        assertEquals(1, r.data.events.single().data.asJsonObject.get("a").asInt)
        assertEquals("5", r.data.nextCursor)
        assertTrue(r.data.hasMore)
        core(t).streams.replay("orders")
        assertEquals("{}", t.requests[1].body)
    }

    @Test fun `subscribe reads events and reconnects from the last sequence`() = runBlocking {
        val t = SseTransport(
            listOf(
                ": hello\nretry: 1\n\n" + frame("1", "{\"a\":1}") + frame("2", "\"two\""),
                frame("3", "null"),
            ),
        )
        val events = withTimeout(10_000) { core(t).streams.subscribe("orders", fromSequence = "0").take(3).toList() }
        assertEquals(listOf("1", "2", "3"), events.map { it.sequence })
        assertEquals("p1.orders.new", events[0].subject)
        assertEquals(1, events[0].data.asJsonObject.get("a").asInt)
        assertEquals("two", events[1].data.asString)
        assertEquals("$root/orders/subscribe?fromSequence=0", t.requests[0].url)
        assertEquals("$root/orders/subscribe?fromSequence=2", t.requests[1].url)
        assertEquals("text/event-stream", t.requests[0].headers["accept"])
        assertEquals("Bearer tok", t.requests[0].headers["authorization"])
    }

    @Test fun `subscribe starts without fromSequence when none is given`() = runBlocking {
        val t = SseTransport(listOf(frame("1", "1")))
        withTimeout(10_000) { core(t).streams.subscribe("orders").take(1).toList() }
        assertEquals("$root/orders/subscribe", t.requests[0].url)
    }

    @Test fun `subscribe ignores malformed frames`() = runBlocking {
        val t = SseTransport(listOf("data: not json\n\ndata: {\"x\":1}\n\n" + frame("9", "true")))
        val e = withTimeout(10_000) { core(t).streams.subscribe("orders").take(1).toList() }
        assertEquals("9", e.single().sequence)
    }

    @Test fun `subscribe surfaces 503 on open as a KError`() = runBlocking {
        val t = SseTransport(
            emptyList(),
            503,
            """{"code":"K9O_STREAM_UNAVAILABLE","message":"no stream plane","context":{},"recommendation":null}""",
        )
        val e = assertFailsWith<KError> { core(t).streams.subscribe("orders").toList() }
        assertEquals("K9O_STREAM_UNAVAILABLE", e.code)
        assertEquals(503, e.status)
    }

    @Test fun `missing slug and empty stream slug are refused before any request`() = runBlocking {
        val t = StubTransport(body = "{}")
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertFailsWith<KError> { core(t, null).streams.list() }.code)
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertFailsWith<KError> { core(t, null).streams.subscribe("a") }.code)
        assertEquals("K9O_INVALID_FIELD_VALUE", assertFailsWith<KError> { core(t).streams.get("") }.code)
        assertTrue(t.requests.isEmpty())
    }
}
