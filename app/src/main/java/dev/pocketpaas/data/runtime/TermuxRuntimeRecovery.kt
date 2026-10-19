package dev.pocketpaas.data.runtime

import dev.pocketpaas.domain.deployment.DeploymentJournalState
import dev.pocketpaas.domain.deployment.RuntimeRecoveryWorkspace
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.process.ProcessExecutor
import dev.pocketpaas.domain.repository.ServerStateRepository
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.runtime.RuntimeRecovery
import dev.pocketpaas.domain.runtime.RuntimeReset
import dev.pocketpaas.domain.system.RuntimeFileStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Coordinator must serialize this with every operation and call it before creating new children. */
@Singleton
class TermuxRuntimeRecovery @Inject constructor(
    private val reset: RuntimeReset,
    private val workspace: RuntimeRecoveryWorkspace,
    private val shell: ProcessExecutor,
    private val files: RuntimeFileStore,
    private val sites: SiteRepository,
    private val server: ServerStateRepository,
) : RuntimeRecovery {
    override suspend fun recover() {
        val registered = sites.listSites()
        if (registered.size > 64) throw IOException("Runtime recovery supports at most 64 registered sites.")
        registered.forEach {
            SiteInput.validateSiteId(it.id)
            require(SiteInput.normalizeDomainName(it.domainName) == it.domainName) { "Persisted site domain is not canonical." }
        }
        if (registered.map { it.domainName }.distinct().size != registered.size) {
            throw IOException("Duplicate site identities require database recovery.")
        }
        // No file mutation or PID clearing is permitted until the authenticated agent proves quiescence.
        reset.resetRuntime(registered.mapNotNull { it.processId }.distinct())
        val byDomain = registered.associateBy { it.domainName }
        val journals = workspace.journals()
        if (journals.size > 64 || journals.map { it.domainName }.distinct().size != journals.size) {
            throw IOException("Conflicting deployment journals require manual recovery.")
        }
        journals.forEach { journal ->
            val site = byDomain[journal.domainName]
                ?: throw IOException("A deployment journal has no registered site; project files were retained.")
            if (site.projectType != journal.projectType) throw IOException("Deployment journal project type changed; files were retained.")
        }
        val layout = shell.runtimeLayout()
        val expectedHosts = registered.associateBy { "${layout.nginxHostsPath.trimEnd('/')}/${it.domainName}.conf" }
        val hostFiles = files.listFiles(layout.nginxHostsPath)
        if (hostFiles.any { it.endsWith(".conf") && it !in expectedHosts }) {
            throw IOException("An unregistered Nginx host requires manual recovery; configurations were retained.")
        }
        journals.forEach { journal ->
            val site = checkNotNull(byDomain[journal.domainName])
            if (journal.state == DeploymentJournalState.COMPLETING ||
                (journal.state == DeploymentJournalState.ACTIVE && site.status == SiteStatus.RUNNING)
            ) workspace.complete(site, journal.deploymentId) else workspace.abort(site, journal.deploymentId)
        }
        // Nginx is confirmed stopped. Remove only registered generated fragments, preserving projects.
        hostFiles.filter { it in expectedHosts }.forEach { files.delete(it) }
        server.updatePublicUrl(null)
        registered.forEach { site ->
            val fresh = sites.getSite(site.id) ?: throw IOException("A site disappeared during runtime recovery.")
            if (fresh.domainName != site.domainName || fresh.projectType != site.projectType ||
                fresh.internalPort != site.internalPort || fresh.status != site.status || fresh.processId != site.processId
            ) throw IOException("A site changed outside the serialized runtime coordinator.")
            sites.updatePublicUrl(site.id, null)
            val status = when (site.status) {
                SiteStatus.DEPLOYING -> SiteStatus.FAILED
                SiteStatus.RUNNING -> SiteStatus.STOPPED
                SiteStatus.FAILED -> SiteStatus.FAILED
                SiteStatus.STOPPED -> SiteStatus.STOPPED
            }
            sites.updateRuntimeState(site.id, status, null)
            if (site.status == SiteStatus.RUNNING || site.status == SiteStatus.DEPLOYING || site.processId != null) {
                sites.appendLogs(site.id, "[recovery] Previous runtime stopped and persisted deployment journal reconciled; start explicitly to resume.\n")
            }
        }
    }
}
