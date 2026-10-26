package dev.k9o.gate

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val JSON = "application/json; charset=utf-8".toMediaType()
private val gson = Gson()

/**
 * Internal OkHttp transport layer for K9OGate.
 *
 * Requests go to `<orgBaseUrl>/@<projectSlug>/gate/<suffix>`; `orgBaseUrl` is used as given.
 * All requests are dispatched on `Dispatchers.IO`.
 */
internal class GateAuthClient(
    private val orgBaseUrl: String,
    private val projectSlug: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private fun url(suffix: String) = "$orgBaseUrl/@$projectSlug/gate/$suffix"

    suspend inline fun <reified B, reified R : Any> post(
        path: String,
        body: B,
    ): R = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(body).toRequestBody(JSON)
        val request = Request.Builder()
            .url(url(path))
            .post(requestBody)
            .header("Accept", "application/json")
            .header("User-Agent", "k9o-gate-kotlin")
            .build()
        execute(request)
    }

    suspend inline fun <reified R : Any> get(
        path: String,
        bearerToken: String,
    ): R = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(path))
            .get()
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .header("User-Agent", "k9o-gate-kotlin")
            .build()
        execute(request)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified R : Any> execute(request: Request): R {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw GateError.Network(e)
        }
        val bodyString = response.body?.string()
        if (!response.isSuccessful) {
            throw gateErrorFromResponse(response.code, bodyString)
        }
        return try {
            val envelopeType = object : TypeToken<Envelope<R>>() {}.type
            val envelope = gson.fromJson<Envelope<R>>(bodyString, envelopeType)
            envelope.data
        } catch (e: Exception) {
            throw GateError.Decoding(e)
        }
    }
}

// MARK: - Request body shapes (internal)

internal data class SignInBody(val email: String, val password: String)
/** The refresh body is closed to `{ refreshToken }`; the gateway refuses any other field. */
internal data class RefreshTokenBody(val refreshToken: String)
internal data class SignOutBody(val refreshToken: String)
internal data class SignOutResponse(val revoked: Boolean)
