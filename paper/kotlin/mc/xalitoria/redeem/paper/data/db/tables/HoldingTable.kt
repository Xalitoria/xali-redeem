package mc.xalitoria.redeem.paper.data.db.tables

import mc.xalitoria.redeem.paper.data.db.DatabaseManager
import org.jetbrains.exposed.v1.core.Table

/**
 * What a player has of one voucher: how many they hold ([amount]), how many times they redeemed it ([redeemed]) and when they
 * last did ([last], milliseconds since the epoch; 0 for never). A voucher that is free to anyone has a row once it was redeemed.
 */
object HoldingTable : Table(DatabaseManager.tableName("holding")) {
    val player = uuid("player")
    val voucher = varchar("voucher", 64)
    val amount = integer("amount").default(0)
    val redeemed = integer("redeemed").default(0)
    val last = long("last").default(0)

    override val primaryKey = PrimaryKey(player, voucher)
}
