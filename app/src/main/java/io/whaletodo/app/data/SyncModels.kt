package io.whaletodo.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local persistence for the sync protocol. No network/WorkManager/state-machine code lives here.
 * The immutable batch body is stored as the exact JSON string sent to the server.
 */
@Entity(
    tableName = "sync_batches",
    indices = [Index(name = "index_sync_batches_owner_state", value = ["ownerId", "state"])],
)
data class SyncBatchRow(
    @PrimaryKey val batchId: String,
    val ownerId: String,
    val createdSessionId: String,
    val attemptSessionId: String? = null,
    val deviceId: String,
    val bodyJson: String,
    val bodySha256: String,
    val createdAt: Long,
    val state: String,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastErrorCode: String? = null,
)

@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(
            name = "index_sync_outbox_owner_state_retry",
            value = ["ownerId", "state", "nextRetryAt"],
        ),
        Index(name = "index_sync_outbox_owner_batch", value = ["ownerId", "batchId"]),
        Index(name = "index_sync_outbox_parent", value = ["parentOpId"]),
    ],
)
data class SyncOutboxRow(
    @PrimaryKey val opId: String,
    val ownerId: String,
    val objectType: String,
    val objectId: String,
    val op: String,
    val payloadJson: String? = null,
    val baseVersion: Int? = null,
    val idempotencyKey: String,
    val parentOpId: String? = null,
    @ColumnInfo(defaultValue = "1") val isHead: Boolean = true,
    val batchId: String? = null,
    val state: String,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(defaultValue = "0") val nextRetryAt: Long = 0,
    val createdAt: Long,
    val lastErrorCode: String? = null,
)

@Entity(tableName = "sync_state")
data class SyncStateRow(
    @PrimaryKey val ownerId: String,
    @ColumnInfo(defaultValue = "0") val cursor: Long = 0,
    val lastPushAt: Long? = null,
    val lastPullAt: Long? = null,
    val deviceId: String,
)

@Entity(
    tableName = "sync_conflicts",
    primaryKeys = ["ownerId", "objectType", "objectId"],
    indices = [Index(name = "index_sync_conflicts_owner_state", value = ["ownerId", "state"])],
)
data class SyncConflictRow(
    val ownerId: String,
    val objectType: String,
    val objectId: String,
    val localPayloadJson: String? = null,
    val localBaseVersion: Int? = null,
    val remoteKnownVersion: Int? = null,
    @ColumnInfo(defaultValue = "0") val remoteDetailFetched: Boolean = false,
    val remotePayloadJson: String? = null,
    /** null=unknown, 0=exists, 1=deleted. */
    val remoteDeleted: Int? = null,
    val reason: String,
    val createdAt: Long,
    val state: String = "open",
)

@Entity(
    tableName = "sync_id_mappings",
    primaryKeys = ["ownerId", "objectType", "guestObjectId"],
    indices = [
        Index(name = "index_sync_id_mappings_owner_natural", value = ["ownerId", "naturalKey"]),
    ],
)
data class SyncIdMappingRow(
    val ownerId: String,
    val objectType: String,
    val guestObjectId: String,
    val accountObjectId: String,
    val naturalKey: String,
    val createdAt: Long,
)

@Entity(
    tableName = "sync_tombstones",
    primaryKeys = ["ownerId", "objectType", "objectId"],
    indices = [Index(name = "index_sync_tombstones_owner_deleted", value = ["ownerId", "deletedAt"])],
)
data class SyncTombstoneRow(
    val ownerId: String,
    val objectType: String,
    val objectId: String,
    val deletedAt: Long,
    val version: Int,
)
