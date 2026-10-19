package dev.pocketpaas.data.deployment

import dev.pocketpaas.domain.deployment.DeploymentControl
import dev.pocketpaas.domain.deployment.DeploymentResult
import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityLevel
import dev.pocketpaas.domain.model.DeploymentRecord
import dev.pocketpaas.domain.model.DeploymentStatus
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.repository.ActivityRepository
import dev.pocketpaas.domain.repository.DeploymentHistoryRepository
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Deployment sonuçlarını kalıcı geçmişe ve aktivite kaydına yazar.
 * Kayıt işlemleri NonCancellable içinde yürür; iptal edilmiş bir yayında da geçmiş kalır.
 */
class RecordingDeploymentControl(
    private val delegate: DeploymentControl,
    private val history: DeploymentHistoryRepository,
    private val activity: ActivityRepository,
    private val clock: Clock,
) : DeploymentControl {
    override suspend fun deployProject(site: Site, archive: File): DeploymentResult {
        val startedAt = clock.millis()
        return try {
            val result = delegate.deployProject(site, archive)
            record(
                site = site,
                archive = archive,
                status = DeploymentStatus.SUCCEEDED,
                message = null,
                startedAt = startedAt,
                finishedAt = clock.millis(),
            )
            result
        } catch (cancelled: CancellationException) {
            record(
                site = site,
                archive = archive,
                status = DeploymentStatus.CANCELLED,
                message = "Deployment iptal edildi.",
                startedAt = startedAt,
                finishedAt = clock.millis(),
            )
            throw cancelled
        } catch (failure: Throwable) {
            record(
                site = site,
                archive = archive,
                status = DeploymentStatus.FAILED,
                message = description(failure),
                startedAt = startedAt,
                finishedAt = clock.millis(),
            )
            throw failure
        }
    }

    override suspend fun startSite(siteId: Long): DeploymentResult {
        val result = delegate.startSite(siteId)
        withContext(NonCancellable) {
            activity.record(ActivityCategory.SITE, ActivityLevel.INFO, "Proje #$siteId başlatıldı.")
        }
        return result
    }

    override suspend fun stopSite(siteId: Long) {
        delegate.stopSite(siteId)
        withContext(NonCancellable) {
            activity.record(ActivityCategory.SITE, ActivityLevel.INFO, "Proje #$siteId durduruldu.")
        }
    }

    override suspend fun stopAll() {
        delegate.stopAll()
        withContext(NonCancellable) {
            activity.record(ActivityCategory.SYSTEM, ActivityLevel.INFO, "Tüm proje süreçleri durduruldu.")
        }
    }

    private suspend fun record(
        site: Site,
        archive: File,
        status: DeploymentStatus,
        message: String?,
        startedAt: Long,
        finishedAt: Long,
    ) {
        withContext(NonCancellable) {
            runCatching {
                history.record(
                    DeploymentRecord(
                        siteId = site.id,
                        domainName = site.domainName,
                        projectType = site.projectType,
                        status = status,
                        archiveName = archive.name,
                        archiveSizeBytes = archive.length(),
                        message = message,
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                    ),
                )
            }
            val level = when (status) {
                DeploymentStatus.SUCCEEDED -> ActivityLevel.INFO
                DeploymentStatus.CANCELLED -> ActivityLevel.WARNING
                DeploymentStatus.FAILED, DeploymentStatus.ROLLED_BACK -> ActivityLevel.ERROR
            }
            val text = when (status) {
                DeploymentStatus.SUCCEEDED -> "Yayınlandı: ${site.domainName}"
                DeploymentStatus.CANCELLED -> "Yayın iptal edildi: ${site.domainName}"
                DeploymentStatus.FAILED -> "Yayın başarısız: ${site.domainName}"
                DeploymentStatus.ROLLED_BACK -> "Yayın geri alındı: ${site.domainName}"
            }
            runCatching {
                activity.record(ActivityCategory.DEPLOYMENT, level, text, message)
            }
        }
    }

    private fun description(failure: Throwable): String =
        (failure.message ?: failure.javaClass.simpleName).replace('\u0000', ' ').take(512)
}
