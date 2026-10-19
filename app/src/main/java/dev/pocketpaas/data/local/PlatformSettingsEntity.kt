package dev.pocketpaas.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "platform_settings")
data class PlatformSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val installationId: String,
    val createdAtEpochMillis: Long,
    val publicUrl: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
