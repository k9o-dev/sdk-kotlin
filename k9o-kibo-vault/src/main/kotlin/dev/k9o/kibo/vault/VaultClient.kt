package dev.k9o.kibo.vault

import okhttp3.OkHttpClient

/**
 * Kibo vault-secrets Android/Kotlin SDK
 *
 * `VaultClient` is the primary entry point. [secrets] is the typed secrets surface over
 * `/@<projectSlug>/vault/secrets`: see [VaultSecrets] for the list/get/create/revealChallenge/reveal/
 * update/rotate/move/delete family.
 *
 * Example:
 * ```kotlin
 * val vault = VaultClient(orgBaseUrl = "https://acme.api.k9o.io", projectSlug = "my-project")
 *
 * val summary = vault.secrets.create(name = "db/pw", value = "hunter2")
 * val challenge = vault.secrets.revealChallenge("db/pw")
 * val revealed = vault.secrets.reveal("db/pw", challenge = challenge.challenge)
 * println(revealed.value) // "hunter2" - plaintext, never base64
 * ```
 */
class VaultClient(
    orgBaseUrl: String,
    projectSlug: String,
    httpClient: OkHttpClient = OkHttpClient(),
) {
    private val transport = VaultTransport(orgBaseUrl, projectSlug, httpClient)

    /** The typed secrets surface. */
    val secrets: VaultSecrets = VaultSecrets(transport)
}

/**
 * The typed secrets family (`list`/`get`/`create`/`revealChallenge`/`reveal`/`update`/`rotate`/`move`/`delete`).
 *
 * `region` is optional on every operation: the gateway sets the residency region of each vault call
 * itself and discards a caller-supplied `region` query parameter. When you pass one it is still sent
 * (older gateways required it).
 *
 * The wire `value` field is base64 in BOTH directions (a raw, non-base64 `value` is rejected as
 * unparseable "Request body could not be parsed."). The public API here always deals in plaintext:
 * [create]/[update] base64-encode internally before putting `value` on the wire, and [reveal]
 * base64-decodes the wire `value` back to plaintext before returning it.
 */
class VaultSecrets internal constructor(private val transport: VaultTransport) {

    /**
     * List secrets (metadata only, never ciphertext). `GET /@<projectSlug>/vault/secrets`.
     */
    suspend fun list(prefix: String? = null, cursor: String? = null, limit: Int? = null): ListSecretsResult =
        transport.get(transport.secretsUrl(prefix, cursor, limit))

    /**
     * Read one secret's metadata (never its value; use [revealChallenge] + [reveal] for that).
     *
     * The gateway answers `GET /@<projectSlug>/vault/secrets/<name>` only as a reveal: with no
     * `X-K9O-Reveal-Challenge` header it refuses with 401 `K9O_REVEAL_CHALLENGE_REQUIRED`, whatever the
     * caller's rights. There is no plain metadata read on that route, so this reads the metadata the
     * route that exists for it returns: it lists by the exact name as prefix and picks the exact match.
     * Throws [VaultError.Api] `K9O_NOT_FOUND` (status 404) when no secret has that name.
     */
    suspend fun get(name: String): SecretSummary {
        var cursor: String? = null
        for (i in 0 until MAX_GET_PAGES) {
            val page = list(prefix = name, cursor = cursor, limit = 100)
            page.secrets.firstOrNull { it.name == name }?.let { return it }
            cursor = page.nextCursor ?: break
        }
        notFound(name)
    }

    private fun notFound(name: String): Nothing =
        throw VaultError.Api(
            code = "K9O_NOT_FOUND",
            statusCode = 404,
            detail = "No secret named '$name' in this project.",
            context = mapOf("name" to name),
        )

    /**
     * Create a secret under the caller's own auth.
     * `POST /@<projectSlug>/vault/secrets/<name>` -> the `sec_<base62>` summary.
     *
     * [region] is optional and discarded by the gateway (see the class doc).
     */
    suspend fun create(
        name: String,
        value: String,
        region: String? = null,
        visibility: SecretVisibility? = null,
        metadata: Map<String, String>? = null,
    ): SecretSummary = transport.post(
        transport.secretUrl(name, region),
        CreateSecretBody(value = encodeBase64(value), visibility = visibility, metadata = metadata),
    )

