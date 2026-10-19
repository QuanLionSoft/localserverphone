package dev.pocketpaas.data.process

import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.process.LaunchedProcess
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessIOException
import dev.pocketpaas.domain.process.ProcessLauncher
import dev.pocketpaas.domain.process.ProcessRequest
import java.io.File
import java.lang.reflect.Method
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Direct execution for accessible native binaries and desktop verification. */
class LocalProcessLauncher(
    private val layout: RuntimeLayout = defaultRuntimeLayout(),
    private val bundledBinaryDirectories: List<File> = emptyList(),
) : ProcessLauncher {
    override suspend fun launch(request: ProcessRequest): LaunchedProcess = withContext(Dispatchers.IO) {
        if (!request.workingDir.isAbsolute || !request.workingDir.isDirectory) {
            throw ProcessIOException("The process working directory is not an accessible absolute directory.")
        }
        val builder = ProcessBuilder().directory(request.workingDir).redirectErrorStream(false)
        val environment = builder.environment()
        environment.putAll(request.environment)
        val inheritedPath = environment["PATH"] ?: environment["Path"].orEmpty()
        val accessibleDirectories = (listOf(TERMUX_BIN) + bundledBinaryDirectories)
            .filter { it.isDirectory && it.canRead() }
        val path = (accessibleDirectories.map(File::getAbsolutePath) + inheritedPath.split(File.pathSeparator))
            .filter(String::isNotBlank).distinct().joinToString(File.pathSeparator)
        if (isWindows) environment.remove("Path")
        environment["PATH"] = path
        val command = when (val command = request.command) {
            is ProcessCommand.Shell -> {
                require(command.value.isNotBlank() && '\u0000' !in command.value) { "Invalid shell command." }
                val shell = findShell(path)
                if (isWindows && File(shell).name.equals("cmd.exe", ignoreCase = true)) {
                    listOf(shell, "/d", "/s", "/c", command.value)
                } else {
                    listOf(shell, "-c", command.value)
                }
            }
            is ProcessCommand.Arguments -> {
                require(command.values.isNotEmpty()) { "Executable arguments are empty." }
                require(command.values.none { '\u0000' in it }) { "Arguments cannot contain NUL." }
                listOf(findBinary(command.values.first(), path)) + command.values.drop(1)
            }
        }
        builder.command(command)
        val process = try {
            builder.start()
        } catch (failure: Exception) {
            throw ProcessIOException("Could not launch ${File(command.first()).name}.", failure)
        }
        LaunchedProcess(
            process = process,
            processId = publicProcessId(process),
            terminate = { graceMillis -> terminate(process, graceMillis) },
        )
    }

    override suspend fun resolveBinary(name: String): String = withContext(Dispatchers.IO) {
        findBinary(name, System.getenv("PATH").orEmpty())
    }

    override suspend fun runtimeLayout(): RuntimeLayout = layout

    private fun findBinary(name: String, inheritedPath: String): String {
        require(name.isNotBlank() && '\u0000' !in name) { "Invalid binary name." }
        val explicit = File(name)
        if (explicit.isAbsolute) {
            if (isExecutable(explicit)) return explicit.canonicalPath
            throw ProcessIOException("The requested executable is unavailable: $name")
        }
        require('/' !in name && '\\' !in name) { "A relative executable path is not supported." }
        val aliases = when (name.lowercase(Locale.ROOT)) {
            "python", "python3" -> listOf(name, "python3", "python")
            "node", "nodejs" -> listOf(name, "node", "nodejs")
            "pip", "pip3" -> listOf(name, "pip3", "pip")
            else -> listOf(name)
        }.distinct()
        val directories = (listOf(TERMUX_BIN) + bundledBinaryDirectories +
            inheritedPath.split(File.pathSeparator).filter(String::isNotBlank).map(::File))
            .filter { it.isAbsolute && it.isDirectory && it.canRead() }.distinct()
        directories.forEach { directory ->
            aliases.forEach { alias ->
                val candidates = if (isWindows && File(alias).extension.isEmpty()) {
                    listOf("$alias.exe", "$alias.com", alias)
                } else {
                    listOf(alias)
                }
                candidates.forEach { candidate ->
                    val file = File(directory, candidate)
                    if (isExecutable(file)) return file.canonicalPath
                }
            }
        }
        throw ProcessIOException("Executable '$name' was not found in an accessible runtime directory or PATH.")
    }

    private fun findShell(path: String): String {
        val windowsShell = System.getenv("ComSpec")?.let(::File)
        val candidates = if (isWindows) {
            listOfNotNull(windowsShell, System.getenv("SystemRoot")?.let { File(it, "System32/cmd.exe") })
        } else {
            listOf(File("/system/bin/sh"), File(TERMUX_BIN, "sh"), File("/bin/sh"))
        }
        return candidates.firstOrNull(::isExecutable)?.canonicalPath
            ?: findBinary(if (isWindows) "cmd.exe" else "sh", path)
    }

    private fun isExecutable(file: File): Boolean = file.isFile && file.canRead() && file.canExecute()

    private suspend fun terminate(process: Process, graceMillis: Long) = withContext(Dispatchers.IO) {
        val handles = PublicProcessHandles.forProcess(process)
        val descendants = handles?.descendants().orEmpty()
        var childFailure: Throwable? = null
        fun recordChildFailure(failure: Throwable) {
            if (childFailure == null) childFailure = failure else childFailure!!.addSuppressed(failure)
        }
        descendants.asReversed().forEach { handle ->
            runCatching { handles?.destroy(handle, forcibly = false) }.exceptionOrNull()?.let(::recordChildFailure)
        }
        process.destroy()
        val deadline = System.nanoTime() + graceMillis.coerceAtLeast(0) * 1_000_000
        while (System.nanoTime() < deadline && (isAlive(process) || descendants.any { handles?.isAlive(it) == true })) {
            delay(20)
        }
        descendants.asReversed().filter { handles?.isAlive(it) == true }
            .forEach { handle ->
                runCatching { handles?.destroy(handle, forcibly = true) }.exceptionOrNull()?.let(::recordChildFailure)
            }
        if (isAlive(process)) process.destroyForcibly()
        process.waitFor()
        childFailure?.let { throw ProcessIOException("The main process exited, but child termination failed.", it) }
    }

    private fun isAlive(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun publicProcessId(process: Process): Int? = try {
        val pid = (Process::class.java.getMethod("pid").invoke(process) as Number).toLong()
        pid.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** Public JVM interfaces only; old Android releases simply lack ProcessHandle. */
    private class PublicProcessHandles(private val root: Any, private val type: Class<*>) {
        private val destroy: Method = type.getMethod("destroy")
        private val destroyForcibly: Method = type.getMethod("destroyForcibly")
        private val alive: Method = type.getMethod("isAlive")

        fun descendants(): List<Any> = try {
            val stream = type.getMethod("descendants").invoke(root)
            try {
                val iterator = Class.forName("java.util.stream.BaseStream").getMethod("iterator").invoke(stream) as Iterator<*>
                buildList { while (iterator.hasNext()) iterator.next()?.let(::add) }
            } finally {
                (stream as AutoCloseable).close()
            }
        } catch (_: ReflectiveOperationException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

        fun isAlive(handle: Any): Boolean = try {
            alive.invoke(handle) as Boolean
        } catch (_: ReflectiveOperationException) {
            false
        }

        fun destroy(handle: Any, forcibly: Boolean) {
            try {
                (if (forcibly) destroyForcibly else destroy).invoke(handle)
            } catch (failure: ReflectiveOperationException) {
                if (isAlive(handle)) throw ProcessIOException("Could not terminate a child process.", failure)
            }
        }

        companion object {
            fun forProcess(process: Process): PublicProcessHandles? = try {
                val type = Class.forName("java.lang.ProcessHandle")
                Process::class.java.getMethod("toHandle").invoke(process)?.let { root ->
                    PublicProcessHandles(root, type)
                }
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }

    private companion object {
        val TERMUX_BIN = File("/data/data/com.termux/files/usr/bin")
        val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        fun defaultRuntimeLayout(): RuntimeLayout {
            val root = File(System.getProperty("java.io.tmpdir"), "pocketpaas-local").absoluteFile
            return RuntimeLayout(
                rootPath = root.path,
                projectsPath = File(root, "htdocs").path,
                nginxConfigPath = File(root, "conf/nginx.conf").path,
                nginxHostsPath = File(root, "conf/conf.d").path,
                runtimePath = File(root, "runtime").path,
                logsPath = File(root, "logs").path,
            )
        }
    }
}
