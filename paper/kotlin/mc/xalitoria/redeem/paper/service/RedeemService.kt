package mc.xalitoria.redeem.paper.service

import com.xalitoria.util.message.fail
import com.xalitoria.util.message.send
import kotlinx.coroutines.launch
import mc.xalitoria.redeem.paper.XaliRedeem
import mc.xalitoria.redeem.paper.XaliRedeem.Companion.debug
import mc.xalitoria.redeem.paper.data.db.repository.HoldingRepository
import mc.xalitoria.redeem.paper.data.model.Consume
import mc.xalitoria.redeem.paper.data.model.Holding
import mc.xalitoria.redeem.paper.voucher.Show
import mc.xalitoria.redeem.paper.voucher.Status
import mc.xalitoria.redeem.paper.voucher.Voucher
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A voucher as one player sees it: what they hold of it, and whether they can redeem it. */
class Entry(val voucher: Voucher, val holding: Holding, val status: Status)

/**
 * What redeeming goes through. The database is the truth ([HoldingRepository.consume] is one guarded statement, so two servers
 * cannot both use the last voucher), but the menu and the placeholders are asked on region threads, so what each online player
 * holds is kept in memory ([holdings]) and brought up to date when they join, redeem, or are given or taken something.
 */
class RedeemService(private val plugin: XaliRedeem) {
    /** The vouchers that are loaded, by name. A reload replaces the map. */
    @Volatile
    var vouchers: Map<String, Voucher> = emptyMap()

    val rewards = RewardRunner(plugin)

    private val holdings = ConcurrentHashMap<UUID, Map<String, Holding>>()

    /** Players whose redeem has not finished, so a double click cannot start a second one. */
    private val busy = ConcurrentHashMap.newKeySet<UUID>()

    private val msg get() = plugin.messageConfig.data

    // --- What a player holds -------------------------------------------------------------------------------

    fun holding(player: UUID, voucher: String): Holding = holdings[player]?.get(voucher) ?: Holding.NONE

    /** Reads what [player] has from the database. Off the region thread. */
    suspend fun load(player: UUID) {
        val read = HoldingRepository.of(player)
        // A player who left while it was read is not kept.
        if (plugin.server.getPlayer(player) != null) holdings[player] = read
    }

    /** A player who is online has changed, or may have changed, in the database (a give or take, perhaps on another server). */
    fun refresh(player: UUID) {
        if (holdings.containsKey(player)) plugin.scope.launch { runCatching { load(player) }.onFailure { plugin.logger.fail("Failed to read the vouchers of $player", it) } }
    }

    fun forget(player: UUID) {
        holdings.remove(player)
        busy.remove(player)
    }

    /** How many vouchers (of every kind) [player] holds. */
    fun total(player: UUID): Int = holdings[player]?.values?.sumOf { it.amount } ?: 0

    // --- Can they redeem -----------------------------------------------------------------------------------

    fun status(player: Player, voucher: Voucher, now: Long = System.currentTimeMillis()): Status {
        val held = holding(player.uniqueId, voucher.id)
        val max = voucher.config.maxRedemptions
        if (voucher.needsHeld && held.amount < 1) return Status.NotHeld
        if (max > 0 && held.redeemed >= max) return Status.LimitReached
        val cooldown = voucher.cooldown.inWholeMilliseconds
        if (cooldown > 0 && held.last > 0 && now < held.last + cooldown) return Status.Cooling(held.last + cooldown - now)
        for (condition in voucher.config.conditions) {
            if (!condition.test(player)) return Status.Blocked(msg.conditions.failure(condition, msg.words))
        }
        return Status.Ready
    }

    /** What [status] says, as the text of the menu and the list. */
    fun describe(status: Status): String = when (status) {
        Status.Ready -> msg.statuses.ready
        Status.NotHeld -> msg.statuses.notHeld
        Status.LimitReached -> msg.statuses.limitReached
        is Status.Cooling -> msg.statuses.cooling(msg.words.duration((status.remainingMillis + 999) / 1000))
        is Status.Blocked -> msg.statuses.blocked(status.reason)
    }

    /** The vouchers [player] is to see, by name: each one's `show` decides. */
    fun entries(player: Player): List<Entry> {
        val now = System.currentTimeMillis()
        return vouchers.values.mapNotNull { voucher ->
            val held = holding(player.uniqueId, voucher.id)
            val status = status(player, voucher, now)
            val shown = when (voucher.config.show) {
                Show.ALWAYS -> true
                Show.HELD -> voucher.free || held.amount > 0
                Show.READY -> status == Status.Ready
            }
            if (shown) Entry(voucher, held, status) else null
        }
    }

    // --- Redeeming -----------------------------------------------------------------------------------------

