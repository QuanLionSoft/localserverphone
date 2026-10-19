package dev.pocketpaas.verification

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import dev.pocketpaas.data.local.SiteEntity
import dev.pocketpaas.data.local.SiteDao
import dev.pocketpaas.data.local.SiteTypeConverters
import androidx.room.TypeConverters

/** Immutable historical schema; newer production fields must never enter old fixtures. */
@Database(
    entities = [LegacyPlatformSettingsEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PhaseOneDatabase : RoomDatabase() {
    abstract fun platformSettingsDao(): LegacyPlatformSettingsDao
}

@Entity(tableName = "platform_settings")
data class LegacyPlatformSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val installationId: String,
    val createdAtEpochMillis: Long,
)

@Dao
abstract class LegacyPlatformSettingsDao {
    @Query("SELECT * FROM platform_settings WHERE id = 1")
    abstract suspend fun getSettings(): LegacyPlatformSettingsEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(settings: LegacyPlatformSettingsEntity): Long
    @Transaction
    open suspend fun getOrCreate(candidate: LegacyPlatformSettingsEntity): LegacyPlatformSettingsEntity {
        insertIfAbsent(candidate)
        return checkNotNull(getSettings())
    }
}

@Database(entities = [LegacyPlatformSettingsEntity::class, SiteEntity::class], version = 2, exportSchema = true)
@TypeConverters(SiteTypeConverters::class)
abstract class PhaseTwoDatabase : RoomDatabase() {
    abstract fun platformSettingsDao(): LegacyPlatformSettingsDao
    abstract fun siteDao(): SiteDao
}
