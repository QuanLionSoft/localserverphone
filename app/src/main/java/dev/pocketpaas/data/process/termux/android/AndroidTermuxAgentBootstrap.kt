package dev.pocketpaas.data.process.termux.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Base64
import dev.pocketpaas.data.process.termux.TermuxAgentBootstrap
import dev.pocketpaas.data.process.termux.TermuxBridgeCredentials
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** RUN_COMMAND delegates execution to Termux's UID, preserving Android's sandbox. */
class AndroidTermuxAgentBootstrap(private val context: Context) : TermuxAgentBootstrap {
    @Suppress("DEPRECATION") // The int-flags API is required for supported Android 8-12 devices.
    override suspend fun start(credentials: TermuxBridgeCredentials): Unit = withContext(Dispatchers.IO) {
        val component = ComponentName("com.termux", "com.termux.app.RunCommandService")
        try {
            val serviceInfo = context.packageManager.getServiceInfo(component, 0)
            if (!serviceInfo.enabled || !serviceInfo.applicationInfo.enabled) {
                throw IOException("Termux is disabled. Enable and initialize Termux first.")
            }
        } catch (_: PackageManager.NameNotFoundException) {
            throw IOException("Termux is not installed. Install and initialize Termux first.")
        }
        if (context.checkSelfPermission(RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw IOException(
                "Grant Pocket PaaS 'Run commands in Termux environment' in Android app permissions.",
            )
        }
        val source = context.assets.open("pocketpaas_agent.py").use { input ->
            val bytes = input.readBytes()
            if (bytes.size > 90 * 1024) throw IOException("Termux bridge bootstrap asset is too large.")
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        val wrapper = "import base64;exec(compile(base64.b64decode('$source'),'pocketpaas_agent.py','exec'))"
        val intent = Intent("com.termux.RUN_COMMAND")
            .setComponent(component)
            .putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_PREFIX/bin/python")
            .putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf("-c", wrapper, "--port", credentials.port.toString(), "--token", credentials.token),
            )
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND_CUSTOM_LOG_LEVEL", "0")
        try {
            if (context.startService(intent) == null) {
                throw IOException("Termux did not accept the process bridge command.")
            }
        } catch (_: SecurityException) {
            throw IOException("Termux RUN_COMMAND permission was denied. Grant it in Android settings.")
        } catch (_: IllegalStateException) {
            throw IOException("Android blocked Termux startup. Open Pocket PaaS and Termux, then retry.")
        }
    }

    companion object {
        const val RUN_COMMAND_PERMISSION: String = "com.termux.permission.RUN_COMMAND"
        const val TERMUX_PREFIX: String = "/data/data/com.termux/files/usr"
        const val TERMUX_HOME: String = "/data/data/com.termux/files/home"
    }
}
