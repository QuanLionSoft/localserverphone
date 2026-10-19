package dev.pocketpaas.domain.setup

interface SetupPreferences {
    suspend fun isCompleted(): Boolean
    suspend fun markCompleted()
}
