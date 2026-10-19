package dev.pocketpaas.data.process.termux

import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import dev.pocketpaas.core.config.ProcessPolicy
import dev.pocketpaas.data.process.ShellExecutor
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.process.ProcessStream
import dev.pocketpaas.domain.process.ProcessTermination
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Executes the production Kotlin HTTP transport against the bundled production Python agent. */
class TermuxBridgeIntegrationTest {
    @Test(timeout = 30_000)
    fun aColdBridgeResetsAuthenticatedOldSessionChildrenWithoutGuessingPids() = runBlocking {
        withFixture { fixture ->
            val ticket = fixture.bridge.create(fixture.request("import time;time.sleep(60)"))
            val child = ProcessHandle.of(ticket.pid.toLong()).orElseThrow()
            val cold = TermuxBridge(TermuxBridgeCredentialsStore { fixture.credentials }, fixture.bootstrap, Gson(), fixture.scope)
            assertTrue(child.isAlive)
            cold.resetRuntime(listOf(ticket.pid))
            assertFalse(child.isAlive)
            assertEquals(1, fixture.bootstrap.starts.get())
            assertTrue(cold.layoutJson().has("rootPath"))
        }
    }

    @Test(timeout = 30_000)
    fun runtimeResetRefusesToDiscardChildrenOwnedByThisLiveBridge() = runBlocking {
        withFixture { fixture ->
            val ticket = fixture.bridge.create(fixture.request("import time;time.sleep(60)"))
            val child = ProcessHandle.of(ticket.pid.toLong()).orElseThrow()
            assertTrue(runCatching { fixture.bridge.resetRuntime(listOf(ticket.pid)) }.exceptionOrNull() is IllegalStateException)
            assertTrue(child.isAlive)
            fixture.bridge.stop(ticket, 0)
        }
    }

    @Test(timeout = 30_000)
    fun unknownLivePidBlocksColdRecoveryWithoutSignalingTheUnownedProcess() = runBlocking {
        withFixture { fixture ->
            val applicationPid = ProcessHandle.current().pid().toInt()
            assertTrue(runCatching { fixture.bridge.resetRuntime(listOf(applicationPid)) }.exceptionOrNull() is IOException)
            assertTrue(ProcessHandle.current().isAlive)
            assertEquals(1, fixture.bootstrap.starts.get())
        }
    }

    @Test(timeout = 20_000)
    fun cancellingBlockedArchiveUploadDisconnectsTheBodyAndDeletesAnUncertainCommit() = runBlocking {
        val production = System.getProperty("pocketpaas.production.root")?.let(::File)
        assumeTrue("Production source path is required.", production != null)
        val scratch = File(requireNotNull(production), "verification/room-jvm/build/test-runtime")
        assertTrue(scratch.isDirectory || scratch.mkdirs())
        val spool = Files.createTempFile(scratch.toPath(), "cancel-upload-", ".zip").toFile()
        val pool = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 4).apply { executor = pool }
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deleted = CountDownLatch(1)
        val attemptedUpload = AtomicReference<String>()
        val id = UUID.randomUUID().toString()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server.createContext("/") { exchange ->
            exchange.use {
                val response = when (exchange.requestURI.path) {
                    "/health" -> "{\"protocolVersion\":1}"
                    "/capabilities" -> "{\"archiveTransferVersion\":1,\"cleanupOwnershipVersion\":1}"
                    "/layout" -> "{\"runtimePath\":\"/runtime\"}"
                    "/archives/$id" -> {
                        if (exchange.requestMethod == "DELETE") {
                            assertEquals(attemptedUpload.get(), exchange.requestHeaders.getFirst("X-PocketPaaS-Upload-Id"))
                            deleted.countDown()
                            "{\"deleted\":true}"
                        } else {
                            attemptedUpload.set(requireNotNull(exchange.requestHeaders.getFirst("X-PocketPaaS-Upload-Id")))
                            exchange.requestBody.read()
                            accepted.countDown()
                            release.await(10, TimeUnit.SECONDS)
                            return@createContext
                        }
                    }
                    else -> error("Unexpected fixture request")
                }
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        try {
            spool.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                repeat(512) { output.write(buffer) }
            }
            server.start()
            val credentials = TermuxBridgeCredentials(server.address.port, "c".repeat(64))
            val bridge = TermuxBridge(
                TermuxBridgeCredentialsStore { credentials },
                TermuxAgentBootstrap { error("The fixture server is already ready") },
                Gson(),
                scope,
            )
            val upload = launch(Dispatchers.IO) { bridge.uploadArchive(spool, id, 64L * 1024L * 1024L) }
            assertTrue("The fixture must accept the binary request body.", accepted.await(5, TimeUnit.SECONDS))
            withTimeout(5_000L) { upload.cancelAndJoin() }
            assertTrue(upload.isCancelled)
            assertEquals("An uncertain upload outcome requires authenticated deletion.", 0L, deleted.count)
        } finally {
            release.countDown()
            scope.cancel()
            server.stop(0)
            pool.shutdownNow()
            spool.delete()
        }
    }

