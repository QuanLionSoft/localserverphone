package dev.pocketpaas.data.process.termux

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedProcessInputStreamTest {
    @Test(timeout = 10_000)
    fun endingAndChangingProducerThreadsDoesNotBreakTheStream() {
        val input = BoundedProcessInputStream(4)
        val first = Thread { input.write(byteArrayOf(41)) }
        first.start()
        first.join(5_000)
        assertTrue(!first.isAlive)
        assertEquals(41, input.read())
        val second = Thread { input.write(byteArrayOf(42)); input.finish() }
        second.start()
        second.join(5_000)

        assertTrue(!second.isAlive)
        assertEquals(42, input.read())
        assertEquals(-1, input.read())
    }

    @Test(timeout = 10_000)
    fun anIdleReadWaitsForMoreBytesAfterThePreviousWriterThreadEnded() {
        val input = BoundedProcessInputStream(4)
        val writer = Thread { input.write(byteArrayOf(1)) }
        writer.start()
        writer.join(5_000)
        assertEquals(1, input.read())
        val pool = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            val read = pool.submit(Callable { entered.countDown(); input.read() })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertPending(read)

            input.write(byteArrayOf(2))

            assertEquals(2, read.get(5, TimeUnit.SECONDS))
        } finally {
            input.close()
            pool.shutdownNow()
        }
    }

    @Test(timeout = 10_000)
    fun aFullBufferBackpressuresTheProducerUntilAReaderMakesRoom() {
        val input = BoundedProcessInputStream(2)
        input.write(byteArrayOf(1, 2))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            val write = pool.submit(Callable { entered.countDown(); input.write(byteArrayOf(3)) })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertPending(write)
            assertEquals(2, input.available())

            assertEquals(1, input.read())
            write.get(5, TimeUnit.SECONDS)
            input.finish()

            assertArrayEquals(byteArrayOf(2, 3), input.readBytes())
        } finally {
            input.close()
            pool.shutdownNow()
        }
    }

    @Test(timeout = 10_000)
    fun closingTheConsumerReleasesABlockedRead() {
        val input = BoundedProcessInputStream(2)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            val read = pool.submit(Callable { entered.countDown(); input.read() })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertPending(read)

            input.close()

            assertFutureIOException(read)
        } finally {
            input.close()
            pool.shutdownNow()
        }
    }

    @Test(timeout = 10_000)
    fun closingTheConsumerReleasesABlockedWrite() {
        val input = BoundedProcessInputStream(1)
        input.write(byteArrayOf(1))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            val write = pool.submit(Callable { entered.countDown(); input.write(byteArrayOf(2)) })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertPending(write)

            input.close()

            assertFutureIOException(write)
            assertEquals(0, input.available())
        } finally {
            input.close()
            pool.shutdownNow()
        }
    }

    @Test(timeout = 10_000)
    fun aRealFailureWakesAWaitingReaderAndRemainsTheReportedCause() {
        val input = BoundedProcessInputStream(1)
        val problem = IOException("authenticated poll transport failed")
        val pool = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            val read = pool.submit(Callable { entered.countDown(); input.read() })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertPending(read)

            input.fail(problem)

            assertSame(problem, assertFutureIOException(read))
            assertSame(problem, captureIOException { input.read() })
        } finally {
            input.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun bufferedOutputIsPreservedBeforeTheOriginalFailureIsThrown() {
        val input = BoundedProcessInputStream(3)
        val first = IOException("first transport failure")
        input.write(byteArrayOf(1, 2))
        input.fail(first)
        input.fail(IOException("later cleanup failure"))
        input.finish()

        assertEquals(1, input.read())
        assertEquals(2, input.read())
        assertSame(first, captureIOException { input.read() })
    }

    @Test
    fun aNormalFinishDrainsBytesThenReturnsStableEof() {
        val input = BoundedProcessInputStream(3)
        input.write(byteArrayOf(1, 2, 3))
        input.finish()

        assertArrayEquals(byteArrayOf(1, 2, 3), input.readBytes())
        assertEquals(-1, input.read())
        captureIOException { input.write(byteArrayOf(4)) }
    }

    @Test
    fun ringWraparoundKeepsByteOrdering() {
        val input = BoundedProcessInputStream(3)
        input.write(byteArrayOf(1, 2, 3))
        val first = ByteArray(2)
        assertEquals(2, input.read(first))
        input.write(byteArrayOf(4, 5))
        input.finish()

        assertArrayEquals(byteArrayOf(1, 2), first)
        assertArrayEquals(byteArrayOf(3, 4, 5), input.readBytes())
    }

    @Test(timeout = 10_000)
    fun aChunkLargerThanCapacityIsTransferredWithoutLoss() {
        val input = BoundedProcessInputStream(7)
        val expected = ByteArray(16 * 1024) { (it % 251).toByte() }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val write = pool.submit(Callable { input.write(expected); input.finish() })

            assertArrayEquals(expected, input.readBytes())
            write.get(5, TimeUnit.SECONDS)
        } finally {
            input.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun zeroLengthReadsNeverWaitAndOverflowingBoundsAreRejected() {
        val input = BoundedProcessInputStream(1)
        assertEquals(0, input.read(ByteArray(0), 0, 0))
        try {
            input.read(ByteArray(1), Int.MAX_VALUE, 1)
            fail("Overflowing read bounds must be rejected")
        } catch (_: IndexOutOfBoundsException) {
            assertEquals(0, input.available())
        } finally {
            input.close()
        }
    }

    private fun assertPending(future: Future<*>) {
        try {
            future.get(100, TimeUnit.MILLISECONDS)
            fail("The operation must still be waiting")
        } catch (_: TimeoutException) {
            assertTrue(!future.isDone)
        }
    }

    private fun assertFutureIOException(future: Future<*>): IOException {
        try {
            future.get(5, TimeUnit.SECONDS)
            throw AssertionError("An output stream failure was expected")
        } catch (failure: ExecutionException) {
            return failure.cause as? IOException ?: throw AssertionError("Unexpected worker failure", failure)
        }
    }

    private fun captureIOException(action: () -> Unit): IOException {
        try {
            action()
            throw AssertionError("An output stream failure was expected")
        } catch (failure: IOException) {
            return failure
        }
    }
}
