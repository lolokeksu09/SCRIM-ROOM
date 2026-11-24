package dev.lolokeksu.scrimroom

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lolokeksu.scrimroom.data.CredentialStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    @Test fun migrationAndHostIsolation() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=ctx.getSharedPreferences("scrim_credential_test",Context.MODE_PRIVATE)
        val secret="synthetic-test-token-only"
        prefs.edit().clear().putString("server_url","https://first.example").putString("server_token",secret).commit()
        try {
            val store=CredentialStore(prefs)
            assertEquals(secret,store.read())
            assertFalse(prefs.contains("server_token"))
            assertFalse(prefs.getString("server_token_encrypted_v1","")!!.contains(secret))
            assertEquals(secret,CredentialStore(prefs).read())
            prefs.edit().putString("server_url","https://wrong.example").commit()
            assertEquals("",store.read())
            assertTrue(store.unavailable())
            prefs.edit().putString("server_url","https://first.example").commit()
            assertEquals(secret,store.read())
            store.switchServer("https://second.example")
            assertEquals("",store.read())
            assertFalse(prefs.contains("server_token_encrypted_v1"))
            store.write("replacement")
            assertEquals("replacement",store.read())
            store.clear()
            assertEquals("",store.read())
        } finally {prefs.edit().clear().commit()}
    }
}
