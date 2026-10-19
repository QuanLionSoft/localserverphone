package dev.pocketpaas.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PlatformSettingsDao {
    @Query("SELECT * FROM platform_settings WHERE id = 1 LIMIT 1")
    abstract suspend fun getSettings(): PlatformSettingsEntity?

    @Query("SELECT publicUrl FROM platform_settings WHERE id = 1")
    abstract fun observePublicUrl(): Flow<String?>

    @Query("UPDATE platform_settings SET publicUrl = :url WHERE id = 1")
    abstract suspend fun updatePublicUrl(url: String?): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(settings: PlatformSettingsEntity): Long

    @Transaction
    open suspend fun getOrCreate(candidate: PlatformSettingsEntity): PlatformSettingsEntity {
        require(candidate.id == PlatformSettingsEntity.SINGLETON_ID)
        getSettings()?.let { return it }
        insertIfAbsent(candidate)
        return checkNotNull(getSettings()) { "Platform settings could not be persisted" }
    }
}
