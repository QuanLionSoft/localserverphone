package dev.pocketpaas.data.local

import dev.pocketpaas.domain.model.ActivityEvent
import dev.pocketpaas.domain.model.DeploymentRecord
import dev.pocketpaas.domain.services.ManagedServiceState
import dev.pocketpaas.domain.ssh.SshAuthorizedKey
import dev.pocketpaas.domain.ssh.SshSettings

internal fun ActivityEventEntity.toDomain(): ActivityEvent = ActivityEvent(
    id = id,
    category = category,
    level = level,
    message = message,
    detail = detail,
    timestamp = timestamp,
)

internal fun DeploymentRecordEntity.toDomain(): DeploymentRecord = DeploymentRecord(
    id = id,
    siteId = siteId,
    domainName = domainName,
    projectType = projectType,
    status = status,
    archiveName = archiveName,
    archiveSizeBytes = archiveSizeBytes,
    message = message,
    startedAt = startedAt,
    finishedAt = finishedAt,
)

internal fun ServiceStateEntity.toDomain(): ManagedServiceState = ManagedServiceState(
    serviceKey = serviceKey,
    displayName = displayName,
    category = category,
    installed = installed,
    status = status,
    processId = processId,
    port = port,
    lastError = lastError,
    updatedAt = updatedAt,
)

internal fun SshSettingsEntity.toDomain(): SshSettings = SshSettings(
    enabled = enabled,
    port = port,
    passwordAuthEnabled = passwordAuthEnabled,
    listenLan = listenLan,
    hostKeyFingerprint = hostKeyFingerprint,
    updatedAt = updatedAt,
)

internal fun SshSettings.toEntity(updatedAt: Long): SshSettingsEntity = SshSettingsEntity(
    id = SshSettingsEntity.SINGLETON_ID,
    enabled = enabled,
    port = port,
    passwordAuthEnabled = passwordAuthEnabled,
    listenLan = listenLan,
    hostKeyFingerprint = hostKeyFingerprint,
    updatedAt = updatedAt,
)

internal fun SshAuthorizedKeyEntity.toDomain(): SshAuthorizedKey = SshAuthorizedKey(
    id = id,
    name = name,
    publicKey = publicKey,
    fingerprint = fingerprint,
    enabled = enabled,
    createdAt = createdAt,
)
