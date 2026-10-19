package dev.pocketpaas.data.process.termux

import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessRequest
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real HTTP bridge and adapter; the fixture does not launch host child processes. */
class RemoteProcessOwnershipTest {
    @Test(timeout = 15_000)
    fun anAcknowledgedFailureCleanupMakesRepeatedTerminationIdempotent() = runBlocking {
        withFixture { fixture ->
            val process = fixture.create()
            assertTrue(runCatching { process.waitFor() }.exceptionOrNull() is IOException)
            assertTrue(fixture.firstStop.await(5, TimeUnit.SECONDS))

            process.terminate(0)
            process.terminate(0)

            assertEquals(1, fixture.stops.get())
            assertTrue(runCatching { process.waitFor() }.exceptionOrNull() is IOException)
            fixture.bridge.resetRuntime(emptyList())
        }
    }

    @Test(timeout = 15_000)
    fun aFailedStopRetainsOwnershipAndAnExplicitRetryCanConfirmCleanup() = runBlocking {
        withFixture(failFirstStop = true) { fixture ->
            val process = fixture.create()
            assertTrue(runCatching { process.waitFor() }.exceptionOrNull() is IOException)
            assertTrue(fixture.firstStop.await(5, TimeUnit.SECONDS))
            assertTrue(runCatching { fixture.bridge.resetRuntime(emptyList()) }.exceptionOrNull() is IllegalStateException)

            process.terminate(0)
            process.terminate(0)

            assertEquals(2, fixture.stops.get())
            fixture.bridge.resetRuntime(emptyList())
            assertTrue(runCatching { process.waitFor() }.exceptionOrNull() is IOException)
        }
    }

    @Test(timeout = 15_000)
    fun aLostForgetResponseDoesNotRequireStoppingAnAlreadyObservedExitedProcess() = runBlocking {
        withFixture(normalExit = true) { fixture ->
            val process = fixture.create()
            assertTrue(runCatching { process.waitFor() }.exceptionOrNull() is IOException)

            process.terminate(0)
            process.terminate(0)

            assertEquals(0, fixture.stops.get())
            fixture.bridge.resetRuntime(emptyList())
        }
    }

    private suspend fun withFixture(
        failFirstStop: Boolean = false,
        normalExit: Boolean = false,
        action: suspend (Fixture) -> Unit,
    ) {
        val fixture = Fixture(failFirstStop, normalExit)
        try {
            action(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(failFirstStop: Boolean, normalExit: Boolean) {
        private val id = UUID.randomUUID().toString()
        private val pool = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 4).apply { executor = pool }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val credentials = TermuxBridgeCredentials(server.address.port, "d".repeat(64))
        val stops = AtomicInteger()
        val firstStop = CountDownLatch(1)
        val bridge = TermuxBridge(
            TermuxBridgeCredentialsStore { credentials },
            TermuxAgentBootstrap { error("The authenticated fixture is already running") },
            Gson(),
            scope,
        )

        init {
            server.createContext("/") { exchange ->
                exchange.use {
                    exchange.requestBody.readBytes()
                    var status = 200
                    val body = when (exchange.requestURI.path) {
                        "/health" -> "{\"protocolVersion\":1}"
                        "/processes" -> "{\"id\":\"$id\",\"pid\":12345}"
                        "/capabilities" -> "{\"runtimeResetVersion\":1}"
                        "/runtime/reset" -> "{\"stopped\":true,\"liveProcesses\":0,\"unconfirmedPids\":[]}"
                        "/processes/$id/poll" -> if (normalExit) {
                            "{\"stdout\":\"\",\"stderr\":\"\",\"stdoutClosed\":true,\"stderrClosed\":true,\"exitCode\":0}"
                        } else {
                            status = 503
                            "{\"error\":\"fixture poll failed\"}"
                        }
                        "/processes/$id/stop" -> {
                            val attempt = stops.incrementAndGet()
                            if (failFirstStop && attempt == 1) status = 503
                            else if (attempt > if (failFirstStop) 2 else 1) status = 404
                            firstStop.countDown()
                            if (status == 200) "{\"exitCode\":-15}" else "{\"error\":\"fixture stop failed\"}"
                        }
                        "/processes/$id" -> {
                            // The child exit is already observed, but deletion acknowledgement fails.
                            status = 503
                            "{\"error\":\"fixture forget response lost\"}"
                        }
                        else -> error("Unexpected fixture request: ${exchange.requestURI.path}")
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
            }
            server.start()
        }

        suspend fun create(): RemoteProcess {
            val ticket = bridge.create(ProcessRequest(
                ProcessCommand.Arguments(listOf("unused-fixture-executable")),
                emptyMap(),
                File(System.getProperty("user.dir")),
            ))
            return RemoteProcess(ticket, bridge, scope)
        }

        fun close() {
            scope.cancel()
            server.stop(0)
            pool.shutdownNow()
        }
    }
}
