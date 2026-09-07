package pl.somaskan.questgpt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the user's OpenAI API key on the headset without putting it in the APK,
 * source code or plaintext SharedPreferences. The encryption key is generated and
 * retained by Android Keystore.
 *
 * This is intended for a private sideloaded Quest build. A distributable/public app
 * should use a trusted backend and short-lived credentials instead of a long-lived
 * API key on the client device.
 */
class OpenAICredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Settings(
        val configured: Boolean,
        val textModel: String,
        val realtimeModel: String,
    )

    fun settings(): Settings = Settings(
        configured = hasKey(),
        textModel = textModel(),
        realtimeModel = realtimeModel(),
    )

    fun hasKey(): Boolean = !prefs.getString(KEY_CIPHERTEXT, null).isNullOrBlank() &&
        !prefs.getString(KEY_IV, null).isNullOrBlank() &&
        runCatching { readKey() }.getOrNull()?.isNotBlank() == true

    fun saveKey(rawKey: String) {
        val clean = rawKey.trim()
        require(clean.length >= 20) { "Klucz OpenAI API jest zbyt krótki." }
        require(clean.startsWith("sk-")) { "Klucz OpenAI API powinien zaczynać się od sk-." }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun readKey(): String? {
        val iv = prefs.getString(KEY_IV, null)?.takeIf { it.isNotBlank() } ?: return null
        val ciphertext = prefs.getString(KEY_CIPHERTEXT, null)?.takeIf { it.isNotBlank() } ?: return null
        val secret = getExistingSecretKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secret,
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
            .trim()
            .takeIf { it.isNotBlank() }
    }

    fun clearKey() {
        prefs.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).apply()
        runCatching {
            val store = keyStore()
            if (store.containsAlias(KEYSTORE_ALIAS)) store.deleteEntry(KEYSTORE_ALIAS)
        }
    }

    fun textModel(): String = prefs.getString(KEY_TEXT_MODEL, DEFAULT_TEXT_MODEL)
        ?.trim()
        ?.takeIf(::validModel)
        ?: DEFAULT_TEXT_MODEL

    fun realtimeModel(): String = prefs.getString(KEY_REALTIME_MODEL, DEFAULT_REALTIME_MODEL)
        ?.trim()
        ?.takeIf(::validModel)
        ?: DEFAULT_REALTIME_MODEL

    fun saveModels(textModel: String, realtimeModel: String) {
        val text = textModel.trim()
        val realtime = realtimeModel.trim()
        require(validModel(text)) { "Nieprawidłowa nazwa modelu tekstowego." }
        require(validModel(realtime)) { "Nieprawidłowa nazwa modelu Realtime." }
        prefs.edit()
            .putString(KEY_TEXT_MODEL, text)
            .putString(KEY_REALTIME_MODEL, realtime)
            .apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        getExistingSecretKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun getExistingSecretKey(): SecretKey? =
        (keyStore().getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val DEFAULT_TEXT_MODEL = "gpt-5.6"
        const val DEFAULT_REALTIME_MODEL = "gpt-realtime-2.1"

        private const val PREFS = "questgpt_openai"
        private const val KEY_IV = "api_key_iv"
        private const val KEY_CIPHERTEXT = "api_key_ciphertext"
        private const val KEY_TEXT_MODEL = "text_model"
        private const val KEY_REALTIME_MODEL = "realtime_model"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "questgpt_openai_api_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        private val MODEL_PATTERN = Regex("[A-Za-z0-9._:-]{2,100}")
        private fun validModel(value: String): Boolean = MODEL_PATTERN.matches(value)
    }
}
