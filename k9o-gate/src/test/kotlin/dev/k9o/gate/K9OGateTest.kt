package dev.k9o.gate

import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class K9OGateTest {
    private lateinit var server: MockWebServer
    private lateinit var gate: GateClient
    private lateinit var store: InMemoryTokenStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemoryTokenStore()
        gate = GateClient(
            orgBaseUrl = server.url("").toString(),
            projectSlug = "test-project",
            tokenStore = store,
            httpClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // MARK: - signIn

    @Test
    fun `signIn posts email and password only and returns user and session`() = runTest {
        server.enqueue(MockResponse().setBody(authResultJson()).setResponseCode(200))
        val result = gate.signIn("user@example.com", "pass")
        assertEquals("eu_TestUserABC123", result.user.id)
        assertEquals("user@example.com", result.user.email)
        assertEquals("2026-01-01T00:00:00Z", result.user.createdAt)
        assertEquals("rt_MockRefreshToken0001", result.session.refreshToken)
        assertEquals("2099-01-01T00:00:00Z", result.session.expiresAt)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/@test-project/gate/signin", recorded.path)
        assertEquals(
            mapOf("email" to "user@example.com", "password" to "pass"),
            Gson().fromJson(recorded.body.readUtf8(), Map::class.java),
        )
    }

    @Test
    fun `signIn persists refresh token to store`() = runTest {
        server.enqueue(MockResponse().setBody(authResultJson()).setResponseCode(200))
        gate.signIn("user@example.com", "pass")
        assertEquals("rt_MockRefreshToken0001", store.load())
    }

    // MARK: - refreshToken

    @Test
    fun `refreshToken parses the flat accessToken and expiresAt and keeps the stored token`() = runTest {
        store.save("rt_InitialToken")
        server.enqueue(MockResponse().setBody(refreshJson()).setResponseCode(200))
        val result = gate.refreshToken()
        assertEquals("access.jwt.value", result.accessToken)
        assertEquals("2099-01-01T00:00:00Z", result.expiresAt)
        // The response carries no new refresh token: the stored one is untouched.
        assertEquals("rt_InitialToken", store.load())
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/@test-project/gate/refresh", recorded.path)
        // The body is closed to { refreshToken }: the gateway refuses any other field.
        assertEquals(
            mapOf("refreshToken" to "rt_InitialToken"),
            Gson().fromJson(recorded.body.readUtf8(), Map::class.java),
        )
    }

    @Test
    fun `refreshToken explicit token overrides store`() = runTest {
        store.save("rt_ShouldNotBeUsed")
        server.enqueue(MockResponse().setBody(refreshJson()).setResponseCode(200))
        gate.refreshToken(refreshToken = "rt_Explicit")
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("rt_Explicit"))
        assertFalse(body.contains("rt_ShouldNotBeUsed"))
    }

    // MARK: - signOut

    @Test
    fun `signOut sends the refresh token, reports revoked and clears the store`() = runTest {
        store.save("rt_ToRevoke")
        server.enqueue(MockResponse().setBody("""{"data":{"revoked":true},"meta":{}}""").setResponseCode(200))
        assertTrue(gate.signOut())
        assertNull(store.load())
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/@test-project/gate/signout", recorded.path)
        assertEquals(
            mapOf("refreshToken" to "rt_ToRevoke"),
            Gson().fromJson(recorded.body.readUtf8(), Map::class.java),
        )
    }

    @Test
    fun `signOut of an unknown token returns false and still clears the store`() = runTest {
        store.save("rt_Unknown")
        server.enqueue(MockResponse().setBody("""{"data":{"revoked":false},"meta":{}}""").setResponseCode(200))
        assertFalse(gate.signOut())
        assertNull(store.load())
    }

    @Test
    fun `signOut is no-op when no token`() = runTest {
        // No token → no network request; if server gets a request the test fails
        assertFalse(gate.signOut())
        assertEquals(0, server.requestCount)
    }

    // MARK: - Error cases

    @Test(expected = GateError.InvalidCredentials::class)
    fun `signIn throws InvalidCredentials on INVALID_CREDENTIALS`() = runTest {
        server.enqueue(MockResponse().setBody(errorJson("K9O_INVALID_CREDENTIALS")).setResponseCode(401))
        gate.signIn("bad@example.com", "wrong")
    }

    @Test(expected = GateError.Unauthenticated::class)
    fun `refreshToken throws Unauthenticated when no token in store`() = runTest {
        gate.refreshToken()
    }

    @Test
    fun `non-JSON 200 body throws Decoding`() = runTest {
        server.enqueue(MockResponse().setBody("not-json").setResponseCode(200))
        try {
            gate.signIn("u@e.com", "p")
            fail("Expected GateError.Decoding")
        } catch (e: GateError.Decoding) {
            // pass
        }
    }

    @Test
    fun `500 error maps to GateError Unknown`() = runTest {
        server.enqueue(MockResponse().setBody(errorJson("K9O_INTERNAL_ERROR")).setResponseCode(500))
        try {
            gate.signIn("u@e.com", "p")
            fail("Expected GateError.Unknown")
        } catch (e: GateError.Unknown) {
            assertEquals(500, e.statusCode)
        }
    }

    // MARK: - AccessToken JWT decode

    @Test
    fun `AccessToken decode reads flat custom claims`() {
        // Payload: {"sub":"eu_TestUserABC123","exp":9999999999,"iat":1700000000,"iss":"https://issuer.example",
        //           "ext.partners.partner_role":"extension_org_admin"}  (flat key, not a nested object)
        val payload = """{"sub":"eu_TestUserABC123","exp":9999999999,"iat":1700000000,"iss":"https://issuer.example","ext.partners.partner_role":"extension_org_admin"}"""
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val token = AccessToken.decode("eyJhbGciOiJFUzI1NiJ9.$b64.sig")
        assertNotNull(token)
        assertEquals("eu_TestUserABC123", token!!.subject)
        assertEquals("https://issuer.example", token.issuer)
        assertEquals("extension_org_admin", token.customClaims["ext.partners.partner_role"])
        assertEquals("extension_org_admin", token.customClaim("partners", "partner_role"))
        assertEquals(1, token.customClaims.size)
        assertNull(token.customClaim("partners", "missing"))
    }

    @Test
    fun `AccessToken decode returns null for malformed JWT`() {
        assertNull(AccessToken.decode("not.a.jwt.at.all.whatsoever"))
        assertNull(AccessToken.decode("only.two"))
        assertNull(AccessToken.decode(""))
    }

    // MARK: - Token store roundtrip

    @Test
    fun `InMemoryTokenStore save load delete`() {
        val s = InMemoryTokenStore()
        assertNull(s.load())
        s.save("rt_Token123")
        assertEquals("rt_Token123", s.load())
        s.delete()
        assertNull(s.load())
    }
}

