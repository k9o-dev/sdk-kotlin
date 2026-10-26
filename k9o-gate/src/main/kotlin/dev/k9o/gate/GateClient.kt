package dev.k9o.gate

import okhttp3.OkHttpClient

/**
 * Kilimanjaro Gate SDK for Android and Kotlin
 *
 * `GateClient` is the primary entry point. Initialise once per project
 * and call `signIn`, `refreshToken`, and `signOut`.
 *
 * Refresh tokens are automatically persisted via [tokenStore] (backed by
 * Android Keystore `EncryptedSharedPreferences` when using the default
 * [KeystoreTokenStore]). Pass `null` to manage token persistence yourself.
 *
 * Example:
 * ```kotlin
 * val gate = GateClient(
 *     orgBaseUrl = "https://acme.api.k9o.io",
 *     projectSlug = "my-project",
 *     tokenStore = KeystoreTokenStore(applicationContext),
 * )
 * val result = gate.signIn(email = "user@example.com", password = "s3cr3t")
 * ```
 */
class GateClient(
    orgBaseUrl: String,
    projectSlug: String,
    private val tokenStore: TokenStore? = null,
    httpClient: OkHttpClient = OkHttpClient(),
) {
    private val client = GateAuthClient(
        orgBaseUrl = orgBaseUrl.trimEnd('/'),
        projectSlug = projectSlug,
        httpClient = httpClient,
    )

    // MARK: - Public API surface

    /**
     * Sign in an existing end-user with email and password.
     *
     * Persists the refresh token in the token store on success.
     * Throws [GateError.InvalidCredentials] on bad credentials.
     *
     * Mirrors: `POST /@<projectSlug>/gate/signin`
     */
    suspend fun signIn(email: String, password: String): AuthResult {
        val result: AuthResult = client.post("signin", SignInBody(email, password))
        tokenStore?.save(result.session.refreshToken)
        return result
    }

    /**
     * Mint a new access token from the stored (or supplied) refresh token.
     *
     * If [refreshToken] is null, loads the token from the store. The gateway answers with a new
     * access token and its expiry only: the refresh token is NOT rotated, so the stored one is left
     * as it is and stays valid until [signOut]. The result has no `user`; read the subject from the
     * access token with [AccessToken.decode].
     *
     * Mirrors: `POST /@<projectSlug>/gate/refresh` with body `{ refreshToken }`.
     */
    suspend fun refreshToken(refreshToken: String? = null): RefreshResult {
        val token = refreshToken
            ?: tokenStore?.load()
            ?: throw GateError.Unauthenticated
        return client.post("refresh", RefreshTokenBody(token))
    }

    /**
     * Sign out the current session. Clears the token store.
     *
     * If [refreshToken] is null, loads the token from the store; with none, nothing is sent.
     * Returns true when the gateway revoked a live session and false when the token was unknown
     * or already revoked (sign-out is idempotent; the store is cleared either way).
     *
     * Mirrors: `POST /@<projectSlug>/gate/signout` with body `{ refreshToken }`.
     */
    suspend fun signOut(refreshToken: String? = null): Boolean {
        val token = refreshToken ?: tokenStore?.load() ?: return false
        val result: SignOutResponse = client.post("signout", SignOutBody(token))
        tokenStore?.delete()
        return result.revoked
    }
}

/**
 * Minimal token-store interface for injection/testing.
 * [KeystoreTokenStore] provides the Android Keystore-backed implementation.
 */
interface TokenStore {
    fun save(token: String)
    fun load(): String?
    fun delete()
}