    /**
     * Mint a single-use reveal challenge; present it as `X-K9O-Reveal-Challenge` on the
     * immediately-following [reveal] call.
     * `POST /@<projectSlug>/vault/secrets/<name>?op=reveal-challenge`. The optional [reason] is sent as
     * `{ reason }`; the gateway accepts it and does not forward it. With no reason there is no body.
     */
    suspend fun revealChallenge(name: String, region: String? = null, reason: String? = null): RevealChallenge {
        val url = transport.secretUrl(name, region, op = "reveal-challenge")
        return if (reason != null) transport.post(url, RevealChallengeBody(reason)) else transport.post(url)
    }

    /**
     * Reveal a secret's plaintext value using a single-use challenge from [revealChallenge].
     * `GET /@<projectSlug>/vault/secrets/<name>` with `X-K9O-Reveal-Challenge`.
     *
     * Base64-decodes the wire `value` back to plaintext (symmetric with [create]'s encode), so
     * [RevealSecretResult.value] is always plaintext.
     */
    suspend fun reveal(name: String, challenge: String, region: String? = null): RevealSecretResult {
        val wire: RevealSecretResult = transport.get(
            transport.secretUrl(name, region),
            headers = mapOf("X-K9O-Reveal-Challenge" to challenge),
        )
        return wire.copy(value = decodeBase64(wire.value))
    }

    /**
     * Update a secret's value (bumps version), owner-only. `PUT /@<projectSlug>/vault/secrets/<name>`.
     *
     * Returns only `{id, name, version}` (see [UpdateSecretResult]); call [get] afterward for the rest.
     */
    suspend fun update(
        name: String,
        value: String,
        region: String? = null,
        metadata: Map<String, String>? = null,
    ): UpdateSecretResult = transport.put(
        transport.secretUrl(name, region),
        UpdateSecretBody(value = encodeBase64(value), metadata = metadata),
    )

    /**
     * Rotate a secret's wrapping key (re-wraps under a fresh `kid`; content unchanged), owner-only.
     * `POST /@<projectSlug>/vault/secrets/<name>?op=rotate`, no body. See [RotateSecretResult].
     */
    suspend fun rotate(name: String, region: String? = null): RotateSecretResult =
        transport.post(transport.secretUrl(name, region, op = "rotate"))

    /**
     * Move (rename) a secret's hierarchical path, owner-only.
     * `POST /@<projectSlug>/vault/secrets/<name>?op=move`. See [MoveSecretResult]; its
     * [MoveSecretResult.event] is a plain `String` (only `"SECRET_PATH_CHANGED"` observed).
     */
    suspend fun move(name: String, newPath: String, region: String? = null): MoveSecretResult =
        transport.post(transport.secretUrl(name, region, op = "move"), MoveSecretBody(newPath = newPath))

    /**
     * Retire a secret, owner-only. `DELETE /@<projectSlug>/vault/secrets/<name>`. Any 2xx is success
     * (the gateway may answer `204 No Content` or `200` with a body; the body is not read).
     */
    suspend fun delete(name: String, region: String? = null) {
        transport.delete<Unit>(transport.secretUrl(name, region))
    }

    private companion object {
        /** Bound on the pages [get] scans for an exact name. */
        const val MAX_GET_PAGES = 20
    }
}

// MARK: - Request body shapes (internal)

private data class CreateSecretBody(val value: String, val visibility: SecretVisibility?, val metadata: Map<String, String>?)
private data class UpdateSecretBody(val value: String, val metadata: Map<String, String>?)
private data class MoveSecretBody(val newPath: String)
private data class RevealChallengeBody(val reason: String)

// MARK: - Base64 wire transport (both directions; see VaultSecrets doc comment)

/** Base64-encode plaintext for the wire (`create`/`update`'s `value`). */
private fun encodeBase64(plaintext: String): String =
    java.util.Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))

/** Inverse of [encodeBase64] — decodes a revealed wire `value` back to plaintext. */
private fun decodeBase64(b64: String): String =
    String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
