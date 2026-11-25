package mc.xalitoria.redeem.paper.data.db.tables

import mc.xalitoria.redeem.paper.data.db.DatabaseManager
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Ad-hoc rewards stored for a player through [net.xalitoria.redeem.XaliRedeemApi] that are not static files in `vouchers/`.
 * Each row is claimed at most once via an atomic guarded update (`claimed = false -> true`).
 */
object PendingRewardTable : LongIdTable(DatabaseManager.tableName("pending")) {
    val player = uuid("player").index()
    val origin = varchar("source", 128).nullable()
    val title = varchar("title", 256)
    val description = text("description")
    val iconBytes = binary("icon_bytes").nullable()
    val itemsBytes = binary("items_bytes")
    val commands = text("commands")
    val playerCommands = text("player_commands")
    val messages = text("messages")
    val broadcasts = text("broadcasts")
    val requiredSlots = integer("required_slots").default(-1)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at").default(0L)
    val claimed = bool("claimed").default(false)
}
