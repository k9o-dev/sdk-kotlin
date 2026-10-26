package dev.k9o.kibo.vault

/**
 * Error type for [VaultClient.secrets] operations.
 *
 * [VaultError.Api] is OPEN: it carries whatever `code` the gateway's wire `KError` sends verbatim
 * (e.g. `K9O_REVEAL_CHALLENGE_REQUIRED`, `K9O_INVALID_FIELD_VALUE`, `K9O_NOT_FOUND`). Callers narrow on
 * [Api.code] by string comparison rather than an exhaustive `when`.
 */
sealed class VaultError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * Any non-2xx gateway response. [code] is the wire `KError.code` VERBATIM (e.g.
     * `K9O_INVALID_FIELD_VALUE`, `K9O_REVEAL_CHALLENGE_REQUIRED`) — NOT stripped of its `K9O_` prefix
     * the way `dev.k9o.gate.GateError`'s internal matching does, since there is no curated closed set
     * here to match a stripped code against.
     */
    class Api(
        val code: String,
        val statusCode: Int,
        detail: String,
        val context: Map<String, Any?> = emptyMap(),
        val recommendation: String? = null,
    ) : VaultError(detail)

    /** Transport-level failure — network unreachable, timeout, etc. */
    class Network(cause: Throwable) : VaultError("Network error: ${cause.message}", cause)

    /** Gateway response could not be parsed into the expected shape (or a malformed/missing envelope,
     *  or an unexpected 204 where a body was expected). */
    class Decoding(cause: Throwable) : VaultError("Response decoding error: ${cause.message}", cause)
}
