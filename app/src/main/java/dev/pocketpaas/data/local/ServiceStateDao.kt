package dev.pocketpaas.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ServiceStateDao {
    @Query("SELECT * FROM services ORDER BY serviceKey ASC")
    abstract fun observeServices(): Flow<List<ServiceStateEntity>>

    @Query("SELECT * FROM services ORDER BY serviceKey ASC")
    abstract suspend fun getServices(): List<ServiceStateEntity>

    @Query("SELECT * FROM services WHERE serviceKey = :serviceKey LIMIT 1")
    abstract suspend fun getService(serviceKey: String): ServiceStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(service: ServiceStateEntity): Long

    @Query("DELETE FROM services WHERE serviceKey = :serviceKey")
    abstract suspend fun delete(serviceKey: String): Int
}
