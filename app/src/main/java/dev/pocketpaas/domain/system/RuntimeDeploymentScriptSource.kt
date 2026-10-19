package dev.pocketpaas.domain.system

fun interface RuntimeDeploymentScriptSource {
    suspend fun readDeploymentScript(): String
}
