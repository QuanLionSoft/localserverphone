package dev.pocketpaas.data.runtime

import dev.pocketpaas.domain.deployment.DeploymentJournal
import dev.pocketpaas.domain.deployment.DeploymentJournalState
import dev.pocketpaas.domain.deployment.RuntimeRecoveryWorkspace
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteCollectionState
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.process.ManagedProcess
import dev.pocketpaas.domain.process.ProcessExecutor
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.process.ProcessResult
import dev.pocketpaas.domain.repository.ServerStateRepository
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.runtime.RuntimeReset
import dev.pocketpaas.domain.system.RuntimeFileStore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxRuntimeRecoveryTest {
    @Test
    fun committedRunningJournalCompletesBeforeItsSiteBecomesStopped() = runBlocking {
        val fixture = Fixture(listOf(site(1, SiteStatus.RUNNING, 321)))
        fixture.journals += journal(1, DeploymentJournalState.ACTIVE)
        fixture.recovery.recover()
        assertEquals(listOf(321), fixture.probed)
        assertTrue(fixture.events.indexOf("reset") < fixture.events.indexOf("complete:1"))
        assertTrue(fixture.events.indexOf("complete:1") < fixture.events.indexOf("status:1:STOPPED"))
        assertEquals(SiteStatus.STOPPED, fixture.repository.getSite(1)?.status)
        assertNull(fixture.repository.getSite(1)?.processId)
        assertNull(fixture.repository.getSite(1)?.publicUrl)
        assertNull(fixture.serverUrl.value)
        assertTrue(fixture.hosts.isEmpty())
    }

    @Test
    fun interruptedDeploymentAbortsButACompletingJournalAlwaysPreservesItsCommit() = runBlocking {
        val fixture = Fixture(listOf(site(1, SiteStatus.DEPLOYING, 322), site(2, SiteStatus.FAILED, null)))
        fixture.journals += journal(1, DeploymentJournalState.ACTIVE)
        fixture.journals += journal(2, DeploymentJournalState.COMPLETING)
        fixture.recovery.recover()
        assertTrue("abort:1" in fixture.events)
        assertTrue("complete:2" in fixture.events)
        assertFalse("complete:1" in fixture.events)
        assertEquals(SiteStatus.FAILED, fixture.repository.getSite(1)?.status)
        assertEquals(SiteStatus.FAILED, fixture.repository.getSite(2)?.status)
    }

    @Test
    fun uncertainAgentOwnershipRetainsPidsUrlsAndProjectJournals() = runBlocking {
        val original = site(1, SiteStatus.RUNNING, 999)
        val fixture = Fixture(listOf(original))
        fixture.resetFailure = IOException("Unowned live PID")
        fixture.journals += journal(1, DeploymentJournalState.ACTIVE)
        assertTrue(runCatching { fixture.recovery.recover() }.exceptionOrNull() is IOException)
        assertEquals(original, fixture.repository.getSite(1))
        assertEquals(listOf("reset"), fixture.events)
        assertEquals(1, fixture.journals.size)
        assertEquals(1, fixture.hosts.size)
        assertEquals("https://old.trycloudflare.com", fixture.serverUrl.value)
    }

    @Test
    fun unknownOrChangedJournalIdentityBlocksEveryJournalMutation() = runBlocking {
        for (changed in listOf(journal(2, DeploymentJournalState.ACTIVE),
            journal(1, DeploymentJournalState.ACTIVE).copy(projectType = ProjectType.NODEJS))) {
            val fixture = Fixture(listOf(site(1, SiteStatus.RUNNING, 11)))
            fixture.journals += journal(1, DeploymentJournalState.ACTIVE)
            fixture.journals += changed
            assertTrue(runCatching { fixture.recovery.recover() }.exceptionOrNull() is IOException)
            assertFalse(fixture.events.any { it.startsWith("abort:") || it.startsWith("complete:") })
            assertEquals(11, fixture.repository.getSite(1)?.processId)
            assertEquals(1, fixture.hosts.size)
        }
    }

    @Test
    fun unregisteredHostIsRetainedAndBlocksRecoveryBeforeJournalCleanup() = runBlocking {
        val fixture = Fixture(listOf(site(1, SiteStatus.RUNNING, null)))
        fixture.journals += journal(1, DeploymentJournalState.ACTIVE)
        fixture.hosts += "$ROOT/nginx/conf.d/unknown.test.conf"
        assertTrue(runCatching { fixture.recovery.recover() }.exceptionOrNull() is IOException)
        assertFalse(fixture.events.any { it.startsWith("complete:") || it.startsWith("delete:") })
        assertEquals(2, fixture.hosts.size)
        assertEquals(SiteStatus.RUNNING, fixture.repository.getSite(1)?.status)
    }

    @Test
    fun journalCleanupFailureCannotClearPidOrClaimStopped() = runBlocking {
        val fixture = Fixture(listOf(site(1, SiteStatus.DEPLOYING, 123)))
        fixture.journals += journal(1, DeploymentJournalState.ACTIVE)
        fixture.workspaceFailure = IOException("Backup rollback failed")
        assertTrue(runCatching { fixture.recovery.recover() }.exceptionOrNull() is IOException)
        assertEquals(SiteStatus.DEPLOYING, fixture.repository.getSite(1)?.status)
        assertEquals(123, fixture.repository.getSite(1)?.processId)
        assertFalse(fixture.events.any { it.startsWith("delete:") })
    }

    @Test
    fun responseLossAfterJournalCompletionCanRetryWithoutRollingBackCommittedSources() = runBlocking {
        val fixture = Fixture(listOf(site(1, SiteStatus.RUNNING, 123)))
        fixture.journals += journal(1, DeploymentJournalState.ACTIVE)
        fixture.serverFailure = IOException("Database unavailable")
        assertTrue(runCatching { fixture.recovery.recover() }.exceptionOrNull() is IOException)
        assertTrue(fixture.journals.isEmpty())
        assertEquals(123, fixture.repository.getSite(1)?.processId)
        fixture.serverFailure = null
        fixture.recovery.recover()
        assertEquals(1, fixture.events.count { it == "complete:1" })
        assertFalse("abort:1" in fixture.events)
        assertEquals(SiteStatus.STOPPED, fixture.repository.getSite(1)?.status)
        assertNull(fixture.repository.getSite(1)?.processId)
    }

    private class Fixture(initial: List<Site>) {
        val events = mutableListOf<String>()
        val journals = mutableListOf<DeploymentJournal>()
        val hosts = initial.map { "$ROOT/nginx/conf.d/${it.domainName}.conf" }.toMutableList()
        var probed: List<Int> = emptyList()
        var resetFailure: IOException? = null
        var workspaceFailure: IOException? = null
        var serverFailure: IOException? = null
        val repository = MemorySites(initial, events)
        val serverUrl = MutableStateFlow<String?>("https://old.trycloudflare.com")
        val server = object : ServerStateRepository {
            override val publicUrl = serverUrl
            override suspend fun updatePublicUrl(url: String?) {
                serverFailure?.let { throw it }
                publicUrl.value = url
                events += "server-url"
            }
        }
        val recovery = TermuxRuntimeRecovery(
            RuntimeReset { pids ->
                events += "reset"
                probed = pids
                resetFailure?.let { throw it }
            },
            object : RuntimeRecoveryWorkspace {
                override suspend fun journals(): List<DeploymentJournal> { events += "journals"; return journals.toList() }
                override suspend fun abort(site: Site, deploymentId: String) { finish(site, deploymentId, "abort") }
                override suspend fun complete(site: Site, deploymentId: String) { finish(site, deploymentId, "complete") }
                private fun finish(site: Site, id: String, kind: String) {
                    events += "$kind:${site.id}"
                    workspaceFailure?.let { throw it }
                    journals.removeAll { it.deploymentId == id }
                }
            },
            object : ProcessExecutor {
                override suspend fun runtimeLayout() = RuntimeLayout(ROOT, "$ROOT/htdocs", "$ROOT/nginx/nginx.conf",
                    "$ROOT/nginx/conf.d", "$ROOT/runtime", "$ROOT/logs")
                override suspend fun execute(request: ProcessRequest, timeoutMillis: Long): ProcessResult = error("No arbitrary shell command")
                override suspend fun executeCommand(command: String, envVars: Map<String, String>, workingDir: File): ProcessResult = error("No arbitrary shell command")
                override suspend fun startProcess(request: ProcessRequest): ManagedProcess = error("No backend started during recovery")
                override suspend fun startProcess(arguments: List<String>, envVars: Map<String, String>, workingDir: File): ManagedProcess = error("No backend started during recovery")
                override suspend fun resolveBinary(name: String): String = error("Unused")
            },
            object : RuntimeFileStore {
                override suspend fun listFiles(directory: String): List<String> = hosts.toList()
                override suspend fun delete(path: String) { events += "delete:$path"; hosts.remove(path) }
                override suspend fun writeTextAtomic(path: String, text: String): Unit = error("Recovery does not create configs")
                override suspend fun readTextOrNull(path: String): String? = error("Unused")
                override suspend fun ensureDirectory(path: String): Unit = error("Unused")
            },
            repository,
            server,
        )
    }

    private class MemorySites(initial: List<Site>, private val events: MutableList<String>) : SiteRepository {
        override val sites = MutableStateFlow(initial)
        override val state: StateFlow<SiteCollectionState> = MutableStateFlow(SiteCollectionState.Ready(initial))
        override suspend fun listSites(): List<Site> = sites.value
        override suspend fun getSite(id: Long): Site? = sites.value.firstOrNull { it.id == id }
        override fun observeSite(id: Long): Flow<Site?> = sites.map { values -> values.firstOrNull { it.id == id } }
        override fun observeSitesByStatus(status: SiteStatus): Flow<List<Site>> = sites.map { values -> values.filter { it.status == status } }
        override suspend fun createSite(domainName: String, projectType: ProjectType): Site = error("Unused")
        override suspend fun updateRuntimeState(id: Long, status: SiteStatus, processId: Int?) {
            events += "status:$id:$status"
            update(id) { it.copy(status = status, processId = processId) }
        }
        override suspend fun compareAndSetRuntimeState(id: Long, expectedStatus: SiteStatus, newStatus: SiteStatus, processId: Int?): Boolean = error("Unused")
        override suspend fun updatePublicUrl(id: Long, publicUrl: String?) { update(id) { it.copy(publicUrl = publicUrl) } }
        override suspend fun appendLogs(id: Long, text: String) { update(id) { it.copy(logs = it.logs + text) } }
        override suspend fun clearLogs(id: Long) { update(id) { it.copy(logs = "") } }
        override suspend fun deleteSiteIfInactive(id: Long): Unit = error("Recovery never deletes a site")
        private fun update(id: Long, change: (Site) -> Site) { sites.value = sites.value.map { if (it.id == id) change(it) else it } }
    }

    companion object {
        private const val ROOT = "/termux/home/.pocket-paas"
        private fun site(id: Long, status: SiteStatus, pid: Int?) = Site(id, "site$id.test", 9000 + id.toInt(),
            ProjectType.STATIC, pid, "https://old.trycloudflare.com/sites/site$id.test/", status, "", 1)
        private fun journal(id: Long, state: DeploymentJournalState) = DeploymentJournal(UUID.randomUUID().toString(),
            "site$id.test", ProjectType.STATIC, state)
    }
}
