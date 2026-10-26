package dev.k9o.core

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

internal fun invalidValue(message: String, context: Map<String, Any?> = emptyMap()) =
    KError(code = "K9O_INVALID_FIELD_VALUE", message = message, context = context)

private val SLUG_RE = Regex("^[a-z][a-z0-9-]{1,62}$")

/** `/@<projectSlug>/<sub>`; refuses a client with no bound project, or a malformed project slug. */
internal fun HttpCore.projectPath(what: String, sub: String): String {
    val slug = projectSlug
    if (slug.isNullOrEmpty()) {
        throw KError(
            code = "K9O_MISSING_PROJECT_CONTEXT",
            message = "$what require projectSlug bound on the client",
            recommendation = "Construct K9OCore with projectSlug.",
        )
    }
    if (!SLUG_RE.matches(slug) || slug.endsWith("-")) {
        throw invalidValue(
            "projectSlug \"$slug\" does not match required format ^[a-z][a-z0-9-]{1,62}$ (must not end with '-')",
            mapOf("slug" to slug, "kind" to "project"),
        )
    }
    return "/@$slug/$sub"
}

/** Render a number the way JSON text from JavaScript would: whole values without a fraction. */
internal fun numberText(n: Number): String = when (n) {
    is Double -> if (n.isFinite() && n == Math.floor(n) && Math.abs(n) < 1e15) n.toLong().toString() else n.toString()
    is Float -> numberText(n.toDouble())
    else -> n.toString()
}

private fun JsonElement.asRecord(url: String): JsonObject =
    if (isJsonObject) asJsonObject else throw KError(
        code = "K9O_INTERNAL_ERROR",
        message = "Malformed envelope from $url: expected a record in data",
    )

private fun Envelope<JsonElement>.rows(): Envelope<List<JsonObject>> {
    val d = data
    if (!d.isJsonArray) {
        throw KError(code = "K9O_INTERNAL_ERROR", message = "Malformed envelope: expected an array in data")
    }
    return Envelope(d.asJsonArray.map { it.asJsonObject }, meta)
}

/** Total-count behaviour for a list: `NONE` skips the count, `EXACT` counts every row, `ESTIMATED` is fast and approximate. */
enum class CountMode(internal val wire: String) { NONE("none"), EXACT("exact"), ESTIMATED("estimated") }

/** Query syntax for [SelectBuilder.textSearch]. */
enum class TextSearchType(internal val wire: String) { PLAIN("plain"), PHRASE("phrase"), WEBSEARCH("websearch") }

/** Distance measure for [SelectBuilder.vectorSearch]. */
enum class VectorMetric(internal val wire: String) { COSINE("cosine"), EUCLIDEAN("euclidean"), DOT("dot") }

private class Filter(val column: String, val op: String, val value: Any)

private fun scalarText(value: Any, op: String, column: String): String = when (value) {
    is String -> value
    is Boolean -> value.toString()
    is Number -> numberText(value)
    else -> throw invalidValue(
        "filter value for '$column' ($op) must be a string, number or boolean",
        mapOf("column" to column),
    )
}

private fun filterText(f: Filter): String {
    val v = f.value
    return if (v is Iterable<*>) {
        "(" + v.joinToString(",") { scalarText(it ?: "null", f.op, f.column) } + ")"
    } else {
        scalarText(v, f.op, f.column)
    }
}

/** Filters, page size, cursor and count, shared by select, update and delete. */
abstract class QueryBuilder<B : QueryBuilder<B>> internal constructor() {
    private val filters = mutableListOf<Filter>()
    private var limitValue: Int? = null
    private var cursorValue: String? = null
    private var countMode: CountMode? = null

    @Suppress("UNCHECKED_CAST")
    private fun self(): B = this as B

    private fun add(column: String, op: String, value: Any): B {
        val f = Filter(column, op, value)
        if (op != "in" && op != "notIn" && value is Iterable<*>) scalarText(Unit, op, column) // a list only suits in/notIn
        filterText(f) // refuse an unusable value now, not at execute()
        filters += f
        return self()
    }

    /** Equality filter; filters combine with AND. [value] is a String, Number or Boolean. */
    fun eq(column: String, value: Any): B = add(column, "eq", value)
    fun neq(column: String, value: Any): B = add(column, "neq", value)
    fun gt(column: String, value: Any): B = add(column, "gt", value)
    fun gte(column: String, value: Any): B = add(column, "gte", value)
    fun lt(column: String, value: Any): B = add(column, "lt", value)
    fun lte(column: String, value: Any): B = add(column, "lte", value)

