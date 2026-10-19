package dev.pocketpaas.data.process

import java.io.File

/** Runs in a separate JVM so pipe pressure and termination checks use real OS processes. */
object ProcessFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        when (arguments.first()) {
            "flood" -> {
                val stdout = Thread {
                    val block = ByteArray(8_192) { 'A'.code.toByte() }
                    repeat(256) { System.out.write(block) }
                    System.out.print("STDOUT-END")
                    System.out.flush()
                }
                val stderr = Thread {
                    val block = ByteArray(8_192) { 'B'.code.toByte() }
                    repeat(256) { System.err.write(block) }
                    System.err.print("STDERR-END")
                    System.err.flush()
                }
                stdout.start()
                stderr.start()
                stdout.join()
                stderr.join()
            }
            "context" -> {
                System.out.println(System.getenv("POCKETPAAS_FIXTURE"))
                System.out.println(File(".").canonicalPath)
            }
            "exit" -> {
                System.err.print("fixture failed")
                System.err.flush()
                System.exit(23)
            }
            "unicode" -> {
                repeat(20_000) { System.out.print("\uD83D\uDE80") }
                System.out.flush()
            }
            "wait" -> {
                System.out.print("READY")
                System.out.flush()
                Thread.sleep(60_000)
            }
            "pid-file" -> {
                val processHandle = Class.forName("java.lang.ProcessHandle")
                val current = processHandle.getMethod("current").invoke(null)
                val pid = processHandle.getMethod("pid").invoke(current) as Long
                File(arguments[1]).writeText(pid.toString())
                System.out.print("READY")
                System.out.flush()
                Thread.sleep(60_000)
            }
            else -> throw IllegalArgumentException("Unknown process fixture mode.")
        }
    }
}
