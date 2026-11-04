package ai.narratium.nativeapp.data

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
 * Model tokens are encrypted at rest using a non-exportable Android Keystore key.
 * The API client still needs a plaintext token in memory when sending requests.
 */
class SecretSettings(context: Context) {
    private val prefs = context.getSharedPreferences("native_model_settings", Context.MODE_PRIVATE)
    private val alias = "narratium-native-api-key-v1"

    fun load(): ModelSettings = ModelSettings(
        baseUrl = prefs.getString("base_url", "https://api.openai.com/v1").orEmpty(),
        model = prefs.getString("model", "gpt-4o-mini").orEmpty(),
        apiKey = decrypt(prefs.getString("encrypted_key", null)),
        temperature = prefs.getFloat("temperature", 0.8f),
    )

    fun save(settings: ModelSettings) {
        require(settings.baseUrl.startsWith("https://", ignoreCase = true)) {
            "模型接口必须使用 HTTPS"
        }
        require(settings.model.isNotBlank()) { "请输入模型名称" }
        val ciphertext = encrypt(settings.apiKey)
        check(prefs.edit()
            .putString("base_url", settings.baseUrl.trim().trimEnd('/'))
            .putString("model", settings.model.trim())
            .putString("encrypted_key", ciphertext)
            .putFloat("temperature", settings.temperature.coerceIn(0f, 2f))
            .commit()) { "无法保存设置" }
    }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keystore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(
            cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
    }

    private fun decrypt(encoded: String?): String {
        if (encoded.isNullOrBlank()) return ""
        return try {
            val data = Base64.decode(encoded, Base64.DEFAULT)
            require(data.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Hardware keys cannot be restored on another device.
            ""
        }
    }
}