    @Test(timeout = 30_000)
    fun binaryArchiveTransferUsesTheAuthenticatedAgentAndCleansUploadedFiles() = runBlocking {
        withFixture { fixture ->
            val archive = File(fixture.runtimeRoot.parentFile, "private-upload.zip")
            val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            archive.writeBytes(bytes)
            val id = UUID.randomUUID().toString()
            val transferred = fixture.bridge.uploadArchive(archive, id, 64L * 1024L * 1024L)
            val expected = File(fixture.runtimeRoot, "runtime/uploads/$id.zip")
            assertEquals(expected.path, transferred)
            assertTrue(bytes.contentEquals(expected.readBytes()))
            fixture.bridge.deleteArchive(transferred)
            fixture.bridge.deleteArchive(transferred)
            assertFalse(expected.exists())
            assertEquals(1, fixture.bootstrap.starts.get())
        }
    }

    @Test(timeout = 30_000)
    fun archiveLimitTraversalAndConflictingUploadCannotReplaceAnExistingArchive() = runBlocking {
        withFixture { fixture ->
            val archive = File(fixture.runtimeRoot.parentFile, "private-upload.zip")
            archive.writeText("original")
            try {
                fixture.bridge.uploadArchive(archive, UUID.randomUUID().toString(), 1)
                fail("The private spool must fit the caller's upload limit.")
            } catch (_: IOException) {
                assertEquals(0, fixture.bootstrap.starts.get())
            }
            val id = UUID.randomUUID().toString()
            val uploaded = fixture.bridge.uploadArchive(archive, id, 1024)
            archive.writeText("replacement")
            try {
                fixture.bridge.uploadArchive(archive, id, 1024)
                fail("An existing archive identifier must not be replaced.")
            } catch (expected: BridgeHttpException) {
                assertEquals(409, expected.status)
            }
            assertEquals("original", File(uploaded).readText())
            try {
                fixture.bridge.deleteArchive(File(fixture.runtimeRoot.parentFile, "$id.zip").path)
                fail("Archive deletion must reject paths outside runtime/uploads.")
            } catch (_: IllegalArgumentException) {
                assertEquals("original", File(uploaded).readText())
            }
            fixture.bridge.deleteArchive(uploaded)
        }
    }

    @Test(timeout = 30_000)
    fun argvCarriesEnvironmentPidAndDrainsBothStreamsUnderBackpressure() = runBlocking {
        withFixture { fixture ->
            val result = fixture.shell.execute(
                fixture.request(
                    "import os,sys; " +
                        "sys.stdout.write('A'*524288+'\\n'+os.environ['BRIDGE_TEST']+'\\n'); " +
                        "sys.stdout.flush(); sys.stderr.write('B'*524288+'\\nerr-end\\n'); sys.stderr.flush()",
                    mapOf("BRIDGE_TEST" to "Turkish-İ-😀"),
                ),
            )
            assertTrue(result.isSuccessful)
            assertNotNull(result.processId)
            assertTrue(requireNotNull(result.processId) > 0)
            assertTrue(result.stdout.replace("\r\n", "\n").endsWith("\nTurkish-İ-😀\n"))
            assertTrue(result.stderr.replace("\r\n", "\n").endsWith("\nerr-end\n"))
            assertTrue(result.stdoutTruncated)
            assertTrue(result.stderrTruncated)
            assertEquals(1, fixture.bootstrap.starts.get())
        }
    }

