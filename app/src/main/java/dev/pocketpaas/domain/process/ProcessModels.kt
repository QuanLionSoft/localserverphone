package dev.pocketpaas.domain.process

import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.SharedFlow

sealed interface ProcessCommand {
    data class Shell(val value: String) : ProcessCommand
    data class Arguments(val values: List<String>) : ProcessCommand
}

data class ProcessRequest(
    val command: ProcessCommand,
    val environment: Map<String, String>,
    val workingDir: File,
)

data class LaunchedProcess(
    val process: Process,
    val processId: Int?,
    /** Returns only after the owned process has acknowledged termination; throws if unconfirmed. */
    val terminate: suspend (graceMillis: Long) -> Unit,
)

enum class ProcessStream { STDOUT, STDERR }

enum class ProcessTermination { EXITED, TIMED_OUT, STOPPED }

data class ProcessOutput(
    val sessionId: String,
    val stream: ProcessStream,
    val text: String,
    val sequence: Long,
    val timestampMillis: Long,
)

data class ProcessResult(
    val processId: Int?,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val durationMillis: Long,
    val termination: ProcessTermination,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
) {
    val isSuccessful: Boolean
        get() = termination == ProcessTermination.EXITED && exitCode == 0
}

interface ManagedProcess {
    val sessionId: String
    val processId: Int?
    val logs: SharedFlow<ProcessOutput>
    suspend fun await(): ProcessResult
    /** Acknowledges owned cleanup after execution failure without changing the failed await() result. */
    suspend fun stop(): ProcessResult
}

class ProcessIOException(message: String, cause: Throwable? = null) : IOException(message, cause)
