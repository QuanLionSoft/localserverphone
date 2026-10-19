package dev.pocketpaas.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SshDao {
    @Query("SELECT * FROM ssh_settings WHERE id = 1 LIMIT 1")
    abstract suspend fun getSettings(): SshSettingsEntity?

    @Query("SELECT * FROM ssh_settings WHERE id = 1 LIMIT 1")
    abstract fun observeSettings(): Flow<SshSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSettings(settings: SshSettingsEntity): Long

    @Query("UPDATE ssh_settings SET hostKeyFingerprint = :fingerprint, updatedAt = :updatedAt WHERE id = 1")
    abstract suspend fun updateHostKeyFingerprint(fingerprint: String?, updatedAt: Long): Int

    @Transaction
    open suspend fun getOrCreateSettings(candidate: SshSettingsEntity): SshSettingsEntity {
        require(candidate.id == SshSettingsEntity.SINGLETON_ID)
        getSettings()?.let { return it }
        upsertSettings(candidate)
        return checkNotNull(getSettings()) { "SSH settings could not be persisted" }
    }

    @Query("SELECT * FROM ssh_authorized_keys ORDER BY createdAt DESC, id DESC")
    abstract suspend fun getAuthorizedKeys(): List<SshAuthorizedKeyEntity>

    @Query("SELECT * FROM ssh_authorized_keys ORDER BY createdAt DESC, id DESC")
    abstract fun observeAuthorizedKeys(): Flow<List<SshAuthorizedKeyEntity>>

    @Query("SELECT COUNT(*) FROM ssh_authorized_keys")
    abstract suspend fun countAuthorizedKeys(): Int

    @Query("SELECT * FROM ssh_authorized_keys WHERE id = :id LIMIT 1")
    abstract suspend fun getAuthorizedKey(id: Long): SshAuthorizedKeyEntity?

    @Query("SELECT * FROM ssh_authorized_keys WHERE fingerprint = :fingerprint LIMIT 1")
    abstract suspend fun findByFingerprint(fingerprint: String): SshAuthorizedKeyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertAuthorizedKey(key: SshAuthorizedKeyEntity): Long

    @Query("DELETE FROM ssh_authorized_keys WHERE id = :id")
    abstract suspend fun deleteAuthorizedKey(id: Long): Int

    @Query("UPDATE ssh_authorized_keys SET enabled = :enabled WHERE id = :id")
    abstract suspend fun setAuthorizedKeyEnabled(id: Long, enabled: Boolean): Int
}
