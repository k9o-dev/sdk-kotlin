package dev.k9o.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ROWS = """{"data":[{"id":"u1","n":1}],"meta":{"rawQuery":{"intent":"select","rowCount":1}}}"""

class QueryTest {
    private fun core(t: StubTransport, slug: String? = "member-portal") =
        K9OCore("https://acme.api.k9o.io", slug, accessToken = "tok", transport = t)

    private fun ok(sql: String, named: Map<String, Any?>, expectedSql: String, expectedParams: String) {
        val r = translateNamedParams(sql, named)
        assertEquals(expectedSql, r.sql)
        val arr = JsonArray().also { a -> r.params.forEach(a::add) }
        assertEquals(JsonParser.parseString(expectedParams), arr)
    }

    // ---- the shared translation vectors ----

    @Test fun `vector 1 role`() =
        ok("SELECT * FROM users WHERE data->>'role' = :role", mapOf("role" to "admin"), "SELECT * FROM users WHERE data->>'role' = \$1", """["admin"]""")

    @Test fun `vector 2 reuse`() = ok("SELECT :a, :b, :a", mapOf("a" to 1, "b" to 2), "SELECT \$1, \$2, \$1", "[1,2]")

    @Test fun `vector 3 quotes and casts`() = ok(
        "SELECT ':not_a_param', \":also_not\", x::int FROM t WHERE y = :y",
        mapOf("y" to true),
        "SELECT ':not_a_param', \":also_not\", x::int FROM t WHERE y = \$1",
        "[true]",
    )

    @Test fun `vector 4 escaped quote and line comment`() = ok(
        "SELECT 'it''s :no' , :v -- :comment\nFROM t",
        mapOf("v" to null),
        "SELECT 'it''s :no' , \$1 -- :comment\nFROM t",
        "[null]",
    )

    @Test fun `vector 5 nested block comment`() =
        ok("SELECT /* :c /* :d */ :e */ :f", mapOf("f" to 1), "SELECT /* :c /* :d */ :e */ \$1", "[1]")

    @Test fun `vector 6 dollar quoting`() = ok(
        "SELECT \$\$ :x \$\$, \$q\$ :y \$q\$, :z",
        mapOf("z" to "s"),
        "SELECT \$\$ :x \$\$, \$q\$ :y \$q\$, \$1",
        """["s"]""",
    )

    @Test fun `vector 7 array slice and a colon b`() =
        ok("SELECT arr[1:2], a:b, :n", mapOf("n" to 5), "SELECT arr[1:2], a:b, \$1", "[5]")

    @Test fun `vector 8 missing value`() {
        val e = assertFailsWith<KError> { translateNamedParams("SELECT :missing", emptyMap()) }
        assertEquals("K9O_INVALID_FIELD_VALUE", e.code)
        assertTrue(e.message!!.contains(":missing"))
    }

    @Test fun `vector 9 non-bindable value`() {
        val e = assertFailsWith<KError> { translateNamedParams("SELECT :a", mapOf("a" to emptyMap<String, Any?>())) }
        assertEquals("K9O_INVALID_FIELD_VALUE", e.code)
        assertTrue(e.message!!.contains("serialise"))
    }

    @Test fun `escape string with backslash quote is skipped`() =
        ok("SELECT E'it\\'s :no', :v", mapOf("v" to 1), "SELECT E'it\\'s :no', \$1", "[1]")

    @Test fun `extra keys are ignored`() = ok("SELECT :a", mapOf("a" to 1, "b" to 2), "SELECT \$1", "[1]")

    // ---- wire shape ----

    @Test fun `named params post rewritten sql and positional values`() = runBlocking {
        val t = StubTransport(body = ROWS)
        val r = core(t).query("SELECT * FROM users WHERE id = :id", mapOf("id" to "u1"))
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals("https://acme.api.k9o.io/@member-portal/query", req.url)
        assertEquals("Bearer tok", req.headers["authorization"])
        assertEquals(JsonParser.parseString("""{"sql":"SELECT * FROM users WHERE id = ${'$'}1","params":["u1"]}"""), JsonParser.parseString(req.body))
        assertEquals("u1", r.data.single().get("id").asString)
        assertEquals(1, (r.meta["rawQuery"] as Map<*, *>)["rowCount"].let { (it as Number).toInt() })
    }

    @Test fun `vector 10 list params sent unchanged`() = runBlocking {
        val t = StubTransport(body = ROWS)
        core(t).query("SELECT \$1", listOf("x"))
        assertEquals(JsonParser.parseString("""{"sql":"SELECT ${'$'}1","params":["x"]}"""), JsonParser.parseString(t.requests.single().body))
    }

    @Test fun `list params keep nulls`() = runBlocking {
        val t = StubTransport(body = ROWS)
        core(t).query("SELECT \$1, \$2", listOf(null, 2.5))
        assertEquals(JsonParser.parseString("[null,2.5]"), JsonParser.parseString(t.requests.single().body).asJsonObject.get("params"))
    }

    @Test fun `vector 11 no params means no params key`() = runBlocking {
        val t = StubTransport(body = ROWS)
        core(t).query("SELECT 1")
        assertFalse(JsonParser.parseString(t.requests.single().body).asJsonObject.has("params"))
    }

    @Test fun `empty map sends no params key`() = runBlocking {
        val t = StubTransport(body = ROWS)
        core(t).query("SELECT 1", emptyMap<String, Any?>())
        assertFalse(JsonParser.parseString(t.requests.single().body).asJsonObject.has("params"))
    }

    // ---- validation: nothing is sent ----

    @Test fun `a bad list value throws before any request`() = runBlocking {
        val t = StubTransport(body = ROWS)
        val e = assertFailsWith<KError> { core(t).query("SELECT \$1", listOf(listOf(1))) }
        assertEquals("K9O_INVALID_FIELD_VALUE", e.code)
        assertTrue(t.requests.isEmpty())
    }

    @Test fun `a missing named value throws before any request`() = runBlocking {
        val t = StubTransport(body = ROWS)
        val e = assertFailsWith<KError> { core(t).query("SELECT :nope", mapOf("x" to 1)) }
        assertEquals("K9O_INVALID_FIELD_VALUE", e.code)
        assertTrue(t.requests.isEmpty())
    }

    @Test fun `a non-finite number is refused`() = runBlocking {
        val t = StubTransport(body = ROWS)
        assertFailsWith<KError> { core(t).query("SELECT \$1", listOf(Double.NaN)) }
        assertTrue(t.requests.isEmpty())
    }

    @Test fun `missing slug refused before any request`() = runBlocking {
        val t = StubTransport(body = ROWS)
        val e = assertFailsWith<KError> { core(t, slug = null).query("SELECT 1") }
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", e.code)
        assertTrue(t.requests.isEmpty())
    }

    @Test fun `read-only refusal surfaces as KError`() = runBlocking {
        val t = StubTransport(403, """{"code":"K9O_RAW_SQL_READ_ONLY","message":"read only","context":{},"recommendation":null}""")
        val e = assertFailsWith<KError> { core(t).query("DELETE FROM t") }
        assertEquals("K9O_RAW_SQL_READ_ONLY", e.code)
        assertEquals(403, e.status)
    }
}
