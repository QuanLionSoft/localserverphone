package dev.pocketpaas.domain.network

interface ManagedNetwork {
    suspend fun startAll()
    suspend fun stopAll()
}
