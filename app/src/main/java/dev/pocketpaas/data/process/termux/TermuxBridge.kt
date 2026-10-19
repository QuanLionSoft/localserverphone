package dev.pocketpaas.data.process.termux

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.runtime.RuntimeReset
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.net.Proxy
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

internal data class RemoteProcessTicket(
    val id: String,
    val pid: Int,
    val credentials: TermuxBridgeCredentials,
)

internal data class RemotePoll(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val stdoutClosed: Boolean,
    val stderrClosed: Boolean,
    val exitCode: Int?,
)

/** Authenticated loopback transport. The token never appears in a URL or diagnostic. */
class TermuxBridge(
    private val credentialsStore: TermuxBridgeCredentialsStore,
    private val bootstrap: TermuxAgentBootstrap,
    private val gson: Gson,
    private val applicationScope: CoroutineScope,
) : RuntimeReset {
    private val startupMutex = Mutex()
    private val heartbeatMutex = Mutex()
    private val ownedProcesses = ConcurrentHashMap<String, RemoteProcessTicket>()
    private var heartbeat: Job? = null

    internal suspend fun create(request: ProcessRequest): RemoteProcessTicket {
        val credentials = ensureReady()
        val command = JsonObject().apply {
            when (val value = request.command) {
                is ProcessCommand.Shell -> {
                    addProperty("kind", "shell")
                    addProperty("value", value.value)
                }
                is ProcessCommand.Arguments -> {
                    addProperty("kind", "argv")
                    add("value", gson.toJsonTree(value.values))
                }
            }
        }
        val payload = JsonObject().apply {
            add("command", command)
            add("env", gson.toJsonTree(request.environment))
            addProperty("cwd", request.workingDir.absolutePath)
        }
        val response = requestJson(credentials, "POST", "/processes", payload)
        val id = response.requiredString("id")
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) {
            throw IOException("Termux returned an invalid process identifier.")
        }
        val pid = response.requiredInt("pid")
        if (pid <= 0) throw IOException("Termux returned an invalid process PID.")
        val ticket = RemoteProcessTicket(id, pid, credentials)
        ownedProcesses[id] = ticket
        startHeartbeat()
        return ticket
    }

    internal suspend fun poll(ticket: RemoteProcessTicket): RemotePoll {
        val result = requestJson(ticket.credentials, "GET", "/processes/${ticket.id}/poll?waitMs=250")
        val exit = result["exitCode"] ?: throw IOException("Termux poll is missing its exit state.")
        return RemotePoll(
            stdout = decodeStream(result.requiredString("stdout")),
            stderr = decodeStream(result.requiredString("stderr")),
            stdoutClosed = result.requiredBoolean("stdoutClosed"),
            stderrClosed = result.requiredBoolean("stderrClosed"),
            exitCode = if (exit.isJsonNull) null else result.requiredInt("exitCode"),
        )
    }

    internal suspend fun stop(ticket: RemoteProcessTicket, graceMillis: Long) {
        require(graceMillis in 0L..10_000L) { "Process termination grace must be between 0 and 10000 ms." }
        val stopped = requestJson(
            ticket.credentials,
            "POST",
            "/processes/${ticket.id}/stop",
            JsonObject().apply { addProperty("graceMillis", graceMillis) },
            readTimeoutMillis = (graceMillis + 3_000L).toInt(),
        )
        stopped.requiredInt("exitCode")
    }

    internal suspend fun forget(ticket: RemoteProcessTicket) {
        try {
            requestJson(ticket.credentials, "DELETE", "/processes/${ticket.id}")
        } finally {
            ownedProcesses.remove(ticket.id)
        }
    }

    internal fun releaseAfterFailure(ticket: RemoteProcessTicket) {
        ownedProcesses.remove(ticket.id)
    }

    suspend fun resolveBinary(name: String): String {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}"))) { "Invalid executable name." }
        val credentials = ensureReady()
        val path = requestJson(
            credentials,
            "GET",
            "/binaries?name=${URLEncoder.encode(name, "UTF-8")}",
        ).requiredString("path")
        if (!path.startsWith("/") || path.contains('\u0000')) {
            throw IOException("Termux returned an invalid executable path.")
        }
        return path
    }

    internal suspend fun layoutJson(): JsonObject = requestJson(ensureReady(), "GET", "/layout")

    /** Binary upload avoids JSON/base64 copies of a project ZIP in APK or agent memory. */
    suspend fun uploadArchive(source: File, archiveId: String, maximumBytes: Long): String {
        requireCanonicalArchiveId(archiveId)
        require(maximumBytes in 1L..MAX_ARCHIVE_BYTES) { "Archive limit must be between 1 and 64 MiB." }
        val length = withContext(Dispatchers.IO) {
            if (!source.isFile) throw IOException("The private archive spool file does not exist.")
            source.length()
        }
        if (length !in 1L..maximumBytes) throw IOException("The archive is empty or exceeds its upload limit.")
        val credentials = ensureReady()
        val capabilities = try {
            requestJson(credentials, "GET", "/capabilities")
        } catch (failure: BridgeHttpException) {
            if (failure.status == 404) {
                throw IOException("The Termux agent must be restarted to enable archive transfer.")
            }
            throw failure
        }
        if (capabilities.requiredInt("archiveTransferVersion") != 1) {
            throw IOException("The Termux archive transfer protocol is incompatible.")
        }
        if (runCatching { capabilities.requiredInt("cleanupOwnershipVersion") }.getOrNull() != 1) {
            throw IOException("The Termux agent must be restarted to enable ownership-safe archive transfer.")
        }
        val runtime = requestJson(credentials, "GET", "/layout").requiredString("runtimePath")
        val expectedPath = archivePath(runtime, archiveId)
        val uploadId = UUID.randomUUID().toString()
        try {
            val response = withTimeout(UPLOAD_TIMEOUT_MILLIS) {
                streamArchive(credentials, source, archiveId, uploadId, length)
            }
            if (response.requiredString("path") != expectedPath) {
                throw IOException("The Termux archive path does not match the requested runtime archive.")
            }
            if (response.requiredInt("bytes").toLong() != length) {
                throw IOException("The Termux archive length does not match the private spool file.")
            }
            if (response.requiredString("uploadId") != uploadId) {
                throw IOException("The Termux archive receipt belongs to another upload attempt.")
            }
            return expectedPath
        } catch (failure: Throwable) {
            // A lost HTTP response may follow a successful atomic commit.
            if (failure !is BridgeHttpException || failure.status !in 400..499) {
                withContext(NonCancellable) {
                    runCatching { requestJson(credentials, "DELETE", "/archives/$archiveId", archiveUploadId = uploadId) }
                }
            }
            throw failure
        }
    }

    suspend fun deleteArchive(archivePath: String) {
        val normalized = archivePath.replace('\\', '/')
        val archiveId = normalized.substringAfterLast('/').removeSuffix(".zip")
        requireCanonicalArchiveId(archiveId)
        val credentials = ensureReady()
        val runtime = requestJson(credentials, "GET", "/layout").requiredString("runtimePath")
        require(archivePath == archivePath(runtime, archiveId)) { "Archive deletion must remain inside runtime/uploads." }
        requestJson(credentials, "DELETE", "/archives/$archiveId")
    }

    private fun archivePath(runtime: String, archiveId: String): String =
        runtime.trimEnd('/', '\\') + if ('\\' in runtime) "\\uploads\\$archiveId.zip" else "/uploads/$archiveId.zip"

    private fun requireCanonicalArchiveId(value: String) {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
            "Archive identifier must be a canonical UUID."
        }
    }

    private suspend fun streamArchive(
        credentials: TermuxBridgeCredentials,
        source: File,
        archiveId: String,
        uploadId: String,
        length: Long,
    ): JsonObject = withContext(Dispatchers.IO) {
        val connection = URL("http://127.0.0.1:${credentials.port}/archives/$archiveId")
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection
        coroutineScope {
            // This child runs cleanup on another IO worker when cancellation interrupts blocking IO.
            val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    connection.disconnect()
                }
            }
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "PUT"
                connection.connectTimeout = 1_000
                connection.readTimeout = 30_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer ${credentials.token}")
                connection.setRequestProperty("X-PocketPaaS-Upload-Id", uploadId)
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/zip")
                connection.setFixedLengthStreamingMode(length)
                source.inputStream().use { input ->
                    connection.outputStream.use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = length
                        while (remaining > 0L) {
                            coroutineContext.ensureActive()
                            val size = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            if (size <= 0) throw IOException("The private archive spool file was truncated during upload.")
                            output.write(buffer, 0, size)
                            remaining -= size
                        }
                        if (input.read() != -1) throw IOException("The private archive spool file changed during upload.")
                    }
                }
                val status = connection.responseCode
                if (status !in 200..299) throw BridgeHttpException(status, "Termux archive upload failed (HTTP $status).")
                val bytes = connection.inputStream.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = stream.read(buffer)
                        if (size < 0) break
                        if (output.size() + size > MAX_BODY_BYTES) throw IOException("Termux archive response exceeds 128 KiB.")
                        output.write(buffer, 0, size)
                    }
                    output.toByteArray()
                }
                val json = try {
                    JsonParser.parseString(bytes.toString(Charsets.UTF_8))
                } catch (_: com.google.gson.JsonParseException) {
                    throw IOException("Termux archive response is malformed.")
                }
                if (!json.isJsonObject) throw IOException("Termux archive response must be a JSON object.")
                json.asJsonObject
            } finally {
                cancellation.cancel()
                connection.disconnect()
            }
        }
    }

    /** Stop all bridge-owned process groups; this also shuts down the Python agent. */
    suspend fun shutdown() {
        startupMutex.withLock {
            val credentials = credentialsStore.get()
            try {
                requestJson(credentials, "POST", "/shutdown", JsonObject(), readTimeoutMillis = 15_000)
            } finally {
                ownedProcesses.clear()
                heartbeatMutex.withLock {
                    heartbeat?.cancel()
                    heartbeat = null
                }
            }
        }
    }

    /** Call only before this APK session starts children; authenticated agent ownership is required. */
    override suspend fun resetRuntime(persistedPids: List<Int>) {
        require(persistedPids.size <= 64 && persistedPids.distinct().size == persistedPids.size && persistedPids.all { it > 0 }) {
            "Runtime recovery permits at most 64 unique positive PIDs."
        }
        check(ownedProcesses.isEmpty()) { "Cold recovery cannot reset children owned by the current application session." }
        val credentials = ensureReady()
        startupMutex.withLock {
            check(ownedProcesses.isEmpty()) { "Cold recovery cannot reset an active application session." }
            val capabilities = requestJson(credentials, "GET", "/capabilities")
            if (runCatching { capabilities.requiredInt("runtimeResetVersion") }.getOrNull() != 1) {
                throw IOException("Restart the Termux agent to enable authenticated runtime recovery.")
            }
            val result = requestJson(credentials, "POST", "/runtime/reset", JsonObject().apply {
                add("persistedPids", gson.toJsonTree(persistedPids))
            }, readTimeoutMillis = 40_000)
            val unresolved = result.get("unconfirmedPids")
            if (!result.requiredBoolean("stopped") || result.requiredInt("liveProcesses") != 0 ||
                unresolved == null || !unresolved.isJsonArray || unresolved.asJsonArray.size() != 0
            ) throw IOException("Persisted runtime ownership is unconfirmed; recovery cannot change project files or clear PIDs.")
        }
    }

    private suspend fun ensureReady(): TermuxBridgeCredentials = startupMutex.withLock {
        val credentials = credentialsStore.get()
        try {
            verifyHealth(credentials)
            return@withLock credentials
        } catch (failure: BridgeHttpException) {
            throw failure
        } catch (_: IOException) {
            // Only an unreachable bridge triggers RUN_COMMAND; an occupied/auth-denying port does not.
        }
        bootstrap.start(credentials)
        val deadline = System.nanoTime() + 15_000_000_000L
        // Reserve the connect/read timeouts and retry delay so startup remains below 15 seconds.
        while (System.nanoTime() < deadline - 2_250_000_000L) {
            try {
                verifyHealth(credentials)
                return@withLock credentials
            } catch (failure: BridgeHttpException) {
                throw failure
            } catch (_: IOException) {
                delay(250L)
            }
        }
        throw IOException(
            "Termux process bridge did not become ready. In Termux run 'pkg install python', " +
                "set allow-external-apps=true in ~/.termux/termux.properties, run termux-reload-settings, " +
                "and grant Pocket PaaS the RUN_COMMAND permission.",
        )
    }

    private suspend fun verifyHealth(credentials: TermuxBridgeCredentials) {
        val health = requestJson(credentials, "GET", "/health", readTimeoutMillis = 1_000)
        val version = try {
            health.requiredInt("protocolVersion")
        } catch (_: IOException) {
            throw BridgeHttpException(409, "Termux process bridge health response is incompatible.")
        }
        if (version != PROTOCOL_VERSION) {
            throw BridgeHttpException(409, "Termux process bridge protocol is incompatible.")
        }
    }

    private suspend fun startHeartbeat() = heartbeatMutex.withLock {
        if (heartbeat?.isActive == true) return@withLock
        heartbeat = applicationScope.launch(Dispatchers.IO) {
            while (isActive && ownedProcesses.isNotEmpty()) {
                delay(60_000L)
                val ticket = ownedProcesses.values.firstOrNull() ?: break
                try {
                    verifyHealth(ticket.credentials)
                } catch (_: IOException) {
                    // Each process poll reports transport failure to its owner; keep this job isolated.
                }
            }
        }
    }

    private suspend fun requestJson(
        credentials: TermuxBridgeCredentials,
        method: String,
        path: String,
        payload: JsonObject? = null,
        readTimeoutMillis: Int = 3_000,
        archiveUploadId: String? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        val body = payload?.let { gson.toJson(it).toByteArray(Charsets.UTF_8) }
        if (body != null && body.size > MAX_BODY_BYTES) {
            throw IOException("Termux process request exceeds 128 KiB.")
        }
        val connection = URL("http://127.0.0.1:${credentials.port}$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 1_000
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Authorization", "Bearer ${credentials.token}")
            archiveUploadId?.let { connection.setRequestProperty("X-PocketPaaS-Upload-Id", it) }
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val message = when (status) {
                    401, 403 -> "Termux bridge authentication failed; the loopback port belongs to a different session."
                    404 -> "Termux process or requested executable was not found."
                    409 -> "Termux bridge state conflicts with the requested operation."
                    else -> "Termux bridge request failed (HTTP $status)."
                }
                throw BridgeHttpException(status, message)
            }
            val bytes = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > MAX_BODY_BYTES) {
                        throw IOException("Termux bridge response exceeds 128 KiB.")
                    }
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            if (bytes.isEmpty()) return@withContext JsonObject()
            try {
                val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8))
                if (!json.isJsonObject) throw BridgeHttpException(409, "Termux bridge response must be a JSON object.")
                json.asJsonObject
            } catch (_: com.google.gson.JsonParseException) {
                throw BridgeHttpException(409, "Termux bridge returned malformed JSON.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeStream(value: String): ByteArray {
        val bytes = try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw IOException("Termux bridge returned malformed process output.")
        }
        if (bytes.size > MAX_POLL_STREAM_BYTES) throw IOException("Termux bridge output chunk is too large.")
        return bytes
    }

    companion object {
        const val PROTOCOL_VERSION: Int = 1
        private const val MAX_BODY_BYTES = 128 * 1024
        private const val MAX_POLL_STREAM_BYTES = 16 * 1024
        private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
        private const val UPLOAD_TIMEOUT_MILLIS = 120_000L
    }
}

internal class BridgeHttpException(val status: Int, message: String) : IOException(message)

internal fun JsonObject.requiredString(name: String): String = field(name).let { value ->
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        throw IOException("Termux bridge response field '$name' must be a string.")
    }
    value.asString
}

internal fun JsonObject.requiredBoolean(name: String): Boolean = field(name).let { value ->
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
        throw IOException("Termux bridge response field '$name' must be a boolean.")
    }
    value.asBoolean
}

internal fun JsonObject.requiredInt(name: String): Int = field(name).let { value ->
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
        throw IOException("Termux bridge response field '$name' must be an integer.")
    }
    val number = value.asString.toIntOrNull()
        ?: throw IOException("Termux bridge response field '$name' is outside the integer range.")
    number
}

private fun JsonObject.field(name: String): JsonElement = get(name)
    ?: throw IOException("Termux bridge response is missing '$name'.")
