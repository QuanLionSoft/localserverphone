package dev.pocketpaas.domain.repository

import dev.pocketpaas.domain.model.PlatformInfo

interface PlatformRepository {
    suspend fun initialize(): PlatformInfo
}
