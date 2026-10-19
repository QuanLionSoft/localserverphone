package dev.pocketpaas.data.deployment

import dev.pocketpaas.core.config.ProcessPolicy
import dev.pocketpaas.data.process.LocalProcessLauncher
import dev.pocketpaas.data.process.ShellExecutor
import dev.pocketpaas.domain.deployment.ProjectEntryPoint
import dev.pocketpaas.domain.deployment.DeploymentJournalState
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.system.RuntimeDeploymentScriptSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ShellDeploymentWorkspaceTest {
    @Test(timeout = 45_000)
    fun realJournalEnumerationValidatesIdentityAndSupportsColdCommitCompletion() = runBlocking<Unit> {
        fixture { workspace, root ->
            val site = site(ProjectType.STATIC)
            assertTrue(workspace.journals().isEmpty())
            val id = UUID.randomUUID().toString()
            val archive = archive(root, id, mapOf("index.html" to "committed"))
            val prepared = workspace.prepare(site, archive.path, id)
            assertEquals(DeploymentJournalState.PREPARED, workspace.journals().single().state)
            workspace.activate(site, prepared)
            val journal = workspace.journals().single()
            assertEquals(id, journal.deploymentId)
            assertEquals(site.domainName, journal.domainName)
            assertEquals(site.projectType, journal.projectType)
            assertEquals(DeploymentJournalState.ACTIVE, journal.state)
            workspace.complete(site, id)
            assertTrue(workspace.journals().isEmpty())
            assertEquals("committed", File(root, "htdocs/${site.domainName}/index.html").readText())
        }
    }

    @Test(timeout = 45_000)
    fun realPythonPreparationSwapAndRollbackKeepPreviousProject() = runBlocking<Unit> {
        fixture { workspace, root ->
            val site = site(ProjectType.STATIC)
            val active = File(root, "htdocs/${site.domainName}").apply { check(mkdirs()) }
            File(active, "index.html").writeText("old")
            val id = UUID.randomUUID().toString()
            val archive = archive(root, id, mapOf("wrapper/index.html" to "İstanbul 😀", "wrapper/assets/main.js" to "ok"))
            val prepared = workspace.prepare(site, archive.path, id)
            assertTrue(prepared.hadPreviousProject)
            assertEquals(ProjectEntryPoint.STATIC, prepared.metadata.entryPoint)
            assertEquals("old", File(active, "index.html").readText())
            workspace.activate(site, prepared)
            assertEquals("İstanbul 😀", File(active, "index.html").readText())
            assertEquals(active.portablePath(), workspace.inspect(site).rootDir)
            workspace.rollback(site, prepared)
            workspace.abort(site, id)
            assertEquals("old", File(active, "index.html").readText())
            assertFalse(File(prepared.backupPath).exists())
            assertFalse(File(prepared.stagingPath).exists())
        }
    }

    @Test(timeout = 45_000)
    fun realPythonNodeMetadataAndFinalizeRemoveOnlyOwnedBackup() = runBlocking<Unit> {
        fixture { workspace, root ->
            val site = site(ProjectType.NODEJS)
            val active = File(root, "htdocs/${site.domainName}").apply { check(mkdirs()) }
            File(active, "private.txt").writeText("old")
            val untouched = File(root, "htdocs/unrelated.test").apply { check(mkdirs()) }
            File(untouched, "keep").writeText("owner")
            val id = UUID.randomUUID().toString()
            val archive = archive(root, id, mapOf("index.js" to "console.log('run')", "package.json" to "{}"))
            val prepared = workspace.prepare(site, archive.path, id)
            workspace.activate(site, prepared)
            val metadata = workspace.inspect(site)
            assertEquals(ProjectEntryPoint.NODEJS, metadata.entryPoint)
            assertTrue(metadata.hasPackageJson)
            workspace.complete(site, prepared)
            workspace.complete(site, prepared)
            assertFalse(File(prepared.backupPath).exists())
            assertFalse(archive.exists())
            assertEquals("owner", File(untouched, "keep").readText())
            assertTrue(File(active, "index.js").isFile)
        }
    }

    @Test(timeout = 45_000)
    fun traversalMetadataAndArchiveIdentityFailuresDoNotReplaceActiveSite() = runBlocking<Unit> {
        fixture { workspace, root ->
            val site = site(ProjectType.NODEJS)
            val active = File(root, "htdocs/${site.domainName}").apply { check(mkdirs()) }
            File(active, "owner").writeText("owner")
            val badId = UUID.randomUUID().toString()
            val bad = archive(root, badId, mapOf("../outside" to "bad"))
            assertTrue(runCatching { workspace.prepare(site, bad.path, badId) }.exceptionOrNull() is IOException)
            workspace.abort(site, badId)
            val missingId = UUID.randomUUID().toString()
            val missing = archive(root, missingId, mapOf("index.js" to "run"))
            assertTrue(runCatching { workspace.prepare(site, missing.path, missingId) }.exceptionOrNull() is IOException)
            assertTrue(runCatching { workspace.prepare(site, bad.path, missingId) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals("owner", File(active, "owner").readText())
            assertFalse(File(root, "outside").exists())
        }
    }

    private suspend fun fixture(block: suspend (ShellDeploymentWorkspace, File) -> Unit) {
        val python = System.getProperty("pocketpaas.test.python")
        val production = System.getProperty("pocketpaas.production.root")
        assumeTrue("Python and production paths must be configured.", python != null && production != null)
        val scratch = File(requireNotNull(production), "verification/room-jvm/build/deployment-workspace-test")
        check(scratch.isDirectory || scratch.mkdirs())
        val root = Files.createTempDirectory(scratch.toPath(), "runtime-").toFile()
        val layout = RuntimeLayout(root.path, File(root, "htdocs").path, File(root, "nginx/nginx.conf").path,
            File(root, "nginx/conf.d").path, File(root, "tmp").path, File(root, "logs").path)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = ShellExecutor(LocalProcessLauncher(layout, listOf(File(requireNotNull(python)).parentFile)), ProcessPolicy(), scope)
        val source = File(production, "app/src/main/assets/pocketpaas_deploy.py").readText()
        val script = if (File.separatorChar == '\\') WINDOWS_FIXTURE_RESOLVER + "\n" + source else source
        try {
            block(ShellDeploymentWorkspace(executor, RuntimeDeploymentScriptSource { script }), root)
        } finally {
            withContext(NonCancellable) { scope.cancel(); scope.coroutineContext.job.join() }
            val parent = scratch.canonicalFile.toPath()
            val target = root.canonicalFile.toPath()
            check(target != parent && target.startsWith(parent))
            root.deleteRecursively()
        }
    }

    private fun archive(root: File, id: String, entries: Map<String, String>): File {
        val directory = File(root, "tmp/uploads").apply { check(isDirectory || mkdirs()) }
        val archive = File(directory, "$id.zip")
        ZipOutputStream(archive.outputStream()).use { output ->
            entries.forEach { (name, value) ->
                output.putNextEntry(ZipEntry(name))
                output.write(value.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
        return archive
    }

    private fun site(type: ProjectType) = Site(1, "example.test", 9000, type, null, null, SiteStatus.STOPPED, "", 1)

    private fun File.portablePath() = path.replace('\\', '/')

    companion object {
        private val WINDOWS_FIXTURE_RESOLVER = """
            import pathlib,sys
            _resolve=pathlib.Path.resolve
            _fixture=_resolve(pathlib.Path(sys.argv[1]))
            def _fixture_resolve(path,strict=False):
                try:
                    return _resolve(path,strict=strict)
                except PermissionError:
                    if not strict or not path.exists():
                        raise
                    resolved=_resolve(path)
                    resolved.relative_to(_fixture)
                    for component in [resolved]+list(resolved.parents):
                        if component==_fixture.parent:
                            break
                        if component.is_symlink() or (hasattr(component,'is_junction') and component.is_junction()):
                            raise PermissionError('Reparse paths require strict resolution.')
                    return resolved
            pathlib.Path.resolve=_fixture_resolve
        """.trimIndent()
    }
}
