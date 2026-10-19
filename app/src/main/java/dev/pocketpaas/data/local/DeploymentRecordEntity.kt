package dev.pocketpaas.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.pocketpaas.domain.model.DeploymentStatus
import dev.pocketpaas.domain.model.ProjectType

@Entity(
    tableName = "deployments",
    indices = [Index(value = ["siteId"]), Index(value = ["startedAt"])],
)
data class DeploymentRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val siteId: Long? = null,
    val domainName: String,
    val projectType: ProjectType,
    val status: DeploymentStatus,
    val archiveName: String? = null,
    val archiveSizeBytes: Long? = null,
    val message: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
)
