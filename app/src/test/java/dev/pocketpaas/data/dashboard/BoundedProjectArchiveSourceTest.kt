package dev.pocketpaas.data.dashboard

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedProjectArchiveSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun exactLimitIsDeliveredAndRemovedAfterUse() = runBlocking {
        val directory = temporary.newFolder()
        val payload = byteArrayOf(80, 75, 3, 4)
        var delivered: File? = null
        val result = source(directory, payload, maximum = 4).useArchive { archive ->
            delivered = archive
            assertArrayEquals(payload, archive.readBytes())
            "deployed"
        }
        assertEquals("deployed", result)
        assertFalse(requireNotNull(delivered).exists())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun oversizeAndEmptyInputsNeverDeployAndLeaveNoTemporaryArchive() = runBlocking {
        for (payload in listOf(byteArrayOf(), ByteArray(5))) {
            val directory = temporary.newFolder()
            var deployed = false
            val failure = runCatching {
                source(directory, payload, maximum = 4).useArchive { deployed = true }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertFalse(deployed)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun deploymentFailureStillReleasesOwnedArchive() = runBlocking {
        val directory = temporary.newFolder()
        val failure = runCatching {
            source(directory, byteArrayOf(1)).useArchive { throw IOException("deployment rejected") }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationClosesBlockedReadBeforeReturningAndDeletesSpool() = runBlocking {
        val directory = temporary.newFolder()
        val stream = BlockingStream()
        val job = launch {
            bounded(directory, provider(stream)).useArchive { error("A blocked input must not deploy") }
        }
        withTimeout(2_000) { stream.entered.await() }
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(stream.closed)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun uploadDeadlineClosesBlockedReadAndDeletesSpool() = runBlocking {
        val directory = temporary.newFolder()
        val stream = BlockingStream()
        val failure = runCatching {
            withTimeout(2_000) {
                bounded(directory, provider(stream), timeout = 200).useArchive { error("Timed-out input must not deploy") }
            }
        }.exceptionOrNull()
        assertTrue("Expected timeout; received ${failure?.javaClass?.name}", failure is TimeoutCancellationException)
        assertTrue(stream.closed)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun failedProviderOpenCancelsProviderAndRemovesTemporaryArchive() = runBlocking {
        val directory = temporary.newFolder()
        var cancelled = false
        val input = object : ArchiveInputProvider {
            override fun open(): InputStream = throw IOException("provider unavailable")
            override fun cancelOpen() { cancelled = true }
        }
        val failure = runCatching { bounded(directory, input).useArchive { Unit } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(cancelled)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun source(directory: File, bytes: ByteArray, maximum: Long = 64) =
        bounded(directory, provider(ByteArrayInputStream(bytes)), maximum)

    private fun bounded(directory: File, input: ArchiveInputProvider, maximum: Long = 64, timeout: Long = 120_000) =
        BoundedProjectArchiveSource(directory, maximum, Dispatchers.IO, input, timeout)

    private fun provider(stream: InputStream) = object : ArchiveInputProvider {
        override fun open(): InputStream = stream
        override fun cancelOpen() = Unit
    }

    private class BlockingStream : InputStream() {
        val entered = CompletableDeferred<Unit>()
        private val released = CountDownLatch(1)
        @Volatile var closed = false
        override fun read(): Int {
            entered.complete(Unit)
            if (!released.await(5, TimeUnit.SECONDS)) throw IOException("Read was not cancelled")
            throw IOException("Stream closed")
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = read()
        override fun close() { closed = true; released.countDown() }
    }
}
