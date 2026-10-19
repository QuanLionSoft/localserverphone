package dev.pocketpaas.data.remote

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.pocketpaas.core.config.RemoteApiPolicy
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.remote.RemoteDeployRequest
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

internal class RemoteMultipartUpload(
    private val uploadDirectory: File,
    private val policy: RemoteApiPolicy,
) {
    suspend fun <T> receive(call: ApplicationCall, operation: suspend (RemoteDeployRequest, File) -> T): T {
        val contentTypes = call.request.headers.getAll("Content-Type")
        if (contentTypes?.size != 1) throw RemoteApiFailure(HttpStatusCode.UnsupportedMediaType, "multipart_required")
        val contentType = try { ContentType.parse(contentTypes.single()) } catch (_: Exception) {
            throw RemoteApiFailure(HttpStatusCode.UnsupportedMediaType, "multipart_required")
        }
        if (!contentType.match(ContentType.MultiPart.FormData)) {
            throw RemoteApiFailure(HttpStatusCode.UnsupportedMediaType, "multipart_required")
        }
        val boundaries = contentType.parameters.filter { it.name.equals("boundary", true) }
        val boundary = boundaries.singleOrNull()?.value
        if (boundary == null || !Regex("[a-zA-Z0-9'()+_,./:=?-]{1,70}").matches(boundary)) badMultipart()
        if (contentType.parameters.any { !it.name.equals("boundary", true) }) badMultipart()
        val lengths = call.request.headers.getAll("Content-Length")
        val declaredLength = if (lengths == null) null else {
            if (lengths.size != 1 || !Regex("[0-9]{1,19}").matches(lengths.single())) badMultipart()
            lengths.single().toLongOrNull() ?: badMultipart()
        }
        val transferEncodings = call.request.headers.getAll("Transfer-Encoding")
        if (transferEncodings != null && (declaredLength != null || transferEncodings.size != 1 ||
                !transferEncodings.single().equals("chunked", true))) badMultipart()
        if (declaredLength != null && declaredLength > policy.maximumRequestBytes) tooLarge()
        val source = call.receiveChannel()
        var archive: File? = null
        var parsed = false
        try {
            val request = withTimeoutOrNull(policy.uploadTimeoutMillis) { withContext(Dispatchers.IO) {
                check(uploadDirectory.isDirectory || uploadDirectory.mkdirs())
                check(!Files.isSymbolicLink(uploadDirectory.toPath()))
                archive = File.createTempFile("remote-upload-", ".zip", uploadDirectory)
                restrictPermissions(requireNotNull(archive))
                val input = BoundedInput(source, policy.maximumRequestBytes, declaredLength)
                if (input.line(72) != "--$boundary") badMultipart()
                val seen = mutableSetOf<String>()
                var metadata: ByteArray? = null
                var archiveBytes = 0L
                var finished = false
                while (!finished) {
                    val headers = readHeaders(input)
                    val disposition = try {
                        ContentDisposition.parse(headers["content-disposition"] ?: badMultipart())
                    } catch (_: Exception) { badMultipart() }
                    if (!disposition.disposition.equals("form-data", true)) badMultipart()
                    val name = disposition.parameters.filter { it.name.equals("name", true) }.singleOrNull()?.value
                        ?: badMultipart()
                    val filenames = disposition.parameters.filter { it.name.equals("filename", true) }
                    if (filenames.size > 1 || disposition.parameters.any {
                            !it.name.equals("name", true) && !it.name.equals("filename", true)
                        }) badMultipart()
                    if (filenames.any { it.value.length !in 1..255 || it.value.any(Char::isISOControl) }) badMultipart()
                    if (name !in setOf("metadata", "archive") || !seen.add(name)) badMultipart()
                    val partType = headers["content-type"]?.let {
                        try { ContentType.parse(it) } catch (_: Exception) { badMultipart() }
                    }
                    if (name == "metadata") {
                        if (partType != null && !partType.match(ContentType.Application.Json)) {
                            throw RemoteApiFailure(HttpStatusCode.UnsupportedMediaType, "metadata_json_required")
                        }
                        if (partType?.parameters?.any {
                                !it.name.equals("charset", true) || !it.value.equals("utf-8", true)
                            } == true) badMultipart()
                        val buffer = ByteArrayOutputStream()
                        val boundaryResult = input.body(boundary, policy.maximumMetadataBytes.toLong()) { bytes, count -> buffer.write(bytes, 0, count) }
                        finished = boundaryResult.second
                        metadata = buffer.toByteArray()
                    } else {
                        if (filenames.size != 1) badMultipart()
                        if (partType != null && !partType.match(ContentType.Application.Zip) &&
                            !partType.match(ContentType.Application.OctetStream)
                        ) throw RemoteApiFailure(HttpStatusCode.UnsupportedMediaType, "archive_zip_required")
                        if (!partType?.parameters.isNullOrEmpty()) badMultipart()
                        requireNotNull(archive).outputStream().buffered(16 * 1024).use { output ->
                            val boundaryResult = input.body(boundary, policy.maximumArchiveBytes) { bytes, count -> output.write(bytes, 0, count) }
                            archiveBytes = boundaryResult.first
                            finished = boundaryResult.second
                        }
                    }
                }
                if (input.byte() != -1) badMultipart()
                if (seen.size != 2 || metadata == null || archiveBytes == 0L) badMultipart()
                input.verifyLength()
                parseMetadata(requireNotNull(metadata))
            } } ?: throw RemoteApiFailure(HttpStatusCode.RequestTimeout, "upload_timeout")
            parsed = true
            source.cancel(null)
            return operation(request, requireNotNull(archive))
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: RemoteApiFailure) {
            throw failure
        } catch (_: java.io.IOException) {
            throw RemoteApiFailure(
                if (parsed) HttpStatusCode.InternalServerError else HttpStatusCode.BadRequest,
                if (parsed) "operation_failed" else "invalid_multipart",
            )
        } finally {
            source.cancel(null)
            withContext(NonCancellable + Dispatchers.IO) {
                archive?.let { file ->
                    if (file.exists() && !file.delete()) {
                        call.application.log.warn("Remote upload cleanup failed; private cache cleanup will retry on service startup")
                    }
                }
            }
        }
    }

    private suspend fun readHeaders(input: BoundedInput): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var remaining = policy.maximumPartHeaderCharacters
        while (true) {
            val line = input.line(remaining)
            remaining -= line.toByteArray(Charsets.UTF_8).size + 2
            if (remaining < 0) tooLarge()
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon < 1) badMultipart()
            val name = line.substring(0, colon).lowercase(java.util.Locale.ROOT)
            if (name !in setOf("content-disposition", "content-type") || headers.containsKey(name)) badMultipart()
            val value = line.substring(colon + 1).trim()
            if (value.isEmpty() || value.any { it.isISOControl() }) badMultipart()
            headers[name] = value
        }
    }

    private fun parseMetadata(bytes: ByteArray): RemoteDeployRequest {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { invalidMetadata() }
        try {
            JsonReader(StringReader(text)).use { reader ->
                reader.strictness = Strictness.STRICT
                if (reader.peek() != JsonToken.BEGIN_OBJECT) invalidMetadata()
                reader.beginObject()
                val seen = mutableSetOf<String>()
                var siteId: Long? = null
                var domain: String? = null
                var type: ProjectType? = null
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (!seen.add(name)) invalidMetadata()
                    when (name) {
                        "siteId" -> {
                            if (reader.peek() != JsonToken.NUMBER) invalidMetadata()
                            val raw = reader.nextString()
                            if (!Regex("[1-9][0-9]{0,18}").matches(raw)) invalidMetadata()
                            siteId = raw.toLongOrNull()?.takeIf { it > 0 } ?: invalidMetadata()
                        }
                        "domainName" -> {
                            if (reader.peek() != JsonToken.STRING) invalidMetadata()
                            val raw = reader.nextString()
                            if (raw.length !in 1..1024) invalidMetadata()
                            domain = SiteInput.normalizeDomainName(raw)
                        }
                        "projectType" -> {
                            if (reader.peek() != JsonToken.STRING) invalidMetadata()
                            type = ProjectType.valueOf(reader.nextString())
                        }
                        else -> invalidMetadata()
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) invalidMetadata()
                if (siteId != null) {
                    if (seen != setOf("siteId")) invalidMetadata()
                } else if (seen != setOf("domainName", "projectType") || domain == null || type == null) invalidMetadata()
                return RemoteDeployRequest(siteId, domain, type)
            }
        } catch (failure: RemoteApiFailure) {
            throw failure
        } catch (_: IllegalArgumentException) { invalidMetadata() }
        catch (_: java.io.IOException) { invalidMetadata() }
        catch (_: IllegalStateException) { invalidMetadata() }
    }

    private fun restrictPermissions(file: File) {
        try {
            Files.setPosixFilePermissions(file.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        } catch (_: UnsupportedOperationException) {
            // Windows host verification inherits the test directory ACL; Android supports POSIX permissions.
        }
    }

    private class BoundedInput(
        private val source: ByteReadChannel,
        private val maximum: Long,
        private val declared: Long?,
    ) {
        private val buffer = ByteArray(16 * 1024)
        private var position = 0
        private var available = 0
        private var received = 0L
        private var ended = false

        suspend fun byte(): Int {
            if (ended) return -1
            if (position == available) {
                if (!refill()) return -1
            }
            return buffer[position++].toInt() and 0xff
        }

        private suspend fun refill(): Boolean {
            if (ended) return false
            currentCoroutineContext().ensureActive()
            do {
                available = source.readAvailable(buffer, 0, buffer.size)
            } while (available == 0)
            position = 0
            if (available == -1) {
                available = 0
                ended = true
                return false
            }
            received += available
            if (received > maximum) tooLarge()
            return true
        }

        suspend fun line(limit: Int): String {
            if (limit <= 0) tooLarge()
            val result = ByteArrayOutputStream()
            while (true) {
                val value = byte()
                if (value == '\r'.code) {
                    if (byte() != '\n'.code) badMultipart()
                    return try {
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(result.toByteArray())).toString()
                    } catch (_: java.nio.charset.CharacterCodingException) { badMultipart() }
                }
                if (value < 32 || value == 127) badMultipart()
                if (result.size() == limit) tooLarge()
                result.write(value)
            }
        }

        suspend fun body(boundary: String, limit: Long, write: (ByteArray, Int) -> Unit): Pair<Long, Boolean> {
            val delimiter = "\r\n--$boundary".toByteArray(Charsets.US_ASCII)
            val pending = ByteArray(delimiter.size)
            val output = ByteArray(16 * 1024)
            var pendingCount = 0
            var outputCount = 0
            var count = 0L
            val lookahead = ByteArray(4)
            var lookaheadPosition = 0
            var lookaheadCount = 0
            fun emit(value: Byte) {
                count++
                if (count > limit) tooLarge()
                output[outputCount++] = value
                if (outputCount == output.size) {
                    write(output, outputCount)
                    outputCount = 0
                }
            }
            while (true) {
                val value = if (lookaheadPosition < lookaheadCount) {
                    lookahead[lookaheadPosition++].toInt() and 0xff
                } else {
                    if (position == available && !refill()) badMultipart()
                    buffer[position++].toInt() and 0xff
                }
                pending[pendingCount++] = value.toByte()
                while (pendingCount > 0 && (0 until pendingCount).any { pending[it] != delimiter[it] }) {
                    emit(pending[0])
                    System.arraycopy(pending, 1, pending, 0, --pendingCount)
                }
                if (pendingCount == delimiter.size) {
                    val first = byte()
                    val second = byte()
                    if (first == '\r'.code && second == '\n'.code) {
                        if (outputCount > 0) write(output, outputCount)
                        return count to false
                    }
                    if (first == -1 || second == -1) badMultipart()
                    lookahead[0] = first.toByte()
                    lookahead[1] = second.toByte()
                    lookaheadCount = 2
                    if (first == '-'.code && second == '-'.code) {
                        val third = byte()
                        if (third == -1) {
                            if (outputCount > 0) write(output, outputCount)
                            return count to true
                        }
                        val fourth = byte()
                        if (third == '\r'.code && fourth == '\n'.code) {
                            if (outputCount > 0) write(output, outputCount)
                            return count to true
                        }
                        if (fourth == -1) badMultipart()
                        lookahead[2] = third.toByte()
                        lookahead[3] = fourth.toByte()
                        lookaheadCount = 4
                    }
                    // The validated boundary cannot contain CR/LF, so a full false marker
                    // has no overlapping marker prefix. Its suffix bytes can start one.
                    for (index in 0 until pendingCount) emit(pending[index])
                    pendingCount = 0
                    lookaheadPosition = 0
                }
            }
        }

        fun verifyLength() {
            if (declared != null && received != declared) badMultipart()
        }
    }
}

private fun badMultipart(): Nothing = throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_multipart")
private fun tooLarge(): Nothing = throw RemoteApiFailure(HttpStatusCode.PayloadTooLarge, "upload_too_large")
private fun invalidMetadata(): Nothing = throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_metadata")
