package dev.pocketpaas.core.config

data class SiteStoragePolicy(
    val minimumPort: Int = 10_000,
    val maximumPort: Int = 65_535,
    val maximumLogCharacters: Int = 65_536,
) {
    init {
        require(minimumPort in 1024..65_535 && maximumPort in minimumPort..65_535)
        require(maximumLogCharacters in 1..1_048_576)
    }
}
