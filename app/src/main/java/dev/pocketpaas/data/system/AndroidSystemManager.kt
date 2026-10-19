package dev.pocketpaas.data.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketpaas.core.config.ServerConfig
import dev.pocketpaas.core.di.IoDispatcher
import dev.pocketpaas.domain.model.RuntimeCapabilities
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.setup.TermuxPermissionEvidence
import dev.pocketpaas.domain.setup.TermuxPermissionInspector
import dev.pocketpaas.domain.setup.TermuxPermissionState
import dev.pocketpaas.domain.setup.TermuxPermissionStatus
import dev.pocketpaas.domain.system.SystemManager
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class AndroidSystemManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val config: ServerConfig,
) : SystemManager, TermuxPermissionInspector {
    override suspend fun prepareLayout(): RuntimeLayout = withContext(ioDispatcher) {
        val allowedRoot = context.noBackupFilesDir.canonicalFile
        val root = directory(File(allowedRoot, "pocket-paas"), allowedRoot)
        val nginx = directory(File(root, "nginx"), root)
        RuntimeLayout(
            rootPath = root.path,
            projectsPath = directory(File(root, "htdocs"), root).path,
            nginxConfigPath = File(nginx, "nginx.conf").path,
            nginxHostsPath = directory(File(nginx, "conf.d"), root).path,
            runtimePath = directory(File(root, "runtime"), root).path,
            logsPath = directory(File(root, "logs"), root).path,
        )
    }

    override suspend fun inspectCapabilities(): RuntimeCapabilities = withContext(ioDispatcher) {
        val permission = inspectPermission()
        RuntimeCapabilities(
            termuxInstalled = permission.state != TermuxPermissionState.TERMUX_MISSING,
            termuxCommandPermissionGranted = permission.granted,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
        )
    }

    override suspend fun inspect(): TermuxPermissionStatus = withContext(ioDispatcher) {
        inspectPermission()
    }

    @Suppress("DEPRECATION") // The int-flags APIs support Android 8-12 as well as newer releases.
    private fun inspectPermission(): TermuxPermissionStatus {
        val packageManager = context.packageManager
        val packageInfo = try {
            packageManager.getPackageInfo(config.termuxPackage, PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (_: PackageManager.NameNotFoundException) {
            return TermuxPermissionStatus(TermuxPermissionState.TERMUX_MISSING)
        }
        val versionName = packageInfo.versionName
        if (packageInfo.applicationInfo?.enabled != true) {
            return TermuxPermissionStatus(TermuxPermissionState.TERMUX_DISABLED, versionName)
        }
        val permissionInfo = try {
            packageManager.getPermissionInfo(RUN_COMMAND_PERMISSION, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return TermuxPermissionStatus(TermuxPermissionState.PERMISSION_UNAVAILABLE, versionName)
        }
        val serviceInfo = try {
            packageManager.getServiceInfo(
                ComponentName(config.termuxPackage, "${config.termuxPackage}.app.RunCommandService"),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return TermuxPermissionStatus(TermuxPermissionState.PERMISSION_UNAVAILABLE, versionName)
        }
        return TermuxPermissionEvidence(
            installed = true,
            enabled = serviceInfo.enabled && serviceInfo.applicationInfo.enabled,
            commandInterfaceAvailable = permissionInfo.packageName == config.termuxPackage &&
                serviceInfo.exported && serviceInfo.permission == RUN_COMMAND_PERMISSION,
            runtimeRequestable = (permissionInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
                PermissionInfo.PROTECTION_DANGEROUS,
            permissionGranted = ContextCompat.checkSelfPermission(context, RUN_COMMAND_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED,
            versionName = versionName,
        ).resolve()
    }

    private fun directory(file: File, allowedRoot: File): File {
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(allowedRoot.path + File.separator)) {
            throw IOException("Runtime directory escaped application storage")
        }
        if (!canonical.isDirectory && !canonical.mkdirs()) {
            throw IOException("Could not create runtime directory: ${canonical.name}")
        }
        if (!canonical.canRead() || !canonical.canWrite()) {
            throw IOException("Runtime directory is not accessible: ${canonical.name}")
        }
        return canonical
    }

    private companion object {
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    }
}
