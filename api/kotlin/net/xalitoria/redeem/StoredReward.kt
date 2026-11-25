package net.xalitoria.redeem

import java.util.UUID

/**
 * A [PendingReward] persisted in XaliRedeem's database with its assigned [id] and creation timestamp.
 */
class StoredReward(
    val id: Long,
    val player: UUID,
    val reward: PendingReward,
    val createdAt: Long,
)
