package dev.pocketpaas.domain.system

import dev.pocketpaas.domain.model.RuntimeCapabilities
import dev.pocketpaas.domain.model.RuntimeLayout

interface SystemManager {
    suspend fun prepareLayout(): RuntimeLayout
    suspend fun inspectCapabilities(): RuntimeCapabilities
}
