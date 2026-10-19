package dev.pocketpaas.domain.model

enum class ActivityCategory { SYSTEM, NETWORK, DEPLOYMENT, SERVICE, SSH, SECURITY, SITE }

enum class ActivityLevel { INFO, WARNING, ERROR }

data class ActivityEvent(
    val id: Long,
    val category: ActivityCategory,
    val level: ActivityLevel,
    val message: String,
    val detail: String?,
    val timestamp: Long,
)
