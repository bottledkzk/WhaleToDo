package io.whaletodo.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert

/** Basic persistence/query surface for sync; worker/state-machine behavior is a later block. */
@Dao
interface SyncDao {
    @Upsert suspend fun upsertBatch(row: SyncBatchRow)

    @Query("SELECT * FROM sync_batches WHERE batchId=:batchId")
    suspend fun batch(batchId: String): SyncBatchRow?

    @Query("SELECT * FROM sync_batches WHERE ownerId=:ownerId AND state=:state ORDER BY createdAt")
    suspend fun batches(ownerId: String, state: String): List<SyncBatchRow>

    @Query("UPDATE sync_batches SET state=:state, lastErrorCode=:error WHERE batchId=:batchId AND ownerId=:ownerId")
    suspend fun updateBatchState(ownerId: String, batchId: String, state: String, error: String?)

    @Query("UPDATE sync_batches SET state=:state, lastErrorCode=:error WHERE batchId=:batchId AND ownerId=:ownerId AND state='sending'")
    suspend fun updateBatchStateIfSending(ownerId: String, batchId: String, state: String, error: String?): Int

    @Query("UPDATE sync_batches SET attemptSessionId=:attemptSessionId, attempts=attempts+1, lastAttemptAt=:at WHERE batchId=:batchId AND ownerId=:ownerId")
    suspend fun recordBatchAttempt(ownerId: String, batchId: String, attemptSessionId: String, at: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(row: SyncOutboxRow): Long

    @Update
    suspend fun updateOutbox(row: SyncOutboxRow): Int

    /**
     * Existing opId is a legal update. A new opId that would become a second chain head
     * must surface the unique-index conflict; do not use @Upsert/REPLACE, which can
     * swallow the conflict and silently drop the new intent.
     */
    @Transaction
    suspend fun insertOrUpdateOutbox(row: SyncOutboxRow) {
        if (updateOutbox(row) == 0) insertOutbox(row)
    }

    @Query("SELECT * FROM sync_outbox WHERE opId=:opId")
    suspend fun outbox(opId: String): SyncOutboxRow?

    @Query("SELECT * FROM sync_outbox WHERE ownerId=:ownerId AND batchId=:batchId ORDER BY createdAt,opId")
    suspend fun outboxForBatch(ownerId: String, batchId: String): List<SyncOutboxRow>

    @Query("SELECT * FROM sync_outbox WHERE ownerId=:ownerId AND state=:state ORDER BY nextRetryAt,createdAt LIMIT :limit")
    suspend fun outboxByState(ownerId: String, state: String, limit: Int = 200): List<SyncOutboxRow>

    @Query("SELECT * FROM sync_outbox WHERE ownerId=:ownerId AND batchId IS NULL AND state='pending' AND isHead=1 ORDER BY nextRetryAt,createdAt LIMIT :limit")
    suspend fun pendingHeads(ownerId: String, limit: Int = 200): List<SyncOutboxRow>

    @Query("SELECT * FROM sync_outbox WHERE ownerId=:ownerId AND batchId IS NULL AND state='pending' AND isHead=1 ORDER BY createdAt,opId LIMIT :limit")
    suspend fun pendingHeadsForPush(ownerId: String, limit: Int): List<SyncOutboxRow>

    @Query("SELECT * FROM sync_outbox WHERE ownerId=:ownerId AND objectType=:objectType AND objectId=:objectId AND isHead=1 LIMIT 1")
    suspend fun head(ownerId: String, objectType: String, objectId: String): SyncOutboxRow?

    /** Settings is a single local row; its dirty head may still use a legacy remote key. */
    @Query("SELECT * FROM sync_outbox WHERE ownerId=:ownerId AND objectType='settings' AND isHead=1 AND state IN ('pending','blocked','sending') LIMIT 1")
    suspend fun settingsHead(ownerId: String): SyncOutboxRow?

    @Delete suspend fun deleteOutbox(row: SyncOutboxRow)

    @Upsert suspend fun upsertState(row: SyncStateRow)

    @Query("SELECT * FROM sync_state WHERE ownerId=:ownerId")
    suspend fun state(ownerId: String): SyncStateRow?

    @Query("UPDATE sync_state SET cursor=:cursor, lastPullAt=:at WHERE ownerId=:ownerId")
    suspend fun updatePullCursor(ownerId: String, cursor: Long, at: Long)

    @Query("UPDATE sync_state SET lastPushAt=:at WHERE ownerId=:ownerId")
    suspend fun touchPush(ownerId: String, at: Long)

    @Upsert suspend fun upsertConflict(row: SyncConflictRow)

    @Query("SELECT * FROM sync_conflicts WHERE ownerId=:ownerId AND objectType=:objectType AND objectId=:objectId")
    suspend fun conflict(ownerId: String, objectType: String, objectId: String): SyncConflictRow?

    @Query("SELECT * FROM sync_conflicts WHERE ownerId=:ownerId AND state=:state ORDER BY createdAt")
    suspend fun conflicts(ownerId: String, state: String): List<SyncConflictRow>

    @Delete suspend fun deleteConflict(row: SyncConflictRow)

    @Upsert suspend fun upsertMapping(row: SyncIdMappingRow)

    @Query("SELECT * FROM sync_id_mappings WHERE ownerId=:ownerId AND objectType=:objectType AND guestObjectId=:guestObjectId")
    suspend fun mapping(ownerId: String, objectType: String, guestObjectId: String): SyncIdMappingRow?

    @Query("SELECT * FROM sync_id_mappings WHERE ownerId=:ownerId ORDER BY objectType,createdAt")
    suspend fun mappings(ownerId: String): List<SyncIdMappingRow>

    @Delete suspend fun deleteMapping(row: SyncIdMappingRow)

    @Upsert suspend fun upsertTombstone(row: SyncTombstoneRow)

    @Query("SELECT * FROM sync_tombstones WHERE ownerId=:ownerId AND objectType=:objectType AND objectId=:objectId")
    suspend fun tombstone(ownerId: String, objectType: String, objectId: String): SyncTombstoneRow?

    @Query("SELECT * FROM sync_tombstones WHERE ownerId=:ownerId ORDER BY deletedAt")
    suspend fun tombstones(ownerId: String): List<SyncTombstoneRow>

    @Delete suspend fun deleteTombstone(row: SyncTombstoneRow)
}
