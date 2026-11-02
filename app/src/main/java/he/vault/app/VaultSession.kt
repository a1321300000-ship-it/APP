package he.vault.app

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Process-wide unlocked session survives Activity recreation, but is cleared when
 * the whole app process enters the background or the screen is switched off.
 */
object VaultSession {
    @Volatile var masterKey: ByteArray? = null
        private set

    @Volatile var items: MutableList<VaultItem> = mutableListOf()

    val ioExecutor = Executors.newSingleThreadExecutor(ThreadFactory { runnable ->
        Thread(runnable, "Vault-IO").apply { isDaemon = true }
    })

    @Synchronized
    fun setMasterKey(key: ByteArray?) {
        if (masterKey !== key) masterKey?.fill(0)
        masterKey = key
    }

    @Synchronized
    fun clear() {
        masterKey?.fill(0)
        masterKey = null
        items = mutableListOf()
    }
}
