# dev.k9o:kibo-vault

Official Android and Kotlin client for the Kibo secrets vault of Kilimanjaro, the AI-native foundation for critical applications.
Kibo is the Keys and Secrets Management product.
Store, list and reveal secrets from your app over the gateway's HTTP API.

## Contents

- [Install](#install)
- [Quick start](#quick-start)
- [Behaviour to know about](#behaviour-to-know-about)
- [Appendix: links](#appendix-links)

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.k9o:kibo-vault:1.3.0")
}
```

Requires Android API 23 or later (or JVM 17).

## Quick start

Vault functions are `suspend` functions. Call them from a coroutine.

```kotlin
import dev.k9o.kibo.vault.VaultClient

val vault = VaultClient(orgBaseUrl = "https://acme.api.k9o.io", projectSlug = "my-project")

vault.secrets.create(name = "db/pw", value = "hunter2")
val challenge = vault.secrets.revealChallenge("db/pw")
val revealed = vault.secrets.reveal("db/pw", challenge = challenge.challenge)
println(revealed.value)
```

```text
hunter2
```

Values are plaintext in your code. The SDK handles the wire encoding.

## Behaviour to know about

- Reading a secret is a two-step flow: ask for a challenge, then reveal with it.
- `region` is optional on every named-secret method. It is sent only when you pass it.
- `get(name)` finds a secret by its exact name and returns its metadata, never its value.
- `get(name)` throws `VaultError.Api` with code `K9O_NOT_FOUND` when no secret has that name.
- `update()` returns `{id, name, version}`, not the full summary.
- `delete()` succeeds on any 2xx response.
- The client adds no `Authorization` header. To authenticate, pass an `httpClient` with an interceptor that adds it.

### Methods

| Method | Meaning |
|---|---|
| `list(prefix, cursor, limit)` | List secret metadata |
| `get(name)` | One secret's metadata, found by exact name |
| `create(name, value, region, visibility, metadata)` | Create a secret |
| `revealChallenge(name, region, reason)` | Get a single-use challenge; `reason` is optional |
| `reveal(name, challenge, region)` | Read the plaintext value with a challenge |
| `update(name, value, region, metadata)` | Set a new value; returns `UpdateSecretResult` |
| `rotate(name, region)` | Re-wrap the secret under a new key |
| `move(name, newPath, region)` | Change the secret's path |
| `delete(name, region)` | Retire the secret |

Failures are thrown as `VaultError`: `Api` (with `code`, `statusCode`), `Network` or `Decoding`.

## Appendix: links

- Website: <https://kilimanjaro.dev>
- Documentation: <https://docs.kilimanjaro.dev>
- Other SDKs: <https://github.com/k9o-dev>
- License: Apache-2.0
