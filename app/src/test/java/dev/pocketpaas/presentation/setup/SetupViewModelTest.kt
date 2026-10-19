@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pocketpaas.presentation.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.pocketpaas.domain.setup.SetupPreferences
import dev.pocketpaas.domain.setup.SetupVerification
import dev.pocketpaas.domain.setup.SetupVerifier
import dev.pocketpaas.domain.setup.TermuxPermissionInspector
import dev.pocketpaas.domain.setup.TermuxPermissionState
import dev.pocketpaas.domain.setup.TermuxPermissionStatus
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SetupViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun installMainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }

    @After
    fun clearOwnersAndDispatcher() {
        stores.forEach(ViewModelStore::clear)
        stores.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun firstInstallationLoadsWithoutAutomaticallyVerifyingOrStarting() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        assertTrue(model.state.value.loading)

        runCurrent()

        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.completed)
        assertFalse(model.state.value.commandCopied)
        assertFalse(model.state.value.verified)
        assertNull(model.state.value.startRequest)
        assertEquals(1, fixture.preferences.reads)
        assertEquals(0, fixture.verifier.calls)
    }

    @Test
    fun aSavedInstallationDoesNotPretendThatThisSessionHasBeenVerified() = runTest {
        val fixture = Fixture().also { it.preferences.completed = true }
        val model = fixture.create()

        runCurrent()

        assertTrue(model.state.value.completed)
        assertFalse(model.state.value.verified)
        assertNull(model.state.value.startRequest)
        assertEquals(0, fixture.verifier.calls)
    }

    @Test
    fun unreadablePreferencesFallBackToSetupWithoutLeakingTheFailure() = runTest {
        val fixture = Fixture().also { it.preferences.onRead = { throw IOException("storage token=$secret") } }
        val model = fixture.create()

        runCurrent()

        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.completed)
        assertTrue(requireNotNull(model.state.value.error).contains("Kurulum bilgisi"))
        assertFalse(requireNotNull(model.state.value.error).contains(secret))
    }

    @Test
    fun repeatedStartPressesShareOneActiveVerification() = runTest {
        val fixture = Fixture().also { it.verifier.onVerify = { awaitCancellation() } }
        val model = fixture.create()
        runCurrent()

        model.checkAndStart()
        model.checkAndStart()
        assertTrue(model.state.value.checking)
        runCurrent()
        model.checkAndStart()
        runCurrent()

        assertEquals(1, fixture.verifier.calls)
        assertTrue(model.state.value.checking)
        assertNull(model.state.value.startRequest)
        model.cancelCheck()
        runCurrent()
    }

    @Test
    fun missingToolsDoNotProduceAServerStartRequest() = runTest {
        val fixture = Fixture().also { it.verifier.onVerify = { SetupVerification(listOf("nginx", "cloudflared")) } }
        val model = fixture.create()
        runCurrent()

        model.checkAndStart()
        runCurrent()

        assertFalse(model.state.value.checking)
        assertFalse(model.state.value.verified)
        assertNull(model.state.value.startRequest)
        val message = requireNotNull(model.state.value.error)
        assertTrue(message.contains("nginx"))
        assertTrue(message.contains("cloudflared"))
        assertEquals(0, fixture.preferences.writes)
    }

    @Test
    fun aConnectionFailureDoesNotExposeCredentialsFromAnException() = runTest {
        val fixture = Fixture().also { it.verifier.onVerify = { throw IOException("Authorization: Bearer $secret") } }
        val model = fixture.create()
        runCurrent()

        model.checkAndStart()
        runCurrent()

        assertFalse(model.state.value.checking)
        assertFalse(model.state.value.verified)
        assertNull(model.state.value.startRequest)
        val message = requireNotNull(model.state.value.error)
        assertTrue(message.contains("Termux bağlantısı"))
        assertFalse(message.contains(secret))
        assertFalse(message.contains("Authorization"))
    }

    @Test
    fun verificationTimeoutIsAnActionableFailureInsteadOfAStuckSpinner() = runTest {
        val fixture = Fixture().also {
            it.verifier.onVerify = { withTimeout(10) { delay(100); SetupVerification(emptyList()) } }
        }
        val model = fixture.create()
        runCurrent()

        model.checkAndStart()
        advanceUntilIdle()

        assertFalse(model.state.value.checking)
        assertFalse(model.state.value.verified)
        assertNull(model.state.value.startRequest)
        assertTrue(requireNotNull(model.state.value.error).contains("Termux bağlantısı"))
    }

    @Test
    fun cancellationBeforeTheQueuedCheckStartsCannotRequestAServer() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()

        model.checkAndStart()
        model.cancelCheck()
        runCurrent()

        assertEquals(0, fixture.verifier.calls)
        assertFalse(model.state.value.checking)
        assertFalse(model.state.value.verified)
        assertNull(model.state.value.startRequest)
    }

    @Test
    fun aCancelledUncooperativeVerifierCannotStartTheServerWhenItReturns() = runTest {
        val fixture = Fixture()
        lateinit var result: Continuation<SetupVerification>
        fixture.verifier.onVerify = { suspendCoroutine { result = it } }
        val model = fixture.create()
        runCurrent()
        model.checkAndStart()
        runCurrent()

        model.cancelCheck()
        result.resume(SetupVerification(emptyList()))
        runCurrent()

        assertFalse(model.state.value.checking)
        assertFalse(model.state.value.verified)
        assertNull(model.state.value.startRequest)
        assertEquals(0, fixture.preferences.writes)
    }

    @Test
    fun aSupersededResultCannotEraseTheNewChecksFailure() = runTest {
        val fixture = Fixture()
        lateinit var previous: Continuation<SetupVerification>
        fixture.verifier.onVerify = { suspendCoroutine { previous = it } }
        val model = fixture.create()
        runCurrent()
        model.checkAndStart()
        runCurrent()
        model.cancelCheck()
        fixture.verifier.onVerify = { SetupVerification(listOf("node")) }
        model.checkAndStart()
        runCurrent()

        previous.resume(SetupVerification(emptyList()))
        runCurrent()

        assertEquals(2, fixture.verifier.calls)
        assertFalse(model.state.value.checking)
        assertFalse(model.state.value.verified)
        assertNull(model.state.value.startRequest)
        assertTrue(requireNotNull(model.state.value.error).contains("node"))
    }

    @Test
    fun aSuccessfulCheckProducesOneConsumableStartRequest() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()

        model.checkAndStart()
        runCurrent()

        val first = requireNotNull(model.state.value.startRequest)
        assertTrue(model.state.value.verified)
        assertFalse(model.consumeStartRequest(first + 1))
        assertEquals(first, model.state.value.startRequest)
        assertTrue(model.consumeStartRequest(first))
        assertFalse(model.consumeStartRequest(first))
        assertNull(model.state.value.startRequest)

        model.checkAndStart()
        runCurrent()
        val second = requireNotNull(model.state.value.startRequest)
        assertTrue(second > first)
        assertFalse(model.consumeStartRequest(first))
        assertEquals(second, model.state.value.startRequest)
    }

    @Test
    fun verificationAloneDoesNotPersistCompletionAndTheRuntimeCallbackSavesOnce() = runTest {
        val fixture = Fixture()
        val persisted = CompletableDeferred<Unit>()
        fixture.preferences.onMark = { persisted.await() }
        val model = fixture.create()
        runCurrent()

        model.complete()
        runCurrent()
        assertEquals(0, fixture.preferences.writes)
        model.checkAndStart()
        runCurrent()
        assertFalse(model.state.value.completed)
        assertEquals(0, fixture.preferences.writes)
        model.complete()
        model.complete()
        runCurrent()
        model.complete()
        assertEquals(1, fixture.preferences.writes)
        assertFalse(model.state.value.completed)

        persisted.complete(Unit)
        runCurrent()
        model.complete()
        runCurrent()

        assertTrue(model.state.value.completed)
        assertEquals(1, fixture.preferences.writes)
    }

    @Test
    fun completionStorageFailureKeepsVerificationAndAllowsAnExplicitRetry() = runTest {
        val fixture = Fixture().also { it.preferences.onMark = { throw IOException("key=$secret") } }
        val model = fixture.create()
        runCurrent()
        model.checkAndStart()
        runCurrent()

        model.complete()
        runCurrent()

        assertFalse(model.state.value.completed)
        assertTrue(model.state.value.verified)
        assertEquals(1, fixture.preferences.writes)
        val message = requireNotNull(model.state.value.error)
        assertTrue(message.contains("kaydedilemedi"))
        assertFalse(message.contains(secret))
        fixture.preferences.onMark = {}
        model.complete()
        runCurrent()

        assertTrue(model.state.value.completed)
        assertEquals(2, fixture.preferences.writes)
    }

    @Test
    fun copiedFeedbackOnlyAppearsAfterTheExplicitClipboardSuccessCallback() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()
        model.checkAndStart()
        runCurrent()
        assertFalse(model.state.value.commandCopied)
        model.showError("Panoya kopyalanamadı")
        assertNotNull(model.state.value.error)

        model.commandCopied()

        assertTrue(model.state.value.commandCopied)
        assertNull(model.state.value.error)
        assertEquals(0, fixture.preferences.writes)
    }

    @Test
    fun anUnfinishedPermissionInspectionCannotVerifyOrStart() = runTest {
        val permission = CompletableDeferred<TermuxPermissionStatus>()
        val fixture = Fixture().also { it.inspector.onInspect = { permission.await() } }
        val model = fixture.create()
        runCurrent()
        assertNull(model.state.value.permissionStatus)
        assertTrue(model.state.value.inspectingPermission)

        model.checkAndStart()
        runCurrent()

        assertEquals(0, fixture.verifier.calls)
        assertFalse(model.state.value.checking)
        assertNull(model.state.value.startRequest)
        permission.complete(TermuxPermissionStatus(TermuxPermissionState.GRANTED))
        runCurrent()
        assertNull(model.state.value.startRequest)
        assertEquals(0, fixture.verifier.calls)
    }

    @Test
    fun unavailablePermissionDoesNotAttemptACommandOrStartTheServer() = runTest {
        val fixture = Fixture().also {
            it.inspector.status = TermuxPermissionStatus(TermuxPermissionState.PERMISSION_UNAVAILABLE, "0.135")
        }
        val model = fixture.create()
        runCurrent()

        model.checkAndStart()
        runCurrent()

        assertEquals(0, fixture.verifier.calls)
        assertFalse(model.state.value.verified)
        assertNull(model.state.value.startRequest)
        assertTrue(requireNotNull(model.state.value.error).contains("F-Droid/GitHub"))
        assertEquals("0.135", model.state.value.permissionStatus?.versionName)
    }

    @Test
    fun aCachedGrantIsCheckedAgainBeforeAnyTermuxCommand() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()
        assertTrue(requireNotNull(model.state.value.permissionStatus).granted)
        fixture.inspector.status = TermuxPermissionStatus(TermuxPermissionState.DENIED)

        model.checkAndStart()
        runCurrent()

        assertEquals(2, fixture.inspector.calls)
        assertEquals(TermuxPermissionState.DENIED, model.state.value.permissionStatus?.state)
        assertEquals(0, fixture.verifier.calls)
        assertFalse(model.state.value.checking)
        assertFalse(model.state.value.verified)
        assertNull(model.state.value.startRequest)
    }

    @Test
    fun aFailedFreshInspectionCannotVerifyAndDoesNotExposeItsException() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()
        fixture.inspector.onInspect = { throw IOException("permission token=$secret") }

        model.checkAndStart()
        runCurrent()

        assertEquals(0, fixture.verifier.calls)
        assertFalse(model.state.value.checking)
        assertFalse(model.state.value.verified)
        assertNull(model.state.value.startRequest)
        assertFalse(requireNotNull(model.state.value.error).contains(secret))
    }

    @Test
    fun anUnreadablePermissionStateClearsReadinessAndUsesSafeFeedback() = runTest {
        val fixture = Fixture().also {
            it.inspector.onInspect = { throw IOException("Authorization: Bearer $secret") }
        }
        val model = fixture.create()
        runCurrent()

        assertNull(model.state.value.permissionStatus)
        assertFalse(model.state.value.inspectingPermission)
        val message = requireNotNull(model.state.value.permissionError)
        assertFalse(message.contains(secret))
        assertFalse(message.contains("Authorization"))
        model.checkAndStart()
        runCurrent()
        assertEquals(0, fixture.verifier.calls)
        assertNull(model.state.value.startRequest)
    }

    @Test
    fun aPermissionRevocationInvalidatesAnUnconsumedServerStartRequest() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()
        model.checkAndStart()
        runCurrent()
        val request = requireNotNull(model.state.value.startRequest)
        fixture.inspector.status = TermuxPermissionStatus(TermuxPermissionState.DENIED)

        model.refreshPermission()
        runCurrent()

        assertFalse(model.state.value.verified)
        assertNull(model.state.value.startRequest)
        assertFalse(model.consumeStartRequest(request))
        assertEquals(1, fixture.verifier.calls)
        assertEquals(0, fixture.preferences.writes)
    }

    @Test
    fun aHiddenScreenRejectsALateUncooperativeVerificationResult() = runTest {
        val fixture = Fixture()
        lateinit var result: Continuation<SetupVerification>
        fixture.verifier.onVerify = { suspendCoroutine { result = it } }
        val model = fixture.create()
        runCurrent()
        model.checkAndStart()
        runCurrent()

        model.onHidden()
        result.resume(SetupVerification(emptyList()))
        runCurrent()

        assertFalse(model.state.value.checking)
        assertFalse(model.state.value.verified)
        assertNull(model.state.value.startRequest)
        assertTrue(requireNotNull(model.state.value.error).contains("kapandığı"))
        assertEquals(0, fixture.preferences.writes)
    }

    @Test
    fun returningFromPermissionSettingsOnlyRefreshesAndDoesNotStartAutomatically() = runTest {
        val fixture = Fixture().also {
            it.inspector.status = TermuxPermissionStatus(TermuxPermissionState.DENIED)
        }
        val model = fixture.create()
        runCurrent()
        fixture.inspector.status = TermuxPermissionStatus(TermuxPermissionState.GRANTED)

        model.refreshPermission()
        runCurrent()

        assertTrue(requireNotNull(model.state.value.permissionStatus).granted)
        assertEquals(2, fixture.inspector.calls)
        assertEquals(0, fixture.verifier.calls)
        assertFalse(model.state.value.checking)
        assertFalse(model.state.value.verified)
        assertNull(model.state.value.startRequest)
        assertEquals(0, fixture.preferences.writes)
    }

    private inner class Fixture {
        val preferences = FakePreferences()
        val verifier = FakeVerifier()
        val inspector = FakePermissionInspector()
        val store = ViewModelStore().also(stores::add)

        fun create(): SetupViewModel = ViewModelProvider.create(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(modelClass.cast(
                SetupViewModel(preferences, verifier, inspector),
            ))
        })[SetupViewModel::class]
    }

    private class FakePreferences : SetupPreferences {
        var completed = false
        var reads = 0
        var writes = 0
        var onRead: suspend () -> Boolean = { completed }
        var onMark: suspend () -> Unit = {}
        override suspend fun isCompleted(): Boolean { reads++; return onRead() }
        override suspend fun markCompleted() { writes++; onMark(); completed = true }
    }

    private class FakeVerifier : SetupVerifier {
        var calls = 0
        var onVerify: suspend () -> SetupVerification = { SetupVerification(emptyList()) }
        override suspend fun verify(): SetupVerification { calls++; return onVerify() }
    }

    private class FakePermissionInspector : TermuxPermissionInspector {
        var status = TermuxPermissionStatus(TermuxPermissionState.GRANTED)
        var calls = 0
        var onInspect: suspend () -> TermuxPermissionStatus = { status }
        override suspend fun inspect(): TermuxPermissionStatus { calls++; return onInspect() }
    }

    companion object {
        private val secret = "a9".repeat(32)
    }
}
