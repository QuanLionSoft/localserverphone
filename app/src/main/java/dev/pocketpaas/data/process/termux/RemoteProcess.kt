package dev.pocketpaas.data.process.termux

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Adapts a Termux-owned subprocess to java.lang.Process without crossing private UID paths.
 * stdout and stderr have independent bounded streams and independent IO producer coroutines.
 */
internal class RemoteProcess(
    private val ticket: RemoteProcessTicket,
    private val bridge: TermuxBridge,
    private val applicationScope: CoroutineScope,
) : Process() {
    private val completion = CountDownLatch(1)
    private val failure = AtomicReference<IOException?>()
    private val failureCleanupStarted = AtomicBoolean()
    private val stdout = BoundedProcessInputStream(PIPE_CAPACITY)
    private val stderr = BoundedProcessInputStream(PIPE_CAPACITY)
    private val stdoutChunks = Channel<ByteArray>(QUEUE_CAPACITY)
    private val stderrChunks = Channel<ByteArray>(QUEUE_CAPACITY)
    private val terminationMutex = Mutex()

    @Volatile
    private var terminationConfirmed = false

    @Volatile
    private var resultCode: Int? = null

    private val stdoutWriter = applicationScope.launch(Dispatchers.IO) {
        writeStream(stdoutChunks, stdout)
    }
    private val stderrWriter = applicationScope.launch(Dispatchers.IO) {
        writeStream(stderrChunks, stderr)
    }
    private val polling = applicationScope.launch(Dispatchers.IO) {
        var stdoutClosed = false
        var stderrClosed = false
        try {
            while (isActive) {
                failure.get()?.let { throw it }
                val output = bridge.poll(ticket)
                if (stdoutClosed && output.stdout.isNotEmpty() || stderrClosed && output.stderr.isNotEmpty()) {
                    throw IOException("Termux supplied output after closing a process stream.")
                }
                if (output.stdout.isNotEmpty()) stdoutChunks.send(output.stdout)
                if (output.stderr.isNotEmpty()) stderrChunks.send(output.stderr)
                if (output.stdoutClosed && !stdoutClosed) {
                    stdoutClosed = true
                    stdoutChunks.close()
                }
                if (output.stderrClosed && !stderrClosed) {
                    stderrClosed = true
                    stderrChunks.close()
                }
                if (output.exitCode != null) {
                    val previous = resultCode
                    if (previous != null && previous != output.exitCode) {
                        throw IOException("Termux changed an already reported process exit code.")
                    }
                    resultCode = output.exitCode
                }
                if (stdoutClosed && stderrClosed && resultCode != null) {
                    stdoutWriter.join()
                    stderrWriter.join()
                    failure.get()?.let { throw it }
                    // An observed exit and two drained producer streams prove the child is gone,
                    // even if deleting its completed transport record subsequently loses a reply.
                    terminationConfirmed = true
                    bridge.forget(ticket)
                    completion.countDown()
                    break
                }
            }
        } catch (cancelled: CancellationException) {
            fail(IOException("Termux process supervision was cancelled.", cancelled))
            cleanupAfterFailure()
        } catch (problem: IOException) {
            fail(problem)
            cleanupAfterFailure()
        } catch (_: Exception) {
            fail(IOException("Termux process supervision failed."))
            cleanupAfterFailure()
        } finally {
            stdoutChunks.close()
            stderrChunks.close()
            stdout.finish()
            stderr.finish()
            if (completion.count != 0L) {
                fail(IOException("Termux process supervision ended before a complete exit result."))
            }
        }
    }

    init {
        // A scope cancelled before launch can skip the coroutine body and its finally block.
        polling.invokeOnCompletion {
            if (completion.count != 0L) {
                fail(IOException("Termux process supervision ended before a complete exit result."))
            }
            if (failure.get() != null && !failureCleanupStarted.get()) {
                // The owning app scope may already be cancelled. This short-lived scope owns
                // just one bounded stop request and cancels itself when cleanup completes.
                val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                cleanupScope.launch {
                    try {
                        cleanupAfterFailure()
                    } finally {
                        cleanupScope.cancel()
                    }
                }
            }
        }
    }

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = stderr

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            throw IOException("Termux subprocess stdin is closed; interactive commands are unsupported.")
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset + length > buffer.size) throw IndexOutOfBoundsException()
            if (length != 0) throw IOException("Termux subprocess stdin is closed; interactive commands are unsupported.")
        }

        override fun close() = Unit
    }

    override fun waitFor(): Int {
        completion.await()
        return completedExitCode()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val done = completion.await(timeout, unit)
        if (done) failure.get()?.let { throw it }
        return done
    }

    override fun exitValue(): Int {
        if (completion.count != 0L) throw IllegalThreadStateException("Termux process is still being supervised.")
        return completedExitCode()
    }

    override fun isAlive(): Boolean = completion.count != 0L

    override fun destroy() {
        requestStop(1_500L)
    }

    override fun destroyForcibly(): Process {
        requestStop(0L)
        return this
    }

    suspend fun terminate(graceMillis: Long) {
        if (completion.count == 0L && failure.get() == null) return
        try {
            confirmTermination(graceMillis)
            if (failure.get() != null) bridge.releaseAfterFailure(ticket)
        } catch (problem: IOException) {
            fail(problem)
            polling.cancel()
            throw problem
        }
    }

    private fun requestStop(graceMillis: Long) {
        if (completion.count == 0L) return
        applicationScope.launch(Dispatchers.IO) {
            try {
                terminate(graceMillis)
            } catch (_: IOException) {
                // terminate already exposes the failure through both streams and waitFor.
            }
        }
    }

    private suspend fun writeStream(chunks: Channel<ByteArray>, output: BoundedProcessInputStream) {
        try {
            for (chunk in chunks) output.write(chunk)
        } catch (cancelled: CancellationException) {
            if (completion.count != 0L) fail(IOException("Termux stream supervision was cancelled.", cancelled))
        } catch (problem: IOException) {
            fail(problem)
        } finally {
            output.finish()
        }
    }

    private fun fail(problem: IOException) {
        if (!failure.compareAndSet(null, problem)) return
        stdoutChunks.cancel()
        stderrChunks.cancel()
        stdout.fail(problem)
        stderr.fail(problem)
        completion.countDown()
    }

    private suspend fun cleanupAfterFailure() {
        if (!failureCleanupStarted.compareAndSet(false, true)) return
        withContext(NonCancellable + Dispatchers.IO) {
            var confirmed = false
            try {
                confirmTermination(500L)
                confirmed = true
            } catch (_: IOException) {
                // Retain this ticket for an explicit Stop retry. No more process polls are sent;
                // the agent's per-process lease still expires even while health heartbeats run.
            } finally {
                if (confirmed) bridge.releaseAfterFailure(ticket)
                stdoutWriter.cancel()
                stderrWriter.cancel()
            }
        }
    }

    private fun completedExitCode(): Int {
        failure.get()?.let { throw it }
        return resultCode ?: throw IOException("Termux process completed without an exit code.")
    }

    private suspend fun confirmTermination(graceMillis: Long) = terminationMutex.withLock {
        if (!terminationConfirmed) {
            try {
                bridge.stop(ticket, graceMillis)
            } catch (problem: IOException) {
                // The poller may independently have observed a fully drained process exit while
                // this request was in flight, then deleted the completed record.
                if (!terminationConfirmed) throw problem
            }
            terminationConfirmed = true
        }
    }

    companion object {
        private const val PIPE_CAPACITY = 64 * 1024
        private const val QUEUE_CAPACITY = 4
    }
}
