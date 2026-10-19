package dev.pocketpaas.domain.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxPermissionStatusTest {
    @Test
    fun missingTermuxCannotBecomeGrantedFromAStalePermissionResult() {
        assertEquals(
            TermuxPermissionState.TERMUX_MISSING,
            available.copy(installed = false, permissionGranted = true).resolve().state,
        )
    }

    @Test
    fun disabledApplicationOrCommandServiceCannotBecomeReady() {
        val status = available.copy(enabled = false, permissionGranted = true).resolve()
        assertEquals(TermuxPermissionState.TERMUX_DISABLED, status.state)
        assertFalse(status.granted)
        assertFalse(status.canRequestRuntimePermission)
    }

    @Test
    fun missingForeignOrIncompatibleCommandInterfaceIsNeverReady() {
        val status = available.copy(commandInterfaceAvailable = false, permissionGranted = true).resolve()
        assertEquals(TermuxPermissionState.PERMISSION_UNAVAILABLE, status.state)
        assertFalse(status.granted)
        assertFalse(status.canRequestRuntimePermission)
    }

    @Test
    fun ungrantedDangerousPermissionCanShowAndroidPermissionRequest() {
        val status = available.resolve()
        assertEquals(TermuxPermissionState.DENIED, status.state)
        assertTrue(status.canRequestRuntimePermission)
        assertFalse(status.granted)
    }

    @Test
    fun ungrantedNonRuntimePermissionDoesNotOfferAnIneffectiveDialog() {
        val status = available.copy(runtimeRequestable = false).resolve()
        assertEquals(TermuxPermissionState.PERMISSION_UNAVAILABLE, status.state)
        assertFalse(status.canRequestRuntimePermission)
    }

    @Test
    fun grantedCompatibleInterfaceDoesNotRequireAnotherDialog() {
        val status = available.copy(permissionGranted = true).resolve()
        assertEquals(TermuxPermissionState.GRANTED, status.state)
        assertTrue(status.granted)
        assertFalse(status.canRequestRuntimePermission)
    }

    @Test
    fun alreadyGrantedNormalPermissionWorksWithoutRuntimeDialog() {
        val status = available.copy(runtimeRequestable = false, permissionGranted = true).resolve()
        assertEquals(TermuxPermissionState.GRANTED, status.state)
        assertFalse(status.canRequestRuntimePermission)
    }

    @Test
    fun installedVersionIsPreservedForTroubleshooting() {
        assertEquals("0.118.3", available.resolve().versionName)
    }

    private val available = TermuxPermissionEvidence(
        installed = true,
        enabled = true,
        commandInterfaceAvailable = true,
        runtimeRequestable = true,
        permissionGranted = false,
        versionName = "0.118.3",
    )
}
