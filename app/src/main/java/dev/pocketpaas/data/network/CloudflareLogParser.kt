package dev.pocketpaas.data.network

/** Parses bounded log chunks without accepting a URL truncated at a chunk boundary. */
class CloudflareLogParser(private val maximumCharacters: Int = 8_192) {
    private val buffer = StringBuilder()
    private var assignmentAnnounced = false
    private var lastUrl: String? = null

    init {
        require(maximumCharacters >= 256)
    }

    fun append(chunk: String): String? {
        if (chunk.isEmpty()) return null
        buffer.append(chunk)
        if (buffer.length > maximumCharacters) buffer.delete(0, buffer.length - maximumCharacters)
        if (!assignmentAnnounced) {
            val markerIndex = buffer.indexOf(ASSIGNMENT_MARKER)
            if (markerIndex < 0) return null
            buffer.delete(0, markerIndex + ASSIGNMENT_MARKER.length)
            assignmentAnnounced = true
        }
        val url = URL.findAll(buffer).map { it.value.lowercase() }
            .lastOrNull { it != "https://api.trycloudflare.com" }
        if (url == null || url == lastUrl) return null
        lastUrl = url
        return url
    }

    fun finish(): String? = append("\n")

    internal val retainedCharacters: Int get() = buffer.length

    private companion object {
        const val ASSIGNMENT_MARKER = "Your quick Tunnel has been created"
        val URL = Regex(
            "(?<![a-zA-Z0-9.-])https://[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.trycloudflare\\.com(?=[^a-zA-Z0-9.-])",
        )
    }
}
