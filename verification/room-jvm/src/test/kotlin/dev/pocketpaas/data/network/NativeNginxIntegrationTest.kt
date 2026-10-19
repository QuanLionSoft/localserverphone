package dev.pocketpaas.data.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pocketpaas.core.config.ProcessPolicy
import dev.pocketpaas.core.config.ServerConfig
import dev.pocketpaas.data.process.LocalProcessLauncher
import dev.pocketpaas.data.process.ShellExecutor
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.process.ManagedProcess
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.process.ProcessResult
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Native Nginx verifies parsing and routing, using ShellExecutor for every native process. */
class NativeNginxIntegrationTest {
    @Test(timeout = 30_000)
    fun nativeStaticAndReactRoutingHandlesMissingFilesAndHiddenPaths() = runBlocking<Unit> {
        nativeFixture {
            val static = site(1, "static.example", ProjectType.STATIC)
            val react = site(2, "react.example", ProjectType.REACT)
            val missingReact = site(3, "missing.example", ProjectType.REACT)
            install(static, "STATIC_INDEX")
            install(react, "REACT_INDEX")
            install(missingReact, null)
            file("$VIRTUAL_ROOT/htdocs/static.example/.env").writeText("PRIVATE_VALUE", Charsets.UTF_8)
            start("native-1")

            assertEquals(200, get("/sites/static.example/").status)
            assertEquals("STATIC_INDEX", get("/sites/static.example/").body)
            val redirect = get("/sites/static.example?x=7")
            assertEquals(308, redirect.status)
            assertEquals("/sites/static.example/?x=7", redirect.location)
            assertEquals(403, get("/sites/static.example/.env").status)
            assertEquals(404, get("/sites/static.example/not-found.txt").status)
            val route = get("/sites/react.example/dashboard/history")
            assertEquals(200, route.status)
            assertEquals("REACT_INDEX", route.body)
            assertEquals(404, get("/sites/missing.example/dashboard").status)
            assertEquals("native-1", get("/_pocketpaas/health").body)
        }
    }

    @Test(timeout = 30_000)
    fun nativeProxyStripsSitePrefixAndForwardsExternalRequestMetadata() = runBlocking<Unit> {
        nativeFixture {
            val nodeBackend = echoServer()
            val djangoBackend = echoServer()
            try {
                val node = site(1, "node.example", ProjectType.NODEJS, nodeBackend.address.port)
                val django = site(2, "django.example", ProjectType.DJANGO, djangoBackend.address.port)
                install(node, null)
                install(django, null)
                start("native-proxy")
                listOf(node, django).forEach { site ->
                    val response = get("/sites/${site.domainName}/echo?x=7", mapOf("X-Forwarded-Proto" to "https"))
                    assertEquals(200, response.status)
                    assertTrue(response.body.contains("uri=/echo?x=7\n"))
                    assertTrue(response.body.contains("prefix=/sites/${site.domainName}\n"))
                    assertTrue(response.body.contains("proto=https\n"))
                    assertTrue(response.body.contains("host=127.0.0.1\n"))
                    assertTrue(response.body.contains("forwarded-for=127.0.0.1\n"))
                }
            } finally {
                nodeBackend.stop(0)
                djangoBackend.stop(0)
            }
        }
    }

    @Test(timeout = 30_000)
    fun nativeCandidateValidationPreservesLiveMasterAndReloadChangesHealthRevision() = runBlocking<Unit> {
        nativeFixture {
            val site = site(1, "static.example", ProjectType.STATIC)
            install(site, "ORIGINAL_INDEX")
            start("native-original")
            val original = file(layout.nginxConfigPath).readText(Charsets.UTF_8)
            val candidateHost = "${layout.nginxHostsPath}/.invalid.candidate"
            file(candidateHost).writeText("pocketpaas_invalid_directive on;\n", Charsets.UTF_8)
            val candidateMaster = "${layout.nginxConfigPath}.candidate"
            file(candidateMaster).writeText(
                adapt(builder.renderMaster(layout, listOf(builder.hostPath(layout, site), candidateHost), "native-candidate")),
                Charsets.UTF_8,
            )
            val test = command("-c", actual(candidateMaster), "-t")
            assertFalse(test.isSuccessful)
            assertTrue(test.stderr.contains("pocketpaas_invalid_directive"))
            assertEquals(original, file(layout.nginxConfigPath).readText(Charsets.UTF_8))
            assertEquals("native-original", get("/_pocketpaas/health").body)
            assertEquals("ORIGINAL_INDEX", get("/sites/static.example/").body)

            file(layout.nginxConfigPath).writeText(adapt(builder.renderMaster(layout, revision = "native-reloaded")), Charsets.UTF_8)
            val reload = command("-c", actual(layout.nginxConfigPath), "-s", "reload")
            assertTrue(reload.stderr, reload.isSuccessful)
            awaitRevision("native-reloaded")
            assertEquals("ORIGINAL_INDEX", get("/sites/static.example/").body)
        }
    }

