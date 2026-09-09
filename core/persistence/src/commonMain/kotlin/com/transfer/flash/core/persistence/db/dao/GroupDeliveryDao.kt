package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.transfer.flash.core.persistence.db.entity.GroupDeliveryEntity
import kotlinx.coroutines.flow.Flow

@Dao
public interface GroupDeliveryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertAll(deliveries: List<GroupDeliveryEntity>)

    @Query(
        "SELECT * FROM group_deliveries WHERE messageId = :messageId " +
            "AND state != 'DELIVERED' ORDER BY nextAttemptAt, memberId",
    )
    public suspend fun pendingForMessage(messageId: String): List<GroupDeliveryEntity>

    @Query("SELECT COUNT(*) FROM group_deliveries WHERE messageId = :messageId")
    public suspend fun memberCount(messageId: String): Int

    @Query("SELECT COUNT(*) FROM group_deliveries WHERE messageId = :messageId AND state = 'DELIVERED'")
    public suspend fun deliveredCount(messageId: String): Int

    /**
     * Observable aggregate for outbound messages in one conversation that currently have
     * per-member delivery rows. Messages absent from `group_deliveries` are deliberately absent.
     */
    @Query(
        "SELECT gd.messageId AS messageId, " +
            "SUM(CASE WHEN gd.state = 'DELIVERED' THEN 1 ELSE 0 END) AS deliveredTo, " +
            "COUNT(*) AS deliveredTotal FROM group_deliveries gd " +
            "INNER JOIN messages m ON m.localId = gd.messageId " +
            "WHERE m.conversationId = :conversationId AND m.senderId = :selfId " +
            "GROUP BY gd.messageId",
    )
    public fun observeDeliveryCounts(
        conversationId: String,
        selfId: String,
    ): Flow<List<GroupDeliveryCount>>

    @Query(
        "UPDATE group_deliveries SET state = 'DELIVERED', deliveredAt = :deliveredAt " +
            "WHERE messageId = :messageId AND memberId = :memberId AND state != 'DELIVERED'",
    )
    public suspend fun markDelivered(messageId: String, memberId: String, deliveredAt: Long): Int

    @Query(
        "UPDATE group_deliveries SET state = :state, attempts = attempts + 1, nextAttemptAt = :nextAttemptAt " +
            "WHERE messageId = :messageId AND memberId = :memberId AND state != 'DELIVERED'",
    )
    public suspend fun reschedule(messageId: String, memberId: String, state: String, nextAttemptAt: Long)

    @Query(
        "UPDATE group_deliveries SET nextAttemptAt = :now, attempts = 0 " +
            "WHERE memberId = :memberId AND state != 'DELIVERED'",
    )
    public suspend fun makePendingDueForMember(memberId: String, now: Long)

    @Query("DELETE FROM group_deliveries WHERE messageId = :messageId")
    public suspend fun deleteForMessage(messageId: String)
}
