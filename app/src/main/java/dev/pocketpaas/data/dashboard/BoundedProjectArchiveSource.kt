package dev.pocketpaas.data.dashboard

import dev.pocketpaas.domain.deployment.ProjectArchiveSource
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

interface ArchiveInputProvider {
    fun open(): InputStream
    fun cancelOpen()
}

/** Its caller serializes acquisitions. Cancellation closes a blocked read before releasing the file. */
class BoundedProjectArchiveSource(
    private val directory: File,
    private val maximumBytes: Long,
    private val ioDispatcher: CoroutineDispatcher,
    private val provider: ArchiveInputProvider,
    private val timeoutMillis: Long = 120_000L,
) : ProjectArchiveSource {
    init {
        require(maximumBytes in 1..64L * 1024 * 1024)
        require(timeoutMillis > 0)
    }

    override suspend fun <T> useArchive(action: suspend (File) -> T): T {
        val allocated = AtomicReference<File?>()
        try {
            val archive = withContext(ioDispatcher) {
                check(directory.isDirectory || directory.mkdirs()) { "The upload cache is unavailable." }
                val abandoned = directory.listFiles() ?: throw IOException("The upload cache cannot be inspected.")
                if (abandoned.size > 128) throw IOException("The upload cache needs cleanup.")
                abandoned.filter { it.name.startsWith("project-") && it.name.endsWith(".zip") }.forEach { file ->
                    if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.delete(file.toPath())
                }
                File.createTempFile("project-", ".zip", directory).also(allocated::set)
            }
            withTimeout(timeoutMillis) { spool(archive) }
            return action(archive)
        } finally {
            withContext(NonCancellable + ioDispatcher) {
                allocated.getAndSet(null)?.let { archive -> runCatching { Files.deleteIfExists(archive.toPath()) } }
            }
        }
    }

    private suspend fun spool(archive: File) = coroutineScope {
        val opened = AtomicReference<InputStream?>()
        val copy = async(ioDispatcher) {
            try {
                provider.open().use { input ->
                    opened.set(input)
                    currentCoroutineContext().ensureActive()
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            val actual = if (count == 0) {
                                val single = input.read()
                                if (single < 0) break
                                buffer[0] = single.toByte()
                                1
                            } else count
                            total += actual
                            if (total > maximumBytes) throw IOException("The ZIP exceeds the archive size limit.")
                            output.write(buffer, 0, actual)
                        }
                        if (total == 0L) throw IOException("The selected ZIP is empty.")
                    }
                }
            } catch (failure: Exception) {
                // Closing a timed-out provider can throw IOException on the IO child.
                // Preserve its existing cancellation instead of replacing the timeout.
                currentCoroutineContext().ensureActive()
                throw failure
            }
        }
        try {
            copy.await()
        } finally {
            withContext(NonCancellable + ioDispatcher) {
                runCatching { provider.cancelOpen() }
                runCatching { opened.getAndSet(null)?.close() }
            }
            copy.cancel()
        }
    }
}
