package dev.k9o.gate

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// MARK: - Core model types

/** Project-level end-user identity. IDs carry the `eu_` prefix. */
data class EndUser(
    val id: String,
    val email: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String?,
    val lastSigninAt: String?,
    val roles: List<String>,
    val groups: List<String>,
)

/** Session tokens returned by sign-in. */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    /** ISO 8601 instant the access token expires. */
    val expiresAt: String,
)

/** Returned by signIn. */
data class AuthResult(
    val user: EndUser,
    val session: Session,
)

/** Returned by refreshToken: a new access token and its expiry. No new refresh token, no user. */
data class RefreshResult(
    val accessToken: String,
    /** ISO 8601 instant the access token expires. */
    val expiresAt: String,
)

// MARK: - Standard response envelope

internal data class Envelope<T>(val data: T, val meta: Map<String, Any>?)

// MARK: - Lightweight JWT access-token decoder

/**
 * Lightweight access-token decoder. Decodes the JWT payload without
 * signature verification. Custom claims are FLAT top-level keys of the form
 * `ext.<namespace>.<name>` (the dots are part of the key; there is no nested object).
 */
class AccessToken(
    val subject: String?,
    val expiresAt: Long?,
    val issuedAt: Long?,
    val issuer: String?,
    val allClaims: Map<String, Any>,
) {
    /**
     * Custom claims keyed by their full wire key (`ext.<namespace>.<name>`):
     * `customClaims["ext.partners.partner_role"]` → "extension_org_admin".
     */
    val customClaims: Map<String, Any>
        get() = allClaims.filterKeys { it.startsWith("ext.") }

    /** One custom claim by namespace and name: reads the flat key `ext.<namespace>.<name>`. */
    fun customClaim(namespace: String, name: String): Any? = allClaims["ext.$namespace.$name"]

    companion object {
        /** Decode a raw JWT string. Returns null if not a valid 3-part JWT. */
        fun decode(jwt: String): AccessToken? {
            val parts = jwt.split(".")
            if (parts.size != 3) return null
            val payloadB64 = parts[1]
                .replace("-", "+")
                .replace("_", "/")
            val padded = when (payloadB64.length % 4) {
                2 -> "$payloadB64=="
                3 -> "$payloadB64="
                else -> payloadB64
            }
            return try {
                val decoded = java.util.Base64.getDecoder().decode(padded)
                val json = String(decoded, Charsets.UTF_8)
                @Suppress("UNCHECKED_CAST")
                val map = Gson().fromJson<Map<String, Any>>(
                    json,
                    object : TypeToken<Map<String, Any>>() {}.type
                ) ?: return null
                AccessToken(
                    subject = map["sub"] as? String,
                    expiresAt = (map["exp"] as? Double)?.toLong(),
                    issuedAt = (map["iat"] as? Double)?.toLong(),
                    issuer = map["iss"] as? String,
                    allClaims = map,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
