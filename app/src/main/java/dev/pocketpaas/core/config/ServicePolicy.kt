package dev.pocketpaas.core.config

data class ServicePolicy(
    val maximumRunningDatabaseServices: Int = 2,
    val installTimeoutMillis: Long = 20 * 60_000L,
    val startupTimeoutMillis: Long = 30_000,
    val readinessPollMillis: Long = 250,
    val stopGraceMillis: Long = 5_000,
    val probeTimeoutMillis: Long = 15_000,
) {
    init {
        require(maximumRunningDatabaseServices in 1..3) { "Database service slots must fit the Termux process budget" }
        require(installTimeoutMillis in 1..60 * 60_000L)
        require(startupTimeoutMillis in 1..120_000)
        require(readinessPollMillis in 50..5_000)
        require(stopGraceMillis in 0..30_000)
        require(probeTimeoutMillis in 1_000..120_000)
    }
}
