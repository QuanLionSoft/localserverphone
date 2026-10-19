package dev.pocketpaas.data.runtime

import dev.pocketpaas.data.process.termux.TermuxBridge
import dev.pocketpaas.domain.deployment.RuntimeArchiveTransfer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TermuxRuntimeArchiveTransfer @Inject constructor(
    private val bridge: TermuxBridge,
) : RuntimeArchiveTransfer {
    override suspend fun upload(source: File, archiveId: String, maximumBytes: Long): String =
        bridge.uploadArchive(source, archiveId, maximumBytes)

    override suspend fun delete(archivePath: String) = bridge.deleteArchive(archivePath)
}
