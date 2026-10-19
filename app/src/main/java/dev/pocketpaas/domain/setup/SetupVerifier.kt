package dev.pocketpaas.domain.setup

data class SetupVerification(val missingTools: List<String>) {
    val ready: Boolean get() = missingTools.isEmpty()
}

interface SetupVerifier {
    suspend fun verify(): SetupVerification
}
