# dev.k9o:gate

Official Android and Kotlin client for Gate, the Customer Identity and Access Management product of Kilimanjaro.
Kilimanjaro is the AI-native foundation for critical applications.
Sign your app's end users in, refresh their access token and sign them out, with tokens kept in the Android Keystore.

Requires Android API 23 or later (or JVM 17) and Kotlin 1.9 or later.

## Contents

- [Install](#install)
- [Quick start](#quick-start)
- [Token storage](#token-storage)
- [Custom claims](#custom-claims)
- [Errors](#errors)
- [Appendix: links](#appendix-links)

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.k9o:gate:1.3.0")
}
```

## Quick start

Gate functions are `suspend` functions. Call them from a coroutine, for example `lifecycleScope.launch { ... }`.

### Sign in

```kotlin
import dev.k9o.gate.GateClient
import dev.k9o.gate.KeystoreTokenStore

val gate = GateClient(
    orgBaseUrl = "https://acme.api.k9o.io",
    projectSlug = "my-project",
    tokenStore = KeystoreTokenStore(applicationContext),
)

val result = gate.signIn(email = "user@example.com", password = "password")
println(result.user.id)
println(result.session.expiresAt)
```

```text
eu_8fK2mXpQ
2026-01-01T12:00:00Z
```

`signIn` saves the refresh token in the token store.

### Refresh and sign out

`refreshToken()` reads the stored refresh token and returns a new access token with its expiry.
The refresh token is not replaced: it stays valid until you sign out.

```kotlin
val refreshed = gate.refreshToken()
println(refreshed.expiresAt)

val revoked = gate.signOut()
println(revoked)
```

```text
2026-01-01T13:00:00Z
true
```

`signOut()` clears the stored refresh token. It returns `true` when a live session was revoked.

## Token storage

`KeystoreTokenStore` keeps the refresh token in `EncryptedSharedPreferences`, protected by a key in the Android Keystore (AES-256-GCM).
The key never leaves the device.
To store tokens somewhere else, implement `TokenStore` and pass it as `tokenStore`.

## Custom claims

Custom claims are flat keys of the form `ext.<namespace>.<name>`. `customClaim(namespace, name)` reads one.

```kotlin
import dev.k9o.gate.AccessToken

val token = AccessToken.decode(result.session.accessToken)
val partnerRole = token?.customClaim("partners", "partner_role")
println(partnerRole)
```

```text
extension_org_admin
```

`customClaims` returns every `ext.` claim keyed by its full name, for example `ext.partners.partner_role`.
`decode` does not verify the signature, so do not use it for security decisions.

## Errors

```kotlin
import dev.k9o.gate.GateError

try {
    gate.signIn(email, password)
} catch (e: GateError.InvalidCredentials) {
    // wrong email or password
} catch (e: GateError.Network) {
    // no connection
} catch (e: GateError.Unknown) {
    // anything else: e.statusCode, e.body
}
```

## Appendix: links

- Website: <https://kilimanjaro.dev>
- Documentation: <https://docs.kilimanjaro.dev>
- Other SDKs: <https://github.com/k9o-dev>
- License: Apache-2.0
