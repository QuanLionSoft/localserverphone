package dev.pocketpaas.domain.repository

import kotlinx.coroutines.flow.Flow

interface ServerStateRepository {
    val publicUrl: Flow<String?>
    suspend fun updatePublicUrl(url: String?)
}
