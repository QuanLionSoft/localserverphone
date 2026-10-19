package dev.pocketpaas.domain.model

data class RuntimeLayout(
    val rootPath: String,
    val projectsPath: String,
    val nginxConfigPath: String,
    val nginxHostsPath: String,
    val runtimePath: String,
    val logsPath: String,
)

data class RuntimeCapabilities(
    val termuxInstalled: Boolean,
    val termuxCommandPermissionGranted: Boolean,
    val supportedAbis: List<String>,
)

data class PlatformInfo(
    val installationId: String,
    val createdAtEpochMillis: Long,
    val layout: RuntimeLayout,
    val capabilities: RuntimeCapabilities,
    val apiPort: Int,
    val nginxPort: Int,
)
