package dev.pocketpaas.domain.process

import dev.pocketpaas.domain.model.RuntimeLayout
import java.io.File

interface ProcessExecutor {
    suspend fun execute(request: ProcessRequest, timeoutMillis: Long): ProcessResult
    suspend fun executeCommand(command: String, envVars: Map<String, String>, workingDir: File): ProcessResult
    suspend fun startProcess(request: ProcessRequest): ManagedProcess
    suspend fun startProcess(arguments: List<String>, envVars: Map<String, String>, workingDir: File): ManagedProcess
    suspend fun resolveBinary(name: String): String
    suspend fun runtimeLayout(): RuntimeLayout
}
