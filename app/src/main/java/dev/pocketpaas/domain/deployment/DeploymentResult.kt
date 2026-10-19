package dev.pocketpaas.domain.deployment

data class DeploymentResult(
    val siteId: Long,
    val processId: Int?,
    val rootDir: String,
    val cleanupWarnings: List<String> = emptyList(),
)
