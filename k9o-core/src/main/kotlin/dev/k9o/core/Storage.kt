package dev.k9o.core

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import java.io.File

/** A bucket. */
data class StorageBucket(
    val name: String,
    /** Stored and returned, not enforced: setting it does not yet change who can read objects. */
    val public: Boolean,
    /** Bytes; null means no limit. */
    val fileSizeLimit: Long?,
    /** MIME allow-list; empty means any. */
    val allowedMimeTypes: List<String>,
    val versioning: Boolean,
    val deleteAfterDays: Int?,
    val createdAt: String,
    val updatedAt: String,
    /** Platform-owned buckets cannot be written through the public routes. */
    val system: Boolean,
    /** The storage location the bucket is linked to, or null for platform-managed storage. */
    val byosBindingId: String?,
)

/** An object listing entry. [path] is relative to the bucket. */
data class StorageObject(val path: String, val size: Long, val lastModified: String?)

/** [expiresIn] is the lifetime the gateway used: at most 900 seconds, even when you asked for more. */
data class SignedUrl(val signedUrl: String, val expiresIn: Int)

data class RemovedObject(val bucket: String, val path: String, val deleted: Boolean)

data class DeletedBucket(val name: String, val deleted: Boolean, val objectsDeleted: Int)

private const val MAX_EXPIRES_IN = 604_800

private fun JsonObject.str(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.asString
private fun JsonObject.long(k: String): Long? = get(k)?.takeIf { !it.isJsonNull }?.asLong

private fun bucket(e: JsonElement): StorageBucket {
    val o = e.asJsonObject
    return StorageBucket(
        name = o.get("name").asString,
        public = o.get("public")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
        fileSizeLimit = o.long("fileSizeLimit"),
        allowedMimeTypes = o.get("allowedMimeTypes")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList(),
        versioning = o.get("versioning")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
        deleteAfterDays = o.long("deleteAfterDays")?.toInt(),
        createdAt = o.str("createdAt") ?: "",
        updatedAt = o.str("updatedAt") ?: "",
        system = o.get("system")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
        byosBindingId = o.str("byosBindingId"),
    )
}

private fun obj(env: Envelope<JsonElement>) = env.data.asJsonObject

private fun objectOf(e: JsonElement): StorageObject {
    val o = e.asJsonObject
    return StorageObject(o.get("path").asString, o.long("size") ?: 0L, o.str("lastModified"))
}

private fun bucketPath(http: HttpCore, name: String, suffix: String = ""): String {
    if (name.isEmpty()) throw invalidValue("bucket name must not be empty", mapOf("field" to "bucket", "reason" to "empty"))
    // `.` and `..` survive percent-encoding and would be collapsed as dot segments before the request leaves.
    if (name == "." || name == "..") {
        throw invalidValue("bucket name must not be a dot segment", mapOf("field" to "bucket", "reason" to "dot_segment"))
    }
    return http.projectPath("storage operations", "storage/buckets/${encodeSegment(name)}$suffix")
}

private fun requireObjectPath(path: String) {
    if (path.isEmpty()) throw invalidValue("object path must not be empty", mapOf("field" to "path", "reason" to "empty"))
}

private fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull.INSTANCE
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Iterable<*> -> JsonArray().also { a -> v.forEach { a.add(toJson(it)) } }
    else -> throw invalidValue("bucket option values must be strings, numbers, booleans, lists or null")
}

/** Bucket operations: `core.storage`. */
class Storage internal constructor(private val http: HttpCore) {
    /** `GET /@<projectSlug>/storage/buckets`. */
    suspend fun listBuckets(): Envelope<List<StorageBucket>> {
        val env = http.request("GET", http.projectPath("storage operations", "storage/buckets"))
        return Envelope(env.data.asJsonArray.map(::bucket), env.meta)
    }

    /** `GET …/buckets/<name>`. */
    suspend fun getBucket(name: String): Envelope<StorageBucket> {
        val env = http.request("GET", bucketPath(http, name))
        return Envelope(bucket(env.data), env.meta)
    }

    /**
     * `POST …/buckets`. The name is 3 to 63 lowercase letters, digits and hyphens. Options left null are not sent.
     * [public], [versioning] and [deleteAfterDays] are stored and returned but not enforced yet.
     */
    suspend fun createBucket(
        name: String,
        public: Boolean? = null,
        fileSizeLimit: Long? = null,
        allowedMimeTypes: List<String>? = null,
        versioning: Boolean? = null,
        deleteAfterDays: Int? = null,
        byosBindingId: String? = null,
    ): Envelope<StorageBucket> {
        val body = JsonObject()
        body.addProperty("name", name)
        public?.let { body.addProperty("public", it) }
        fileSizeLimit?.let { body.addProperty("fileSizeLimit", it) }
        allowedMimeTypes?.let { body.add("allowedMimeTypes", toJson(it)) }
        versioning?.let { body.addProperty("versioning", it) }
        deleteAfterDays?.let { body.addProperty("deleteAfterDays", it) }
        byosBindingId?.let { body.addProperty("byosBindingId", it) }
        val env = http.request("POST", http.projectPath("storage operations", "storage/buckets"), body = body)
        return Envelope(bucket(env.data), env.meta)
    }

