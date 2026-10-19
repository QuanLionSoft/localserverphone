package dev.pocketpaas.domain.runtime

/** Runs once before the new application session creates any managed child. */
fun interface RuntimeRecovery {
    suspend fun recover()
}

/** Authenticated process-group reset; no application guesses or signals persisted PIDs. */
fun interface RuntimeReset {
    suspend fun resetRuntime(persistedPids: List<Int>)
}
