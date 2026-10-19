package dev.pocketpaas.domain.usecase

import dev.pocketpaas.domain.model.PlatformInfo
import dev.pocketpaas.domain.repository.PlatformRepository
import javax.inject.Inject

class InitializePlatform @Inject constructor(
    private val repository: PlatformRepository,
) {
    suspend operator fun invoke(): PlatformInfo = repository.initialize()
}
