package dev.pocketpaas.data.process.termux.android

import android.content.Context
import android.util.AtomicFile
import dev.pocketpaas.data.process.termux.TermuxBridgeCredentials
import dev.pocketpaas.data.process.termux.TermuxBridgeCredentialsStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App-private, excluded from Android backup and never placed in shared storage. */
class AndroidTermuxCredentialsStore(context: Context) : TermuxBridgeCredentialsStore {
    private val credentialsFile = AtomicFile(File(context.noBackupFilesDir, "termux-bridge.credentials"))
    private val mutex = Mutex()

    override suspend fun get(): TermuxBridgeCredentials = mutex.withLock {
        withContext(Dispatchers.IO) {
            val persisted = try {
                credentialsFile.openRead()
            } catch (_: FileNotFoundException) {
                null
            }
            if (persisted != null) {
                val lines = persisted.bufferedReader(Charsets.UTF_8).use { it.readLines() }
                if (lines.size != 2) throw IOException("Termux bridge credentials are damaged.")
                val port = lines[0].toIntOrNull()
                    ?: throw IOException("Termux bridge credentials are damaged.")
                try {
                    TermuxBridgeCredentials(port, lines[1])
                } catch (_: IllegalArgumentException) {
                    throw IOException("Termux bridge credentials are damaged.")
                }
            } else {
                val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                val credentials = TermuxBridgeCredentials(DEFAULT_PORT, secret)
                val output = credentialsFile.startWrite()
                try {
                    output.write("${credentials.port}\n${credentials.token}\n".toByteArray(Charsets.UTF_8))
                    credentialsFile.finishWrite(output)
                } catch (failure: Throwable) {
                    credentialsFile.failWrite(output)
                    throw failure
                }
                credentials
            }
        }
    }

    companion object {
        const val DEFAULT_PORT: Int = 8766
    }
}
