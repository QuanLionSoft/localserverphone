package dev.pocketpaas.domain.deployment

import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.Site

data class DeploymentJournal(
    val deploymentId: String,
    val domainName: String,
    val projectType: ProjectType,
    val state: DeploymentJournalState,
)

enum class DeploymentJournalState { PREPARING, PREPARED, ACTIVATING, ACTIVE, ABORTING, ROLLED_BACK, COMPLETING }

/** Only validated journal records confer authority to remove staging/backup directories. */
interface RuntimeRecoveryWorkspace {
    suspend fun journals(): List<DeploymentJournal>
    suspend fun abort(site: Site, deploymentId: String)
    suspend fun complete(site: Site, deploymentId: String)
}
