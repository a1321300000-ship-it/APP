package he.vault.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object VaultCrypto {
    // Keep the 1 MiB maximum so legacy encrypted files remain readable.
    const val CHUNK_SIZE = 1024 * 1024
    private const val STREAM_BUFFER_SIZE = 64 * 1024
    private const val STREAM_MAGIC = 0x564C5431
    private const val GCM_TAG_BITS = 128
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun derive(code: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), salt, 150_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun sealBytes(plain: ByteArray, keyBytes: ByteArray, aad: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plain)
    }

    fun openBytes(blob: ByteArray, keyBytes: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size >= 28) { "נתוני ההצפנה פגומים" }
        val nonce = blob.copyOfRange(0, 12)
        val encrypted = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted)
    }

    private fun chunkAad(context: String, index: Long): ByteArray =
        context.toByteArray(Charsets.UTF_8) + ByteBuffer.allocate(8).putLong(index).array()

    fun encryptStream(input: InputStream, output: OutputStream, keyBytes: ByteArray, context: String): Long {
        val data = DataOutputStream(output)
        data.writeInt(STREAM_MAGIC)
        // New data is encrypted in 64 KiB records; the record framing and AAD are unchanged.
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var chunk = 0L
        var totalBytes = 0L
        while (true) {
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) continue
                count += read
            }
            if (count == 0) break
            val nonce = randomBytes(12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(chunkAad(context, chunk))
            data.writeInt(count)
            data.write(nonce)
            data.write(cipher.doFinal(buffer, 0, count))
            totalBytes += count
            chunk++
            if (count < buffer.size) break
        }
        data.writeInt(0)
        data.flush()
        return totalBytes
    }

    fun decryptStream(input: InputStream, output: OutputStream, keyBytes: ByteArray, context: String) {
        val data = DataInputStream(input)
        require(data.readInt() == STREAM_MAGIC) { "קובץ מוצפן לא מזוהה" }
        var chunk = 0L
        while (true) {
            val plainLength = data.readInt()
            if (plainLength == 0) break
            require(plainLength in 1..CHUNK_SIZE) { "מבנה קובץ לא תקין" }
            val nonce = ByteArray(12)
            data.readFully(nonce)
            val encrypted = ByteArray(plainLength + 16)
            data.readFully(encrypted)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(chunkAad(context, chunk))
            val plain = cipher.doFinal(encrypted)
            require(plain.size == plainLength) { "אורך מקטע לא תקין" }
            output.write(plain)
            chunk++
        }
        output.flush()
    }
}
