package dev.pocketpaas.domain.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyFormatTest {

    @Test
    fun `parses ed25519 key and produces sha256 fingerprint`() {
        // 32-byte ed25519 public key with type prefix 00 00 00 0b s s h - e d 2 5 5 1 9
        val blob = buildWireBlob("ssh-ed25519", ByteArray(32) { 0x42 })
        val line = "ssh-ed25519 $blob my-laptop"
        val parsed = SshKeyFormat.parse(line)
        assertEquals("ssh-ed25519", parsed.type)
        assertEquals("my-laptop", parsed.comment)
        assertTrue(parsed.fingerprint.startsWith("SHA256:"))
        assertTrue(parsed.line.startsWith("ssh-ed25519 $blob"))
    }

    @Test
    fun `rejects duplicated fingerprint via wire type mismatch`() {
        val blob = buildWireBlob("ssh-ed25519", ByteArray(32) { 0x42 })
        // Label says ed25519 but blob encodes rsa
        val wrongBlob = buildWireBlob("ssh-rsa", ByteArray(32) { 0x42 })
        val line = "ssh-ed25519 $wrongBlob comment"
        try {
            SshKeyFormat.parse(line)
            error("should have thrown")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `rejects oversized line`() {
        try {
            SshKeyFormat.parse("ssh-ed25519 " + "A".repeat(9000))
            error("should have thrown")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `validates name rejects blank`() {
        try {
            SshKeyFormat.validateName("  ")
            error("should have thrown")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `validates name accepts normal`() {
        assertEquals("laptop", SshKeyFormat.validateName("laptop"))
    }

    private fun buildWireBlob(type: String, keyBytes: ByteArray): String {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val buffer = ByteArray(4 + typeBytes.size + keyBytes.size)
        buffer[0] = (typeBytes.size shr 24).toByte()
        buffer[1] = (typeBytes.size shr 16).toByte()
        buffer[2] = (typeBytes.size shr 8).toByte()
        buffer[3] = typeBytes.size.toByte()
        typeBytes.copyInto(buffer, 4)
        keyBytes.copyInto(buffer, 4 + typeBytes.size)
        return java.util.Base64.getEncoder().encodeToString(buffer)
    }
}
