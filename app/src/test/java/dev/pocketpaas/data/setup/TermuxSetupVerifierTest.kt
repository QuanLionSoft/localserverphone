package dev.pocketpaas.data.setup

import dev.pocketpaas.data.process.termux.BridgeHttpException
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.process.ManagedProcess
import dev.pocketpaas.domain.process.ProcessExecutor
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.process.ProcessResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxSetupVerifierTest {
    @Test
    fun readinessRequiresTheBridgeAndEveryRequiredExecutable() = runTest {
        val shell = FakeShell()
        val result = TermuxSetupVerifier(shell).verify()
        assertTrue(result.ready)
        assertEquals(emptyList<String>(), result.missingTools)
        assertEquals(listOf("layout", "python", "nginx", "node", "npm", "cloudflared"), shell.calls)
    }

    @Test
    fun missingExecutablesAreReportedTogetherWithoutRequiringPip() = runTest {
        val shell = FakeShell(resolve = { tool ->
            if (tool in setOf("nginx", "npm")) throw BridgeHttpException(404, "executable missing")
            "/termux/bin/$tool"
        })
        val result = TermuxSetupVerifier(shell).verify()
        assertFalse(result.ready)
        assertEquals(listOf("nginx", "npm"), result.missingTools)
        assertEquals("cloudflared", shell.calls.last())
        assertFalse("pip" in shell.calls)
    }

    @Test
    fun unreachableBridgeFailsBeforeAnyExecutableCheck() = runTest {
        val problem = IOException("bridge unavailable")
        val shell = FakeShell(layoutFailure = problem)
        val failure = runCatching { TermuxSetupVerifier(shell).verify() }.exceptionOrNull()
        assertPreservedFailure(problem, failure)
        assertEquals(listOf("layout"), shell.calls)
    }

    @Test
    fun authenticationAndProtocolFailuresCannotBeReportedAsReadyOrMissingPackages() = runTest {
        for (status in listOf(401, 403, 409, 500)) {
            val problem = BridgeHttpException(status, "bridge rejected request")
            val shell = FakeShell(resolve = { throw problem })
            val failure = runCatching { TermuxSetupVerifier(shell).verify() }.exceptionOrNull()
            assertSame(problem, failure)
            assertEquals(listOf("layout", "python"), shell.calls)
        }
    }

    @Test
    fun transportFailureAfterSuccessfulLayoutStillFailsVerification() = runTest {
        val problem = IOException("connection lost")
        val shell = FakeShell(resolve = { tool ->
            if (tool == "nginx") throw problem
            "/termux/bin/$tool"
        })
        val failure = runCatching { TermuxSetupVerifier(shell).verify() }.exceptionOrNull()
        assertPreservedFailure(problem, failure)
        assertEquals(listOf("layout", "python", "nginx"), shell.calls)
    }

    @Test
    fun cancellationPropagatesWithoutBecomingAMissingExecutable() = runTest {
        val problem = CancellationException("screen closed")
        val shell = FakeShell(resolve = { throw problem })
        val failure = runCatching { TermuxSetupVerifier(shell).verify() }.exceptionOrNull()
        assertPreservedFailure(problem, failure)
    }

    @Test
    fun theWholeVerificationHasOneThirtySecondDeadline() = runTest {
        val shell = FakeShell(resolve = { awaitCancellation() })
        val failure = runCatching { TermuxSetupVerifier(shell).verify() }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertEquals(listOf("layout", "python"), shell.calls)
    }

    private fun assertPreservedFailure(original: Exception, actual: Throwable?) {
        assertEquals(original.javaClass, actual?.javaClass)
        assertEquals(original.message, actual?.message)
        // Coroutine stack recovery may copy an exception and retain the original as its cause.
        if (actual !== original) assertSame(original, actual?.cause)
    }

    private class FakeShell(
        private val layoutFailure: Exception? = null,
        private val resolve: suspend (String) -> String = { "/termux/bin/$it" },
    ) : ProcessExecutor {
        val calls = mutableListOf<String>()

        override suspend fun runtimeLayout(): RuntimeLayout {
            calls += "layout"
            layoutFailure?.let { throw it }
            return RuntimeLayout(
                rootPath = "/termux/.pocket-paas",
                projectsPath = "/termux/.pocket-paas/htdocs",
                nginxConfigPath = "/termux/.pocket-paas/nginx/nginx.conf",
                nginxHostsPath = "/termux/.pocket-paas/nginx/conf.d",
                runtimePath = "/termux/.pocket-paas/runtime",
                logsPath = "/termux/.pocket-paas/logs",
            )
        }

        override suspend fun resolveBinary(name: String): String {
            calls += name
            return resolve(name)
        }

        override suspend fun execute(request: ProcessRequest, timeoutMillis: Long): ProcessResult =
            error("Setup verification must not execute commands.")

        override suspend fun executeCommand(
            command: String,
            envVars: Map<String, String>,
            workingDir: File,
        ): ProcessResult = error("Setup verification must not execute commands.")

        override suspend fun startProcess(request: ProcessRequest): ManagedProcess =
            error("Setup verification must not start processes.")

        override suspend fun startProcess(
            arguments: List<String>,
            envVars: Map<String, String>,
            workingDir: File,
        ): ManagedProcess = error("Setup verification must not start processes.")
    }
}
