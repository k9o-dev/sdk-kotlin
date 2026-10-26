# dev.k9o:core-kotlin

Call Kilimanjaro from your Android and Kotlin app. Run AI inference and create embeddings, query and search your data, store files, and publish to and subscribe to streams.

## Contents

- [Install](#install)
- [Quick start](#quick-start)
- [Query with SQL](#query-with-sql)
- [Search](#search)
- [Data](#data)
- [Storage](#storage)
- [Streams](#streams)
- [Errors](#errors)
- [Appendix: links](#appendix-links)
- [About Kilimanjaro](#about-kilimanjaro)

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.k9o:core-kotlin:1.4.0")
}
```

Requires JVM 17. On Android, API 23 or later. Pure Kotlin, so it runs on servers and on Android.

## Quick start

```kotlin
import dev.k9o.core.K9OCore

val core = K9OCore(
    baseUrl = "https://acme.api.k9o.io",
    projectSlug = "member-portal",
    accessToken = memberToken,            // or tokenProvider = { fetchToken() }
)

// suspend function: call it from a coroutine
val env = core.ai.embed(input = listOf("search_document: a", "search_document: b"))
println("Output: ${env.data.embeddings.size}")
println("Output: ${env.data.dimensions}")
```

```text
Output: 2
Output: 768
```

`embeddings` holds one vector per input, in the order you sent them. `dimensions` is the length of each vector.
The input can be one `String` or a `List<String>`.

Call `ai.embed` with a project member's or an extension's token, typically from a backend. The gateway refuses an end user's token.

### Generate text

```kotlin
val reply = core.ai.generate(
    "Summarize in one line: ship the README today.",
    maxTokens = 100,
)
println("Output: ${reply.data.content}")
println("Output: Used ${reply.data.usage.totalTokens} tokens")
```

```text
Output: The README ships today.
Output: Used 31 tokens
```

`prompt` is required. `model`, `maxTokens` and `temperature` are optional. When `model` is omitted the org's default LLM is used,
and the call fails with 400 if the org has none. `finishReason` says why the model stopped.
Use the token of a project author or admin. Models served by an external provider answer `K9O_NOT_IMPLEMENTED` (501) until that provider is built.

## Query with SQL

```kotlin
val rows = core.query(
    "SELECT id, data->>'role' AS role FROM users WHERE data->>'role' = :role",
    mapOf("role" to "admin"),
)
println("Output: ${rows.data.size}")
println("Output: ${rows.data.first().get("role").asString}")
```

```text
Output: 2
Output: admin
```

A query is read-only: it runs one `SELECT` statement per call, and a write is refused with `K9O_RAW_SQL_READ_ONLY` (change data through `core.from(...)`).
Row-level security applies, and the caller needs the project-editor role. A statement can hold at most 100000 characters and 1000 parameters.
`dataset.<name>` reads an analytics dataset. `data` is a `List<JsonObject>`, one per row.

`params` is either a `Map` of named values or a `List` of positional values:

- Named: each `:name` in the SQL is rewritten to `$1, $2, ...` by the client, in order of first use. Quoted text, comments, dollar-quoted strings and `::` casts are left alone.
- Positional: the SQL is sent as written and the list binds to `$1, $2, ...`.

Values are `String`, `Number`, `Boolean` or `null`; serialise anything else (for example as JSON text) first. A `:name` with no value throws `KError` `K9O_INVALID_FIELD_VALUE` before any request.

```kotlin
val n = core.query("SELECT count(*) AS n FROM orders WHERE status = $1", listOf("open"))
println("Output: ${n.data.first().get("n").asInt}")
```

```text
Output: 14
```

## Search

```kotlin
val hits = core.search("articles", column = "body", q = "climate resilience", limit = 5)
println("Output: ${hits.data.size}")
println("Output: ${hits.data.first().get("title").asString}")
```

```text
Output: 5
Output: Building climate resilience
```

`search` runs a full-text search over one text column, best matches first. `column` and `q` are required; `language` (default `english`),
`type` (`TextSearchType.PLAIN`, `PHRASE` or `WEBSEARCH`), `limit` and `cursor` are optional. Page with `meta["cursor"]` (`next`).

Semantic search on the data builder returns the rows nearest to an embedding, closest first:

```kotlin
val near = core.from("articles").select()
    .vectorSearch("embedding", queryVector, metric = VectorMetric.COSINE, limit = 3)
    .execute()
println("Output: ${near.data.size}")
```

```text
Output: 3
```

`metric` is `COSINE` (default), `EUCLIDEAN` or `DOT`; `limit` is 1 to 1000 (the gateway default is 10). `textSearch(column, query, language, type)` on the builder composes with filters.
A bad column name, an empty query or embedding, or an out-of-range limit throws `K9O_INVALID_FIELD_VALUE` before any request.

## Data

```kotlin
val created = core.from("posts").insert(mapOf("title" to "Hello", "status" to "open"))
val id = created.data.get("id").asString

val page = core.from("posts").select()
    .eq("status", "open").gte("views", 10).limit(20).count(CountMode.EXACT)
    .execute()
println("Output: ${page.data.size}")

core.from("posts").update(mapOf("status" to "closed")).eq("id", id).execute()
core.from("posts").delete().eq("id", id).execute()
```

```text
Output: 1
```

Filters (`eq neq gt gte lt lte in notIn like ilike is`) combine with AND. `in` and `is` are Kotlin keywords, so write them in backticks:
``.`in`("status", listOf("open", "new"))``. `update` and `delete` change one record and need `.eq("id", ...)`.
`limit`, `cursor(meta cursor.next)` and `count(CountMode.NONE | EXACT | ESTIMATED)` page the list. Records are Gson `JsonObject`s.

## Storage

```kotlin
val avatars = core.storage.from("avatars")
avatars.upload("users/42.png", pngBytes, contentType = "image/png", upsert = true)

val url = avatars.createSignedUrl("users/42.png", expiresIn = 600)
println("Output: ${url.data.expiresIn}")
println("Output: ${avatars.list(prefix = "users/").data.size}")
avatars.remove("users/42.png")
```

```text
Output: 600
Output: 1
```

`core.storage` manages buckets: `listBuckets`, `getBucket`, `createBucket`, `updateBucket` (a key set to `null` clears that field) and `deleteBucket(name, empty = true)`.
`upload` takes a `ByteArray`, a `String` or a `File`; without `upsert = true` an existing path is refused (409).
`expiresIn` is required (1 to 604800 seconds); a signed URL never lives longer than 900 seconds, and the answer reports the lifetime you got.

## Streams

```kotlin
core.streams.create("Orders", maxBytes = 1_073_741_824)
core.streams.publish("orders", mapOf("id" to 7), subject = "new")

val page = core.streams.replay("orders", limit = 10)
println("Output: ${page.data.events.size}")

core.streams.subscribe("orders", fromSequence = page.data.nextCursor).collect { event ->
    println("Output: ${event.sequence} ${event.data}")
}
```

```text
Output: 1
Output: 2 {"id":8}
```

`create`, `list`, `get`, `delete`, `publish` and `replay` are suspend functions. `subscribe` returns a cold `Flow<StreamEvent>` that reads server-sent events
and reopens the connection from the last sequence received whenever the server closes it; cancel the collector to stop.
`fromSequence` is exclusive: you get events after it. Publishing needs project admin; replay and subscribe need project admin or auditor.
A `503` with `K9O_STREAM_UNAVAILABLE` means streams are not available in this deployment.

## Errors

Failures are thrown as `KError`:

| Property | Meaning |
|---|---|
| `code` | Stable machine-readable code |
| `message` | What happened |
| `recommendation` | What to do next, when there is one |
| `status` | HTTP status, when the failure came from the server |
| `requestId` | The request id the server reported, when there is one |
| `context` | Extra detail; never contains your input |

## Appendix: links

- Website: <https://kilimanjaro.dev>
- Documentation: <https://docs.kilimanjaro.dev>
- Other SDKs: <https://github.com/k9o-dev>
- License: Apache-2.0

### Methods

| Method | Meaning |
|---|---|
| `ai.embed(input, model?)` | Embed one `String` or a `List<String>`; returns `embeddings`, `model` and `dimensions` |
| `ai.generate(prompt, model?, maxTokens?, temperature?)` | Generate text (the org's default model if `model` is omitted); returns `modelId`, `content`, `finishReason`, `usage`, `usageType` |
| `query(sql, params?)` | Run one read-only SQL statement; `params` is a `List` or a `Map` |
| `search(collection, column, q, ...)` | Full-text search over one column, best matches first |
| `from(collection)` | Data builder: `select`, `insert`, `update`, `delete` |
| `storage.*` | Buckets and objects: upload, list, remove, signed URL |
| `streams.*` | `create`, `list`, `get`, `delete`, `publish`, `replay`, `subscribe` |

`usage` has `promptTokens`, `completionTokens` and `totalTokens`. Every call needs `projectSlug` on the client.

### About Kilimanjaro

Kilimanjaro is a modular developer platform for building secure enterprise applications.
You define AI agents, functions and data pipelines on the platform.
The SDKs let you deploy and invoke them, read and write your data, publish to and subscribe to streams, sign users in, and encrypt with keys you hold.

It provides four building blocks:

- **Keep**: AI security and governance. Guardrails and policy for the agents you run.
- **Pulse**: data intelligence and real-time stream processing. Streams, analytics and live feeds.
- **Gate**: customer identity and access management. Sign-in and sessions, and automatic data scoping so a signed-in user reaches only their own data.
- **Kibo**: cryptographic keys and secrets management. Client-side encryption with keys you hold, and a secrets vault.
