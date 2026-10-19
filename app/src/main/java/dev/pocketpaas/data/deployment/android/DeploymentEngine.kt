package dev.pocketpaas.data.deployment.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketpaas.core.config.DeploymentPolicy
import dev.pocketpaas.core.di.IoDispatcher
import dev.pocketpaas.data.deployment.DeploymentOrchestrator
import dev.pocketpaas.data.local.SiteEntity
import dev.pocketpaas.data.local.toDomain
import dev.pocketpaas.domain.deployment.DeploymentResult
import dev.pocketpaas.domain.model.Site
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Android content-provider adapter; runtime projects themselves belong to the Termux UID. */
@Singleton
class DeploymentEngine @Inject constructor(
    private val orchestrator: DeploymentOrchestrator,
    private val policy: DeploymentPolicy,
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun deployProject(site: SiteEntity, zipUri: Uri, contentResolver: ContentResolver): DeploymentResult =
        deployProject(site.toDomain(), zipUri, contentResolver)

    suspend fun deployProject(site: Site, zipUri: Uri, contentResolver: ContentResolver): DeploymentResult {
        require(zipUri.scheme == "content") { "Choose a ZIP through the Android document picker." }
        val spool = withContext(ioDispatcher) {
            val directory = File(context.cacheDir, "deployment-uploads")
            check(directory.isDirectory || directory.mkdirs()) { "Deployment upload cache could not be created." }
            File.createTempFile("project-", ".zip", directory)
        }
        try {
            spoolUri(zipUri, contentResolver, spool)
            return orchestrator.deployProject(site, spool)
        } finally {
            withContext(NonCancellable + ioDispatcher) { spool.delete() }
        }
    }

    suspend fun deployProject(site: Site, archiveFile: File): DeploymentResult = orchestrator.deployProject(site, archiveFile)
    suspend fun startSite(siteId: Long): DeploymentResult = orchestrator.startSite(siteId)
    suspend fun stopSite(siteId: Long) = orchestrator.stopSite(siteId)
    suspend fun stopAll() = orchestrator.stopAll()
    suspend fun retryCleanup(siteId: Long): List<String> = orchestrator.retryCleanup(siteId)

    private suspend fun spoolUri(uri: Uri, resolver: ContentResolver, target: File) = coroutineScope {
        val opened = AtomicReference<InputStream?>()
        val copy = async(ioDispatcher) {
            val input = resolver.openInputStream(uri) ?: throw IOException("The selected ZIP could not be opened.")
            opened.set(input)
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) {
                            val single = source.read()
                            if (single < 0) break
                            total++
                            if (total > policy.maximumArchiveBytes) throw IOException("ZIP exceeds the archive size limit.")
                            output.write(single)
                        } else {
                            total += count
                            if (total > policy.maximumArchiveBytes) throw IOException("ZIP exceeds the archive size limit.")
                            output.write(buffer, 0, count)
                        }
                    }
                    if (total == 0L) throw IOException("The selected ZIP is empty.")
                }
            }
        }
        try {
            copy.await()
        } finally {
            // Closing the provider stream unblocks an IO read when the parent coroutine is cancelled.
            withContext(NonCancellable + ioDispatcher) { runCatching { opened.getAndSet(null)?.close() } }
            copy.cancel()
        }
    }
}
