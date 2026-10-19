package dev.pocketpaas.domain.remote

interface ApiKeyStore {
    suspend fun token(): String
}
