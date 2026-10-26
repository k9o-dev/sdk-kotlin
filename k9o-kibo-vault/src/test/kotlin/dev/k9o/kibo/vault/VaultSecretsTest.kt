package dev.k9o.kibo.vault

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Wire-shape tests for `VaultClient.secrets.*`: list / get / create / revealChallenge / reveal /
 * update / rotate / move / delete (URL, query, method, body, headers, response parsing):
 *  - `region` is optional on every call and sent only when given
 *  - `get` lists by exact-name prefix (the route answers a plain GET only as a reveal)
 *  - `value` base64-encoded on write, base64-decoded on reveal
 *  - the `X-K9O-Reveal-Challenge` header on `reveal`
 *
 * Hermetic: all requests go to an in-process MockWebServer.
 */
class VaultSecretsTest {
    private lateinit var server: MockWebServer
    private lateinit var vault: VaultClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        vault = VaultClient(
            orgBaseUrl = server.url("").toString(),
            projectSlug = "member-portal",
            httpClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun summaryJson(
        name: String = "db/pw",
        visibility: String = """{"kind":"private"}""",
    ) = """
        {
          "id": "sec_abc", "name": "$name", "ownerId": "usr_1", "visibility": $visibility, "version": 1,
          "kid": "vkey_1", "createdAt": "t", "createdBy": "usr_1", "updatedAt": "t", "updatedBy": "usr_1",
          "rotatedAt": null, "retiredAt": null, "metadata": {}
        }
    """.trimIndent()

    private fun envelope(data: String) = """{"data": $data, "meta": {}}"""

    // MARK: - list

    @Test
    fun `list GETs vault secrets with prefix cursor limit and no region`() = runTest {
        server.enqueue(MockResponse().setBody(envelope("""{"secrets": [${summaryJson()}], "nextCursor": null}""")))
        val result = vault.secrets.list(prefix = "db/", limit = 10)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/@member-portal/vault/secrets?prefix=db%2F&limit=10", recorded.path)
        assertEquals("sec_abc", result.secrets[0].id)
        assertNull(result.nextCursor)
    }

    @Test
    fun `list omits cursor and limit when not supplied`() = runTest {
        server.enqueue(MockResponse().setBody(envelope("""{"secrets": [], "nextCursor": "cur_1"}""")))
        vault.secrets.list()
        val recorded = server.takeRequest()
        assertEquals("/@member-portal/vault/secrets", recorded.path)
    }

    // MARK: - get

    @Test
    fun `get lists by the exact name as prefix and returns the exact match`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                envelope("""{"secrets": [${summaryJson(name = "db/pw2")}, ${summaryJson(name = "db/pw")}], "nextCursor": null}"""),
            ),
        )
        val result = vault.secrets.get("db/pw")
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/@member-portal/vault/secrets?prefix=db%2Fpw&limit=100", recorded.path)
        assertNull(recorded.getHeader("X-K9O-Reveal-Challenge"))
        assertEquals("db/pw", result.name)
        assertTrue(result.visibility is SecretVisibility.Private)
    }

    @Test
    fun `get follows nextCursor until it finds the exact name`() = runTest {
        server.enqueue(MockResponse().setBody(envelope("""{"secrets": [${summaryJson(name = "db/pw2")}], "nextCursor": "cur_1"}""")))
        server.enqueue(MockResponse().setBody(envelope("""{"secrets": [${summaryJson(name = "db/pw")}], "nextCursor": null}""")))
        assertEquals("db/pw", vault.secrets.get("db/pw").name)
        server.takeRequest()
        assertEquals("/@member-portal/vault/secrets?prefix=db%2Fpw&cursor=cur_1&limit=100", server.takeRequest().path)
    }

    @Test
    fun `get throws K9O_NOT_FOUND when no secret has that name`() = runTest {
        server.enqueue(MockResponse().setBody(envelope("""{"secrets": [${summaryJson(name = "db/pw2")}], "nextCursor": null}""")))
        try {
            vault.secrets.get("db/pw")
            fail("Expected VaultError.Api")
        } catch (e: VaultError.Api) {
            assertEquals("K9O_NOT_FOUND", e.code)
            assertEquals(404, e.statusCode)
        }
    }

    // MARK: - create

    @Test
    fun `create POSTs with region query and base64-encoded value`() = runTest {
        server.enqueue(MockResponse().setBody(envelope(summaryJson())))
        vault.secrets.create(name = "db/pw", region = "us01", value = "hunter2")
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/@member-portal/vault/secrets/db%2Fpw?region=us01", recorded.path)
        val body = recorded.body.readUtf8()
        val expectedB64 = java.util.Base64.getEncoder().encodeToString("hunter2".toByteArray(Charsets.UTF_8))
        assertTrue(body.contains("\"value\":\"$expectedB64\""))
        assertFalse(body.contains("hunter2"))
    }

    @Test
    fun `create without region sends no region query`() = runTest {
        server.enqueue(MockResponse().setBody(envelope(summaryJson())))
        vault.secrets.create(name = "db/pw", value = "hunter2")
        assertEquals("/@member-portal/vault/secrets/db%2Fpw", server.takeRequest().path)
    }

    @Test
    fun `create omits visibility and metadata keys when not supplied`() = runTest {
        server.enqueue(MockResponse().setBody(envelope(summaryJson())))
        vault.secrets.create(name = "db/pw", region = "us01", value = "hunter2")
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("visibility"))
        assertFalse(body.contains("metadata"))
    }

    @Test
    fun `create serializes Shared visibility with principals and roles`() = runTest {
        server.enqueue(MockResponse().setBody(envelope(summaryJson())))
        vault.secrets.create(
            name = "db/pw",
            region = "us01",
            value = "hunter2",
            visibility = SecretVisibility.Shared(principals = listOf("usr_1"), roles = listOf("admin")),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"kind\":\"shared\""))
        assertTrue(body.contains("\"principals\":[\"usr_1\"]"))
        assertTrue(body.contains("\"roles\":[\"admin\"]"))
    }

    // MARK: - revealChallenge

    @Test
    fun `revealChallenge POSTs op=reveal-challenge and region, no body`() = runTest {
        server.enqueue(MockResponse().setBody(envelope("""{"challenge": "rvc_1", "expiresAt": "t"}""")))
        val result = vault.secrets.revealChallenge("db/pw", region = "us01")
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/@member-portal/vault/secrets/db%2Fpw?op=reveal-challenge&region=us01", recorded.path)
        assertEquals(0L, recorded.bodySize)
        assertEquals("rvc_1", result.challenge)
    }

    @Test
    fun `revealChallenge with a reason sends it as a JSON body and no region`() = runTest {
        server.enqueue(MockResponse().setBody(envelope("""{"challenge": "rvc_1", "expiresAt": "t"}""")))
        vault.secrets.revealChallenge("db/pw", reason = "incident 42")
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/@member-portal/vault/secrets/db%2Fpw?op=reveal-challenge", recorded.path)
        assertEquals("""{"reason":"incident 42"}""", recorded.body.readUtf8())
    }

    // MARK: - reveal

    @Test
    fun `reveal GETs with the X-K9O-Reveal-Challenge header and decodes base64 value to plaintext`() = runTest {
        val wireValue = java.util.Base64.getEncoder().encodeToString("hunter2".toByteArray(Charsets.UTF_8))
        server.enqueue(
            MockResponse().setBody(
                envelope(
                    """{"id":"sec_abc","name":"db/pw","version":1,"value":"$wireValue","revealedAt":"t",
                        "attestationTier":{"urn":"u","operator":"platform","verified":true}}""",
                ),
            ),
        )
        val result = vault.secrets.reveal("db/pw", region = "us01", challenge = "rvc_1")
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("rvc_1", recorded.getHeader("X-K9O-Reveal-Challenge"))
        assertEquals("hunter2", result.value)
        assertEquals("platform", result.attestationTier.operator)
        assertTrue(result.attestationTier.verified)
    }

    // MARK: - update

    @Test
    fun `update PUTs with region query and base64-encoded value, returns narrow shape`() = runTest {
        server.enqueue(MockResponse().setBody(envelope("""{"id":"sec_abc","name":"db/pw","version":2}""")))
        val result = vault.secrets.update("db/pw", region = "us01", value = "newpw")
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/@member-portal/vault/secrets/db%2Fpw?region=us01", recorded.path)
        val expectedB64 = java.util.Base64.getEncoder().encodeToString("newpw".toByteArray(Charsets.UTF_8))
        assertTrue(recorded.body.readUtf8().contains("\"value\":\"$expectedB64\""))
        assertEquals(2, result.version)
    }

    // MARK: - rotate

    @Test
    fun `rotate POSTs op=rotate and region, no body, returns undocumented live shape`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                envelope("""{"id":"sec_abc","name":"db/pw","version":2,"rotatedAt":"t","ledgerSequenceNumber":2}"""),
            ),
        )
        val result = vault.secrets.rotate("db/pw", region = "us01")
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/@member-portal/vault/secrets/db%2Fpw?op=rotate&region=us01", recorded.path)
        assertEquals(0L, recorded.bodySize)
        assertEquals(2, result.ledgerSequenceNumber)
    }

    // MARK: - move

    @Test
    fun `move POSTs op=move and region with newPath body, returns undocumented live shape`() = runTest {
        server.enqueue(MockResponse().setBody(envelope("""{"id":"sec_abc","name":"db/pw2","event":"SECRET_PATH_CHANGED"}""")))
        val result = vault.secrets.move("db/pw", region = "us01", newPath = "db/pw2")
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/@member-portal/vault/secrets/db%2Fpw?op=move&region=us01", recorded.path)
        assertEquals("""{"newPath":"db/pw2"}""", recorded.body.readUtf8())
        assertEquals("SECRET_PATH_CHANGED", result.event)
    }

    // MARK: - delete

    @Test
    fun `delete DELETEs with region query and handles a 204 empty body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        vault.secrets.delete("db/pw", region = "us01")
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/@member-portal/vault/secrets/db%2Fpw?region=us01", recorded.path)
    }

    @Test
    fun `delete accepts a 200 with a body and sends no region when none is given`() = runTest {
        server.enqueue(MockResponse().setBody(envelope("""{"id":"sec_abc","retiredAt":"t"}""")))
        vault.secrets.delete("db/pw")
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/@member-portal/vault/secrets/db%2Fpw", recorded.path)
    }

    // MARK: - Error cases

    @Test
    fun `K9O_INVALID_FIELD_VALUE maps to VaultError Api verbatim`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"code":"K9O_INVALID_FIELD_VALUE","message":"value rejected","context":{},"recommendation":null}""",
            ),
        )
        try {
            vault.secrets.rotate("db/pw", region = "us01")
            fail("Expected VaultError.Api")
        } catch (e: VaultError.Api) {
            assertEquals("K9O_INVALID_FIELD_VALUE", e.code)
            assertEquals(422, e.statusCode)
        }
    }

    @Test
    fun `non-KError 500 body maps to VaultError Api with K9O_INTERNAL_ERROR fallback code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"oops": true}"""))
        try {
            vault.secrets.list()
            fail("Expected VaultError.Api")
        } catch (e: VaultError.Api) {
            assertEquals("K9O_INTERNAL_ERROR", e.code)
            assertEquals(500, e.statusCode)
        }
    }

    @Test
    fun `non-JSON 200 body throws VaultError Decoding`() = runTest {
        server.enqueue(MockResponse().setBody("not-json"))
        try {
            vault.secrets.list()
            fail("Expected VaultError.Decoding")
        } catch (e: VaultError.Decoding) {
            // pass
        }
    }
}
