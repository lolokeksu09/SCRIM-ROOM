package dev.lolokeksu.scrimroom.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with a non-exportable Android Keystore key, bound to the saved server URL.
 * A failed decrypt is not a logout: retain ciphertext until explicit recovery/reset.
 */
internal class CredentialStore(private val prefs: SharedPreferences) {
    companion object {
        private val lock = Any()
        private const val ALIAS = "scrim_room_token_v1"
        private const val SEALED = "server_token_encrypted_v1"
        private const val LEGACY = "server_token"
    }
    private fun key(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        check(create) { "Ключ доступа недоступен" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun aad() = (prefs.getString("server_url","") ?: "").toByteArray(Charsets.UTF_8)
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key(true))
        cipher.updateAAD(aad())
        return Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val parts=value.split(':',limit=2)
        require(parts.size==2)
        val iv=Base64.decode(parts[0],Base64.NO_WRAP)
        require(iv.size==12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,iv))
        cipher.updateAAD(aad())
        return cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    fun read(): String = synchronized(lock) {
        val sealed=prefs.getString(SEALED,null)
        if(sealed!=null) return@synchronized runCatching { decrypt(sealed) }.getOrDefault("")
        val legacy=prefs.getString(LEGACY,"").orEmpty()
        if(legacy.isEmpty()) return@synchronized ""
        // Persist the replacement and remove plaintext in the same preferences transaction.
        runCatching { write(legacy); legacy }.getOrDefault("")
    }
    fun unavailable(): Boolean = synchronized(lock) {
        (prefs.contains(SEALED) || !prefs.getString(LEGACY,"").isNullOrEmpty()) && read().isEmpty()
    }
    fun write(value:String) = synchronized(lock) {
        require(value.isNotBlank())
        val sealed=encrypt(value)
        check(prefs.edit().putString(SEALED,sealed).remove(LEGACY).commit()) { "Не удалось сохранить защищённый токен" }
    }
    private fun clearEdit()=prefs.edit().remove(LEGACY).remove(SEALED).remove("submitted_fingerprint").remove("uploaded_uris")
    fun clear() = synchronized(lock) { check(clearEdit().commit()) { "Не удалось отключить аккаунт" } }
    fun switchServer(url:String) = synchronized(lock) {
        if(url!=prefs.getString("server_url","")) check(clearEdit().putString("server_url",url).commit()) { "Не удалось сохранить адрес сервера" }
    }
}