    private suspend fun nativeFixture(block: suspend NativeFixture.() -> Unit) {
        val path = System.getProperty("pocketpaas.test.nginx")
        assumeTrue("Set POCKETPAAS_TEST_NGINX to an official native Nginx binary", path != null && File(path).isFile)
        val fixture = NativeFixture(File(checkNotNull(path)).absoluteFile)
        try {
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private class NativeFixture(private val binary: File) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val base = File(System.getProperty("pocketpaas.production.root", "."), "verification/room-jvm/build/native-nginx-test")
        private val physicalRoot: File
        val layout = RuntimeLayout(
            rootPath = VIRTUAL_ROOT,
            projectsPath = "$VIRTUAL_ROOT/htdocs",
            nginxConfigPath = "$VIRTUAL_ROOT/nginx/nginx.conf",
            nginxHostsPath = "$VIRTUAL_ROOT/nginx/conf.d",
            runtimePath = "$VIRTUAL_ROOT/runtime",
            logsPath = "$VIRTUAL_ROOT/logs",
        )
        private val config = ServerConfig(nginxPort = ServerSocket(0).use { it.localPort })
        val builder = NginxConfigBuilder(config)
        private val shell = ShellExecutor(
            LocalProcessLauncher(),
            ProcessPolicy(stopGraceMillis = 500, drainTimeoutMillis = 5_000),
            scope,
        )
        private var handle: ManagedProcess? = null

        init {
            check(base.isDirectory || base.mkdirs())
            physicalRoot = Files.createTempDirectory(base.toPath(), "run-").toFile().absoluteFile
            listOf(
                layout.projectsPath, layout.nginxHostsPath, layout.runtimePath, layout.logsPath,
                "${layout.runtimePath}/client-body", "${layout.runtimePath}/proxy-temp",
            ).forEach { check(file(it).isDirectory || file(it).mkdirs()) }
        }

        fun install(site: Site, index: String?) {
            val root = "${layout.projectsPath}/${site.domainName}"
            check(file(root).isDirectory || file(root).mkdirs())
            if (index != null) file("$root/index.html").writeText(index, Charsets.UTF_8)
            file(builder.hostPath(layout, site)).writeText(adapt(builder.renderVirtualHost(site, root, layout)), Charsets.UTF_8)
        }

        suspend fun start(revision: String) {
            check(handle == null)
            file(layout.nginxConfigPath).writeText(adapt(builder.renderMaster(layout, revision = revision)), Charsets.UTF_8)
            val check = command("-c", actual(layout.nginxConfigPath), "-t")
            assertTrue(check.stderr, check.isSuccessful)
            handle = shell.startProcess(
                listOf(binary.absolutePath, "-p", physicalRoot.absolutePath.replace('\\', '/') + "/", "-c", actual(layout.nginxConfigPath), "-g", "daemon off;"),
                emptyMap(),
                physicalRoot,
            )
            awaitRevision(revision)
        }

        suspend fun command(vararg options: String): ProcessResult = shell.execute(
            ProcessRequest(
                ProcessCommand.Arguments(listOf(binary.absolutePath, "-p", physicalRoot.absolutePath.replace('\\', '/') + "/") + options),
                emptyMap(),
                physicalRoot,
            ),
            timeoutMillis = 5_000,
        )

        suspend fun awaitRevision(revision: String) {
            withTimeout(8_000) {
                while (true) {
                    val response = runCatching { get("/_pocketpaas/health") }.getOrNull()
                    if (response?.status == 200 && response.body == revision) return@withTimeout
                    delay(25)
                }
            }
        }

        suspend fun get(path: String, headers: Map<String, String> = emptyMap()): Response = withContext(Dispatchers.IO) {
            val connection = URL("http://127.0.0.1:${config.nginxPort}$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                connection.connectTimeout = 1_000
                connection.readTimeout = 1_000
                connection.instanceFollowRedirects = false
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                val status = connection.responseCode
                val input = if (status in 200..399) connection.inputStream else connection.errorStream
                val body = input?.use { String(it.readNBytes(8_192), Charsets.UTF_8) }.orEmpty()
                Response(status, body, connection.getHeaderField("Location"))
            } finally {
                connection.disconnect()
            }
        }

        fun file(path: String): File = File(actual(path))
        fun actual(path: String): String {
            require(path == VIRTUAL_ROOT || path.startsWith("$VIRTUAL_ROOT/"))
            return physicalRoot.absolutePath.replace('\\', '/') + path.removePrefix(VIRTUAL_ROOT)
        }

        fun adapt(text: String): String {
            val translated = text.replace("$VIRTUAL_ROOT/", physicalRoot.absolutePath.replace('\\', '/') + "/")
            // Windows Nginx has no openat/fstatat symlink directive. Termux production keeps this protection.
            return if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")) {
                translated.replace("disable_symlinks on;", "")
            } else translated
        }

        suspend fun close() = withContext(NonCancellable) {
            try {
                handle?.let { running ->
                    runCatching { command("-c", actual(layout.nginxConfigPath), "-s", "quit") }
                    running.stop()
                }
            } finally {
                scope.cancel()
                withTimeout(8_000) { scope.coroutineContext.job.join() }
            }
        }
    }

    private data class Response(val status: Int, val body: String, val location: String?)

    private companion object {
        const val VIRTUAL_ROOT = "/runtime"

        fun site(id: Long, domain: String, type: ProjectType, port: Int = 18000 + id.toInt()) = Site(
            id, domain, port, type, null, null, SiteStatus.STOPPED, "", 1,
        )

        fun echoServer(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> respondEcho(exchange) }
            start()
        }

        fun respondEcho(exchange: HttpExchange) {
            exchange.use {
                val body = """
                    uri=${it.requestURI}
                    prefix=${it.requestHeaders.getFirst("X-Forwarded-Prefix")}
                    proto=${it.requestHeaders.getFirst("X-Forwarded-Proto")}
                    host=${it.requestHeaders.getFirst("X-Forwarded-Host")}
                    forwarded-for=${it.requestHeaders.getFirst("X-Forwarded-For")}
                """.trimIndent() + "\n"
                val bytes = body.toByteArray(Charsets.UTF_8)
                it.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
                it.sendResponseHeaders(200, bytes.size.toLong())
                it.responseBody.use { output -> output.write(bytes) }
            }
        }
    }
}
