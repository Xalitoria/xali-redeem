package mc.xalitoria.redeem.paper.data.db.repository

import mc.xalitoria.redeem.paper.data.db.DatabaseManager.dbTransaction
import mc.xalitoria.redeem.paper.data.db.tables.HoldingTable
import mc.xalitoria.redeem.paper.data.model.Consume
import mc.xalitoria.redeem.paper.data.model.Holding
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.uuid.toKotlinUuid

/**
 * The vouchers players hold and have used. Every change is one guarded statement, so that servers sharing the database
 * cannot both redeem the last voucher, or both get past a cooldown.
 */
object HoldingRepository {
    private fun ResultRow.toHolding() = Holding(this[HoldingTable.amount], this[HoldingTable.redeemed], this[HoldingTable.last])

    private fun JdbcTransaction.read(player: UUID, voucher: String): Holding? =
        HoldingTable.selectAll()
            .where { (HoldingTable.player eq player.toKotlinUuid()) and (HoldingTable.voucher eq voucher) }
            .singleOrNull()?.toHolding()

    /** Everything [player] has, by voucher. */
    suspend fun of(player: UUID): Map<String, Holding> = dbTransaction {
        HoldingTable.selectAll().where { HoldingTable.player eq player.toKotlinUuid() }
            .associate { it[HoldingTable.voucher] to it.toHolding() }
    }

    suspend fun get(player: UUID, voucher: String): Holding = dbTransaction { read(player, voucher) ?: Holding.NONE }

    /** Gives [player] [amount] more of [voucher]; the number they hold now. */
    suspend fun give(player: UUID, voucher: String, amount: Int): Int {
        require(amount >= 1) { "amount must be at least 1" }
        return dbTransaction {
            val id = player.toKotlinUuid()
            ensure(player, voucher)
            HoldingTable.update({ (HoldingTable.player eq id) and (HoldingTable.voucher eq voucher) }) {
                it[HoldingTable.amount] = HoldingTable.amount + amount
            }
            read(player, voucher)!!.amount
        }
    }

    /** Takes up to [amount] of [voucher] from [player]; how many were taken, which is fewer when they held fewer. */
    suspend fun take(player: UUID, voucher: String, amount: Int): Int {
        require(amount >= 1) { "amount must be at least 1" }
        return dbTransaction {
            val id = player.toKotlinUuid()
            val where = { extra: org.jetbrains.exposed.v1.core.Op<Boolean> ->
                (HoldingTable.player eq id) and (HoldingTable.voucher eq voucher) and extra
            }
            if (HoldingTable.update({ where(HoldingTable.amount greaterEq amount) }) { it[HoldingTable.amount] = HoldingTable.amount - amount } > 0) {
                amount
            } else {
                // They hold fewer: the last of what they have goes. Another server may take it first, which leaves 0 to take.
                val held = read(player, voucher)?.amount ?: 0
                if (held > 0 && HoldingTable.update({ where(HoldingTable.amount eq held) }) { it[HoldingTable.amount] = 0 } > 0) held else 0
            }
        }
    }

    /**
     * Uses one use of [voucher] for [player]. A voucher that has to be held ([needsHeld]) takes one from what they hold; a free one
     * takes nothing. It is refused when they hold none, have used it [max] times already (0 for no limit), or used it less than
     * [cooldown] milliseconds before [now].
     */
    suspend fun consume(player: UUID, voucher: String, needsHeld: Boolean, cooldown: Long, max: Int, now: Long): Consume = dbTransaction {
        val id = player.toKotlinUuid()
        ensure(player, voucher)
        val before = read(player, voucher) ?: Holding.NONE
        val need = if (needsHeld) 1 else 0
        var where = (HoldingTable.player eq id) and (HoldingTable.voucher eq voucher) and (HoldingTable.amount greaterEq need)
        if (max > 0) where = where and (HoldingTable.redeemed less max)
        if (cooldown > 0) where = where and (HoldingTable.last lessEq now - cooldown)
        val used = HoldingTable.update({ where }) {
            it[HoldingTable.amount] = HoldingTable.amount - need
            it[HoldingTable.redeemed] = HoldingTable.redeemed + 1
            it[HoldingTable.last] = now
        }
        if (used > 0) return@dbTransaction Consume.Done(before.last)

        // Refused: say why, from what is there now (another server may have changed it since the read above).
        val held = read(player, voucher) ?: Holding.NONE
        when {
            held.amount < need -> Consume.NoneHeld
            max > 0 && held.redeemed >= max -> Consume.LimitReached
            else -> Consume.Cooling((held.last + cooldown - now).coerceAtLeast(1))
        }
    }

    /** Puts back what [consume] used, for a redemption that could not be finished. [previousLast] is what it returned. */
    suspend fun refund(player: UUID, voucher: String, needsHeld: Boolean, previousLast: Long) {
        dbTransaction {
            HoldingTable.update({ (HoldingTable.player eq player.toKotlinUuid()) and (HoldingTable.voucher eq voucher) and (HoldingTable.redeemed greaterEq 1) }) {
                if (needsHeld) it[HoldingTable.amount] = HoldingTable.amount + 1
                it[HoldingTable.redeemed] = HoldingTable.redeemed - 1
                it[HoldingTable.last] = previousLast
            }
        }
    }

    /** Forgets how often [player] used [voucher] and when, so that its limit and cooldown start again. What they hold stays. */
    suspend fun resetUsage(player: UUID, voucher: String): Boolean = dbTransaction {
        HoldingTable.update({ (HoldingTable.player eq player.toKotlinUuid()) and (HoldingTable.voucher eq voucher) }) {
            it[HoldingTable.redeemed] = 0
            it[HoldingTable.last] = 0
        } > 0
    }

    /** Makes the row if it is not there; another server may make it at the same moment, which does no harm. */
    private fun JdbcTransaction.ensure(player: UUID, voucher: String) {
        HoldingTable.insertIgnore {
            it[HoldingTable.player] = player.toKotlinUuid()
            it[HoldingTable.voucher] = voucher
        }
    }
}
