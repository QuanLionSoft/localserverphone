package dev.pocketpaas.data.process.termux

data class TermuxBridgeCredentials(val port: Int, val token: String) {
    init {
        require(port in 1024..65535) { "Termux bridge port must be unprivileged." }
        require(token.matches(Regex("[a-f0-9]{64}"))) { "Invalid Termux bridge credentials." }
    }

    override fun toString(): String = "TermuxBridgeCredentials(port=$port, token=[redacted])"
}

fun interface TermuxBridgeCredentialsStore {
    suspend fun get(): TermuxBridgeCredentials
}

fun interface TermuxAgentBootstrap {
    suspend fun start(credentials: TermuxBridgeCredentials)
}
