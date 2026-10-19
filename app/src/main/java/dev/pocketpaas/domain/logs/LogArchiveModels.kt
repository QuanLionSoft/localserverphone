package dev.pocketpaas.domain.logs

import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityEvent
import dev.pocketpaas.domain.model.ActivityLevel
import dev.pocketpaas.domain.model.DeploymentRecord

enum class LogExportFormat {
    JSON,
    CSV,
}

data class ActivityFilter(
    val query: String = "",
    val level: ActivityLevel? = null,
    val category: ActivityCategory? = null,
) {
    val isActive: Boolean
        get() = query.isNotBlank() || level != null || category != null

    fun matches(event: ActivityEvent): Boolean {
        if (level != null && event.level != level) return false
        if (category != null && event.category != category) return false
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return event.message.contains(needle, ignoreCase = true) ||
            (event.detail?.contains(needle, ignoreCase = true) == true) ||
            event.category.name.contains(needle, ignoreCase = true) ||
            event.level.name.contains(needle, ignoreCase = true)
    }

    fun matchesDeployment(record: DeploymentRecord): Boolean {
        if (category != null && category != ActivityCategory.DEPLOYMENT && category != ActivityCategory.SITE) {
            return false
        }
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return record.domainName.contains(needle, ignoreCase = true) ||
            record.projectType.name.contains(needle, ignoreCase = true) ||
            record.status.name.contains(needle, ignoreCase = true) ||
            (record.archiveName?.contains(needle, ignoreCase = true) == true) ||
            (record.message?.contains(needle, ignoreCase = true) == true)
    }
}
