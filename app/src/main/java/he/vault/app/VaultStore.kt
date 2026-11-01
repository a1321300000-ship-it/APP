package he.vault.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class VaultItem(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val created: Long
)

data class FailedAttempt(val time: Long, val count: Int)

class VaultStore(private val context: Context) {
    private val base = File(context.filesDir, "vault").apply { mkdirs() }
    private val dataDir = File(base, "data").apply { mkdirs() }
    private val indexFile = File(base, "index.enc")
    private val credentialFile = File(base, "credentials.json")
    private val prefs = context.getSharedPreferences("vault_settings", Context.MODE_PRIVATE)

    fun hasCredentials(): Boolean = Credentials.load(credentialFile) != null
    fun loadCredentials(): CredentialSet? = Credentials.load(credentialFile)
    fun saveCredentials(set: CredentialSet) = Credentials.save(credentialFile, set)

    fun loadIndex(master: ByteArray): MutableList<VaultItem> {
        if (!indexFile.exists()) return mutableListOf()
        val plain = VaultCrypto.openBytes(indexFile.readBytes(), master, "vault-index".toByteArray())
        val array = JSONArray(String(plain, Charsets.UTF_8))
        val result = mutableListOf<VaultItem>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            result.add(VaultItem(o.getString("id"), o.getString("name"), o.getString("mime"), o.getLong("size"), o.getLong("created")))
        }
        return result
    }

    fun saveIndex(items: List<VaultItem>, master: ByteArray) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("mime", item.mime)
                put("size", item.size)
                put("created", item.created)
            })
        }
        val encrypted = VaultCrypto.sealBytes(array.toString().toByteArray(Charsets.UTF_8), master, "vault-index".toByteArray())
        val temp = File(base, "index.enc.tmp")
        temp.writeBytes(encrypted)
        if (indexFile.exists() && !indexFile.delete()) throw IllegalStateException("לא ניתן לעדכן את רשימת הקבצים")
        if (!temp.renameTo(indexFile)) throw IllegalStateException("לא ניתן לשמור את רשימת הקבצים")
    }

    fun importFile(input: InputStream, name: String, mime: String, master: ByteArray): VaultItem {
        val id = UUID.randomUUID().toString()
        val target = File(dataDir, "$id.bin")
        try {
            val plainSize = BufferedInputStream(input).use { source ->
                BufferedOutputStream(FileOutputStream(target)).use { output ->
                    VaultCrypto.encryptStream(source, output, master, "file:$id")
                }
            }
            return VaultItem(id, name, mime.ifBlank { "application/octet-stream" }, plainSize, System.currentTimeMillis())
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    fun exportFile(item: VaultItem, output: OutputStream, master: ByteArray) {
        val source = File(dataDir, "${item.id}.bin")
        require(source.exists()) { "הקובץ אינו נמצא בכספת" }
        BufferedInputStream(FileInputStream(source)).use { input ->
            BufferedOutputStream(output).use { out ->
                VaultCrypto.decryptStream(input, out, master, "file:${item.id}")
            }
        }
    }

    fun decryptToFile(item: VaultItem, target: File, master: ByteArray) {
        val source = File(dataDir, "${item.id}.bin")
        BufferedInputStream(FileInputStream(source)).use { input ->
            BufferedOutputStream(FileOutputStream(target)).use { out ->
                VaultCrypto.decryptStream(input, out, master, "file:${item.id}")
            }
        }
    }

    fun delete(item: VaultItem) {
        File(dataDir, "${item.id}.bin").delete()
    }

    fun deleteOriginal(resolver: ContentResolver, uri: Uri): Boolean =
        try { DocumentsContract.deleteDocument(resolver, uri) } catch (_: Exception) { false }

    fun preferenceBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    fun setPreferenceBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    fun preferenceInt(key: String, default: Int) = prefs.getInt(key, default)
    fun setPreferenceInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    fun preferenceLong(key: String, default: Long) = prefs.getLong(key, default)
    fun setPreferenceLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }

    fun recordFailure(): Pair<Int, Int> {
        val consecutive = prefs.getInt("consecutive_failures", 0) + 1
        val total = prefs.getInt("total_failures", 0) + 1
        prefs.edit().putInt("consecutive_failures", consecutive).putInt("total_failures", total).apply()
        val history = try { JSONArray(prefs.getString("attempt_history", "[]")) } catch (_: Exception) { JSONArray() }
        history.put(JSONObject().put("time", System.currentTimeMillis()).put("count", consecutive))
        while (history.length() > 100) history.remove(0)
        prefs.edit().putString("attempt_history", history.toString()).apply()
        return Pair(total, consecutive)
    }

    fun resetConsecutiveFailures() { prefs.edit().putInt("consecutive_failures", 0).apply() }
    fun clearAttemptHistory() { prefs.edit().remove("attempt_history").apply() }
    fun getAttemptHistory(): List<FailedAttempt> {
        val array = try { JSONArray(prefs.getString("attempt_history", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            try {
                val item = array.getJSONObject(i)
                FailedAttempt(item.getLong("time"), item.getInt("count"))
            } catch (_: Exception) { null }
        }.reversed()
    }

    fun writeBackup(uri: Uri, resolver: ContentResolver, master: ByteArray) {
        val creds = loadCredentials() ?: throw IllegalStateException("פרטי הכניסה חסרים")
        val header = Credentials.toJson(creds)
        val zipFile = File(File(base, ".tmp").apply { mkdirs() }, "vault-backup-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
                if (indexFile.exists()) addZipFile(zip, indexFile, "index.enc")
                dataDir.listFiles()?.filter { it.isFile && it.name.matches(Regex("[a-fA-F0-9-]+\\.bin")) }?.forEach { file ->
                    addZipFile(zip, file, "data/${file.name}")
                }
            }
            resolver.openOutputStream(uri, "w")?.use { raw ->
                val data = java.io.DataOutputStream(raw)
                data.write("VLTBACK1".toByteArray(Charsets.US_ASCII))
                data.writeInt(header.size)
                data.write(header)
                data.flush()
                FileInputStream(zipFile).use { input ->
                    VaultCrypto.encryptStream(input, data, master, "vault-backup-payload")
                }
            } ?: throw IllegalStateException("לא ניתן לפתוח את קובץ הגיבוי לכתיבה")
        } finally {
            zipFile.delete()
        }
    }

    fun restoreBackup(uri: Uri, resolver: ContentResolver, code: String): ByteArray {
        val tempZip = File(File(base, ".tmp").apply { mkdirs() }, "vault-restore-${UUID.randomUUID()}.zip")
        val tempDir = File(File(base, ".tmp").apply { mkdirs() }, "vault-restore-${UUID.randomUUID()}")
        try {
            val decoded = resolver.openInputStream(uri)?.use { raw ->
                val data = java.io.DataInputStream(BufferedInputStream(raw))
                val magic = ByteArray(8)
                data.readFully(magic)
                require(String(magic, Charsets.US_ASCII) == "VLTBACK1") { "קובץ הגיבוי אינו מזוהה" }
                val headerLength = data.readInt()
                require(headerLength in 1..16_384) { "כותרת הגיבוי פגומה" }
                val header = ByteArray(headerLength)
                data.readFully(header)
                val creds = Credentials.fromJson(header)
                val master = Credentials.unlockAny(creds, code)
                    ?: throw SecurityException("הקוד אינו מתאים לגיבוי")
                FileOutputStream(tempZip).use { out ->
                    VaultCrypto.decryptStream(data, out, master, "vault-backup-payload")
                }
                Pair(master, creds)
            } ?: throw IllegalStateException("לא ניתן לקרוא את קובץ הגיבוי")
            val restoredMaster = decoded.first
            val restoredCredentials = decoded.second

            tempDir.mkdirs()
            var total = 0L
            var entries = 0
            ZipInputStream(BufferedInputStream(FileInputStream(tempZip))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= 10_002) { "בגיבוי יש יותר מדי פריטים" }
                    val safe = entry.name == "index.enc" || entry.name.matches(Regex("data/[a-fA-F0-9-]+\\.bin"))
                    require(safe && !entry.isDirectory) { "הגיבוי מכיל נתיב לא מורשה" }
                    val outFile = File(tempDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= 8L * 1024 * 1024 * 1024) { "קובץ הגיבוי גדול מדי" }
                            out.write(buffer, 0, n)
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(File(tempDir, "index.enc").exists()) { "רשימת הקבצים חסרה בגיבוי" }
            val restoredData = File(tempDir, "data")
            val replacement = File(base, "data.restore")
            replacement.deleteRecursively()
            if (restoredData.exists()) copyDirectory(restoredData, replacement) else replacement.mkdirs()
            dataDir.deleteRecursively()
            if (!replacement.renameTo(dataDir)) {
                copyDirectory(replacement, dataDir)
                replacement.deleteRecursively()
            }
            File(tempDir, "index.enc").copyTo(indexFile, overwrite = true)
            saveCredentials(restoredCredentials)
            return restoredMaster
        } finally {
            tempZip.delete()
            tempDir.deleteRecursively()
        }
    }

    private fun addZipFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input -> input.copyTo(zip, 64 * 1024) }
        zip.closeEntry()
    }

    private fun copyDirectory(from: File, to: File) {
        to.mkdirs()
        from.listFiles()?.forEach { child ->
            val target = File(to, child.name)
            if (child.isDirectory) copyDirectory(child, target) else child.copyTo(target, overwrite = true)
        }
    }
}
