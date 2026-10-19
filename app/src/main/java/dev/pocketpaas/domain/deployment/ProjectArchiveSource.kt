package dev.pocketpaas.domain.deployment

import java.io.File

/** Acquires an archive for one deployment and releases its temporary files on every exit. */
interface ProjectArchiveSource {
    suspend fun <T> useArchive(action: suspend (File) -> T): T
}
