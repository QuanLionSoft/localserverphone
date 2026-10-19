package dev.pocketpaas.domain.model

enum class DeploymentStatus { SUCCEEDED, FAILED, ROLLED_BACK, CANCELLED }

data class DeploymentRecord(
    val id: Long = 0,
    val siteId: Long?,
    val domainName: String,
    val projectType: ProjectType,
    val status: DeploymentStatus,
    val archiveName: String?,
    val archiveSizeBytes: Long?,
    val message: String?,
    val startedAt: Long,
    val finishedAt: Long?,
)
