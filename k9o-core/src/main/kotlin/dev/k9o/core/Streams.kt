package dev.k9o.core

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** A user stream, as the gateway returns it. */
data class StreamRecord(
    val id: String,
    val slug: String,
    val projectId: String,
    val displayName: String,
    val subjectPrefix: String,
    val region: String,
    val retentionSeconds: Long,
    val retentionClass: String,
    val maxBytes: Long?,
    val status: String,
    val createdAt: String,
    val createdBy: String?,
)

data class PublishResult(val streamSlug: String, val subject: String, val bytes: Long, val accepted: Boolean)

data class RetiredStream(val slug: String, val status: String)

/** One event on a stream. [sequence] is its position in the stream; [data] is the published payload. */
data class StreamEvent(val sequence: String, val subject: String, val data: JsonElement, val publishedAt: String)

/**
 * A page of events. [nextCursor] is the sequence to pass as `fromSequence` for the next page, or null when the
 * stream is empty. [hasMore] is true when the page is full and there may be more events.
 */
data class ReplayResult(
    val streamSlug: String,
    val events: List<StreamEvent>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

private const val DEFAULT_RECONNECT_MS = 1000L
private const val MAX_RECONNECT_MS = 30_000L

private fun JsonObject.str(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.asString
private fun JsonObject.long(k: String): Long? = get(k)?.takeIf { !it.isJsonNull }?.asLong

private fun record(e: JsonElement): StreamRecord {
    val o = e.asJsonObject
    return StreamRecord(
        id = o.str("id") ?: "",
        slug = o.get("slug").asString,
        projectId = o.str("projectId") ?: "",
        displayName = o.str("displayName") ?: "",
        subjectPrefix = o.str("subjectPrefix") ?: "",
        region = o.str("region") ?: "",
        retentionSeconds = o.long("retentionSeconds") ?: 0L,
        retentionClass = o.str("retentionClass") ?: "",
        maxBytes = o.long("maxBytes"),
        status = o.str("status") ?: "",
        createdAt = o.str("createdAt") ?: "",
        createdBy = o.str("createdBy"),
    )
}

private fun event(o: JsonObject, sequence: String?): StreamEvent = StreamEvent(
    sequence = sequence ?: o.get("sequence")?.takeIf { !it.isJsonNull }?.asString ?: "",
    subject = o.str("subject") ?: "",
    data = o.get("data") ?: JsonNull.INSTANCE,
    publishedAt = o.str("publishedAt") ?: "",
)

private class Frame(val id: String?, val data: String?, val retry: Long?)

/** Read one SSE frame (up to a blank line); null at end of input. Comment lines and unknown fields are ignored. */
private fun readFrame(reader: BufferedReader): Frame? {
    var id: String? = null
    var retry: Long? = null
    val data = mutableListOf<String>()
    var seen = false
    while (true) {
        val line = reader.readLine() ?: return if (seen) Frame(id, if (data.isEmpty()) null else data.joinToString("\n"), retry) else null
        if (line.isEmpty()) {
            if (seen) return Frame(id, if (data.isEmpty()) null else data.joinToString("\n"), retry)
            continue
        }
        if (line.startsWith(":")) continue
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        val value = if (colon == -1) "" else line.substring(colon + 1).removePrefix(" ")
        when {
            field == "data" -> data += value
            field == "id" -> id = value
            field == "retry" && value.isNotEmpty() && value.all { it in '0'..'9' } -> retry = value.toLongOrNull()
            field == "event" -> {}
            else -> continue
        }
        seen = true
    }
}

/** User data streams: `core.streams`. */
class Streams internal constructor(private val http: HttpCore) {
    private val gson = Gson()

    private fun path(slug: String?, tail: String = ""): String {
        if (slug != null && slug.isEmpty()) throw invalidValue("stream slug must not be empty", mapOf("field" to "slug"))
        val sub = "data-streams/user" + (if (slug == null) "" else "/" + encodeSegment(slug)) + tail
        return http.projectPath("streams operations", sub)
    }

    /**
     * `POST /@<projectSlug>/data-streams/user`. [displayName] is 1 to 80 characters. [maxBytes] is the reserved storage
     * ceiling (1, 2, 4, 8, 16, 32 or 64 GiB). [retentionSeconds] is whole hours of seconds (3600 or more); omit for
     * the 24 hour default. Needs project admin.
     */
    suspend fun create(displayName: String, maxBytes: Long, retentionSeconds: Long? = null): Envelope<StreamRecord> {
        val body = JsonObject()
        body.addProperty("displayName", displayName)
        body.addProperty("maxBytes", maxBytes)
        retentionSeconds?.let { body.addProperty("retentionSeconds", it) }
        val env = http.request("POST", path(null), body = body)
        return Envelope(record(env.data), env.meta)
    }

    /** `GET …/data-streams/user`: the project's active streams. */
    suspend fun list(): Envelope<List<StreamRecord>> {
        val env = http.request("GET", path(null))
        return Envelope(env.data.asJsonArray.map(::record), env.meta)
    }

    /** `GET …/data-streams/user/<slug>`. */
    suspend fun get(slug: String): Envelope<StreamRecord> {
        val env = http.request("GET", path(slug))
        return Envelope(record(env.data), env.meta)
    }

    /** `DELETE …/data-streams/user/<slug>`: retire a stream; it reads as absent afterwards. Needs project admin. */
    suspend fun delete(slug: String): Envelope<RetiredStream> {
        val env = http.request("DELETE", path(slug))
        val o = env.data.asJsonObject
        return Envelope(RetiredStream(o.get("slug").asString, o.str("status") ?: ""), env.meta)
    }

    /**
     * `POST …/publish`: append an event. [data] is any JSON value. Returns once the gateway has accepted it
     * (HTTP 202). [subject] is a routing suffix of up to 8 dot-separated tokens of `[a-z0-9_-]`. Needs project admin.
     */
    suspend fun publish(slug: String, data: JsonElement, subject: String? = null): Envelope<PublishResult> {
        val body = JsonObject()
        body.add("data", data)
        subject?.let { body.addProperty("subject", it) }
        val env = http.request("POST", path(slug, "/publish"), body = body)
        val o = env.data.asJsonObject
        return Envelope(
            PublishResult(
                o.str("streamSlug") ?: slug,
                o.str("subject") ?: "",
                o.long("bytes") ?: 0L,
                o.get("accepted")?.asBoolean ?: true,
            ),
            env.meta,
        )
    }

    suspend fun publish(slug: String, data: Map<String, Any?>, subject: String? = null): Envelope<PublishResult> =
        publish(slug, gson.toJsonTree(data), subject)

    suspend fun publish(slug: String, text: String, subject: String? = null): Envelope<PublishResult> =
        publish(slug, com.google.gson.JsonPrimitive(text), subject)

    /**
     * `POST …/replay`: read a bounded range of events. [fromSequence] is exclusive (events after it);
     * [untilSequence] stops at that sequence; [limit] is 1 to 10000 (the gateway default is 100). Only the given
     * fields are sent. Needs project admin or auditor.
     */
    suspend fun replay(
        slug: String,
        fromSequence: String? = null,
        untilSequence: String? = null,
        limit: Int? = null,
    ): Envelope<ReplayResult> {
        val body = JsonObject()
        fromSequence?.let { body.addProperty("fromSequence", it) }
        untilSequence?.let { body.addProperty("untilSequence", it) }
        limit?.let { body.addProperty("limit", it) }
        val env = http.request("POST", path(slug, "/replay"), body = body)
        val o = env.data.asJsonObject
        return Envelope(
            ReplayResult(
                o.str("streamSlug") ?: slug,
                o.get("events")?.takeIf { it.isJsonArray }?.asJsonArray?.map { event(it.asJsonObject, null) } ?: emptyList(),
                o.str("nextCursor"),
                o.get("hasMore")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            ),
            env.meta,
        )
    }

    /**
     * `GET …/subscribe` (server-sent events): tail a stream live as a cold [Flow]. The connection reopens by itself
     * from the last sequence received whenever the server closes it. [fromSequence] is exclusive; omit it to start
     * from the beginning. The flow ends when the collector is cancelled; a non-2xx answer (for example 503) is
     * thrown as a [KError]. Needs project admin or auditor.
     */
    fun subscribe(slug: String, fromSequence: String? = null): Flow<StreamEvent> {
        val api = path(slug, "/subscribe")
        return channelFlow {
            var cursor = fromSequence
            var wait = DEFAULT_RECONNECT_MS
            while (isActive) {
                val response = http.openStream(api, if (cursor == null) emptyMap() else mapOf("fromSequence" to cursor))
                val handle = coroutineContext.job.invokeOnCompletion { runCatching { response.close() } }
                try {
                    val reader = BufferedReader(response.reader)
                    withContext(Dispatchers.IO) {
                        while (true) {
                            val frame = readFrame(reader) ?: break
                            frame.retry?.let { wait = minOf(it, MAX_RECONNECT_MS) }
                            val text = frame.data ?: continue
                            val parsed = try {
                                JsonParser.parseString(text)
                            } catch (e: Exception) {
                                continue
                            }
                            if (!parsed.isJsonObject || !parsed.asJsonObject.has("data")) continue
                            val ev = event(parsed.asJsonObject, frame.id)
                            if (ev.sequence.isNotEmpty()) cursor = ev.sequence
                            send(ev)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    // A break in the connection is treated like the server closing it, unless we were cancelled.
                    if (!isActive) return@channelFlow
                } finally {
                    handle.dispose()
                    runCatching { response.close() }
                }
                delay(wait)
            }
        }
    }
}
