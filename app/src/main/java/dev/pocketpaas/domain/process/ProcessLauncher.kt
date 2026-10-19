package dev.pocketpaas.domain.process

import dev.pocketpaas.domain.model.RuntimeLayout

interface ProcessLauncher {
    suspend fun launch(request: ProcessRequest): LaunchedProcess
    suspend fun resolveBinary(name: String): String
    suspend fun runtimeLayout(): RuntimeLayout
}