    /** Membership filter: the column equals any of [values] (Strings or Numbers). */
    fun `in`(column: String, values: List<Any>): B = add(column, "in", values)
    fun notIn(column: String, values: List<Any>): B = add(column, "notIn", values)
    fun like(column: String, pattern: String): B = add(column, "like", pattern)
    fun ilike(column: String, pattern: String): B = add(column, "ilike", pattern)

    /** `value` is `"null"`, `"true"` or `"false"`. */
    fun `is`(column: String, value: String): B = add(column, "is", value)

    /** Explicit operator: `eq neq gt gte lt lte in notIn like ilike is`. */
    fun filter(column: String, op: String, value: Any): B = add(column, op, value)

    /** Page size. */
    fun limit(n: Int): B {
        limitValue = n
        return self()
    }

    /** Opaque cursor from a previous response's `meta["cursor"]` (`next`). */
    fun cursor(opaque: String): B {
        cursorValue = opaque
        return self()
    }

    fun count(mode: CountMode): B {
        countMode = mode
        return self()
    }

    internal fun idFilter(): Any? = filters.firstOrNull { it.column == "id" && it.op == "eq" }?.value

    internal open fun toQuery(): LinkedHashMap<String, String> {
        val q = LinkedHashMap<String, String>()
        for (f in filters) q[f.column] = "${f.op}.${filterText(f)}"
        limitValue?.let { q["limit"] = it.toString() }
        cursorValue?.let { q["cursor"] = it }
        countMode?.let { q["count"] = it.wire }
        return q
    }
}

private val SEARCH_COLUMN = Regex("^[a-zA-Z_][a-zA-Z0-9_]{0,62}$")
private val SEARCH_LANGUAGE = Regex("^[a-zA-Z_]+$")
private val COLLECTION = Regex("^[a-z][a-z0-9_-]{0,62}$")

private fun collectionPath(http: HttpCore, collection: String): String {
    val path = http.projectPath("data operations", "data/$collection")
    if (!COLLECTION.matches(collection)) {
        throw KError(
            code = "K9O_INVALID_FIELD_VALUE",
            message = "collection name \"$collection\" does not match required format",
            context = mapOf("collection" to collection),
            recommendation = "Use lowercase letters / digits / underscores / hyphens; start with a letter; 1-63 chars.",
        )
    }
    return path
}

/** A list query on one collection. Call [execute] to run it. */
class SelectBuilder internal constructor(
    private val http: HttpCore,
    private val collection: String,
) : QueryBuilder<SelectBuilder>() {
    private val search = LinkedHashMap<String, String>()

    /**
     * Full-text search over one text column; composes with filters, [limit] and [cursor]. Results come back best
     * match first. [language] is a Postgres text-search configuration name.
     */
    fun textSearch(
        column: String,
        query: String,
        language: String = "english",
        type: TextSearchType = TextSearchType.PLAIN,
    ): SelectBuilder {
        if (!SEARCH_COLUMN.matches(column)) throw invalidValue("textSearch column '$column' is not a valid identifier")
        if (query.isEmpty()) throw invalidValue("textSearch query must be non-empty")
        if (!SEARCH_LANGUAGE.matches(language)) {
            throw invalidValue("textSearch language must be a text-search configuration name")
        }
        search["textSearch"] = "$column:$query"
        if (language != "english") search["textSearchLanguage"] = language else search.remove("textSearchLanguage")
        if (type != TextSearchType.PLAIN) search["textSearchType"] = type.wire else search.remove("textSearchType")
        return this
    }

    /**
     * Semantic search: the rows nearest to [embedding] in one vector column, closest first. [limit] is 1 to 1000
     * (the gateway default is 10 when omitted).
     */
    fun vectorSearch(
        column: String,
        embedding: List<Number>,
        metric: VectorMetric = VectorMetric.COSINE,
        limit: Int? = null,
    ): SelectBuilder {
        if (!SEARCH_COLUMN.matches(column)) throw invalidValue("vectorSearch column '$column' is not a valid identifier")
        if (embedding.isEmpty() || embedding.any { (it is Double && !it.isFinite()) || (it is Float && !it.isFinite()) }) {
            throw invalidValue("vectorSearch embedding must be a non-empty list of numbers")
        }
        if (limit != null && (limit < 1 || limit > 1000)) {
            throw invalidValue("vectorSearch limit must be an integer from 1 to 1000")
        }
        search["vectorSearch"] = "$column:[" + embedding.joinToString(",") { numberText(it) } + "]"
        if (metric != VectorMetric.COSINE) search["vectorMetric"] = metric.wire else search.remove("vectorMetric")
        if (limit != null) search["vectorLimit"] = limit.toString() else search.remove("vectorLimit")
        return this
    }

    override fun toQuery(): LinkedHashMap<String, String> {
        val q = super.toQuery()
        q.putAll(search)
        return q
    }

    /** `GET /@<projectSlug>/data/<collection>`; `data` is the list of records. */
    suspend fun execute(): Envelope<List<JsonObject>> =
        http.request("GET", collectionPath(http, collection), toQuery()).rows()
}

