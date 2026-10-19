package dev.pocketpaas.data.dashboard

import dev.pocketpaas.domain.dashboard.DashboardOperationKind
import dev.pocketpaas.domain.remote.RemoteErrorKind
import dev.pocketpaas.domain.remote.RemoteOperationException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardOperationCoordinatorTest {
    @Test
    fun successfulNewDeploymentCarriesReturnedSiteId() = runTest {
        val coordinator = DashboardOperationCoordinator(this)
        coordinator.submit(DashboardOperationKind.DEPLOY) { DashboardCommandResult("published", siteId = 42) }
        yield()
        val result = coordinator.operation.value
        assertEquals(42L, result.siteId)
        assertFalse(result.running)
        assertFalse(result.failed)
        assertFalse(result.cancelled)
    }

    @Test
    fun cancellationIsDistinctFromSuccessAndRetainsExistingSiteIdentity() = runTest {
        val coordinator = DashboardOperationCoordinator(this)
        val entered = CompletableDeferred<Unit>()
        coordinator.submit(DashboardOperationKind.DEPLOY, siteId = 7) { entered.complete(Unit); awaitCancellation() }
        entered.await()
        coordinator.cancel()
        yield()
        val result = coordinator.operation.value
        assertEquals(7L, result.siteId)
        assertTrue(result.cancelled)
        assertFalse(result.failed)
        assertFalse(result.running)
    }

    @Test
    fun cancellationBeforeDispatchDoesNotLeaveOperationPermanentlyRunning() = runTest {
        val coordinator = DashboardOperationCoordinator(this)
        var entered = false
        coordinator.submit(DashboardOperationKind.DEPLOY) { entered = true; DashboardCommandResult("unexpected") }
        coordinator.cancel()
        yield()
        assertFalse(entered)
        assertTrue(coordinator.operation.value.cancelled)
        assertFalse(coordinator.operation.value.running)
    }

    @Test
    fun secondCommandIsRejectedEvenBeforeFirstCoroutineDispatches() = runTest {
        val coordinator = DashboardOperationCoordinator(this)
        var secondEntered = false
        coordinator.submit(DashboardOperationKind.DEPLOY) { awaitCancellation() }
        val firstId = coordinator.operation.value.id
        coordinator.submit(DashboardOperationKind.SITE_START, siteId = 10) { secondEntered = true; DashboardCommandResult("unexpected") }
        yield()
        assertEquals(firstId, coordinator.operation.value.id)
        assertFalse(secondEntered)
        coordinator.cancel()
    }

    @Test
    fun replacedOperationCannotOverwriteNewStopCommandAfterItsCleanupFinishes() = runTest {
        val coordinator = DashboardOperationCoordinator(this)
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        coordinator.submit(DashboardOperationKind.DEPLOY) {
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }
        entered.await()
        coordinator.submit(DashboardOperationKind.SERVER_STOP, replace = true) { DashboardCommandResult("stopped") }
        yield()
        val stopId = coordinator.operation.value.id
        assertEquals("stopped", coordinator.operation.value.message)
        cleanup.complete(Unit)
        yield()
        assertEquals(stopId, coordinator.operation.value.id)
        assertEquals(DashboardOperationKind.SERVER_STOP, coordinator.operation.value.kind)
        assertEquals("stopped", coordinator.operation.value.message)
        assertFalse(coordinator.operation.value.cancelled)
    }

    @Test
    fun unexpectedErrorsAreSanitizedAndTypedUnavailableErrorExplainsRequiredAction() = runTest {
        val coordinator = DashboardOperationCoordinator(this)
        coordinator.submit(DashboardOperationKind.DEPLOY) { throw IOException("secret-token-and-private-path") }
        yield()
        assertTrue(coordinator.operation.value.failed)
        assertFalse(coordinator.operation.value.message.orEmpty().contains("secret"))
        coordinator.submit(DashboardOperationKind.SITE_START) { throw RemoteOperationException(RemoteErrorKind.UNAVAILABLE) }
        yield()
        assertEquals("Önce ana sunucuyu başlatın.", coordinator.operation.value.message)
    }

    @Test
    fun dismissingRunningOperationDoesNotDiscardItsOwnerAndCompletedResultCanBeDismissed() = runTest {
        val coordinator = DashboardOperationCoordinator(this)
        val release = CompletableDeferred<Unit>()
        coordinator.submit(DashboardOperationKind.DEPLOY) { release.await(); DashboardCommandResult("done") }
        coordinator.dismissResult()
        assertTrue(coordinator.operation.value.running)
        release.complete(Unit)
        yield()
        val id = coordinator.operation.value.id
        coordinator.dismissResult()
        assertEquals(id, coordinator.operation.value.id)
        assertNull(coordinator.operation.value.message)
        assertNull(coordinator.operation.value.kind)
    }
}
