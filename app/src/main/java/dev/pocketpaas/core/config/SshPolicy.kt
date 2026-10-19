package dev.pocketpaas.core.config

data class SshPolicy(
    val defaultPort: Int = 8022,
    val minimumPort: Int = 1024,
    val maximumPort: Int = 65_535,
    val reservedPorts: Set<Int> = setOf(8080, 8081, 8767),
    val maximumAuthorizedKeys: Int = 32,
    val startupTimeoutMillis: Long = 15_000,
    val stopGraceMillis: Long = 5_000,
    val probeTimeoutMillis: Long = 15_000,
) {
    init {
        require(defaultPort in minimumPort..maximumPort) { "Default SSH port must be unprivileged" }
        require(defaultPort !in reservedPorts) { "Default SSH port must not collide with managed listeners" }
        require(maximumAuthorizedKeys in 1..256) { "Authorized key limit must be reasonable" }
        require(startupTimeoutMillis in 1_000..120_000)
        require(stopGraceMillis in 0..30_000)
        require(probeTimeoutMillis in 1_000..120_000)
    }

    fun isAllowedPort(port: Int): Boolean = port in minimumPort..maximumPort && port !in reservedPorts
}
