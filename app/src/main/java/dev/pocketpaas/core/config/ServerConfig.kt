package dev.pocketpaas.core.config

data class ServerConfig(
    val apiHost: String = "127.0.0.1",
    val apiPort: Int = 8080,
    val nginxHost: String = "127.0.0.1",
    val nginxPort: Int = 8081,
    val termuxPackage: String = "com.termux",
) {
    init {
        require(apiHost == "127.0.0.1") { "Initial API binding must be loopback" }
        require(nginxHost == "127.0.0.1") { "Initial Nginx binding must be loopback" }
        require(apiPort in 1024..65535) { "API port must be unprivileged" }
        require(nginxPort in 1024..65535) { "Nginx port must be unprivileged" }
        require(apiPort != nginxPort) { "API and Nginx ports must differ" }
        require(termuxPackage.matches(Regex("[a-zA-Z][a-zA-Z0-9_.]+")))
    }
}
