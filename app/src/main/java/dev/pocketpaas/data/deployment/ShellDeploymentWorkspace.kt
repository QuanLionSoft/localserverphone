package dev.pocketpaas.data.deployment

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pocketpaas.domain.deployment.DeploymentWorkspace
import dev.pocketpaas.domain.deployment.DeploymentJournal
import dev.pocketpaas.domain.deployment.DeploymentJournalState
import dev.pocketpaas.domain.deployment.RuntimeRecoveryWorkspace
import dev.pocketpaas.domain.deployment.PreparedProject
import dev.pocketpaas.domain.deployment.ProjectEntryPoint
import dev.pocketpaas.domain.deployment.ProjectMetadata
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessExecutor
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.system.RuntimeDeploymentScriptSource
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.DeflaterOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShellDeploymentWorkspace @Inject constructor(
    private val shell: ProcessExecutor,
    private val scripts: RuntimeDeploymentScriptSource,
) : DeploymentWorkspace, RuntimeRecoveryWorkspace {
    override suspend fun prepare(site: Site, archivePath: String, deploymentId: String): PreparedProject {
        validate(site, deploymentId)
        val layout = shell.runtimeLayout()
        require(archivePath.portable() == "${layout.runtimePath.trimEnd('/', '\\')}/uploads/$deploymentId.zip".portable()) {
            "Deployment archive must be the runtime upload for this deployment."
        }
        val json = invoke("prepare", site, deploymentId)
        return PreparedProject(
            deploymentId = json.string("deploymentId"), stagingPath = json.string("stagingPath"),
            activePath = json.string("activePath"), backupPath = json.string("backupPath"),
            archivePath = json.string("archivePath"), hadPreviousProject = json.boolean("hadPreviousProject"),
            metadata = json.metadata(),
        ).also { prepared ->
            validateMetadata(site, prepared.metadata)
            val projects = layout.projectsPath.trimEnd('/', '\\').portable()
            val runtime = layout.runtimePath.trimEnd('/', '\\').portable()
            if (prepared.deploymentId != deploymentId || prepared.activePath != "$projects/${site.domainName}" ||
                prepared.stagingPath != "$projects/.deploy-$deploymentId" ||
                prepared.backupPath != "$projects/.backup-$deploymentId" ||
                prepared.archivePath != "$runtime/uploads/$deploymentId.zip" ||
                prepared.metadata.rootDir != prepared.activePath
            ) throw IOException("Runtime returned an unexpected deployment layout.")
        }
    }

    override suspend fun activate(site: Site, prepared: PreparedProject) =
        invoke("activate", site, prepared.deploymentId).requireSuccess()

    override suspend fun rollback(site: Site, prepared: PreparedProject) = abort(site, prepared.deploymentId)

    override suspend fun abort(site: Site, deploymentId: String) = invoke("abort", site, deploymentId).requireSuccess()

    override suspend fun complete(site: Site, prepared: PreparedProject) =
        invoke("complete", site, prepared.deploymentId).requireSuccess()

    override suspend fun complete(site: Site, deploymentId: String) = invoke("complete", site, deploymentId).requireSuccess()

    override suspend fun journals(): List<DeploymentJournal> {
        val response = invokeRaw("journals", "recovery.invalid", ProjectType.STATIC, null)
        val records = response.get("journals")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IOException("Missing runtime deployment journals.")
        if (records.size() > 64) throw IOException("Runtime deployment journal limit exceeded.")
        return records.map { entry ->
            if (!entry.isJsonObject) throw IOException("Invalid runtime deployment journal.")
            val record = entry.asJsonObject
            val identifier = record.string("deploymentId")
            val domain = record.string("domain")
            try {
                require(UUID.fromString(identifier).toString() == identifier)
                require(SiteInput.normalizeDomainName(domain) == domain)
                DeploymentJournal(identifier, domain, ProjectType.valueOf(record.string("projectType")),
                    DeploymentJournalState.valueOf(record.string("state").uppercase(java.util.Locale.ROOT)))
            } catch (failure: IllegalArgumentException) {
                throw IOException("Runtime deployment journal identity is invalid.", failure)
            }
        }.also { recordsList ->
            if (recordsList.map { it.deploymentId }.distinct().size != recordsList.size ||
                recordsList.map { it.domainName }.distinct().size != recordsList.size
            ) throw IOException("Conflicting runtime deployment journals require manual recovery.")
        }
    }

    override suspend fun inspect(site: Site): ProjectMetadata {
        val metadata = invoke("inspect", site, null).metadata()
        validateMetadata(site, metadata)
        val active = "${shell.runtimeLayout().projectsPath.trimEnd('/', '\\').portable()}/${site.domainName}"
        if (metadata.rootDir !in setOf(active, "$active/dist", "$active/build")) {
            throw IOException("Runtime returned an unexpected project root.")
        }
        return metadata
    }

    private suspend fun invoke(operation: String, site: Site, deploymentId: String?): JsonObject {
        validate(site, deploymentId)
        return invokeRaw(operation, site.domainName, site.projectType, deploymentId)
    }

    private suspend fun invokeRaw(operation: String, domainName: String, projectType: ProjectType, deploymentId: String?): JsonObject {
        val layout = shell.runtimeLayout()
        val python = shell.resolveBinary("python")
        val source = scripts.readDeploymentScript().toByteArray(Charsets.UTF_8)
        require(source.size <= 32 * 1024) { "Deployment helper exceeds its bootstrap size limit." }
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(source) }
        }.toByteArray()
        val encoded = Base64.getEncoder().encodeToString(compressed)
        val bootstrap = "import base64,zlib;exec(compile(zlib.decompress(base64.b64decode('$encoded')),'pocketpaas_deploy.py','exec'))"
        val arguments = listOf(python, "-B", "-c", bootstrap, layout.rootPath, layout.projectsPath,
            layout.runtimePath, operation, domainName, projectType.name) + listOfNotNull(deploymentId)
        val result = shell.execute(ProcessRequest(ProcessCommand.Arguments(arguments), emptyMap(), File(layout.rootPath)),
            timeoutMillis = if (operation == "prepare" || operation == "abort" || operation == "complete") 120_000 else 15_000)
        if (!result.isSuccessful) throw IOException("Deployment workspace $operation failed: ${result.stderr.takeLast(512)}")
        if (result.stdoutTruncated || result.stderrTruncated) throw IOException("Deployment workspace response was truncated.")
        return try {
            val json = JsonParser.parseString(result.stdout)
            if (!json.isJsonObject) throw IOException("Deployment workspace response must be an object.")
            json.asJsonObject
        } catch (failure: com.google.gson.JsonParseException) {
            throw IOException("Invalid deployment workspace response.", failure)
        }
    }

    private fun validate(site: Site, deploymentId: String?) {
        SiteInput.validateSiteId(site.id)
        require(SiteInput.normalizeDomainName(site.domainName) == site.domainName) { "Site domain must be canonical." }
        if (deploymentId != null) require(UUID.fromString(deploymentId).toString() == deploymentId) {
            "Deployment ID must be a canonical UUID."
        }
    }

    private fun validateMetadata(site: Site, metadata: ProjectMetadata) {
        val accepted = when (site.projectType) {
            ProjectType.STATIC -> setOf(ProjectEntryPoint.STATIC)
            ProjectType.NODEJS -> setOf(ProjectEntryPoint.NODEJS)
            ProjectType.REACT -> setOf(ProjectEntryPoint.REACT)
            ProjectType.DJANGO -> setOf(ProjectEntryPoint.DJANGO, ProjectEntryPoint.FLASK)
        }
        if (metadata.entryPoint !in accepted ||
            (metadata.entryPoint in setOf(ProjectEntryPoint.DJANGO, ProjectEntryPoint.FLASK) && !metadata.hasRequirements) ||
            (metadata.entryPoint in setOf(ProjectEntryPoint.NODEJS, ProjectEntryPoint.REACT) && !metadata.hasPackageJson)
        ) throw IOException("Runtime returned inconsistent project metadata.")
    }

    private fun JsonObject.metadata(): ProjectMetadata {
        val json = get("metadata")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("Missing project metadata.")
        val entry = try { ProjectEntryPoint.valueOf(json.string("entryPoint")) } catch (failure: IllegalArgumentException) {
            throw IOException("Invalid project entry point.", failure)
        }
        return ProjectMetadata(json.string("rootDir"), entry, json.boolean("hasRequirements"), json.boolean("hasPackageJson"))
    }

    private fun JsonObject.string(name: String): String = get(name)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString ?: throw IOException("Invalid deployment response field $name.")

    private fun JsonObject.boolean(name: String): Boolean = get(name)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
    }?.asBoolean ?: throw IOException("Invalid deployment response field $name.")

    private fun JsonObject.requireSuccess() {
        if (!boolean("ok")) throw IOException("Runtime did not confirm the deployment operation.")
    }

    private fun String.portable(): String = if (File.separatorChar == '\\') replace('\\', '/') else this
}
