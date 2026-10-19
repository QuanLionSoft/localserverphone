package dev.pocketpaas.domain.deployment

import dev.pocketpaas.domain.model.Site

enum class ProjectEntryPoint { STATIC, DJANGO, FLASK, NODEJS, REACT }

data class ProjectMetadata(
    val rootDir: String,
    val entryPoint: ProjectEntryPoint,
    val hasRequirements: Boolean,
    val hasPackageJson: Boolean,
)

data class PreparedProject(
    val deploymentId: String,
    val stagingPath: String,
    val activePath: String,
    val backupPath: String,
    val archivePath: String,
    val hadPreviousProject: Boolean,
    val metadata: ProjectMetadata,
)

/** Every operation runs with the runtime UID and journals its directory swaps. */
interface DeploymentWorkspace {
    suspend fun prepare(site: Site, archivePath: String, deploymentId: String): PreparedProject
    suspend fun activate(site: Site, prepared: PreparedProject)
    suspend fun rollback(site: Site, prepared: PreparedProject)
    suspend fun abort(site: Site, deploymentId: String)
    suspend fun complete(site: Site, prepared: PreparedProject)
    suspend fun inspect(site: Site): ProjectMetadata
}
