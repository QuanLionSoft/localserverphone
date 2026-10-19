package dev.pocketpaas.core.config

data class RemoteApiPolicy(
    val maximumArchiveBytes: Long = 64L * 1024 * 1024,
    val maximumRequestBytes: Long = 66L * 1024 * 1024,
    val maximumMetadataBytes: Int = 8192,
    val maximumPartHeaderCharacters: Int = 4096,
    val uploadTimeoutMillis: Long = 120_000,
    val operationTimeoutMillis: Long = 35L * 60 * 1000,
    val maximumReturnedLogCharacters: Int = 4096,
    val maximumConnections: Int = 32,
    val headerTimeoutMillis: Long = 30_000,
) {
    init {
        require(maximumArchiveBytes in 1..(64L * 1024 * 1024))
        require(maximumRequestBytes in maximumArchiveBytes..(66L * 1024 * 1024))
        require(maximumMetadataBytes in 1..8192)
        require(maximumPartHeaderCharacters in 256..8192)
        require(uploadTimeoutMillis in 1..300_000)
        require(operationTimeoutMillis in 1..(40L * 60 * 1000))
        require(maximumReturnedLogCharacters in 0..8192)
        require(maximumConnections in 1..64)
        require(headerTimeoutMillis in 1..60_000)
    }
}