    @Test(timeout = 30_000)
    fun managedRemoteProcessStreamsLogsAndStopsCleanly() = runBlocking {
        withFixture { fixture ->
            val managed = fixture.shell.startProcess(
                fixture.request("import time; print('remote-ready',flush=True); time.sleep(60)"),
            )
            val output = withTimeout(10_000L) {
                managed.logs.first { it.stream == ProcessStream.STDOUT && "remote-ready" in it.text }
            }
            assertEquals(managed.sessionId, output.sessionId)
            val stopped = withTimeout(10_000L) { managed.stop() }
            assertEquals(ProcessTermination.STOPPED, stopped.termination)
            assertFalse(stopped.isSuccessful)
            assertNotNull(stopped.exitCode)
            assertTrue(stopped.stdout.contains("remote-ready"))
        }
    }

    @Test(timeout = 30_000)
    fun occupiedAgentWithWrongTokenFailsWithoutStartingAnotherAgent() = runBlocking {
        withFixture { fixture ->
            fixture.bridge.layoutJson()
            val unexpectedStarts = AtomicInteger()
            val other = TermuxBridge(
                TermuxBridgeCredentialsStore { fixture.credentials.copy(token = "b".repeat(64)) },
                TermuxAgentBootstrap { unexpectedStarts.incrementAndGet() },
                Gson(),
                fixture.scope,
            )
            try {
                other.layoutJson()
                fail("An agent owned by another token must reject the request.")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("authentication"))
                assertFalse(expected.toString().contains(fixture.credentials.token))
                assertFalse(expected.toString().contains("b".repeat(64)))
            }
            assertEquals(0, unexpectedStarts.get())
            assertEquals(1, fixture.bootstrap.starts.get())
        }
    }

    @Test(timeout = 30_000)
    fun repeatedCommandsReuseTheReadyBridge() = runBlocking {
        withFixture { fixture ->
            repeat(3) {
                val result = fixture.shell.execute(fixture.request("print('reused')"))
                assertTrue(result.isSuccessful)
                assertEquals("reused\n", result.stdout.replace("\r\n", "\n"))
            }
            assertEquals(1, fixture.bootstrap.starts.get())
        }
    }

    @Test(timeout = 30_000)
    fun unavailableTransportFailsTheRunningCommandWithoutFabricatingAnExitCode() = runBlocking {
        withFixture { fixture ->
            val managed = fixture.shell.startProcess(
                fixture.request("import time; print('transport-ready',flush=True); time.sleep(60)"),
            )
            withTimeout(10_000L) { managed.logs.first { "transport-ready" in it.text } }
            fixture.bootstrap.agent?.destroyForcibly()
            try {
                withTimeout(10_000L) { managed.await() }
                fail("Transport loss must fail the command.")
            } catch (expected: IOException) {
                assertFalse(expected.toString().contains(fixture.credentials.token))
            } finally {
                managed.processId?.let { pid ->
                    ProcessHandle.of(pid.toLong()).ifPresent { process ->
                        if (process.isAlive) process.destroyForcibly()
                    }
                }
            }
        }
    }

    @Test(timeout = 30_000)
    fun shellAndRuntimeBinaryResolutionWorkOnPosixHosts() = runBlocking {
        // Android is POSIX. Windows host verification exercises argv and the exact same transport.
        assumeTrue("The production shell contract requires a POSIX host.", File.separatorChar == '/')
        withFixture { fixture ->
            val layout = fixture.shell.runtimeLayout()
            assertTrue(layout.projectsPath.startsWith(layout.rootPath + "/"))
            assertTrue(fixture.shell.resolveBinary("python").startsWith("/"))
            val result = fixture.shell.executeCommand(
                "printf '%s\\n' \"\$BRIDGE_TEST\"; printf 'shell-stderr\\n' >&2",
                mapOf("BRIDGE_TEST" to "shell-env"),
                fixture.runtimeRoot,
            )
            assertEquals("shell-env\n", result.stdout)
            assertEquals("shell-stderr\n", result.stderr)
            assertTrue(result.isSuccessful)
        }
    }

    @Test(timeout = 30_000)
    fun cancellingOwnerBeforePollingStartsStillTerminatesTheRemoteChild() = runBlocking {
        withFixture { fixture ->
            fixture.bridge.layoutJson()
            val dispatcher = StandardTestDispatcher()
            val owner = CoroutineScope(SupervisorJob() + dispatcher)
            val bridge = TermuxBridge(
                TermuxBridgeCredentialsStore { fixture.credentials },
                fixture.bootstrap,
                Gson(),
                owner,
            )
            val launched = TermuxProcessLauncher(bridge, owner).launch(
                fixture.request("import time; time.sleep(60)"),
            )
            val child = ProcessHandle.of(requireNotNull(launched.processId).toLong()).orElseThrow()
            try {
                assertTrue(child.isAlive)
                // Neither queued stream writer nor queued poll producer has run.
                owner.cancel()
                dispatcher.scheduler.runCurrent()
                withTimeout(10_000L) {
                    while (child.isAlive) delay(25)
                }
                try {
                    launched.process.waitFor()
                    fail("A cancelled supervisor must expose an IOException.")
                } catch (_: IOException) {
                    assertFalse(launched.process.isAlive)
                }
            } finally {
                owner.cancel()
                if (child.isAlive) child.destroyForcibly()
            }
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val python = System.getProperty("pocketpaas.test.python")?.takeIf(String::isNotBlank)
        assumeTrue("Set POCKETPAAS_TEST_PYTHON to run the real Python bridge integration tests.", python != null)
        val production = System.getProperty("pocketpaas.production.root")?.let(::File)
        assumeTrue("Production source path is required.", production != null)
        val script = File(requireNotNull(production), "app/src/main/assets/pocketpaas_agent.py")
        assertTrue("The production agent asset must exist.", script.isFile)
        val scratch = File(production, "verification/room-jvm/build/test-runtime")
        assertTrue(scratch.isDirectory || scratch.mkdirs())
        val home = Files.createTempDirectory(scratch.toPath(), "p-").toFile()
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val credentials = TermuxBridgeCredentials(port, "a".repeat(64))
        val bootstrap = HostBootstrap(requireNotNull(python), script, home, scope)
        val bridge = TermuxBridge(TermuxBridgeCredentialsStore { credentials }, bootstrap, Gson(), scope)
        val shell = ShellExecutor(
            TermuxProcessLauncher(bridge, scope),
            ProcessPolicy(commandTimeoutMillis = 15_000, maximumCaptureCharacters = 16_384, stopGraceMillis = 100),
            scope,
        )
        try {
            block(Fixture(requireNotNull(python), File(home, ".pocket-paas"), scope, credentials, bootstrap, bridge, shell))
        } catch (failure: Throwable) {
            throw AssertionError("Host bridge diagnostics: ${bootstrap.diagnostics()}", failure)
        } finally {
            runCatching { bridge.shutdown() }
            bootstrap.agent?.let { process ->
                runCatching {
                    withTimeout(5_000L) {
                        while (process.isAlive) delay(25)
                    }
                }
            }
            scope.cancel()
            bootstrap.cleanup()
            val scratchPath = scratch.canonicalFile.toPath()
            val homePath = home.canonicalFile.toPath()
            check(homePath != scratchPath && homePath.startsWith(scratchPath)) {
                "Refusing to delete a test directory outside the intended runtime scratch directory."
            }
            home.deleteRecursively()
        }
    }

    private data class Fixture(
        val python: String,
        val runtimeRoot: File,
        val scope: CoroutineScope,
        val credentials: TermuxBridgeCredentials,
        val bootstrap: HostBootstrap,
        val bridge: TermuxBridge,
        val shell: ShellExecutor,
    ) {
        fun request(code: String, environment: Map<String, String> = emptyMap()) = ProcessRequest(
            ProcessCommand.Arguments(listOf(python, "-c", code)),
            environment,
            runtimeRoot,
        )
    }

    private class HostBootstrap(
        private val python: String,
        private val script: File,
        private val home: File,
        private val scope: CoroutineScope,
    ) : TermuxAgentBootstrap {
        val starts = AtomicInteger()
        private val output = StringBuilder()
        var agent: Process? = null
            private set

        override suspend fun start(credentials: TermuxBridgeCredentials) {
            starts.incrementAndGet()
            val process = ProcessBuilder(
                python,
                "-B",
                "-c",
                HOST_RUNNER,
                script.absolutePath,
                "--port",
                credentials.port.toString(),
                "--token",
                credentials.token,
            ).apply {
                directory(home)
                environment()["HOME"] = home.absolutePath
                environment()["PREFIX"] = File(home, "prefix").absolutePath
                environment()["PYTHONIOENCODING"] = "utf-8"
                redirectErrorStream(false)
            }.start()
            agent = process
            process.outputStream.close()
            // The actual bridge host process also gets independent IO stream readers.
            scope.launch(Dispatchers.IO) {
                runCatching {
                    process.inputStream.bufferedReader().use { input ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            appendDiagnostic(String(buffer, 0, count))
                        }
                    }
                }
            }
            scope.launch(Dispatchers.IO) {
                runCatching {
                    process.errorStream.bufferedReader().use { input ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            appendDiagnostic(String(buffer, 0, count))
                        }
                    }
                }
            }
        }

        fun cleanup() {
            agent?.let { process ->
                if (process.isAlive) process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                process.inputStream.close()
                process.errorStream.close()
                process.outputStream.close()
            }
        }

        private fun appendDiagnostic(text: String) = synchronized(output) {
            output.append(text)
            if (output.length > 8192) output.delete(0, output.length - 8192)
        }

        fun diagnostics(): String = synchronized(output) {
            "alive=${agent?.isAlive}, starts=${starts.get()}, " +
                output.toString().replace(Regex("[a-fA-F0-9]{64}"), "[redacted]")
        }

        companion object {
            // This Windows-only host adapter avoids AppContainer's denied strict realpath query.
            // The packaged production asset keeps its strict POSIX containment validation.
            private val HOST_RUNNER = """
                import importlib.util,os,sys,traceback
                spec=importlib.util.spec_from_file_location('pocketpaas_host_test_agent',sys.argv[1])
                del sys.argv[1]
                agent=importlib.util.module_from_spec(spec)
                spec.loader.exec_module(agent)
                original_upload=agent.Agent.upload_archive
                def diagnostic_upload(self,*arguments,**keywords):
                    try:
                        return original_upload(self,*arguments,**keywords)
                    except Exception as failure:
                        frames=','.join(os.path.basename(frame.filename)+':'+str(frame.lineno)+':'+frame.name for frame in traceback.extract_tb(failure.__traceback__))
                        print('fixture archive failure: '+type(failure).__name__+', errno='+str(getattr(failure,'errno',None))+', winerror='+str(getattr(failure,'winerror',None))+', frames='+frames,file=sys.stderr,flush=True)
                        raise
                agent.Agent.upload_archive=diagnostic_upload
                if os.name=='nt':
                    original_resolve=agent.Path.resolve
                    fixture_root=original_resolve(agent.Path(os.environ['HOME']))
                    def windows_fixture_resolve(path,strict=False):
                        try:
                            return original_resolve(path,strict=strict)
                        except PermissionError:
                            if not strict or not path.exists():
                                raise
                            normalized=original_resolve(path)
                            try:
                                normalized.relative_to(fixture_root)
                            except ValueError:
                                raise PermissionError('Strict resolution denied outside the host fixture.')
                            for component in [normalized]+list(normalized.parents):
                                if component==fixture_root.parent:
                                    break
                                if component.is_symlink() or (hasattr(component,'is_junction') and component.is_junction()):
                                    raise PermissionError('Reparse paths require strict host resolution.')
                            return normalized
                    agent.Path.resolve=windows_fixture_resolve
                agent.main()
            """.trimIndent()
        }
    }
}
