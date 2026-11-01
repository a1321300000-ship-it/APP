package he.vault.app

import android.util.Base64
import org.json.JSONObject
import java.io.File

data class KeyEnvelope(val salt: ByteArray, val wrapped: ByteArray)
data class CredentialSet(val pin: KeyEnvelope, val pattern: KeyEnvelope)

object Credentials {
    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)

    fun create(master: ByteArray, pin: String, pattern: String): CredentialSet =
        CredentialSet(wrap(master, pin), wrap(master, pattern))

    private fun wrap(master: ByteArray, code: String): KeyEnvelope {
        val salt = VaultCrypto.randomBytes(16)
        val key = VaultCrypto.derive(code, salt)
        val wrapped = VaultCrypto.sealBytes(master, key, "vault-master-key".toByteArray())
        key.fill(0)
        return KeyEnvelope(salt, wrapped)
    }

    fun unlock(set: CredentialSet, code: String, type: String): ByteArray? {
        val env = if (type == "pin") set.pin else set.pattern
        return try {
            val key = VaultCrypto.derive(code, env.salt)
            val master = VaultCrypto.openBytes(env.wrapped, key, "vault-master-key".toByteArray())
            key.fill(0)
            if (master.size == 32) master else null
        } catch (_: Exception) {
            null
        }
    }

    fun unlockAny(set: CredentialSet, code: String): ByteArray? =
        unlock(set, code, "pin") ?: unlock(set, code, "pattern")

    fun replace(set: CredentialSet, master: ByteArray, code: String, type: String): CredentialSet =
        if (type == "pin") set.copy(pin = wrap(master, code))
        else set.copy(pattern = wrap(master, code))

    fun toJson(set: CredentialSet): ByteArray {
        val root = JSONObject()
        root.put("version", 1)
        root.put("pinSalt", b64(set.pin.salt))
        root.put("pinWrapped", b64(set.pin.wrapped))
        root.put("patternSalt", b64(set.pattern.salt))
        root.put("patternWrapped", b64(set.pattern.wrapped))
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun fromJson(bytes: ByteArray): CredentialSet {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.getInt("version") == 1)
        return CredentialSet(
            KeyEnvelope(decode(root.getString("pinSalt")), decode(root.getString("pinWrapped"))),
            KeyEnvelope(decode(root.getString("patternSalt")), decode(root.getString("patternWrapped")))
        )
    }

    fun load(file: File): CredentialSet? = try {
        if (file.exists()) fromJson(file.readBytes()) else null
    } catch (_: Exception) { null }

    fun save(file: File, set: CredentialSet) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(toJson(set))
        if (file.exists() && !file.delete()) throw IllegalStateException("לא ניתן לעדכן קובץ הגדרות")
        if (!temp.renameTo(file)) throw IllegalStateException("לא ניתן לשמור קובץ הגדרות")
    }
}
