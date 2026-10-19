package dev.pocketpaas.domain.deployment

import java.io.File

/** Transfers a bounded private APK spool file into the authenticated runtime. */
interface RuntimeArchiveTransfer {
    suspend fun upload(source: File, archiveId: String, maximumBytes: Long): String

    /** Idempotent deletion of an archive returned by [upload]. */
    suspend fun delete(archivePath: String)
}
