package dev.k9o.core

/**
 * Closed-taxonomy error thrown by every core surface. Wire shape is FLAT JSON
 * `{code, message, context, recommendation}` on non-2xx. Request bodies are never placed in the message.
 */
class KError(
    val code: String,
    message: String,
    val context: Map<String, Any?> = emptyMap(),
    val recommendation: String? = null,
    val status: Int? = null,
    val requestId: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    override fun toString(): String = "KError($code): $message"
}
