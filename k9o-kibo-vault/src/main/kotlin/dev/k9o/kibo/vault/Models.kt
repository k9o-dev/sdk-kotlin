package dev.k9o.kibo.vault

/**
 * Secrets model types.
 *
 * [UpdateSecretResult]/[RotateSecretResult]/[MoveSecretResult] are typed to what the gateway returns,
 * not the OpenAPI schema's `SecretSummary` - see each type's doc comment.
 */

/** Secret visibility — write ops (create/update) stay owner-only regardless of this tag. Wire
 *  shape is a `{kind: "private"}` / `{kind: "shared", principals?, roles?}` discriminated union — see
 *  [SecretVisibilityAdapter] for the Gson (de)serialization. */
sealed class SecretVisibility {
    object Private : SecretVisibility()
    data class Shared(val principals: List<String>? = null, val roles: List<String>? = null) : SecretVisibility()
}

/** A secret WITHOUT its sealed ciphertext — the create/list/get metadata shape. */
data class SecretSummary(
    /** `sec_<base62>`. */
    val id: String,
    val name: String,
    val ownerId: String,
    val visibility: SecretVisibility,
    val version: Int,
    /** The wrapping-key id at this version. */
    val kid: String,
    val createdAt: String,
    val createdBy: String,
    val updatedAt: String,
    val updatedBy: String,
    val rotatedAt: String?,
    val retiredAt: String?,
    val metadata: Map<String, String>,
)

/** [VaultSecrets.list] result. */
data class ListSecretsResult(
    val secrets: List<SecretSummary>,
    val nextCursor: String?,
)

/** Releasing-node observed attestation tier (K4) — present on every reveal. */
data class AttestationTier(
    /** `attestation:destination:region` trust-tier URN. */
    val urn: String,
    /** Node provenance: `platform` (Kibo-operated) or `client` (Private Perimeter). */
    val operator: String,
    /** Whether node attestation was verified against the vendor PKI on the URN's attestation segment. */
    val verified: Boolean,
)

/** [VaultSecrets.revealChallenge] result — a single-use challenge to present on the
 *  immediately-following [VaultSecrets.reveal] call. */
data class RevealChallenge(val challenge: String, val expiresAt: String)

/**
 * [VaultSecrets.reveal] result. [value] is the decoded PLAINTEXT — [VaultSecrets.reveal] base64-decodes
 * the wire value before returning it, the inverse of [VaultSecrets.create]/[VaultSecrets.update]'s
 * encode. Never the raw base64.
 */
data class RevealSecretResult(
    val id: String,
    val name: String,
    val version: Int,
    val value: String,
    val revealedAt: String,
    val attestationTier: AttestationTier,
)

/**
 * [VaultSecrets.update] result: the gateway returns only `{id, name, version}`, narrower than the OpenAPI
 * `SecretSummary`. Call [VaultSecrets.get] afterward if the full summary is needed.
 */
data class UpdateSecretResult(val id: String, val name: String, val version: Int)

/**
 * [VaultSecrets.rotate] result: not described by the OpenAPI schema (which declares a summary or a reveal
 * challenge for the route); typed to what the gateway returns. [ledgerSequenceNumber] is undocumented.
 */
data class RotateSecretResult(
    val id: String,
    val name: String,
    val version: Int,
    val rotatedAt: String,
    val ledgerSequenceNumber: Int,
)

/**
 * [VaultSecrets.move] result: not the OpenAPI `SecretSummary`; it has no `version` and carries an
 * undocumented [event] field (`"SECRET_PATH_CHANGED"` observed; typed as a plain `String`).
 */
data class MoveSecretResult(val id: String, val name: String, val event: String)
