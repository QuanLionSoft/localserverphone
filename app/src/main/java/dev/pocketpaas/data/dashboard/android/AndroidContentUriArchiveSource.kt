package dev.pocketpaas.data.dashboard.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import dev.pocketpaas.core.config.DeploymentPolicy
import dev.pocketpaas.data.dashboard.ArchiveInputProvider
import dev.pocketpaas.data.dashboard.BoundedProjectArchiveSource
import dev.pocketpaas.domain.deployment.ProjectArchiveSource
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher

/** Acquisition happens inside the runtime mutation, so server Stop also cancels provider reads. */
class AndroidContentUriArchiveSource(
    private val context: Context,
    private val uri: Uri,
    private val policy: DeploymentPolicy,
    private val ioDispatcher: CoroutineDispatcher,
    private val resolver: ContentResolver = context.contentResolver,
) : ProjectArchiveSource {
    override suspend fun <T> useArchive(action: suspend (File) -> T): T {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Choose a ZIP through the document picker." }
        val cancellation = CancellationSignal()
        val provider = object : ArchiveInputProvider {
            override fun open(): InputStream {
                val descriptor = resolver.openAssetFileDescriptor(uri, "r", cancellation)
                    ?: throw IOException("The selected ZIP could not be opened.")
                return try {
                    object : FilterInputStream(descriptor.createInputStream()) {
                        override fun close() {
                            try { super.close() } finally { descriptor.close() }
                        }
                    }
                } catch (failure: Throwable) {
                    runCatching { descriptor.close() }
                    throw failure
                }
            }
            override fun cancelOpen() = cancellation.cancel()
        }
        return BoundedProjectArchiveSource(
            directory = File(context.cacheDir, "dashboard-uploads"),
            maximumBytes = policy.maximumArchiveBytes,
            ioDispatcher = ioDispatcher,
            provider = provider,
        ).useArchive(action)
    }
}
