package dev.pocketpaas.data.remote

import dev.pocketpaas.core.config.RemoteApiPolicy
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.model.SiteNotFoundException
import dev.pocketpaas.domain.model.SiteStillActiveException
import dev.pocketpaas.domain.remote.ApiKeyStore
import dev.pocketpaas.domain.remote.RemoteErrorKind
import dev.pocketpaas.domain.remote.RemoteOperationException
import dev.pocketpaas.domain.remote.RemoteSiteOperations
import dev.pocketpaas.domain.repository.SiteRepository
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

data class RemoteApiDependencies(
    val sites: SiteRepository,
    val operations: RemoteSiteOperations,
    val keys: ApiKeyStore,
    val uploadDirectory: File,
    val policy: RemoteApiPolicy = RemoteApiPolicy(),
)

data class RemoteErrorResponse(val error: String)

data class RemoteSiteResponse(
    val id: Long,
    val domainName: String,
    val internalPort: Int,
    val projectType: String,
    val processId: Int?,
    val publicUrl: String?,
    val status: String,
    val logs: String,
    val createdAt: Long,
)

internal class RemoteApiFailure(val status: HttpStatusCode, val code: String) : IllegalArgumentException(code)

fun Application.installRemoteApi(dependencies: RemoteApiDependencies) {
    install(ContentNegotiation) { gson { serializeNulls(); disableHtmlEscaping() } }
    install(StatusPages) {
        exception<Throwable> { call, failure ->
            if (failure is CancellationException) throw failure
            val (status, code) = when (failure) {
                is RemoteApiFailure -> failure.status to failure.code
                is RemoteOperationException -> when (failure.kind) {
                    RemoteErrorKind.NOT_FOUND -> HttpStatusCode.NotFound to "site_not_found"
                    RemoteErrorKind.CONFLICT -> HttpStatusCode.Conflict to "operation_conflict"
                    RemoteErrorKind.UNAVAILABLE -> HttpStatusCode.ServiceUnavailable to "runtime_unavailable"
                    RemoteErrorKind.FAILED -> HttpStatusCode.InternalServerError to "operation_failed"
                }
                is SiteNotFoundException -> HttpStatusCode.NotFound to "site_not_found"
                is SiteStillActiveException -> HttpStatusCode.Conflict to "operation_conflict"
                is BadRequestException -> HttpStatusCode.BadRequest to "invalid_request"
                else -> HttpStatusCode.InternalServerError to "operation_failed"
            }
            if (status == HttpStatusCode.Unauthorized) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"PocketPaaS\"")
            }
            call.respond(status, RemoteErrorResponse(code))
        }
        status(HttpStatusCode.NotFound) { call, status -> call.respond(status, RemoteErrorResponse("not_found")) }
    }
    val authentication = createApplicationPlugin("PocketPaaSBearerAuthentication") {
        onCall { call ->
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header(HttpHeaders.Connection, "close")
            call.response.header("X-Content-Type-Options", "nosniff")
            if (call.request.path() == "/api" || call.request.path().startsWith("/api/")) {
                verifyBearer(call, dependencies.keys)
            }
        }
    }
    install(authentication)
    val admission = Semaphore(1)
    val upload = RemoteMultipartUpload(dependencies.uploadDirectory, dependencies.policy)
    routing {
        route("/api") {
            get("/sites") {
                call.respond(dependencies.sites.listSites().map { it.response(dependencies.policy) })
            }
            post("/deploy") {
                val result = admitted(admission) {
                    upload.receive(call) { request, archive ->
                        operationDeadline(dependencies.policy) { dependencies.operations.deploy(request, archive) }
                    }
                }
                call.respond(HttpStatusCode.Created, result.response(dependencies.policy))
            }
            post("/sites/{id}/start") {
                val id = siteId(call)
                requireEmptyBody(call)
                val result = admitted(admission) {
                    operationDeadline(dependencies.policy) { dependencies.operations.start(id) }
                }
                call.respond(result.response(dependencies.policy))
            }
            post("/sites/{id}/stop") {
                val id = siteId(call)
                requireEmptyBody(call)
                val result = admitted(admission) {
                    operationDeadline(dependencies.policy) { dependencies.operations.stop(id) }
                }
                call.respond(result.response(dependencies.policy))
            }
        }
    }
}

private suspend fun verifyBearer(call: ApplicationCall, keys: ApiKeyStore) {
    val headers = call.request.headers.getAll(HttpHeaders.Authorization)
    val raw = headers?.singleOrNull()
    val supplied = raw?.let {
        if (it.length == 71 && it.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) it.substring(7) else null
    }
    if (supplied == null || !Regex("[a-f0-9]{64}").matches(supplied)) {
        throw RemoteApiFailure(HttpStatusCode.Unauthorized, "unauthorized")
    }
    val expected = try { keys.token() } catch (failure: CancellationException) { throw failure }
    catch (_: Exception) { throw RemoteApiFailure(HttpStatusCode.ServiceUnavailable, "credentials_unavailable") }
    if (!Regex("[a-f0-9]{64}").matches(expected)) {
        throw RemoteApiFailure(HttpStatusCode.ServiceUnavailable, "credentials_unavailable")
    }
    if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), supplied.toByteArray(Charsets.US_ASCII))) {
        throw RemoteApiFailure(HttpStatusCode.Unauthorized, "unauthorized")
    }
}

private fun siteId(call: ApplicationCall): Long {
    val raw = call.parameters["id"]
    if (raw == null || !Regex("[1-9][0-9]{0,18}").matches(raw)) {
        throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_site_id")
    }
    return raw.toLongOrNull()?.takeIf { it > 0 }
        ?: throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_site_id")
}

private fun requireEmptyBody(call: ApplicationCall) {
    val lengths = call.request.headers.getAll(HttpHeaders.ContentLength)
    if (!call.request.headers.getAll(HttpHeaders.TransferEncoding).isNullOrEmpty() ||
        (lengths != null && (lengths.size != 1 || lengths.single() != "0"))
    ) throw RemoteApiFailure(HttpStatusCode.BadRequest, "body_not_allowed")
}

private suspend fun <T> admitted(semaphore: Semaphore, block: suspend () -> T): T {
    if (!semaphore.tryAcquire()) throw RemoteApiFailure(HttpStatusCode.Conflict, "operation_in_progress")
    try { return block() } finally { semaphore.release() }
}

private suspend fun <T> operationDeadline(policy: RemoteApiPolicy, operation: suspend () -> T): T =
    withTimeoutOrNull(policy.operationTimeoutMillis) { CompletedOperation(operation()) }?.value
        ?: throw RemoteApiFailure(HttpStatusCode.GatewayTimeout, "operation_timeout")

private data class CompletedOperation<T>(val value: T)

private fun Site.response(policy: RemoteApiPolicy): RemoteSiteResponse = RemoteSiteResponse(
    id, domainName, internalPort, projectType.name, processId, publicUrl, status.name,
    if (policy.maximumReturnedLogCharacters == 0) "" else SiteInput.logTail(logs, policy.maximumReturnedLogCharacters), createdAt,
)
