package dev.pocketpaas.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareLogParserTest {
    @Test
    fun acceptsTheAssignedUrlAfterEveryPossibleChunkSplit() {
        val message = "Your quick Tunnel has been created! Visit it at:\n| https://blue-bird.trycloudflare.com |\n"
        for (split in 1 until message.length) {
            val parser = CloudflareLogParser()
            val first = parser.append(message.take(split))
            val second = parser.append(message.drop(split))
            assertEquals("https://blue-bird.trycloudflare.com", second ?: first)
        }
    }

    @Test
    fun waitsForDelimiterToRejectSuffixesAcrossChunks() {
        val parser = announcedParser()
        assertNull(parser.append("https://evil.trycloudflare.com"))
        assertNull(parser.append(".attacker.example\n"))
        assertEquals("https://good.trycloudflare.com", parser.append("https://good.trycloudflare.com\n"))
    }

    @Test
    fun ignoresApiHostInvalidLabelsAndUnrelatedUrls() {
        val parser = announcedParser()
        assertNull(parser.append("https://api.trycloudflare.com\n"))
        assertNull(parser.append("https://-bad.trycloudflare.com https://bad-.trycloudflare.com\n"))
        assertNull(parser.append("https://${"a".repeat(64)}.trycloudflare.com\n"))
        assertNull(parser.append("https://sub.good.trycloudflare.com\n"))
        assertNull(parser.append("prefixhttps://good.trycloudflare.com\n"))
    }

    @Test
    fun ignoresUrlsBeforeTheAssignmentAnnouncement() {
        val parser = CloudflareLogParser()
        assertNull(parser.append("https://random.trycloudflare.com\n"))
        assertNull(parser.append("Your quick Tunnel has been created\n"))
        assertEquals("https://assigned.trycloudflare.com", parser.append("https://assigned.trycloudflare.com\n"))
    }

    @Test
    fun normalizesHostCaseAndDeduplicatesEvents() {
        val parser = announcedParser()
        assertEquals("https://a-b.trycloudflare.com", parser.append("https://A-B.trycloudflare.com\n"))
        assertNull(parser.append("https://a-b.trycloudflare.com\n"))
    }

    @Test
    fun keepsMemoryBoundedAndCanFinishAnUnterminatedLine() {
        val parser = CloudflareLogParser(256)
        parser.append("Your quick Tunnel has been created\n")
        repeat(100) { parser.append("x".repeat(1_000)) }
        assertTrue(parser.retainedCharacters <= 256)
        assertNull(parser.append("\nhttps://end.trycloudflare.com"))
        assertEquals("https://end.trycloudflare.com", parser.finish())
    }

    private fun announcedParser() = CloudflareLogParser().also {
        it.append("Your quick Tunnel has been created\n")
    }
}
