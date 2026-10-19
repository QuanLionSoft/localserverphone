package dev.pocketpaas.domain.remote

import java.io.File

interface RemoteApiServer {
    suspend fun start()
    suspend fun stop()
}

fun interface RemoteApiServerFactory {
    fun create(bindHost: String, operations: RemoteSiteOperations): RemoteApiServer
}

data class RemoteUploadStorage(val directory: File)
