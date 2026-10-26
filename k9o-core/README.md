# dev.k9o:core-kotlin

Official Kotlin client for Kilimanjaro, the AI-native foundation for critical applications.
Pure Kotlin on JVM 17, so it runs on servers and on Android.

## Contents

- [Install](#install)
- [Quick start](#quick-start)
- [Errors](#errors)
- [Appendix: links](#appendix-links)

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.k9o:core-kotlin:1.3.0")
}
```

Requires JVM 17. On Android, API 23 or later.

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
println(env.data.embeddings.size)
println(env.data.dimensions)
```

```text
2
768
```

`embeddings` holds one vector per input, in the order you sent them. `dimensions` is the length of each vector.
`ai.embed` is the only call this package exposes. The input can be one `String` or a `List<String>`.

Call `ai.embed` with a project member's or an extension's token, typically from a backend. The gateway refuses an end user's token.

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
