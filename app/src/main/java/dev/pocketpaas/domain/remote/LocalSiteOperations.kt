package dev.pocketpaas.domain.remote

import dev.pocketpaas.domain.deployment.ProjectArchiveSource
import dev.pocketpaas.domain.model.Site

interface LocalSiteOperations : RemoteSiteOperations {
    suspend fun deploy(request: RemoteDeployRequest, source: ProjectArchiveSource): Site
}
