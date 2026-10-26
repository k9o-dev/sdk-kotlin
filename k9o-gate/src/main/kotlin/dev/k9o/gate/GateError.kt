package dev.k9o.gate

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Closed error taxonomy for K9OGate SDK operations.
 * Maps the gateway KError wire codes to Kotlin sealed class cases.
 */
sealed class GateError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Session token missing, expired, or revoked (HTTP 401). */
    object Unauthenticated : GateError("Session expired or not authenticated.")
    /** Caller lacks permission for the requested operation (HTTP 403). */
    object Forbidden : GateError("Access forbidden for the current session.")
    /** Email/password (or other credential) did not match (HTTP 401 INVALID_CREDENTIALS). */
    object InvalidCredentials : GateError("Invalid credentials.")
    /** Transport-level failure — network unreachable, timeout, etc. */
    class Network(cause: Throwable) : GateError("Network error: ${cause.message}", cause)
    /** Gateway response could not be parsed into the expected shape. */
    class Decoding(cause: Throwable) : GateError("Response decoding error: ${cause.message}", cause)
    /** Any other non-2xx HTTP status not covered by the cases above. */
    class Unknown(val statusCode: Int, val body: String?) :
        GateError("Unexpected error (HTTP $statusCode): ${body ?: "<no body>"}")
}

// MARK: — Wire error envelope

// The gateway emits a KError at the ROOT of the error body:
// { code, message, context?, recommendation? } — NOT wrapped in { error: … }.
private data class WireKError(val code: String?, val message: String?)

internal fun gateErrorFromResponse(statusCode: Int, body: String?): GateError {
    if (body != null) {
        try {
            val wire = Gson().fromJson(body, WireKError::class.java)
            // Wire codes are platform-namespaced (K9O_*); strip to match.
            return when (wire?.code?.removePrefix("K9O_")) {
                "UNAUTHENTICATED", "SESSION_EXPIRED", "MISSING_TOKEN" -> GateError.Unauthenticated
                "FORBIDDEN" -> GateError.Forbidden
                "INVALID_CREDENTIALS" -> GateError.InvalidCredentials
                else -> GateError.Unknown(statusCode, body)
            }
        } catch (_: JsonSyntaxException) {
            // fall through to Unknown
        }
    }
    return GateError.Unknown(statusCode, body)
}
