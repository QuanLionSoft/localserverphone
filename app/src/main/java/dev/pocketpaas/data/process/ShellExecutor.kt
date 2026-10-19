package dev.pocketpaas.data.process

import dev.pocketpaas.core.config.ProcessPolicy
import dev.pocketpaas.core.di.ApplicationScope
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.process.LaunchedProcess
import dev.pocketpaas.domain.process.ManagedProcess
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessExecutor
import dev.pocketpaas.domain.process.ProcessIOException
import dev.pocketpaas.domain.process.ProcessLauncher
import dev.pocketpaas.domain.process.ProcessOutput
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.process.ProcessResult
import dev.pocketpaas.domain.process.ProcessStream
import dev.pocketpaas.domain.process.ProcessTermination
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Owns process lifetimes and drains both output pipes independently of process waits. */
@Singleton
class ShellExecutor @Inject constructor(
    private val launcher: ProcessLauncher,
    private val policy: ProcessPolicy,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ProcessExecutor {
    private val permits = Semaphore(policy.maxConcurrentProcesses)
    private val longRunningPermits = Semaphore(policy.maxLongRunningProcesses)
    private val stdoutDispatcher = Dispatchers.IO.limitedParallelism(policy.maxConcurrentProcesses)
    private val stderrDispatcher = Dispatchers.IO.limitedParallelism(policy.maxConcurrentProcesses)
    private val waitDispatcher = Dispatchers.IO.limitedParallelism(policy.maxConcurrentProcesses)

    override suspend fun executeCommand(
        command: String,
        envVars: Map<String, String>,
        workingDir: File,
    ): ProcessResult = execute(ProcessRequest(ProcessCommand.Shell(command), envVars, workingDir))

    suspend fun execute(request: ProcessRequest): ProcessResult = execute(request, policy.commandTimeoutMillis)

    override suspend fun execute(
        request: ProcessRequest,
        timeoutMillis: Long,
    ): ProcessResult {
        require(timeoutMillis > 0) { "The command timeout must be positive." }
        val process = createManagedProcess(request, timeoutMillis)
        return try {
            process.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                suppress(cancelled, runCatching { process.stop() }.exceptionOrNull())
            }
            throw cancelled
        }
    }

    /** A managed server survives the initiating coroutine and runs until exit or stop. */
    override suspend fun startProcess(request: ProcessRequest): ManagedProcess = createManagedProcess(request, null)

    override suspend fun startProcess(
        arguments: List<String>,
        envVars: Map<String, String>,
        workingDir: File,
    ): ManagedProcess = startProcess(ProcessRequest(ProcessCommand.Arguments(arguments), envVars, workingDir))

    override suspend fun resolveBinary(name: String): String = launcher.resolveBinary(name)

    override suspend fun runtimeLayout(): RuntimeLayout = launcher.runtimeLayout()

    private suspend fun createManagedProcess(request: ProcessRequest, timeoutMillis: Long?): RunningProcess {
        val immutableRequest = snapshotAndValidate(request)
        currentCoroutineContext().ensureActive()
        applicationScope.ensureActive()
        val longRunning = timeoutMillis == null
        var longRunningPermitAcquired = false
        var processPermitAcquired = false
        var ownershipTransferred = false
        try {
            // Waiting daemons acquire no total-process slot, so a control command can still run.
            if (longRunning) {
                longRunningPermits.acquire()
                longRunningPermitAcquired = true
            }
            permits.acquire()
            processPermitAcquired = true
            // Launch and transfer ownership together: cancellation cannot leave an untracked child.
            val managed = withContext(NonCancellable) {
                applicationScope.ensureActive()
                val launched = launcher.launch(immutableRequest)
                try {
                    applicationScope.ensureActive()
                    val session = RunningProcess(launched, policy.stopGraceMillis)
                    val monitorEntered = AtomicBoolean()
                    val result = applicationScope.async(start = CoroutineStart.UNDISPATCHED) {
                        monitorEntered.set(true)
                        try {
                            supervise(launched, session, timeoutMillis)
                        } finally {
                            permits.release()
                            if (longRunning) longRunningPermits.release()
                        }
                    }
                    // UNDISTPATCHED enters synchronously. Refuse ownership if no monitor started.
                    if (!monitorEntered.get()) {
                        result.cancel()
                        throw CancellationException("Application scope stopped before process supervision started.")
                    }
                    session.attach(result)
                    ownershipTransferred = true
                    session
                } catch (failure: Throwable) {
                    suppress(failure, runCatching { launched.terminate(policy.stopGraceMillis) }.exceptionOrNull())
                    suppress(failure, closeStreams(launched.process))
                    throw failure
                }
            }
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    suppress(cancelled, runCatching { managed.stop() }.exceptionOrNull())
                }
                throw cancelled
            }
            return managed
        } finally {
            if (!ownershipTransferred) {
                if (processPermitAcquired) permits.release()
                if (longRunningPermitAcquired) longRunningPermits.release()
            }
        }
    }

    private suspend fun supervise(
        launched: LaunchedProcess,
        session: RunningProcess,
        timeoutMillis: Long?,
    ): ProcessResult = supervisorScope {
        val startedAt = System.nanoTime()
        val stdout = BoundedTail(policy.maximumCaptureCharacters)
        val stderr = BoundedTail(policy.maximumCaptureCharacters)
        val readerFailure = CompletableDeferred<ProcessIOException>()
        val stdoutReader = async(stdoutDispatcher) {
            gobble(launched.process.inputStream, ProcessStream.STDOUT, stdout, session, readerFailure)
        }
        val stderrReader = async(stderrDispatcher) {
            gobble(launched.process.errorStream, ProcessStream.STDERR, stderr, session, readerFailure)
        }
        // These three jobs use separate IO pools. A blocking wait never occupies a reader slot.
        val processWait = async(waitDispatcher) {
            try {
                launched.process.waitFor()
            } catch (failure: Exception) {
                throw ProcessIOException("Waiting for process ${session.sessionId} failed.", failure)
            }
        }
        val timeout = timeoutMillis?.let { duration -> async { delay(duration) } }
        var primaryFailure: Throwable? = null
        try {
            withContext(Dispatchers.IO) { launched.process.outputStream.close() }
            val termination = select {
                processWait.onAwait { ProcessTermination.EXITED }
                session.stopSignal.onAwait { ProcessTermination.STOPPED }
                readerFailure.onAwait { throw it }
                timeout?.onAwait { ProcessTermination.TIMED_OUT }
            }
            if (termination != ProcessTermination.EXITED) session.terminate(policy.stopGraceMillis)
            val exitCode = try {
                withTimeout(policy.drainTimeoutMillis) { processWait.await() }
            } catch (failure: TimeoutCancellationException) {
                throw ProcessIOException("The terminated process could not be reaped.", failure)
            }
            try {
                withTimeout(policy.drainTimeoutMillis) {
                    stdoutReader.await()
                    stderrReader.await()
                }
            } catch (failure: TimeoutCancellationException) {
                throw ProcessIOException("Process output pipes did not close after exit.", failure)
            }
            ProcessResult(
                processId = launched.processId,
                exitCode = exitCode,
                stdout = stdout.text,
                stderr = stderr.text,
                durationMillis = (System.nanoTime() - startedAt) / 1_000_000,
                termination = termination,
                stdoutTruncated = stdout.truncated,
                stderrTruncated = stderr.truncated,
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            withContext(NonCancellable) {
                // Cancellation makes a blocking job inactive before its read/wait actually ends.
                // Kill first on every failure; closing a pipe before that can wait on the read lock.
                if (primaryFailure != null || !processWait.isCompleted || !stdoutReader.isCompleted || !stderrReader.isCompleted) {
                    cleanupFailure = runCatching { session.terminate(policy.stopGraceMillis) }.exceptionOrNull()
                }
                closeStreams(launched.process)?.let { failure ->
                    if (cleanupFailure == null) cleanupFailure = failure else suppress(cleanupFailure!!, failure)
                }
                timeout?.cancel()
                stdoutReader.cancel()
                stderrReader.cancel()
                processWait.cancel()
                stdoutReader.join()
                stderrReader.join()
                processWait.join()
                timeout?.join()
                session.captureOutput(stdout.text, stderr.text, stdout.truncated, stderr.truncated)
            }
            cleanupFailure?.let { failure ->
                if (primaryFailure != null) suppress(primaryFailure, failure)
                else throw ProcessIOException("Process cleanup failed.", failure)
            }
        }
    }

    private fun gobble(
        input: InputStream,
        stream: ProcessStream,
        capture: BoundedTail,
        session: RunningProcess,
        failureSignal: CompletableDeferred<ProcessIOException>,
    ) {
        try {
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4_096)
                var pendingHighSurrogate: Char? = null
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    var chunk = String(buffer, 0, count)
                    pendingHighSurrogate?.let { chunk = it + chunk }
                    pendingHighSurrogate = null
                    if (chunk.last().isHighSurrogate()) {
                        pendingHighSurrogate = chunk.last()
                        chunk = chunk.dropLast(1)
                    }
                    if (chunk.isNotEmpty()) {
                        capture.append(chunk)
                        session.publish(stream, chunk)
                    }
                }
                if (pendingHighSurrogate != null) {
                    capture.append("\uFFFD")
                    session.publish(stream, "\uFFFD")
                }
            }
        } catch (failure: Exception) {
            val detail = failure.message?.replace(Regex("[\\r\\n\\u0000]+"), " ")?.take(240)
            val wrapped = ProcessIOException(
                "Reading $stream for ${session.sessionId} failed (${failure.javaClass.simpleName})" +
                    if (detail.isNullOrBlank()) "." else ": $detail",
                failure,
            )
            failureSignal.complete(wrapped)
            throw wrapped
        }
    }

    private fun snapshotAndValidate(request: ProcessRequest): ProcessRequest {
        val command = when (val value = request.command) {
            is ProcessCommand.Shell -> {
                require(value.value.isNotBlank() && '\u0000' !in value.value) { "The shell command is empty or invalid." }
                value
            }
            is ProcessCommand.Arguments -> {
                require(value.values.isNotEmpty() && value.values.first().isNotBlank()) { "Executable arguments are empty." }
                require(value.values.none { '\u0000' in it }) { "Arguments cannot contain NUL." }
                ProcessCommand.Arguments(value.values.toList())
            }
        }
        val environment = request.environment.toMap()
        environment.forEach { (name, value) ->
            require(ENVIRONMENT_NAME.matches(name)) { "Invalid environment variable name: $name" }
            require('\u0000' !in value) { "Environment variable values cannot contain NUL." }
        }
        require(request.workingDir.isAbsolute) { "The process working directory must be absolute." }
        return request.copy(command = command, environment = environment, workingDir = request.workingDir.absoluteFile)
    }

    private fun closeStreams(process: Process): Throwable? {
        var failure: Throwable? = null
        listOf(process.outputStream, process.inputStream, process.errorStream).forEach { stream ->
            runCatching { stream.close() }.exceptionOrNull()?.let { cause ->
                if (failure == null) failure = cause else suppress(failure!!, cause)
            }
        }
        return failure
    }

    private fun suppress(primary: Throwable, secondary: Throwable?) {
        if (secondary != null && secondary !== primary) primary.addSuppressed(secondary)
    }

    private class RunningProcess(
        private val launched: LaunchedProcess,
        private val stopGraceMillis: Long,
    ) : ManagedProcess {
        override val processId: Int? = launched.processId
        override val sessionId: String = UUID.randomUUID().toString()
        private val startedAt = System.nanoTime()
        private val terminationMutex = Mutex()
        private var terminationConfirmed = false
        private var stopAcknowledgement: ProcessResult? = null
        @Volatile private var captured = CapturedOutput("", "", false, false)
        val stopSignal = CompletableDeferred<Unit>()
        private val sequence = AtomicLong()
        private val output = MutableSharedFlow<ProcessOutput>(
            replay = 128,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val logs: SharedFlow<ProcessOutput> = output.asSharedFlow()
        private lateinit var result: Deferred<ProcessResult>

        fun attach(completion: Deferred<ProcessResult>) {
            result = completion
        }

        @Synchronized
        fun publish(stream: ProcessStream, text: String) {
            // Gaps in sequence expose dropped old events to slow consumers without blocking pipes.
            output.tryEmit(ProcessOutput(sessionId, stream, text, sequence.incrementAndGet(), System.currentTimeMillis()))
        }

        override suspend fun await(): ProcessResult = result.await()

        override suspend fun stop(): ProcessResult {
            stopSignal.complete(Unit)
            try {
                return result.await()
            } catch (failure: Exception) {
                // A failed output/result is historical evidence, not proof that termination failed.
                // Keep await() failed, but allow a fresh, owned stop acknowledgement to recover.
                currentCoroutineContext().ensureActive()
                return terminationMutex.withLock {
                    try {
                        terminateLocked(stopGraceMillis)
                    } catch (stopFailure: Exception) {
                        if (stopFailure !== failure) stopFailure.addSuppressed(failure)
                        throw stopFailure
                    }
                    stopAcknowledgement ?: captured.let { output ->
                        ProcessResult(
                            processId = processId,
                            exitCode = runCatching { launched.process.exitValue() }.getOrNull(),
                            stdout = output.stdout,
                            stderr = output.stderr,
                            durationMillis = (System.nanoTime() - startedAt) / 1_000_000,
                            termination = ProcessTermination.STOPPED,
                            stdoutTruncated = output.stdoutTruncated,
                            stderrTruncated = output.stderrTruncated,
                        ).also { stopAcknowledgement = it }
                    }
                }
            }
        }

        suspend fun terminate(graceMillis: Long) = terminationMutex.withLock {
            terminateLocked(graceMillis)
        }

        private suspend fun terminateLocked(graceMillis: Long) {
            if (terminationConfirmed) return
            launched.terminate(graceMillis)
            terminationConfirmed = true
        }

        fun captureOutput(stdout: String, stderr: String, stdoutTruncated: Boolean, stderrTruncated: Boolean) {
            captured = CapturedOutput(stdout, stderr, stdoutTruncated, stderrTruncated)
        }
    }

    private data class CapturedOutput(
        val stdout: String,
        val stderr: String,
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean,
    )

    private class BoundedTail(private val capacity: Int) {
        private val buffer = StringBuilder(capacity)
        var truncated: Boolean = false
            private set
        val text: String get() = buffer.toString()

        fun append(text: String) {
            buffer.append(text)
            if (buffer.length > capacity) {
                var remove = buffer.length - capacity
                if (remove < buffer.length && buffer[remove].isLowSurrogate() && buffer[remove - 1].isHighSurrogate()) remove++
                buffer.delete(0, remove)
                truncated = true
            }
        }
    }

    private companion object {
        val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
