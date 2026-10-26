# Changelog

## 1.4.0 (unreleased)

### Changed

- README description.

## 1.3.0 — 2026-10-26

### Changed

- `get(name)` finds the secret by its exact name through the list route and throws `K9O_NOT_FOUND` when it is absent. It used to always fail.
- `region` is optional on every named-secret method and is sent only when you pass it.
- Parameter order is now `create(name, value, region, ...)`,
  `update(name, value, region, ...)`, `reveal(name, challenge, region)` and `move(name, newPath, region)`.
- `delete` accepts any 2xx response.

### Added

- `revealChallenge(name, region, reason)` takes an optional `reason`.


## 1.2.0

First release on Maven Central as `dev.k9o:kibo-vault`.

- `VaultClient.secrets`: list, get, create, revealChallenge, reveal, update, rotate, move and delete.
- Android API 23 or later, or JVM 17.
