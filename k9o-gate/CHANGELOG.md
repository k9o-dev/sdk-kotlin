# Changelog

## 1.4.0 (unreleased)

### Changed

- README description.

## 1.3.0 — 2026-10-26

### Changed

- `refreshToken(refreshToken)` takes only the refresh token and returns a `RefreshResult` with `accessToken` and `expiresAt`.
- `refreshToken` no longer replaces the stored refresh token. The gateway does not rotate it.
- `signOut(refreshToken)` returns a `Boolean`: `true` when a live session was revoked.
- `AccessToken.customClaims` returns the flat `ext.<namespace>.<name>` keys of the token.

### Removed

- The `projectHint` argument of `refreshToken`. The gateway rejects it.

### Added

- `RefreshResult`.
- `AccessToken.customClaim(namespace, name)` reads one custom claim.


## 1.2.0

First release on Maven Central as `dev.k9o:gate`.

- `GateClient` with `signIn`, `refreshToken` and `signOut` (suspend functions).
- `KeystoreTokenStore` keeps the refresh token in `EncryptedSharedPreferences`, backed by the Android Keystore.
- `TokenStore` interface for custom storage, `AccessToken` payload decoder with `customClaims`, and a `GateError` hierarchy.
- Android API 23 or later, or JVM 17.
