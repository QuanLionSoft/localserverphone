package dev.pocketpaas.data.remote.android

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketpaas.core.di.IoDispatcher
import dev.pocketpaas.domain.remote.ApiKeyStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A separate secret from the Termux bridge token; Android backup never includes this directory. */
@Singleton
class AndroidApiKeyStore @Inject constructor(
    @ApplicationContext context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ApiKeyStore {
    private val baseFile = File(context.noBackupFilesDir, "remote-api.key")
    private val file = AtomicFile(baseFile)
    private val mutex = Mutex()

    override suspend fun token(): String = mutex.withLock {
        withContext(ioDispatcher) {
            val hadPersistedFile = hasPersistedFile()
            val persisted = try {
                file.openRead()
            } catch (_: FileNotFoundException) {
                if (hadPersistedFile || hasPersistedFile()) {
                    throw IOException("The remote API key file could not be read; local storage recovery is required.")
                }
                null
            }
            if (persisted == null) {
                newToken().also(::persist)
            } else {
                persisted.use { input ->
                    val buffer = ByteArray(MAXIMUM_FILE_BYTES + 1)
                    var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        if (read == 0) {
                            val single = input.read()
                            if (single < 0) break
                            buffer[count++] = single.toByte()
                        } else {
                            count += read
                        }
                    }
                    if (count > MAXIMUM_FILE_BYTES) damaged()
                    val value = String(buffer, 0, count, Charsets.US_ASCII)
                    if (!value.matches(STORED_TOKEN_PATTERN)) damaged()
                    value.removeSuffix("\n")
                }
            }
        }
    }

    private fun persist(token: String) {
        val output = file.startWrite()
        try {
            output.write((token + "\n").toByteArray(Charsets.US_ASCII))
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun newToken(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .joinToString("") { byte -> HEX[byte.toInt().ushr(4) and 15].toString() + HEX[byte.toInt() and 15] }

    private fun hasPersistedFile(): Boolean = baseFile.exists() ||
        File(baseFile.path + ".bak").exists() || File(baseFile.path + ".new").exists()

    private fun damaged(): Nothing = throw IOException(
        "The remote API key file is damaged; local app storage recovery is required.",
    )

    companion object {
        private const val MAXIMUM_FILE_BYTES = 512
        private const val HEX = "0123456789abcdef"
        private val STORED_TOKEN_PATTERN = Regex("[0-9a-f]{64}\\n?")
    }
}
