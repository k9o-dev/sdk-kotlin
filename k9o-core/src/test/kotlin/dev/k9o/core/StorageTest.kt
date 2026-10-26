package dev.k9o.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BUCKET =
    """{"name":"avatars","public":false,"fileSizeLimit":1000,"allowedMimeTypes":["image/png"],"versioning":false,"deleteAfterDays":null,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z","system":false,"byosBindingId":null}"""
private const val OBJ = """{"data":{"path":"a/b.png","size":3,"lastModified":null},"meta":{}}"""

class StorageTest {
    private fun core(t: StubTransport, slug: String? = "member-portal") =
        K9OCore("https://acme.api.k9o.io", slug, accessToken = "tok", transport = t)

    private val root = "https://acme.api.k9o.io/@member-portal/storage/buckets"

    @Test fun `listBuckets and getBucket`() = runBlocking {
        val t = StubTransport(body = """{"data":[$BUCKET],"meta":{}}""")
        val r = core(t).storage.listBuckets()
        assertEquals("GET", t.requests.single().method)
        assertEquals(root, t.requests.single().url)
        assertEquals(StorageBucket("avatars", false, 1000, listOf("image/png"), false, null, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", false, null), r.data.single())

        val t2 = StubTransport(body = """{"data":$BUCKET,"meta":{}}""")
        core(t2).storage.getBucket("my bucket")
        assertEquals("$root/my%20bucket", t2.requests.single().url)
    }

    @Test fun `createBucket sends name and only the given options`() = runBlocking {
        val t = StubTransport(201, """{"data":$BUCKET,"meta":{}}""")
        core(t).storage.createBucket("avatars", public = false, fileSizeLimit = 1000, allowedMimeTypes = listOf("image/png"))
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals(root, req.url)
        assertEquals(
            com.google.gson.JsonParser.parseString("""{"name":"avatars","public":false,"fileSizeLimit":1000,"allowedMimeTypes":["image/png"]}"""),
            com.google.gson.JsonParser.parseString(req.body),
        )
    }

    @Test fun `updateBucket patches only what is given and null clears`() = runBlocking {
        val t = StubTransport(body = """{"data":$BUCKET,"meta":{}}""")
        core(t).storage.updateBucket("avatars", mapOf("fileSizeLimit" to null, "public" to true))
        val req = t.requests.single()
        assertEquals("PATCH", req.method)
        assertEquals("$root/avatars", req.url)
        assertEquals(
            com.google.gson.JsonParser.parseString("""{"fileSizeLimit":null,"public":true}"""),
            com.google.gson.JsonParser.parseString(req.body),
        )
    }

    @Test fun `deleteBucket sends empty=true only when asked`() = runBlocking {
        val t = StubTransport(body = """{"data":{"name":"avatars","deleted":true,"objectsDeleted":4},"meta":{}}""")
        val r = core(t).storage.deleteBucket("avatars", empty = true)
        core(t).storage.deleteBucket("avatars")
        assertEquals("DELETE", t.requests[0].method)
        assertEquals("$root/avatars?empty=true", t.requests[0].url)
        assertEquals("$root/avatars", t.requests[1].url)
        assertEquals(4, r.data.objectsDeleted)
    }

    @Test fun `upload posts multipart with file, path, contentType, upsert and metadata`() = runBlocking {
        val t = StubTransport(201, OBJ)
        val r = core(t).storage.from("avatars").upload("a/b.png", byteArrayOf(1, 2, 3), "image/png", upsert = true, metadata = mapOf("k" to "v"))
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals("$root/avatars/objects", req.url)
        assertNull(req.body)
        assertNull(req.headers["content-type"])
        val parts = req.parts!!
        assertEquals(listOf("file", "path", "contentType", "upsert", "metadata"), parts.map { it.name })
        assertEquals("b.png", parts[0].filename)
        assertEquals("image/png", parts[0].contentType)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(parts[0].bytes))
        assertEquals("a/b.png", String(parts[1].bytes))
        assertEquals("true", String(parts[3].bytes))
        assertEquals("""{"k":"v"}""", String(parts[4].bytes))
        assertEquals(StorageObject("a/b.png", 3, null), r.data)
    }

    @Test fun `upload defaults to upsert false and accepts a string`() = runBlocking {
        val t = StubTransport(201, OBJ)
        core(t).storage.from("avatars").upload("note.txt", "hello")
        val parts = t.requests.single().parts!!
        assertEquals(listOf("file", "path", "upsert"), parts.map { it.name })
        assertEquals("false", String(parts[2].bytes))
        assertEquals("hello", String(parts[0].bytes))
    }

    @Test fun `createSignedUrl posts path, expiresIn and download`() = runBlocking {
        val t = StubTransport(body = """{"data":{"signedUrl":"https://s/x","expiresIn":900},"meta":{}}""")
        val r = core(t).storage.from("avatars").createSignedUrl("a/b.png", 3600, download = true)
        val req = t.requests.single()
        assertEquals("$root/avatars/objects/sign", req.url)
        assertEquals(
            com.google.gson.JsonParser.parseString("""{"path":"a/b.png","expiresIn":3600,"download":true}"""),
            com.google.gson.JsonParser.parseString(req.body),
        )
        assertEquals(SignedUrl("https://s/x", 900), r.data)
    }

    @Test fun `createSignedUrl refuses an out-of-range expiresIn`() = runBlocking {
        val t = StubTransport(body = "{}")
        val h = core(t).storage.from("avatars")
        assertEquals("K9O_INVALID_FIELD_VALUE", assertFailsWith<KError> { h.createSignedUrl("a", 0) }.code)
        assertEquals("K9O_INVALID_FIELD_VALUE", assertFailsWith<KError> { h.createSignedUrl("a", 604_801) }.code)
        assertTrue(t.requests.isEmpty())
    }

    @Test fun `list sends prefix only when given`() = runBlocking {
        val t = StubTransport(body = """{"data":[{"path":"a/b.png","size":3,"lastModified":"2026-01-01T00:00:00Z"}],"meta":{}}""")
        val h = core(t).storage.from("avatars")
        val r = h.list("a/")
        h.list()
        assertEquals("$root/avatars/objects?prefix=a%2F", t.requests[0].url)
        assertEquals("$root/avatars/objects", t.requests[1].url)
        assertEquals("2026-01-01T00:00:00Z", r.data.single().lastModified)
    }

    @Test fun `remove deletes by path query`() = runBlocking {
        val t = StubTransport(body = """{"data":{"bucket":"avatars","path":"a b.png","deleted":true},"meta":{}}""")
        val r = core(t).storage.from("avatars").remove("a b.png")
        assertEquals("DELETE", t.requests.single().method)
        assertEquals("$root/avatars/objects?path=a+b.png", t.requests.single().url)
        assertTrue(r.data.deleted)
    }

    @Test fun `empty or dot names and paths and a missing slug are refused before any request`() = runBlocking {
        val t = StubTransport(body = "{}")
        val c = core(t)
        fun code(f: suspend () -> Unit) = assertFailsWith<KError> { runBlocking { f() } }.code
        assertEquals("K9O_INVALID_FIELD_VALUE", code { c.storage.getBucket("") })
        assertEquals("K9O_INVALID_FIELD_VALUE", code { c.storage.getBucket("..") })
        assertEquals("K9O_INVALID_FIELD_VALUE", code { c.storage.from("..").list() })
        assertEquals("K9O_INVALID_FIELD_VALUE", code { c.storage.from("b").upload("", "x") })
        assertEquals("K9O_INVALID_FIELD_VALUE", code { c.storage.from("b").remove("") })
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", code { core(t, null).storage.listBuckets() })
        assertTrue(t.requests.isEmpty())
    }

    @Test fun `gateway errors surface as KError`() = runBlocking {
        val t = StubTransport(409, """{"code":"K9O_ALREADY_EXISTS","message":"exists","context":{},"recommendation":null}""")
        val e = assertFailsWith<KError> { core(t).storage.from("avatars").upload("a", "x") }
        assertEquals("K9O_ALREADY_EXISTS", e.code)
        assertEquals(409, e.status)
    }
}
