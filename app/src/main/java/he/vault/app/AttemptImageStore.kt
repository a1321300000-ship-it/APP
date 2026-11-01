package he.vault.app

import android.content.Context
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Attempt photos are encrypted with a device-local Android Keystore key.
 * No image bytes are written to shared storage or included in cloud services.
 */
class AttemptImageStore(context: Context) {
    private val directory = File(File(context.filesDir, "vault"), "attempt-photos").apply { mkdirs() }

    fun save(id: String, jpeg: ByteArray) {
        require(id.matches(Regex("[a-fA-F0-9-]{16,64}"))) { "מזהה תמונה לא תקין" }
        require(jpeg.isNotEmpty() && jpeg.size <= 12 * 1024 * 1024) { "גודל התמונה אינו נתמך" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(jpeg)
        val target = File(directory, "$id.img.enc")
        val temp = File(directory, "$id.tmp")
        FileOutputStream(temp).use { output ->
            output.write("VIMG1".toByteArray(Charsets.US_ASCII))
            output.write(cipher.iv)
            output.write(encrypted)
            output.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            temp.delete()
            throw IllegalStateException("לא ניתן לעדכן תמונת ניסיון")
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("לא ניתן לשמור תמונת ניסיון")
        }
    }

    fun read(id: String): ByteArray? {
        require(id.matches(Regex("[a-fA-F0-9-]{16,64}"))) { "מזהה תמונה לא תקין" }
        val file = File(directory, "$id.img.enc")
        if (!file.isFile) return null
        val bytes = file.readBytes()
        require(bytes.size >= 5 + 12 + 16) { "קובץ התמונה פגום" }
        require(String(bytes, 0, 5, Charsets.US_ASCII) == "VIMG1") { "קובץ התמונה אינו מזוהה" }
        val iv = bytes.copyOfRange(5, 17)
        val ciphertext = bytes.copyOfRange(17, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext)
    }

    fun exists(id: String): Boolean {
        if (!id.matches(Regex("[a-fA-F0-9-]{16,64}"))) return false
        return File(directory, "$id.img.enc").isFile
    }

    fun delete(id: String) {
        if (!id.matches(Regex("[a-fA-F0-9-]{16,64}"))) return
        File(directory, "$id.img.enc").delete()
    }

    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "vault_attempt_photo_aes_v1"
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
