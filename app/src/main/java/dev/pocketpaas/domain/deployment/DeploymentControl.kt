package dev.pocketpaas.domain.deployment

import dev.pocketpaas.domain.model.Site
import java.io.File

interface DeploymentControl {
    suspend fun deployProject(site: Site, archive: File): DeploymentResult
    suspend fun startSite(siteId: Long): DeploymentResult
    suspend fun stopSite(siteId: Long)
    suspend fun stopAll()
}
