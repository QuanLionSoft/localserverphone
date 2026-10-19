package dev.pocketpaas.core.config

data class DeploymentPolicy(
    val maximumArchiveBytes: Long = 64L * 1024 * 1024,
    val installTimeoutMillis: Long = 15 * 60_000L,
    val buildTimeoutMillis: Long = 15 * 60_000L,
    val startupTimeoutMillis: Long = 30_000,
    val readinessPollMillis: Long = 100,
    val stableReadinessMillis: Long = 500,
    val maximumBackendProcesses: Int = 5,
) {
    init {
        require(maximumArchiveBytes in 1..64L * 1024 * 1024)
        require(installTimeoutMillis in 1..60 * 60_000L)
        require(buildTimeoutMillis in 1..60 * 60_000L)
        require(startupTimeoutMillis in 1..120_000)
        require(readinessPollMillis in 1..5_000)
        require(stableReadinessMillis in 0..10_000)
        require(maximumBackendProcesses in 1..5)
    }
}
