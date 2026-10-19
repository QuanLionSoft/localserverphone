package dev.pocketpaas.domain.ssh

import java.util.Base64

/**
 * OpenSSH authorized_keys satır biçimi ve `ssh-keygen -lf` ile aynı SHA-256 parmak izi.
 * Ayrıntılı doğrulama, shell kaçış ihtiyacını ortadan kaldırır ve anahtar eklemeyi güvenli tutar.
 */
object SshKeyFormat {
    const val MAXIMUM_LINE_CHARACTERS = 8_192
    const val MAXIMUM_COMMENT_CHARACTERS = 256

    val SUPPORTED_TYPES: Set<String> = setOf(
        "ssh-ed25519",
        "ssh-rsa",
        "ecdsa-sha2-nistp256",
        "ecdsa-sha2-nistp384",
        "ecdsa-sha2-nistp521",
    )

    private val BLOB_PATTERN = Regex("[A-Za-z0-9+/]{16,}={0,2}")

    data class ParsedKey(
        val type: String,
        val comment: String,
        val line: String,
        val fingerprint: String,
    )

    fun validateName(name: String): String {
        val value = name.trim()
        require(value.isNotEmpty()) { "Key name must not be blank" }
        require(value.length <= MAXIMUM_COMMENT_CHARACTERS) { "Key name is too long" }
        require(value.none { it.isISOControl() }) { "Key name must not contain control characters" }
        return value
    }

    fun parse(rawLine: String): ParsedKey {
        val trimmed = rawLine.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= MAXIMUM_LINE_CHARACTERS) {
            "Public key must not be blank or oversized"
        }
        require(trimmed.none { it.isISOControl() }) { "Public key must be a single line" }
        val parts = trimmed.split(Regex("\\s+"))
        require(parts.size in 2..3) { "Public key must contain a type, a base64 blob and an optional comment" }
        val type = parts[0]
        require(type in SUPPORTED_TYPES) { "Unsupported public key type: $type" }
        val blob = parts[1]
        require(BLOB_PATTERN.matches(blob)) { "Public key blob is not valid base64" }
        val decoded = try {
            Base64.getDecoder().decode(blob)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Public key blob is not valid base64", failure)
        }
        require(decoded.size in 32..16_384) { "Public key blob has an unexpected size" }
        requireMatchesWireType(decoded, type)
        val comment = if (parts.size == 3) sanitizeComment(parts[2]) else "pocketpaas"
        return ParsedKey(
            type = type,
            comment = comment,
            line = "$type $blob $comment",
            fingerprint = fingerprint(decoded),
        )
    }

    /** `ssh-keygen -lf` çıktısındaki `SHA256:<base64>` biçimini üretir. */
    fun fingerprint(decodedBlob: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(decodedBlob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    fun sanitizeComment(raw: String): String {
        val cleaned = raw.trim().replace(Regex("\\s+"), " ")
        require(cleaned.isNotEmpty() && cleaned.length <= MAXIMUM_COMMENT_CHARACTERS) {
            "Key comment is blank or too long"
        }
        require(cleaned.none { it.isISOControl() }) { "Key comment must not contain control characters" }
        return cleaned
    }

    private fun requireMatchesWireType(decoded: ByteArray, type: String) {
        require(decoded.size >= 4 + type.length) { "Public key blob is truncated" }
        val length = ((decoded[0].toInt() and 0xff) shl 24) or
            ((decoded[1].toInt() and 0xff) shl 16) or
            ((decoded[2].toInt() and 0xff) shl 8) or
            (decoded[3].toInt() and 0xff)
        require(length == type.length && decoded.size >= 4 + length) {
            "Public key blob does not start with its key type"
        }
        val wireType = String(decoded, 4, length, Charsets.US_ASCII)
        require(wireType == type) { "Public key blob type does not match its label" }
    }
}
