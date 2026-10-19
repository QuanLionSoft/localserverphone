package dev.pocketpaas.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ssh_settings")
data class SshSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val enabled: Boolean = false,
    val port: Int = 8022,
    val passwordAuthEnabled: Boolean = false,
    val listenLan: Boolean = false,
    val hostKeyFingerprint: String? = null,
    val updatedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
