package dev.pocketpaas.domain.system

fun interface RuntimeScriptSource {
    suspend fun readFileScript(): String
}
