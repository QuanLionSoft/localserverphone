package dev.pocketpaas.data.process

import dev.pocketpaas.core.config.ProcessPolicy
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.process.LaunchedProcess
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessIOException
import dev.pocketpaas.domain.process.ProcessLauncher
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.process.ProcessStream
import dev.pocketpaas.domain.process.ProcessTermination
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Optional
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellExecutorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val launcher = LocalProcessLauncher()

    @After
    fun closeScope() = runBlocking<Unit> {
        applicationScope.cancel()
        applicationScope.coroutineContext.job.join()
    }

    @Test(timeout = 20_000)
    fun concurrentMultiMegabytePipesAndUnterminatedLinesRemainBounded() = runBlocking<Unit> {
        val executor = executor(ProcessPolicy(maximumCaptureCharacters = 3_072))
        val result = executor.execute(request("flood"))
        assertTrue(result.isSuccessful)
        assertTrue(result.stdoutTruncated)
        assertTrue(result.stderrTruncated)
        assertTrue(result.stdout.length <= 3_072)
        assertTrue(result.stderr.length <= 3_072)
        assertTrue(result.stdout.endsWith("STDOUT-END"))
        assertTrue(result.stderr.endsWith("STDERR-END"))
    }

    @Test(timeout = 15_000)
    fun nonzeroExitReturnsCapturedError() = runBlocking<Unit> {
        val result = executor().execute(request("exit"))
        assertEquals(23, result.exitCode)
        assertEquals("fixture failed", result.stderr)
        assertFalse(result.isSuccessful)
        assertEquals(ProcessTermination.EXITED, result.termination)
    }

    @Test(timeout = 15_000)
    fun argvPreservesEnvironmentValuesAndWorkingDirectory() = runBlocking<Unit> {
        val directory = temporaryFolder.newFolder("directory with spaces")
        val value = "spaces & \"quotes\" = \u0130stanbul"
        val result = executor().execute(request("context", directory, mapOf("POCKETPAAS_FIXTURE" to value)))
        val lines = result.stdout.lineSequence().toList()
        assertTrue(result.isSuccessful)
        assertEquals(value, lines[0])
        assertEquals(directory.canonicalPath, lines[1])
    }

    @Test(timeout = 15_000)
    fun captureTruncationKeepsSurrogatePairsIntact() = runBlocking<Unit> {
        val result = executor(ProcessPolicy(maximumCaptureCharacters = 33)).execute(request("unicode"))
        assertTrue(result.isSuccessful)
        assertTrue(result.stdoutTruncated)
        assertTrue(result.stdout.length <= 33)
        assertFalse(result.stdout.first().isLowSurrogate())
        assertFalse(result.stdout.last().isHighSurrogate())
        assertEquals("\uD83D\uDE80".repeat(result.stdout.length / 2), result.stdout)
    }

    @Test(timeout = 15_000)
    fun timeoutTerminatesAndReapsTheChild() = runBlocking<Unit> {
        val result = executor().execute(request("wait"), timeoutMillis = 1_000)
        assertEquals(ProcessTermination.TIMED_OUT, result.termination)
        assertFalse(result.isSuccessful)
        result.processId?.let { assertFalse(pidIsAlive(it.toLong())) }
    }

    @Test(timeout = 20_000)
    fun cancellingExecuteTerminatesTheChildAndReleasesTheOnlyPermit() = runBlocking<Unit> {
        val executor = executor(ProcessPolicy(maxConcurrentProcesses = 1, stopGraceMillis = 50))
        val pidFile = File(temporaryFolder.root, "child.pid")
        val command = async(Dispatchers.Default) {
            executor.execute(request("pid-file", extraArguments = listOf(pidFile.absolutePath)))
        }
        withTimeout(10_000) { while (!pidFile.isFile || pidFile.length() == 0L) delay(10) }
        val pid = pidFile.readText().toLong()
        assertTrue(pidIsAlive(pid))
        command.cancelAndJoin()
        assertFalse(pidIsAlive(pid))
        val following = withTimeout(5_000) { executor.execute(request("context")) }
        assertTrue(following.isSuccessful)
    }

    @Test(timeout = 15_000)
    fun liveOutputReplaysAndStopIsIdempotent() = runBlocking<Unit> {
        val managed = executor().startProcess(request("wait"))
        val event = withTimeout(8_000) { managed.logs.first { it.text.contains("READY") } }
        assertEquals(managed.sessionId, event.sessionId)
        assertEquals(ProcessStream.STDOUT, event.stream)
        assertTrue(event.sequence > 0)
        val stopped = managed.stop()
        assertEquals(ProcessTermination.STOPPED, stopped.termination)
        assertEquals(stopped, managed.stop())
        assertEquals(stopped, managed.await())
        stopped.processId?.let { assertFalse(pidIsAlive(it.toLong())) }
    }

    @Test(timeout = 20_000)
    fun cancellationWhileWaitingForPermitDoesNotLeakCapacity() = runBlocking<Unit> {
        val executor = executor(ProcessPolicy(maxConcurrentProcesses = 1))
        val first = executor.startProcess(request("wait"))
        withTimeout(8_000) { first.logs.first { it.text.contains("READY") } }
        val waiting = async { executor.execute(request("context")) }
        delay(100)
        assertFalse(waiting.isCompleted)
        waiting.cancelAndJoin()
        first.stop()
        assertTrue(withTimeout(5_000) { executor.execute(request("context")) }.isSuccessful)
    }

    @Test(timeout = 25_000)
    fun queuedDaemonCannotOccupyTheReservedControlSlot() = runBlocking<Unit> {
        val executor = executor(ProcessPolicy(maxConcurrentProcesses = 2, stopGraceMillis = 50))
        val first = executor.startProcess(request("wait"))
        withTimeout(8_000) { first.logs.first { it.text.contains("READY") } }
        val waiterEntered = CompletableDeferred<Unit>()
        val waiting = async {
            waiterEntered.complete(Unit)
            executor.startProcess(request("wait"))
        }
        waiterEntered.await()
        delay(100)
        assertFalse(waiting.isCompleted)
        assertTrue(withTimeout(5_000) { executor.execute(request("context")) }.isSuccessful)
        assertFalse(waiting.isCompleted)
        first.stop()
        val next = withTimeout(5_000) { waiting.await() }
        withTimeout(8_000) { next.logs.first { it.text.contains("READY") } }
        assertEquals(ProcessTermination.STOPPED, next.stop().termination)
    }

    @Test(timeout = 25_000)
    fun cancellationWhileWaitingForDaemonCapacityDoesNotLeakEitherPermit() = runBlocking<Unit> {
        val executor = executor(ProcessPolicy(maxConcurrentProcesses = 2, stopGraceMillis = 50))
        val first = executor.startProcess(request("wait"))
        withTimeout(8_000) { first.logs.first { it.text.contains("READY") } }
        val waiting = async { executor.startProcess(request("wait")) }
        delay(100)
        assertFalse(waiting.isCompleted)
        waiting.cancelAndJoin()
        first.stop()
        val next = withTimeout(5_000) { executor.startProcess(request("wait")) }
        withTimeout(8_000) { next.logs.first { it.text.contains("READY") } }
        assertTrue(withTimeout(5_000) { executor.execute(request("context")) }.isSuccessful)
        next.stop()
    }

    @Test(timeout = 20_000)
    fun failedDaemonLaunchReleasesBothPermits() = runBlocking<Unit> {
        val failOnce = AtomicBoolean(true)
        val transientLauncher = object : ProcessLauncher {
            override suspend fun launch(request: ProcessRequest): LaunchedProcess {
                if (failOnce.compareAndSet(true, false)) throw ProcessIOException("Transient launch failure.")
                return launcher.launch(request)
            }
            override suspend fun resolveBinary(name: String): String = launcher.resolveBinary(name)
            override suspend fun runtimeLayout(): RuntimeLayout = launcher.runtimeLayout()
        }
        val executor = ShellExecutor(transientLauncher, ProcessPolicy(maxConcurrentProcesses = 2), applicationScope)
        try {
            executor.startProcess(request("wait"))
            fail("The failed launch must be reported.")
        } catch (failure: ProcessIOException) {
            assertEquals("Transient launch failure.", failure.message)
        }
        val next = withTimeout(5_000) { executor.startProcess(request("wait")) }
        withTimeout(8_000) { next.logs.first { it.text.contains("READY") } }
        assertTrue(withTimeout(5_000) { executor.execute(request("context")) }.isSuccessful)
        next.stop()
    }

    @Test(timeout = 20_000)
    fun cancellationAfterDaemonPermitWhileWaitingForTotalCapacityReleasesIt() = runBlocking<Unit> {
        val executor = executor(ProcessPolicy(maxConcurrentProcesses = 2, stopGraceMillis = 50))
        val pidFiles = List(2) { File(temporaryFolder.root, "command-$it.pid") }
        val commands = pidFiles.map { pidFile ->
            async(Dispatchers.Default) {
                executor.execute(request("pid-file", extraArguments = listOf(pidFile.absolutePath)))
            }
        }
        withTimeout(10_000) { while (pidFiles.any { !it.isFile || it.length() == 0L }) delay(10) }
        val waiting = async { executor.startProcess(request("wait")) }
        delay(100)
        assertFalse(waiting.isCompleted)
        waiting.cancelAndJoin()
        commands.forEach { it.cancelAndJoin() }
        val next = withTimeout(5_000) { executor.startProcess(request("wait")) }
        withTimeout(8_000) { next.logs.first { it.text.contains("READY") } }
        assertTrue(withTimeout(5_000) { executor.execute(request("context")) }.isSuccessful)
        next.stop()
    }

    @Test(timeout = 20_000)
    fun applicationScopeCancellationCleansUpAndPreservesCancellation() = runBlocking<Unit> {
        val pidFile = File(temporaryFolder.root, "application-scope-child.pid")
        val command = async(Dispatchers.Default) {
            executor().execute(request("pid-file", extraArguments = listOf(pidFile.absolutePath)))
        }
        withTimeout(10_000) { while (!pidFile.isFile || pidFile.length() == 0L) delay(10) }
        val pid = pidFile.readText().toLong()
        applicationScope.cancel()
        try {
            command.await()
            fail("Application scope cancellation must cancel command supervision.")
        } catch (_: CancellationException) {
            applicationScope.coroutineContext.job.join()
            assertFalse(pidIsAlive(pid))
        }
    }

    @Test(timeout = 15_000)
    fun scopeCancellationDuringLaunchCannotOrphanTheChild() = runBlocking<Unit> {
        lateinit var child: LaunchedProcess
        val cancelsScopeDuringLaunch = object : ProcessLauncher {
            override suspend fun launch(request: ProcessRequest): LaunchedProcess {
                child = launcher.launch(request)
                applicationScope.cancel()
                return child
            }
            override suspend fun resolveBinary(name: String): String = launcher.resolveBinary(name)
            override suspend fun runtimeLayout(): RuntimeLayout = launcher.runtimeLayout()
        }
        val executor = ShellExecutor(cancelsScopeDuringLaunch, ProcessPolicy(), applicationScope)
        try {
            executor.execute(request("wait"))
            fail("A canceled application scope cannot take ownership of a child.")
        } catch (_: CancellationException) {
            assertFalse(child.process.isAlive)
            child.processId?.let { assertFalse(pidIsAlive(it.toLong())) }
        }
    }

    @Test(timeout = 10_000)
    fun stdoutAndStderrReadOnDifferentSimultaneousThreads() = runBlocking<Unit> {
        val process = CoordinatedProcess()
        val executor = ShellExecutor(FakeLauncher(process), ProcessPolicy(), applicationScope)
        val result = executor.execute(dummyRequest())
        assertTrue(result.isSuccessful)
        assertEquals("stdout", result.stdout)
        assertEquals("stderr", result.stderr)
        assertEquals(2, process.readerThreadIds.size)
        assertNotEquals(process.readerThreadIds.first(), process.readerThreadIds.last())
    }

    @Test(timeout = 10_000)
    fun readFailureFailsTheResultAndTerminatesTheProcess() = runBlocking<Unit> {
        val process = CoordinatedProcess(failStdout = true)
        val executor = ShellExecutor(FakeLauncher(process), ProcessPolicy(), applicationScope)
        try {
            executor.execute(dummyRequest())
            fail("A broken output stream cannot produce a successful result.")
        } catch (failure: ProcessIOException) {
            assertTrue(failure.message.orEmpty().contains("STDOUT"))
            assertTrue(process.wasDestroyed.get())
        }
    }

    @Test(timeout = 10_000)
    fun stoppingAfterAReadFailureAcknowledgesCleanupWithoutRewritingTheFailedResult() = runBlocking<Unit> {
        val process = CoordinatedProcess(failStdout = true)
        val stops = AtomicInteger()
        val source = FakeLauncher(process) { stops.incrementAndGet(); process.destroy() }
        val managed = ShellExecutor(source, ProcessPolicy(), applicationScope).startProcess(dummyRequest())
        assertTrue(runCatching { managed.await() }.exceptionOrNull() is ProcessIOException)
        assertEquals(1, stops.get())
        val stopped = managed.stop()
        assertEquals(ProcessTermination.STOPPED, stopped.termination)
        assertFalse(stopped.isSuccessful)
        assertEquals(stopped, managed.stop())
        assertEquals(1, stops.get())
        assertTrue(runCatching { managed.await() }.exceptionOrNull() is ProcessIOException)
    }

    @Test(timeout = 10_000)
    fun anUnconfirmedCleanupCanFailThenBeRetriedUntilTheOwnedStopIsAcknowledged() = runBlocking<Unit> {
        val process = CoordinatedProcess(failStdout = true)
        val stops = AtomicInteger()
        val source = FakeLauncher(process) {
            val attempt = stops.incrementAndGet()
            process.destroy()
            if (attempt <= 2) throw IOException("The owned stop was not acknowledged.")
        }
        val managed = ShellExecutor(source, ProcessPolicy(), applicationScope).startProcess(dummyRequest())
        assertTrue(runCatching { managed.await() }.exceptionOrNull() is ProcessIOException)
        assertEquals(1, stops.get())
        val retryFailure = runCatching { managed.stop() }.exceptionOrNull()
        assertTrue(retryFailure is IOException)
        assertEquals("The owned stop was not acknowledged.", retryFailure?.message)
        assertEquals(2, stops.get())
        val stopped = managed.stop()
        assertEquals(ProcessTermination.STOPPED, stopped.termination)
        assertFalse(stopped.isSuccessful)
        assertEquals(3, stops.get())
        assertEquals(stopped, managed.stop())
        assertEquals(3, stops.get())
        assertTrue(runCatching { managed.await() }.exceptionOrNull() is ProcessIOException)
    }

    @Test(timeout = 10_000)
    fun concurrentCleanupRetriesShareOneAcknowledgedOwnedStop() = runBlocking<Unit> {
        val process = CoordinatedProcess(failStdout = true)
        val stops = AtomicInteger()
        val source = FakeLauncher(process) {
            val attempt = stops.incrementAndGet()
            process.destroy()
            if (attempt == 1) throw IOException("Initial cleanup acknowledgement was lost.")
        }
        val managed = ShellExecutor(source, ProcessPolicy(), applicationScope).startProcess(dummyRequest())
        assertTrue(runCatching { managed.await() }.exceptionOrNull() is ProcessIOException)
        val callers = List(8) { async(Dispatchers.Default) { managed.stop() } }
        val results = callers.map { it.await() }
        assertTrue(results.all { it.termination == ProcessTermination.STOPPED && !it.isSuccessful })
        assertTrue(results.all { it == results.first() })
        assertEquals(2, stops.get())
    }

    @Test(timeout = 10_000)
    fun requiredShellApiExecutesARealShellCommand() = runBlocking<Unit> {
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            "echo shell-output"
        } else {
            "printf 'shell-output\\n'"
        }
        val result = executor().executeCommand(command, emptyMap(), temporaryFolder.root)
        assertTrue(result.isSuccessful)
        assertEquals("shell-output", result.stdout.trim())
    }

    @Test(timeout = 10_000)
    fun unavailableBinaryFailsExplicitly() = runBlocking<Unit> {
        try {
            launcher.resolveBinary("pocketpaas-nonexistent-binary-89f73d")
            fail("Missing executables must be reported.")
        } catch (failure: ProcessIOException) {
            assertTrue(failure.message.orEmpty().contains("not found"))
        }
    }

    private fun executor(policy: ProcessPolicy = ProcessPolicy()): ShellExecutor =
        ShellExecutor(launcher, policy, applicationScope)

    private fun request(
        mode: String,
        directory: File = temporaryFolder.root,
        environment: Map<String, String> = emptyMap(),
        extraArguments: List<String> = emptyList(),
    ): ProcessRequest {
        val java = File(System.getProperty("java.home"), "bin/java" + if (File.separatorChar == '\\') ".exe" else "")
        val classpath = listOf(ProcessFixture::class.java, Unit::class.java).map { type ->
            File(type.protectionDomain.codeSource.location.toURI()).absolutePath
        }.distinct().joinToString(File.pathSeparator)
        return ProcessRequest(
            ProcessCommand.Arguments(listOf(java.absolutePath, "-Dfile.encoding=UTF-8", "-cp", classpath, ProcessFixture::class.java.name, mode) + extraArguments),
            environment,
            directory,
        )
    }

    private fun dummyRequest(): ProcessRequest = ProcessRequest(
        ProcessCommand.Arguments(listOf("fixture")), emptyMap(), temporaryFolder.root,
    )

    private fun pidIsAlive(pid: Long): Boolean {
        val type = Class.forName("java.lang.ProcessHandle")
        val handle = type.getMethod("of", java.lang.Long.TYPE).invoke(null, pid) as Optional<*>
        return handle.isPresent && type.getMethod("isAlive").invoke(handle.get()) as Boolean
    }

    private class FakeLauncher(
        private val process: CoordinatedProcess,
        private val terminate: () -> Unit = { process.destroy() },
    ) : ProcessLauncher {
        override suspend fun launch(request: ProcessRequest): LaunchedProcess =
            LaunchedProcess(process, null) { terminate() }
        override suspend fun resolveBinary(name: String): String = name
        override suspend fun runtimeLayout(): RuntimeLayout = RuntimeLayout("/tmp", "/tmp/projects", "/tmp/nginx.conf", "/tmp/conf.d", "/tmp/runtime", "/tmp/logs")
    }

    private class CoordinatedProcess(private val failStdout: Boolean = false) : Process() {
        private val bothReaders = CountDownLatch(2)
        private val bothFinished = CountDownLatch(2)
        private val destroyed = CountDownLatch(1)
        val readerThreadIds = ConcurrentLinkedQueue<Long>()
        val wasDestroyed = AtomicBoolean()
        private val stdout = CoordinatedInput("stdout", failStdout)
        private val stderr = CoordinatedInput("stderr", false)
        private val stdin = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun getOutputStream() = stdin
        override fun waitFor(): Int {
            bothFinished.await()
            if (failStdout) destroyed.await()
            return if (wasDestroyed.get()) 143 else 0
        }
        override fun exitValue(): Int {
            if (bothFinished.count != 0L || (failStdout && !wasDestroyed.get())) throw IllegalThreadStateException()
            return if (wasDestroyed.get()) 143 else 0
        }
        override fun destroy() {
            wasDestroyed.set(true)
            destroyed.countDown()
            while (bothReaders.count > 0) bothReaders.countDown()
            while (bothFinished.count > 0) bothFinished.countDown()
        }

        private inner class CoordinatedInput(value: String, private val fails: Boolean) : InputStream() {
            private val bytes = value.toByteArray(Charsets.UTF_8)
            private var offset = 0
            private val entered = AtomicBoolean()
            private val finished = AtomicBoolean()
            override fun read(): Int {
                val buffer = ByteArray(1)
                return if (read(buffer, 0, 1) == -1) -1 else buffer[0].toInt() and 0xff
            }
            override fun read(destination: ByteArray, start: Int, length: Int): Int {
                if (entered.compareAndSet(false, true)) {
                    readerThreadIds.add(Thread.currentThread().id)
                    bothReaders.countDown()
                    if (!bothReaders.await(3, TimeUnit.SECONDS)) throw IOException("Both pipes must drain concurrently.")
                }
                if (fails) throw IOException("Simulated broken pipe.")
                if (offset == bytes.size) {
                    if (finished.compareAndSet(false, true)) bothFinished.countDown()
                    return -1
                }
                val count = minOf(length, bytes.size - offset)
                bytes.copyInto(destination, start, offset, offset + count)
                offset += count
                return count
            }
            override fun close() {
                if (finished.compareAndSet(false, true)) bothFinished.countDown()
            }
        }
    }
}
