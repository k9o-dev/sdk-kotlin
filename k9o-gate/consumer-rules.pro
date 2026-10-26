# Consumer ProGuard rules for k9o-gate Android SDK
# Keep TokenStore interface and implementations intact for DI frameworks.
-keep interface dev.kilimanjaro.gate.TokenStore { *; }
-keep class dev.kilimanjaro.gate.KeystoreTokenStore { *; }
-keep class dev.kilimanjaro.gate.GateClient { *; }
-keep class dev.kilimanjaro.gate.GateError { *; }
-keep class dev.kilimanjaro.gate.AuthResult { *; }
-keep class dev.kilimanjaro.gate.Session { *; }
-keep class dev.kilimanjaro.gate.EndUser { *; }
-keep class dev.kilimanjaro.gate.AccessToken { *; }