// MARK: - Helpers

class InMemoryTokenStore : TokenStore {
    private var token: String? = null
    override fun save(token: String) { this.token = token }
    override fun load(): String? = token
    override fun delete() { token = null }
}

fun authResultJson() = """
{
  "data": {
    "user": {
      "id": "eu_TestUserABC123",
      "email": "user@example.com",
      "status": "active",
      "createdAt": "2026-01-01T00:00:00Z",
      "updatedAt": null,
      "lastSigninAt": null,
      "roles": ["PROJECT_END_USER"],
      "groups": []
    },
    "session": {
      "accessToken": "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJldV9UZXN0VXNlckFCQzEyMyIsImV4cCI6OTk5OTk5OTk5OSwiaWF0IjoxNzAwMDAwMDAwLCJpc3MiOiJodHRwczovL2FwaS5rOW8uaW8iLCJleHQucGFydG5lcnMiOnsicGFydG5lcl9yb2xlIjoiZXh0ZW5zaW9uX29yZ19hZG1pbiJ9fQ.sig",
      "refreshToken": "rt_MockRefreshToken0001",
      "expiresAt": "2099-01-01T00:00:00Z"
    }
  },
  "meta": {}
}
""".trimIndent()

fun refreshJson() = """{"data":{"accessToken":"access.jwt.value","expiresAt":"2099-01-01T00:00:00Z"},"meta":{}}"""

fun errorJson(code: String) = """{"code":"$code","message":"Error","context":{},"recommendation":null}"""
