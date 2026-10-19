package dev.pocketpaas.core.config

data class ProcessPolicy(
    val maxConcurrentProcesses: Int = 8,
    val commandTimeoutMillis: Long = 900_000,
    val maximumCaptureCharacters: Int = 65_536,
    val stopGraceMillis: Long = 2_000,
    val drainTimeoutMillis: Long = 3_000,
) {
    /** Leave one total-process slot available for health, file operations, and reload commands. */
    val maxLongRunningProcesses: Int get() = (maxConcurrentProcesses - 1).coerceAtLeast(1)

    init {
        require(maxConcurrentProcesses in 1..8)
        require(commandTimeoutMillis in 1..86_400_000)
        require(maximumCaptureCharacters in 1..1_048_576)
        require(stopGraceMillis in 0..10_000)
        require(drainTimeoutMillis in 1..10_000)
    }
}
