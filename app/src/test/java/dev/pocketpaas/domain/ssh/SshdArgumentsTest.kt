package dev.pocketpaas.domain.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshdArgumentsTest {
    @Test
    fun `builds loopback arguments without password auth`() {
        val args = SshdArguments.build(port = 8022, passwordAuthEnabled = false, listenLan = false)
        assertTrue(args.contains("8022"))
        assertTrue(args.contains("PasswordAuthentication=no"))
        assertTrue(args.contains("ListenAddress=127.0.0.1"))
        assertFalse(args.any { it.contains(";") || it.contains("\u0000") })
    }

    @Test
    fun `builds lan arguments with password auth`() {
        val args = SshdArguments.build(port = 2222, passwordAuthEnabled = true, listenLan = true)
        assertTrue(args.contains("PasswordAuthentication=yes"))
        assertTrue(args.contains("ListenAddress=0.0.0.0"))
        assertEquals("-D", args.first())
    }

    @Test
    fun `rejects privileged port`() {
        try {
            SshdArguments.build(port = 22, passwordAuthEnabled = false, listenLan = false)
            error("should have thrown")
        } catch (_: IllegalArgumentException) {
        }
    }
}
