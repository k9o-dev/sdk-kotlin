# Changelog

## 1.3.0 — 2026-10-26

### Changed

- The README now covers the install requirements and shows the expected output of the example.
- Version aligned with the other SDKs. `ai.embed` is still the only call, and the public API is unchanged.


## 1.2.0

First release on Maven Central as `dev.k9o:core-kotlin`.

- `K9OCore` project client with `ai.embed(model?, input)`.
- Errors are thrown as `KError` (`code`, `message`, `context`, `recommendation`, `status`).
- Pure Kotlin on JVM 17, so it also runs on Android. No retries.
