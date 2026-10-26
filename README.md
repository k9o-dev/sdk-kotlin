# Kilimanjaro for Kotlin and Android

Official Kotlin and Android clients for Kilimanjaro, the AI-native foundation for building critical applications.

Kilimanjaro's products are Keep (AI Security and Governance), Pulse (Data Intelligence),
Gate (Customer Identity and Access Management) and Kibo (Keys and Secrets Management).

## Contents

- [Packages](#packages)
- [Install](#install)
- [Quick start](#quick-start)
- [Appendix: links](#appendix-links)

## Packages

| Package | Maven coordinate | What it is for | Platform |
|---|---|---|---|
| [`k9o-core`](k9o-core) | `dev.k9o:core-kotlin` | Project client with `ai.embed` | JVM 17, Android |
| [`k9o-gate`](k9o-gate) | `dev.k9o:gate` | Gate: sign end users in, refresh their access token, sign out; tokens in the Android Keystore | Android API 23+, JVM 17 |
| [`k9o-kibo-vault`](k9o-kibo-vault) | `dev.k9o:kibo-vault` | Kibo: store, list and reveal secrets | Android API 23+, JVM 17 |

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.k9o:core-kotlin:1.3.0")
    implementation("dev.k9o:gate:1.3.0")
    implementation("dev.k9o:kibo-vault:1.3.0")
}
```

Add only the packages you use. All three come from Maven Central.
They need Android API 23 or later, or JVM 17.

## Quick start

```kotlin
import dev.k9o.core.K9OCore

val core = K9OCore(
    baseUrl = "https://acme.api.k9o.io",
    projectSlug = "member-portal",
    accessToken = memberToken,
)

// suspend function: call it from a coroutine
val env = core.ai.embed(input = listOf("search_document: a"))
println(env.data.dimensions)
```

```text
768
```

Each package has its own README with a complete example and the expected output.

## Appendix: links

- Website: <https://kilimanjaro.dev>
- Documentation: <https://docs.kilimanjaro.dev>
- Other SDKs and the CLI: <https://github.com/k9o-dev>
- Issues and discussions: use the tabs of this repository
- License: Apache-2.0
