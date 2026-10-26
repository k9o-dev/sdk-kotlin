package dev.k9o.gate

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric unit tests for [KeystoreTokenStore].
 *
 * The `AndroidKeyStore` JCE provider is unavailable under JVM (Robolectric).
 * Tests use the `internal` constructor to inject plain [SharedPreferences]
 * (fully supported by Robolectric) instead of `EncryptedSharedPreferences`.
 * This validates the save/load/delete/migration contract without touching
 * the Android Keystore — which is tested via instrumented tests on-device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeystoreTokenStoreTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var legacyPrefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Use plain SharedPreferences for test isolation — matches the internal
        // constructor that KeystoreTokenStore exposes for exactly this use case.
        prefs = context.getSharedPreferences("test_k9o_gate_tokens", Context.MODE_PRIVATE)
        legacyPrefs = context.getSharedPreferences("test_k9o_gate_tokens_plain", Context.MODE_PRIVATE)
        // Clear between tests
        prefs.edit().clear().apply()
        legacyPrefs.edit().clear().apply()
    }

    private fun makeStore() = KeystoreTokenStore(prefs, legacyPrefs)

    // MARK: - Basic save / load / delete

    @Test
    fun `save and load roundtrip`() {
        val store = makeStore()
        store.save("rt_abc123")
        assertEquals("rt_abc123", store.load())
    }

    @Test
    fun `load returns null when empty`() {
        val store = makeStore()
        assertNull(store.load())
    }

    @Test
    fun `save overwrites previous token`() {
        val store = makeStore()
        store.save("rt_first")
        store.save("rt_second")
        assertEquals("rt_second", store.load())
    }

    @Test
    fun `delete clears stored token`() {
        val store = makeStore()
        store.save("rt_to_delete")
        store.delete()
        assertNull(store.load())
    }

    @Test
    fun `delete is idempotent when no token stored`() {
        val store = makeStore()
        store.delete()
        assertNull(store.load())
    }

    // MARK: - Multiple instances share same underlying prefs file

    @Test
    fun `two instances on same prefs share stored token`() {
        val storeA = makeStore()
        val storeB = makeStore()
        storeA.save("rt_shared")
        assertEquals("rt_shared", storeB.load())
    }

    // MARK: - Legacy migration

    @Test
    fun `legacy plain entry is migrated on first load`() {
        // Seed a legacy plain entry to simulate first-upgrade from pre-encryption SDK.
        legacyPrefs.edit().putString("refresh_token_plain", "rt_legacy").apply()

        val store = makeStore()
        // load() triggers migrateOnce()
        assertEquals("rt_legacy", store.load())
        // Legacy entry must be deleted after migration
        assertNull(legacyPrefs.getString("refresh_token_plain", null))
    }

    @Test
    fun `legacy plain entry is migrated on first save`() {
        legacyPrefs.edit().putString("refresh_token_plain", "rt_legacy_save").apply()
        val store = makeStore()
        // save() also triggers migrateOnce() before writing
        store.save("rt_new")
        assertNull(legacyPrefs.getString("refresh_token_plain", null))
        // The save wins — new token is current
        assertEquals("rt_new", store.load())
    }

    @Test
    fun `migration runs only once per instance`() {
        legacyPrefs.edit().putString("refresh_token_plain", "rt_migrate_once").apply()

        val store = makeStore()
        store.load() // first load — migration fires + deletes legacy

        // Re-seed legacy prefs after migration to verify the guard
        legacyPrefs.edit().putString("refresh_token_plain", "rt_should_not_migrate_again").apply()
        // Second load — migration must NOT run again (migrationChecked = true)
        assertEquals("rt_migrate_once", store.load())
        // Legacy entry was NOT consumed (migration guard held)
        assertEquals(
            "rt_should_not_migrate_again",
            legacyPrefs.getString("refresh_token_plain", null),
        )
    }

    @Test
    fun `delete clears legacy plain entry when no migration ran`() {
        legacyPrefs.edit().putString("refresh_token_plain", "rt_legacy_clear").apply()
        val store = makeStore()
        // delete() without load/save first — migration never ran; delete still clears legacy
        store.delete()
        assertNull(legacyPrefs.getString("refresh_token_plain", null))
    }

    @Test
    fun `no migration when legacy prefs is empty`() {
        // Sanity: no legacy entry, no migration side-effects
        val store = makeStore()
        assertNull(store.load())
        assertNull(prefs.getString("refresh_token", null))
    }
}
