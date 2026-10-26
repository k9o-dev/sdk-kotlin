package dev.k9o.gate

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val PREFS_FILE = "k9o_gate_tokens"
private const val KEY_REFRESH_TOKEN = "refresh_token"

// Legacy plain-prefs file + key used by apps that hand-rolled the doc-comment
// pattern from SDK versions prior to the EncryptedSharedPreferences migration.
private const val LEGACY_PREFS_FILE = "k9o_gate_tokens_plain"
private const val LEGACY_KEY_REFRESH_TOKEN = "refresh_token_plain"

/**
 * Android token store backed by `EncryptedSharedPreferences`.
 *
 * Tokens are encrypted at rest using AES-256-GCM (file level). The encryption
 * key is stored in the Android Keystore — hardware-backed on devices that
 * support it, software-backed otherwise. Key material never leaves the Keystore.
 *
 * **Requirements:** Android API 23+ (`minSdk 23`).
 *
 * **Usage — inject via [TokenStore] interface:**
 * ```kotlin
 * val gate = GateClient(
 *     orgBaseUrl = "https://acme.api.k9o.io",
 *     projectSlug = "my-project",
 *     tokenStore = KeystoreTokenStore(applicationContext),
 * )
 * ```
 *
 * **Migration:** on first [save] or [load], any token stored under the legacy
 * plain-SharedPreferences key is migrated to the encrypted store automatically
 * and the plain entry is deleted. No data loss on first-upgrade.
 *
 * **BYOS / Kibo composition:** `KeystoreTokenStore` is the device-side half of
 * the credential-custody story. The refresh token stored here is a short-lived
 * opaque string issued by the Kilimanjaro gateway; long-lived secret material
 * (API keys, BYOK-registered key references) is Kibo-sequestered on the cloud
 * side. No plaintext secret ever persists in `KeystoreTokenStore`.
 */
class KeystoreTokenStore
/**
 * Internal primary constructor — accepts pre-constructed [SharedPreferences]
 * instances. Enables unit tests to inject plain (unencrypted) prefs under
 * Robolectric, where the Android Keystore JCE provider is unavailable.
 *
 * For production use, call the public [KeystoreTokenStore(Context)] constructor.
 */
internal constructor(
    private val prefs: SharedPreferences,
    private val legacyPrefs: SharedPreferences,
) : TokenStore {

    /**
     * Production constructor. Creates an `EncryptedSharedPreferences`-backed
     * store using an AES-256-GCM `MasterKey` in the Android Keystore.
     *
     * @param context Android `Context` — use `applicationContext` to avoid
     *   Activity lifecycle leaks.
     */
    constructor(context: Context) : this(
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ),
        legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE),
    )

    private var migrationChecked = false

    // MARK: - TokenStore

    override fun save(token: String) {
        migrateOnce()
        prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()
    }

    override fun load(): String? {
        migrateOnce()
        return prefs.getString(KEY_REFRESH_TOKEN, null)
    }

    override fun delete() {
        prefs.edit().remove(KEY_REFRESH_TOKEN).apply()
        // Also clear any legacy plain entry in case migration never ran.
        if (legacyPrefs.contains(LEGACY_KEY_REFRESH_TOKEN)) {
            legacyPrefs.edit().remove(LEGACY_KEY_REFRESH_TOKEN).apply()
        }
    }

    // MARK: - Migration

    /**
     * One-shot migration from the legacy plain-SharedPreferences entry.
     * Runs at most once per [KeystoreTokenStore] instance lifetime.
     *
     * Migration sequence:
     * 1. Read plain token from legacy prefs (if present)
     * 2. Write to encrypted prefs
     * 3. Delete legacy plain entry
     */
    private fun migrateOnce() {
        if (migrationChecked) return
        migrationChecked = true
        val legacy = legacyPrefs.getString(LEGACY_KEY_REFRESH_TOKEN, null) ?: return
        prefs.edit().putString(KEY_REFRESH_TOKEN, legacy).apply()
        legacyPrefs.edit().remove(LEGACY_KEY_REFRESH_TOKEN).apply()
    }
}
