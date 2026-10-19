package dev.pocketpaas.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DeploymentRecordDao {
    @Query("SELECT * FROM deployments ORDER BY id DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<DeploymentRecordEntity>>

    @Query("SELECT * FROM deployments ORDER BY id DESC LIMIT :limit")
    abstract suspend fun recent(limit: Int): List<DeploymentRecordEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRecord(record: DeploymentRecordEntity): Long

    @Query("DELETE FROM deployments WHERE id NOT IN (SELECT id FROM deployments ORDER BY id DESC LIMIT :keep)")
    protected abstract suspend fun pruneBeyond(keep: Int): Int

    @Transaction
    open suspend fun record(record: DeploymentRecordEntity, keep: Int): Long {
        require(keep > 0) { "Retention limit must be positive" }
        val id = insertRecord(record.copy(id = 0))
        pruneBeyond(keep)
        return id
    }
}
