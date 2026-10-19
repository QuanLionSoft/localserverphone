package dev.pocketpaas.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [PlatformSettingsEntity::class, SiteEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(SiteTypeConverters::class)
abstract class PocketPaaSDatabase : RoomDatabase() {
    abstract fun platformSettingsDao(): PlatformSettingsDao
    abstract fun siteDao(): SiteDao

    companion object {
        const val NAME = "pocket_paas.db"
    }
}