/** An update of one record, chosen with `.eq("id", ...)`. Call [execute] to run it. */
class UpdateBuilder internal constructor(
    private val http: HttpCore,
    private val collection: String,
    private val patch: JsonObject,
) : QueryBuilder<UpdateBuilder>() {
    /** `PATCH /@<projectSlug>/data/<collection>/<id>`; `data` is the updated record. */
    suspend fun execute(): Envelope<JsonObject> {
        val id = idFilter() ?: throw KError(
            code = "K9O_INVALID_FIELD_VALUE",
            message = "update() requires an `.eq(\"id\", \"...\")` filter; changing many rows in one call is not supported",
            context = mapOf("collection" to collection),
            recommendation = "Chain `.eq(\"id\", \"rec_...\")` before execute(); to change several rows, call update once per id.",
        )
        val path = collectionPath(http, collection) + "/" + encodeSegment(scalarText(id, "eq", "id"))
        val env = http.request("PATCH", path, body = patch)
        return Envelope(env.data.asRecord(path), env.meta)
    }
}

/** A delete of one record, chosen with `.eq("id", ...)`. Call [execute] to run it. */
class DeleteBuilder internal constructor(
    private val http: HttpCore,
    private val collection: String,
) : QueryBuilder<DeleteBuilder>() {
    /** `DELETE /@<projectSlug>/data/<collection>/<id>`; `data` is the deleted record. */
    suspend fun execute(): Envelope<JsonObject> {
        val id = idFilter() ?: throw KError(
            code = "K9O_INVALID_FIELD_VALUE",
            message = "delete() requires an `.eq(\"id\", \"...\")` filter; deleting many rows in one call is not supported",
            context = mapOf("collection" to collection),
            recommendation = "Chain `.eq(\"id\", \"rec_...\")` before execute(); to delete several rows, call delete once per id.",
        )
        val path = collectionPath(http, collection) + "/" + encodeSegment(scalarText(id, "eq", "id"))
        val env = http.request("DELETE", path)
        return Envelope(env.data.asRecord(path), env.meta)
    }
}

private val gsonTree = Gson()

/** Entry point of the data builder: `core.from("posts")`. */
class FromBuilder internal constructor(private val http: HttpCore, private val collection: String) {
    /** Start a list query. */
    fun select(): SelectBuilder = SelectBuilder(http, collection)

    /** `POST /@<projectSlug>/data/<collection>`; returns the stored record with its generated `id` and metadata. */
    suspend fun insert(record: JsonObject): Envelope<JsonObject> {
        val path = collectionPath(http, collection)
        val env = http.request("POST", path, body = record)
        return Envelope(env.data.asRecord(path), env.meta)
    }

    suspend fun insert(record: Map<String, Any?>): Envelope<JsonObject> = insert(gsonTree.toJsonTree(record).asJsonObject)

    /** Start an update of one record; chain `.eq("id", ...)` and call `execute()`. */
    fun update(patch: JsonObject): UpdateBuilder = UpdateBuilder(http, collection, patch)

    fun update(patch: Map<String, Any?>): UpdateBuilder = UpdateBuilder(http, collection, gsonTree.toJsonTree(patch).asJsonObject)

    /** Start a delete of one record; chain `.eq("id", ...)` and call `execute()`. */
    fun delete(): DeleteBuilder = DeleteBuilder(http, collection)
}

// ---- query(sql, params) ----

private fun bindable(value: Any?, where: String): JsonElement = when {
    value == null -> JsonNull.INSTANCE
    value is String -> JsonPrimitive(value)
    value is Boolean -> JsonPrimitive(value)
    value is Number && !(value is Double && !value.isFinite()) && !(value is Float && !value.isFinite()) -> JsonPrimitive(value)
    else -> throw invalidValue(
        "$where must be a string, number, boolean or null; serialise anything else (for example as JSON text) first",
    )
}

private fun isIdentChar(c: Char) = c == '_' || c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
private fun isIdentStart(c: Char) = c == '_' || c in 'a'..'z' || c in 'A'..'Z'

