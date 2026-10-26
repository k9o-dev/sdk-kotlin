# Changelog

## 1.4.0 (unreleased)

### Added

- `query(sql, params?)`: run one read-only SQL statement with positional (`List`) or named (`Map`, `:name` placeholders) parameters.
- `search(collection, column, q, ...)`: full-text search over one column, with `language`, `type`, `limit` and `cursor`.
- `from(collection)` data builder: `select`, `insert`, `update` and `delete`, the filters `eq neq gt gte lt lte in notIn like ilike is`, `limit`, `cursor`, `count`, `textSearch` and `vectorSearch`.
- `storage`: buckets (`listBuckets`, `getBucket`, `createBucket`, `updateBucket`, `deleteBucket`) and objects (`upload`, `list`, `remove`, `createSignedUrl`).
- `streams`: `create`, `list`, `get`, `delete`, `publish`, `replay` and `subscribe` (a `Flow` of events that reconnects from the last sequence).
- `ai.generate`: generate text from a prompt (the org's default model unless `model` is given) and read the generated text, finish reason and token usage.

### Changed

- README description.

## 1.3.0 — 2026-10-26

### Changed

- The README now covers the install requirements and shows the expected output of the example.
- Version aligned with the other SDKs. `ai.embed` is still the only call, and the public API is unchanged.


## 1.2.0

First release on Maven Central as `dev.k9o:core-kotlin`.

- `K9OCore` project client with `ai.embed(model?, input)`.
- Errors are thrown as `KError` (`code`, `message`, `context`, `recommendation`, `status`).
- Pure Kotlin on JVM 17, so it also runs on Android. No retries.
