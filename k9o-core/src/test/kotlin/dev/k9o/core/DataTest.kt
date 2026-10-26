package dev.k9o.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val LIST = """{"data":[{"id":"rec_1","title":"a"},{"id":"rec_2","title":"b"}],"meta":{"cursor":{"next":"c2"}}}"""
private const val ONE = """{"data":{"id":"rec_1","title":"a"},"meta":{}}"""

class DataTest {
    private fun core(t: StubTransport, slug: String? = "member-portal") =
        K9OCore("https://acme.api.k9o.io", slug, accessToken = "tok", transport = t)

    private val base = "https://acme.api.k9o.io/@member-portal/data/posts"

    @Test fun `select with no filters`() = runBlocking {
        val t = StubTransport(body = LIST)
        val r = core(t).from("posts").select().execute()
        val req = t.requests.single()
        assertEquals("GET", req.method)
        assertEquals(base, req.url)
        assertEquals("Bearer tok", req.headers["authorization"])
        assertEquals(null, req.body)
        assertEquals(2, r.data.size)
        assertEquals("rec_2", r.data[1].get("id").asString)
        assertEquals("c2", ((r.meta["cursor"]) as Map<*, *>)["next"])
    }

    @Test fun `every filter operator, limit, cursor and count`() = runBlocking {
        val t = StubTransport(body = LIST)
        core(t).from("posts").select()
            .eq("a", "x").neq("b", 2).gt("c", 3).gte("d", 4.5).lt("e", 5).lte("f", true)
            .`in`("g", listOf("p", "q")).notIn("h", listOf(1, 2)).like("i", "x%").ilike("j", "Y%").`is`("k", "null")
            .limit(10).cursor("abc def").count(CountMode.EXACT)
            .execute()
        assertEquals(
            "$base?a=eq.x&b=neq.2&c=gt.3&d=gte.4.5&e=lt.5&f=lte.true&g=in.%28p%2Cq%29&h=notIn.%281%2C2%29" +
                "&i=like.x%25&j=ilike.Y%25&k=is.null&limit=10&cursor=abc+def&count=exact",
            t.requests.single().url,
        )
    }

    @Test fun `whole doubles are written without a fraction`() = runBlocking {
        val t = StubTransport(body = LIST)
        core(t).from("posts").select().eq("n", 3.0).execute()
        assertEquals("$base?n=eq.3", t.requests.single().url)
    }

    @Test fun `insert posts the record and returns the stored one`() = runBlocking {
        val t = StubTransport(201, ONE)
        val r = core(t).from("posts").insert(mapOf("title" to "a", "draft" to null, "n" to 2))
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals(base, req.url)
        assertEquals(JsonParser.parseString("""{"title":"a","n":2}"""), JsonParser.parseString(req.body))
        assertEquals("rec_1", r.data.get("id").asString)
    }

    @Test fun `insert accepts a JsonObject and keeps explicit nulls`() = runBlocking {
        val t = StubTransport(201, ONE)
        val rec = JsonObject().also { it.add("gone", com.google.gson.JsonNull.INSTANCE) }
        core(t).from("posts").insert(rec)
        assertEquals(JsonParser.parseString("""{"gone":null}"""), JsonParser.parseString(t.requests.single().body))
    }

    @Test fun `update patches by id`() = runBlocking {
        val t = StubTransport(body = ONE)
        core(t).from("posts").update(mapOf("title" to "z")).eq("id", "rec 1/x").execute()
        val req = t.requests.single()
        assertEquals("PATCH", req.method)
        assertEquals("$base/rec%201%2Fx", req.url)
        assertEquals(JsonParser.parseString("""{"title":"z"}"""), JsonParser.parseString(req.body))
    }

    @Test fun `delete by id`() = runBlocking {
        val t = StubTransport(body = ONE)
        core(t).from("posts").delete().eq("id", "rec_1").execute()
        val req = t.requests.single()
        assertEquals("DELETE", req.method)
        assertEquals("$base/rec_1", req.url)
        assertEquals(null, req.body)
    }

    @Test fun `update and delete without an id filter are refused before any request`() = runBlocking {
        val t = StubTransport(body = ONE)
        val c = core(t)
        assertEquals("K9O_INVALID_FIELD_VALUE", assertFailsWith<KError> { c.from("posts").update(mapOf("a" to 1)).execute() }.code)
        assertEquals("K9O_INVALID_FIELD_VALUE", assertFailsWith<KError> { c.from("posts").delete().eq("title", "a").execute() }.code)
        assertTrue(t.requests.isEmpty())
    }

