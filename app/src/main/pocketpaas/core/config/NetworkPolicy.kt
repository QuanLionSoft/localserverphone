package dev.pocketpaas.core.config

data class NetworkPolicy(
    val startupTimeoutMillis: Long = 15_000,
    val tunnelStartupTimeoutMillis: Long = 60_000,
    val readinessPollMillis: Long = 150,
    val readinessConnectTimeoutMillis: Int = 500,
    val configTestTimeoutMillis: Long = 10_000,
    val tunnelMetricsPort: Int = 8767,
    val maximumVirtualHosts: Int = 64,
) {
    init {
        require(startupTimeoutMillis in 1..120_000)
        require(tunnelStartupTimeoutMillis in 1..180_000)
        require(readinessPollMillis in 1..5_000)
        require(readinessConnectTimeoutMillis in 1..10_000)
        require(configTestTimeoutMillis in 1..60_000)
        require(tunnelMetricsPort in 1024..65535)
        require(maximumVirtualHosts in 1..128)
    }
}
