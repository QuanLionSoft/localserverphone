package dev.pocketpaas.domain.setup

enum class TermuxPermissionState {
    GRANTED,
    DENIED,
    TERMUX_MISSING,
    TERMUX_DISABLED,
    PERMISSION_UNAVAILABLE,
}

/** Describes the installed command interface, independently of its runtime packages. */
data class TermuxPermissionStatus(
    val state: TermuxPermissionState,
    val versionName: String? = null,
) {
    val granted: Boolean get() = state == TermuxPermissionState.GRANTED
    val canRequestRuntimePermission: Boolean get() = state == TermuxPermissionState.DENIED
}

fun interface TermuxPermissionInspector {
    suspend fun inspect(): TermuxPermissionStatus
}

/** Android facts are resolved separately so an undefined permission cannot imply readiness. */
internal data class TermuxPermissionEvidence(
    val installed: Boolean,
    val enabled: Boolean,
    val commandInterfaceAvailable: Boolean,
    val runtimeRequestable: Boolean,
    val permissionGranted: Boolean,
    val versionName: String? = null,
) {
    fun resolve(): TermuxPermissionStatus = TermuxPermissionStatus(
        state = when {
            !installed -> TermuxPermissionState.TERMUX_MISSING
            !enabled -> TermuxPermissionState.TERMUX_DISABLED
            !commandInterfaceAvailable -> TermuxPermissionState.PERMISSION_UNAVAILABLE
            permissionGranted -> TermuxPermissionState.GRANTED
            runtimeRequestable -> TermuxPermissionState.DENIED
            else -> TermuxPermissionState.PERMISSION_UNAVAILABLE
        },
        versionName = versionName,
    )
}
