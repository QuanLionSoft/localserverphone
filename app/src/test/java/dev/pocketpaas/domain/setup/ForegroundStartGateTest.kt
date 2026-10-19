package dev.pocketpaas.domain.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStartGateTest {
    @Test
    fun aStartRequiresAnExplicitRequestOnAResumedScreen() {
        val gate = ForegroundStartGate()

        assertNull(gate.request())
        gate.permissionResult(1L)
        gate.onResume()
        assertFalse(gate.takeReady())

        val ticket = requireNotNull(gate.request())
        assertFalse(gate.takeReady())
        gate.permissionResult(ticket)
        assertTrue(gate.takeReady())
    }

    @Test
    fun aPermissionResultBeforeResumeWaitsForTheForeground() {
        val gate = resumedGate()
        val ticket = requireNotNull(gate.request())
        gate.onPause()

        gate.permissionResult(ticket)

        assertFalse(gate.takeReady())
        gate.onResume()
        assertTrue(gate.takeReady())
    }

    @Test
    fun pausingAReadyRequestPreventsDispatchUntilResume() {
        val gate = resumedGate()
        val ticket = requireNotNull(gate.request())
        gate.permissionResult(ticket)

        gate.onPause()

        assertFalse(gate.takeReady())
        assertNull(gate.request())
        gate.onResume()
        assertTrue(gate.takeReady())
    }

    @Test
    fun stoppingDiscardsBothReadyAndOutstandingPermissionRequests() {
        for (resultBeforeStop in listOf(false, true)) {
            val gate = resumedGate()
            val ticket = requireNotNull(gate.request())
            if (resultBeforeStop) gate.permissionResult(ticket)

            gate.onStop()
            gate.permissionResult(ticket)
            gate.onResume()

            assertFalse(gate.takeReady())
        }
    }

    @Test
    fun wrongAndSupersededTicketsCannotCompleteTheCurrentRequest() {
        val gate = resumedGate()
        val previous = requireNotNull(gate.request())
        val current = requireNotNull(gate.request())
        assertTrue(current > previous)

        gate.permissionResult(previous)
        gate.permissionResult(current + 1L)

        assertFalse(gate.takeReady())
        gate.permissionResult(current)
        assertTrue(gate.takeReady())
    }

    @Test
    fun aNewRequestDoesNotInheritThePreviousRequestsReadyState() {
        val gate = resumedGate()
        val previous = requireNotNull(gate.request())
        gate.permissionResult(previous)

        val current = requireNotNull(gate.request())

        assertFalse(gate.takeReady())
        gate.permissionResult(current)
        assertTrue(gate.takeReady())
    }

    @Test
    fun completionAndConsumptionAreEffectiveOnlyOnce() {
        val gate = resumedGate()
        val ticket = requireNotNull(gate.request())
        gate.permissionResult(ticket)
        gate.permissionResult(ticket)

        assertTrue(gate.takeReady())
        assertFalse(gate.takeReady())
        gate.permissionResult(ticket)
        gate.onPause()
        gate.onResume()
        assertFalse(gate.takeReady())
    }

    @Test
    fun explicitCancellationRejectsLateResultsAndAllowsAFreshRequest() {
        val gate = resumedGate()
        val cancelled = requireNotNull(gate.request())

        gate.cancel()
        gate.permissionResult(cancelled)

        assertFalse(gate.takeReady())
        val fresh = requireNotNull(gate.request())
        assertTrue(fresh > cancelled)
        gate.permissionResult(fresh)
        assertTrue(gate.takeReady())
    }

    private fun resumedGate() = ForegroundStartGate().also { it.onResume() }
}