    /**
     * `PATCH …/buckets/<name>`. [changes] holds only the fields to change (`public`, `fileSizeLimit`,
     * `allowedMimeTypes`, `versioning`, `deleteAfterDays`, `byosBindingId`); a key set to null clears that field.
     */
    suspend fun updateBucket(name: String, changes: Map<String, Any?>): Envelope<StorageBucket> {
        val body = JsonObject()
        changes.forEach { (k, v) -> body.add(k, toJson(v)) }
        val env = http.request("PATCH", bucketPath(http, name), body = body)
        return Envelope(bucket(env.data), env.meta)
    }

    /** `DELETE …/buckets/<name>`. A non-empty bucket is refused unless [empty] is true, which purges its objects first. */
    suspend fun deleteBucket(name: String, empty: Boolean = false): Envelope<DeletedBucket> {
        val env = http.request("DELETE", bucketPath(http, name), if (empty) mapOf("empty" to "true") else emptyMap())
        val o = obj(env)
        return Envelope(
            DeletedBucket(o.get("name").asString, o.get("deleted")?.asBoolean ?: true, o.long("objectsDeleted")?.toInt() ?: 0),
            env.meta,
        )
    }

    /** A handle on one bucket for object operations: `core.storage.from("avatars").upload(...)`. */
    fun from(bucket: String): BucketHandle = BucketHandle(http, bucket)
}

/** Object operations on one bucket. */
class BucketHandle internal constructor(private val http: HttpCore, private val bucket: String) {
    /**
     * `POST …/objects` (multipart). The bytes pass through the gateway, which enforces the bucket's size and MIME
     * limits. [upsert] false (the default) refuses to overwrite an existing path (409). [metadata] is caller-defined
     * string metadata, at most 10 keys and 2 KB in total.
     */
    suspend fun upload(
        path: String,
        data: ByteArray,
        contentType: String? = null,
        upsert: Boolean = false,
        metadata: Map<String, String>? = null,
    ): Envelope<StorageObject> {
        requireObjectPath(path)
        val api = bucketPath(http, bucket, "/objects")
        val parts = mutableListOf(
            FormPart("file", data, filename = path.substringAfterLast('/').ifEmpty { "file" }, contentType = contentType),
            FormPart("path", path.toByteArray()),
        )
        if (contentType != null) parts += FormPart("contentType", contentType.toByteArray())
        parts += FormPart("upsert", (if (upsert) "true" else "false").toByteArray())
        if (metadata != null) {
            val m = JsonObject().also { o -> metadata.forEach { (k, v) -> o.addProperty(k, v) } }
            parts += FormPart("metadata", m.toString().toByteArray())
        }
        val env = http.request("POST", api, parts = parts)
        return Envelope(objectOf(env.data), env.meta)
    }

    suspend fun upload(
        path: String,
        text: String,
        contentType: String? = null,
        upsert: Boolean = false,
        metadata: Map<String, String>? = null,
    ): Envelope<StorageObject> = upload(path, text.toByteArray(Charsets.UTF_8), contentType, upsert, metadata)

    suspend fun upload(
        path: String,
        file: File,
        contentType: String? = null,
        upsert: Boolean = false,
        metadata: Map<String, String>? = null,
    ): Envelope<StorageObject> = upload(path, file.readBytes(), contentType, upsert, metadata)

    /**
     * `POST …/objects/sign`: a pre-authenticated GET URL for one object. [expiresIn] is required (1 to 604800
     * seconds, no default); the URL lives at most 900 seconds. [download] true makes it an attachment.
     */
    suspend fun createSignedUrl(path: String, expiresIn: Int, download: Boolean? = null): Envelope<SignedUrl> {
        requireObjectPath(path)
        if (expiresIn < 1 || expiresIn > MAX_EXPIRES_IN) {
            throw KError(
                code = "K9O_INVALID_FIELD_VALUE",
                message = "expiresIn is required and must be an integer from 1 to $MAX_EXPIRES_IN seconds",
                context = mapOf("field" to "expiresIn", "reason" to "out_of_range"),
                recommendation = "Pass an explicit expiresIn; signed URLs have no default lifetime.",
            )
        }
        val body = JsonObject()
        body.addProperty("path", path)
        body.addProperty("expiresIn", expiresIn)
        download?.let { body.addProperty("download", it) }
        val env = http.request("POST", bucketPath(http, bucket, "/objects/sign"), body = body)
        val o = obj(env)
        return Envelope(SignedUrl(o.get("signedUrl").asString, o.get("expiresIn").asInt), env.meta)
    }

    /** `GET …/objects`, optionally under [prefix]. */
    suspend fun list(prefix: String? = null): Envelope<List<StorageObject>> {
        val env = http.request(
            "GET",
            bucketPath(http, bucket, "/objects"),
            if (prefix != null) mapOf("prefix" to prefix) else emptyMap(),
        )
        return Envelope(env.data.asJsonArray.map(::objectOf), env.meta)
    }

    /** `DELETE …/objects?path=`: delete one object. */
    suspend fun remove(path: String): Envelope<RemovedObject> {
        requireObjectPath(path)
        val env = http.request("DELETE", bucketPath(http, bucket, "/objects"), mapOf("path" to path))
        val o = obj(env)
        return Envelope(
            RemovedObject(o.get("bucket").asString, o.get("path").asString, o.get("deleted")?.asBoolean ?: true),
            env.meta,
        )
    }
}
