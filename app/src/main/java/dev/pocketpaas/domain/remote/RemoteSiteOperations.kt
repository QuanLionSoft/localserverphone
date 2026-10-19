package dev.pocketpaas.domain.remote

import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.Site
import java.io.File

data class RemoteDeployRequest(
    val siteId: Long? = null,
    val domainName: String? = null,
    val projectType: ProjectType? = null,
)

interface RemoteSiteOperations {
    suspend fun deploy(request: RemoteDeployRequest, archive: File): Site
    suspend fun start(siteId: Long): Site
    suspend fun stop(siteId: Long): Site
}

enum class RemoteErrorKind { NOT_FOUND, CONFLICT, UNAVAILABLE, FAILED }

class RemoteOperationException(val kind: RemoteErrorKind) : IllegalStateException(kind.name)
