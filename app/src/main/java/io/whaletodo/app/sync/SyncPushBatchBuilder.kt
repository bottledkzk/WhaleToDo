package io.whaletodo.app.sync

import io.whaletodo.app.data.SyncOutboxRow
import io.whaletodo.network.SyncPushBody
import io.whaletodo.network.SyncPushMutation
import java.security.MessageDigest

internal data class BuiltPushBatch(
    val batchId: String,
    val deviceId: String,
    val bodyJson: String,
    val bodySha256: String,
    val rows: List<SyncOutboxRow>,
)

/** Pure batch construction: no Room/network access, deterministic for a fixed input. */
internal object SyncPushBatchBuilder {
    const val MAX_MUTATIONS = 20

    fun build(
        ownerId: String,
        deviceId: String,
        batchId: String,
        rows: List<SyncOutboxRow>,
    ): BuiltPushBatch {
        require(rows.all { it.ownerId == ownerId }) { "outbox owner mismatch" }
        val selected = rows
            .sortedWith(compareBy<SyncOutboxRow>({ it.createdAt }, { it.opId }))
            .take(MAX_MUTATIONS)
        require(selected.isNotEmpty())
        val body = SyncPushBody(
            batchId = batchId,
            deviceId = deviceId,
            mutations = selected.map {
                SyncPushMutation(
                    objectType = it.objectType,
                    objectId = it.objectId,
                    op = it.op,
                    expectedVersion = it.baseVersion,
                    payloadJson = it.payloadJson,
                )
            },
        )
        val json = body.toJson()
        return BuiltPushBatch(
            batchId = batchId,
            deviceId = deviceId,
            bodyJson = json,
            bodySha256 = sha256(json),
            rows = selected,
        )
    }

    internal fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
