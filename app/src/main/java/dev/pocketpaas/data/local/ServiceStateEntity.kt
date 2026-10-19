package dev.pocketpaas.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.pocketpaas.domain.services.ManagedServiceStatus
import dev.pocketpaas.domain.services.ServiceCategory

@Entity(tableName = "services")
data class ServiceStateEntity(
    @PrimaryKey val serviceKey: String,
    val displayName: String,
    val category: ServiceCategory,
    val installed: Boolean = false,
    val status: ManagedServiceStatus = ManagedServiceStatus.UNKNOWN,
    val processId: Int? = null,
    val port: Int? = null,
    val lastError: String? = null,
    val updatedAt: Long,
)
