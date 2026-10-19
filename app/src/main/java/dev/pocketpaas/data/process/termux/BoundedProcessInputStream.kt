package dev.pocketpaas.data.process.termux

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/** A bounded byte pipe whose lifetime belongs to its producer, rather than a worker thread. */
internal class BoundedProcessInputStream(capacity: Int) : InputStream() {
    private val monitor = Object()
    private val bytes = ByteArray(capacity.also { require(it > 0) { "Stream capacity must be positive." } })
    private var head = 0
    private var size = 0
    private var finished = false
    private var closed = false
    private var failure: IOException? = null

    override fun read(): Int = synchronized(monitor) {
        if (!awaitReadable()) return@synchronized -1
        val value = bytes[head].toInt() and 0xff
        head = (head + 1) % bytes.size
        size--
        monitor.notifyAll()
        value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        return synchronized(monitor) {
            if (!awaitReadable()) return@synchronized -1
            val count = minOf(length, size)
            val first = minOf(count, bytes.size - head)
            bytes.copyInto(buffer, offset, head, head + first)
            if (first < count) bytes.copyInto(buffer, offset + first, 0, count - first)
            head = (head + count) % bytes.size
            size -= count
            monitor.notifyAll()
            count
        }
    }

    override fun available(): Int = synchronized(monitor) { if (closed) 0 else size }

    /** Blocks the producer when the fixed-size ring is full. Input chunks are copied, not retained. */
    fun write(chunk: ByteArray) {
        var offset = 0
        synchronized(monitor) { ensureWritable() }
        while (offset < chunk.size) {
            synchronized(monitor) {
                while (size == bytes.size) {
                    ensureWritable()
                    awaitChange()
                }
                ensureWritable()
                val tail = (head + size) % bytes.size
                val count = minOf(chunk.size - offset, bytes.size - size, bytes.size - tail)
                chunk.copyInto(bytes, tail, offset, offset + count)
                offset += count
                size += count
                monitor.notifyAll()
            }
        }
    }

    /** A normal producer EOF is observed after all buffered bytes have been read. */
    fun finish() = synchronized(monitor) {
        finished = true
        monitor.notifyAll()
    }

    /** Preserves captured bytes, then exposes the original failure instead of a successful EOF. */
    fun fail(problem: IOException) = synchronized(monitor) {
        if (failure == null) failure = problem
        finished = true
        monitor.notifyAll()
    }

    /** Consumer cancellation immediately releases blocked readers and producers. */
    override fun close() = synchronized(monitor) {
        closed = true
        size = 0
        monitor.notifyAll()
    }

    private fun awaitReadable(): Boolean {
        while (true) {
            if (closed) throw failure ?: IOException("The process output stream is closed.")
            if (size > 0) return true
            failure?.let { throw it }
            if (finished) return false
            awaitChange()
        }
    }

    private fun ensureWritable() {
        failure?.let { throw it }
        if (closed) throw IOException("The process output consumer is closed.")
        if (finished) throw IOException("The process output producer has finished.")
    }

    private fun awaitChange() {
        try {
            monitor.wait()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Waiting for process output was interrupted.").apply { initCause(interrupted) }
        }
    }
}