    /**
     * Redeems the voucher called [id] for [player], from their own thread, and tells them how it went. [after] runs on their
     * thread once it is over, whichever way it went (the menu draws itself again there).
     */
    fun redeem(player: Player, id: String, after: Runnable? = null) {
        val voucher = vouchers[id.lowercase()]
        if (voucher == null) {
            player send msg.redeem.unknownVoucher(id)
            after?.run()
            return
        }
        val refused = refusal(voucher, status(player, voucher))
        if (refused != null) {
            player send refused
            after?.run()
            return
        }
        if (!busy.add(player.uniqueId)) {
            player send msg.redeem.busy
            return
        }
        plugin.scope.launch {
            try {
                val outcome = HoldingRepository.consume(
                    player.uniqueId, voucher.id, voucher.needsHeld,
                    voucher.cooldown.inWholeMilliseconds, voucher.config.maxRedemptions, System.currentTimeMillis(),
                )
                // Brought up to date whatever happened: the database may know better than the memory did.
                if (holdings.containsKey(player.uniqueId)) load(player.uniqueId)
                if (outcome is Consume.Done) complete(player, voucher, outcome.previousLast, after)
                else onPlayer(player, after) { player send turnedAway(voucher, outcome) }
            } catch (e: Exception) {
                plugin.logger.fail("Failed to redeem ${voucher.id} for ${player.name}", e)
                onPlayer(player, after) { player send msg.commands.unexpectedError }
            }
        }
    }

    /** What to tell a player whose redeem the database refused ([outcome] is not [Consume.Done]). */
    private fun turnedAway(voucher: Voucher, outcome: Consume): String = when (outcome) {
        is Consume.Done, Consume.NoneHeld -> msg.redeem.notHeld(voucher.name)
        Consume.LimitReached -> msg.redeem.limitReached(voucher.name)
        is Consume.Cooling -> msg.redeem.cooling(voucher.name, msg.words.duration((outcome.remainingMillis + 999) / 1000))
    }

    /** What to tell a player whose [status] does not allow redeeming [voucher]; null when it does. */
    private fun refusal(voucher: Voucher, status: Status): String? = when (status) {
        Status.Ready -> null
        Status.NotHeld -> msg.redeem.notHeld(voucher.name)
        Status.LimitReached -> msg.redeem.limitReached(voucher.name)
        is Status.Cooling -> msg.redeem.cooling(voucher.name, msg.words.duration((status.remainingMillis + 999) / 1000))
        is Status.Blocked -> status.reason
    }

    /**
     * The voucher has been used up; gives what it is for on the player's thread. The conditions are looked at once more first,
     * since time passed (inventory space, above all, is lost to a second redeem started at the same moment): if one fails the
     * voucher is given back.
     */
    private fun complete(player: Player, voucher: Voucher, previousLast: Long, after: Runnable?) {
        val scheduled = player.scheduler.run(plugin, {
            try {
                val blocked = voucher.config.conditions.firstOrNull { !it.test(player) }
                if (blocked != null) {
                    player send msg.redeem.interrupted(voucher.name)
                    player send msg.conditions.failure(blocked, msg.words)
                    giveBack(player.uniqueId, voucher, previousLast)
                } else {
                    debug("{0} redeems {1}", player.name, voucher.id)
                    rewards.run(player, voucher)
                    player send msg.redeem.redeemed(voucher.name)
                    busy.remove(player.uniqueId)
                }
            } catch (e: Exception) {
                plugin.logger.fail("Failed to give the rewards of ${voucher.id} to ${player.name}", e)
                player send msg.commands.unexpectedError
                busy.remove(player.uniqueId)
            } finally {
                after?.run()
            }
        }, { giveBack(player.uniqueId, voucher, previousLast) })
        // The player left between the database and here: nothing was given, so the voucher is theirs again.
        if (scheduled == null) giveBack(player.uniqueId, voucher, previousLast)
    }

    private fun giveBack(player: UUID, voucher: Voucher, previousLast: Long) {
        debug("Giving {0} back to {1}", voucher.id, player)
        plugin.scope.launch {
            try {
                HoldingRepository.refund(player, voucher.id, voucher.needsHeld, previousLast)
                if (holdings.containsKey(player)) load(player)
            } catch (e: Exception) {
                plugin.logger.fail("Failed to give $player their ${voucher.id} voucher back", e)
            } finally {
                busy.remove(player)
            }
        }
    }

    /** Runs [task] on the player's thread, then [after]; nothing runs if they have left. */
    private fun onPlayer(player: Player, after: Runnable?, task: () -> Unit) {
        player.scheduler.run(plugin, { try { task() } finally { after?.run(); busy.remove(player.uniqueId) } }, { busy.remove(player.uniqueId) })
            ?: busy.remove(player.uniqueId)
    }
}
