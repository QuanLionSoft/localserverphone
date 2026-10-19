package dev.pocketpaas.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ssh_authorized_keys",
    indices = [Index(value = ["fingerprint"], unique = true)],
)
data class SshAuthorizedKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val publicKey: String,
    val fingerprint: String,
    val enabled: Boolean = true,
    val createdAt: Long,
)