    @Test fun `missing slug, bad slug and bad collection are refused before any request`() = runBlocking {
        val t = StubTransport(body = LIST)
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertFailsWith<KError> { core(t, null).from("posts").select().execute() }.code)
        assertEquals("K9O_INVALID_FIELD_VALUE", assertFailsWith<KError> { core(t, "Bad Slug").from("posts").select().execute() }.code)
        assertEquals("K9O_INVALID_FIELD_VALUE", assertFailsWith<KError> { core(t).from("../x").select().execute() }.code)
        assertEquals("K9O_INVALID_FIELD_VALUE", assertFailsWith<KError> { core(t).from("posts").select().eq("a", listOf(1)).execute() }.code)
        assertTrue(t.requests.isEmpty())
    }

    @Test fun `gateway errors surface as KError`() = runBlocking {
        val t = StubTransport(404, """{"code":"K9O_NOT_FOUND","message":"no such record","context":{},"recommendation":null}""")
        val e = assertFailsWith<KError> { core(t).from("posts").delete().eq("id", "x").execute() }
        assertEquals("K9O_NOT_FOUND", e.code)
        assertEquals(404, e.status)
    }

    // ---- textSearch / vectorSearch ----

    @Test fun `textSearch wire shape, defaults omitted`() = runBlocking {
        val t = StubTransport(body = LIST)
        core(t).from("articles").select().textSearch("body", "climate resilience").limit(5).execute()
        assertEquals(
            "https://acme.api.k9o.io/@member-portal/data/articles?limit=5&textSearch=body%3Aclimate+resilience",
            t.requests.single().url,
        )
    }

    @Test fun `textSearch language and type`() = runBlocking {
        val t = StubTransport(body = LIST)
        core(t).from("articles").select().textSearch("body", "a \"b c\" -d", language = "french", type = TextSearchType.WEBSEARCH).execute()
        assertEquals(
            "https://acme.api.k9o.io/@member-portal/data/articles?textSearch=body%3Aa+%22b+c%22+-d&textSearchLanguage=french&textSearchType=websearch",
            t.requests.single().url,
        )
    }

    @Test fun `vectorSearch wire shape`() = runBlocking {
        val t = StubTransport(body = LIST)
        core(t).from("docs").select().vectorSearch("embedding", listOf(0.1, 1.0, -2), metric = VectorMetric.DOT, limit = 3).execute()
        assertEquals(
            "https://acme.api.k9o.io/@member-portal/data/docs?vectorSearch=embedding%3A%5B0.1%2C1%2C-2%5D&vectorMetric=dot&vectorLimit=3",
            t.requests.single().url,
        )
    }

    @Test fun `vectorSearch defaults omit metric and limit`() = runBlocking {
        val t = StubTransport(body = LIST)
        core(t).from("docs").select().vectorSearch("embedding", listOf(1, 2)).execute()
        assertEquals("https://acme.api.k9o.io/@member-portal/data/docs?vectorSearch=embedding%3A%5B1%2C2%5D", t.requests.single().url)
    }

    @Test fun `search validation errors throw K9O_INVALID_FIELD_VALUE and send nothing`() {
        val t = StubTransport(body = LIST)
        val sel = { core(t).from("docs").select() }
        fun bad(f: () -> Unit) = assertEquals("K9O_INVALID_FIELD_VALUE", assertFailsWith<KError> { f() }.code)
        bad { sel().textSearch("1bad", "x") }
        bad { sel().textSearch("a b", "x") }
        bad { sel().textSearch("a".repeat(64), "x") }
        bad { sel().textSearch("body", "") }
        bad { sel().textSearch("body", "x", language = "en-US") }
        bad { sel().vectorSearch("bad col", listOf(1)) }
        bad { sel().vectorSearch("e", emptyList()) }
        bad { sel().vectorSearch("e", listOf(Double.NaN)) }
        bad { sel().vectorSearch("e", listOf(1), limit = 0) }
        bad { sel().vectorSearch("e", listOf(1), limit = 1001) }
        assertTrue(t.requests.isEmpty())
    }

    @Test fun `root search is a text search with paging`() = runBlocking {
        val t = StubTransport(body = LIST)
        val r = core(t).search("articles", column = "body", q = "water", language = "spanish", type = TextSearchType.PHRASE, limit = 20, cursor = "c1")
        assertEquals(
            "https://acme.api.k9o.io/@member-portal/data/articles?limit=20&cursor=c1&textSearch=body%3Awater&textSearchLanguage=spanish&textSearchType=phrase",
            t.requests.single().url,
        )
        assertEquals(2, r.data.size)
    }

    @Test fun `root search with a bad column throws before any request`() = runBlocking {
        val t = StubTransport(body = LIST)
        assertEquals("K9O_INVALID_FIELD_VALUE", assertFailsWith<KError> { core(t).search("articles", column = "bad col", q = "x") }.code)
        assertTrue(t.requests.isEmpty())
    }
}