/** Result of [translateNamedParams]. */
internal class Translated(val sql: String, val params: List<JsonElement>)

/**
 * Rewrite `:name` placeholders to `$n`. Skips quoted text, comments, dollar-quoted strings and `::` casts, so
 * `data->>'role' = :role` and `x::int` come out right.
 */
internal fun translateNamedParams(sql: String, named: Map<String, Any?>): Translated {
    val order = mutableListOf<String>()
    val out = StringBuilder()
    val n = sql.length
    var i = 0
    while (i < n) {
        val c = sql[i]
        val next = if (i + 1 < n) sql[i + 1] else null
        if (c == '\'' || ((c == 'E' || c == 'e') && next == '\'' && !(i > 0 && isIdentChar(sql[i - 1])))) {
            val escapes = c != '\''
            var j = if (escapes) i + 2 else i + 1
            while (j < n) {
                if (escapes && sql[j] == '\\') { j += 2; continue }
                if (sql[j] == '\'') {
                    if (j + 1 < n && sql[j + 1] == '\'') { j += 2; continue }
                    break
                }
                j++
            }
            val end = minOf(j + 1, n)
            out.append(sql, i, end)
            i = end
        } else if (c == '"') {
            var j = i + 1
            while (j < n) {
                if (sql[j] == '"') {
                    if (j + 1 < n && sql[j + 1] == '"') { j += 2; continue }
                    break
                }
                j++
            }
            val end = minOf(j + 1, n)
            out.append(sql, i, end)
            i = end
        } else if (c == '-' && next == '-') {
            var j = sql.indexOf('\n', i)
            if (j == -1) j = n
            out.append(sql, i, j)
            i = j
        } else if (c == '/' && next == '*') {
            var depth = 1
            var j = i + 2
            while (j < n && depth > 0) {
                if (sql[j] == '/' && j + 1 < n && sql[j + 1] == '*') { depth++; j += 2 }
                else if (sql[j] == '*' && j + 1 < n && sql[j + 1] == '/') { depth--; j += 2 }
                else j++
            }
            out.append(sql, i, j)
            i = j
        } else if (c == '$') {
            var j = i + 1
            if (j < n && isIdentStart(sql[j])) {
                while (j < n && isIdentChar(sql[j])) j++
            }
            if (j < n && sql[j] == '$') {
                val tag = sql.substring(i, j + 1)
                val close = sql.indexOf(tag, i + tag.length)
                val end = if (close == -1) n else close + tag.length
                out.append(sql, i, end)
                i = end
            } else {
                out.append(c)
                i++
            }
        } else if (c == ':' && next == ':') {
            out.append("::")
            i += 2
        } else if (c == ':' && next != null && isIdentStart(next) && !(i > 0 && isIdentChar(sql[i - 1]))) {
            var j = i + 2
            while (j < n && isIdentChar(sql[j])) j++
            val name = sql.substring(i + 1, j)
            var idx = order.indexOf(name)
            if (idx == -1) {
                if (!named.containsKey(name)) throw invalidValue("no value for :$name", mapOf("name" to name))
                order += name
                idx = order.size - 1
            }
            out.append('$').append(idx + 1)
            i = j
        } else {
            out.append(c)
            i++
        }
    }
    return Translated(out.toString(), order.map { bindable(named[it], "the value for :$it") })
}

internal class Querier(private val http: HttpCore) {
    suspend fun query(sql: String, params: List<Any?>?, named: Map<String, Any?>?): Envelope<List<JsonObject>> {
        val path = http.projectPath("query", "query")
        var text = sql
        var values: List<JsonElement> = emptyList()
        if (params != null) {
            values = params.mapIndexed { i, v -> bindable(v, "params[$i]") }
        } else if (named != null) {
            val t = translateNamedParams(sql, named)
            text = t.sql
            values = t.params
        }
        val body = JsonObject()
        body.addProperty("sql", text)
        if (values.isNotEmpty()) body.add("params", JsonArray().also { a -> values.forEach(a::add) })
        return http.request("POST", path, body = body).rows()
    }

    suspend fun search(
        collection: String,
        column: String,
        q: String,
        language: String?,
        type: TextSearchType?,
        limit: Int?,
        cursor: String?,
    ): Envelope<List<JsonObject>> {
        var b = SelectBuilder(http, collection).textSearch(column, q, language ?: "english", type ?: TextSearchType.PLAIN)
        if (limit != null) b = b.limit(limit)
        if (cursor != null) b = b.cursor(cursor)
        return b.execute()
    }
}
