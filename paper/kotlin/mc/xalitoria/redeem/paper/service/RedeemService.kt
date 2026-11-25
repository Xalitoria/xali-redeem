package mc.xalitoria.redeem.paper.service

import com.xalitoria.util.message.fail
import com.xalitoria.util.message.send
import kotlinx.coroutines.launch
import mc.xalitoria.redeem.paper.XaliRedeem
import mc.xalitoria.redeem.paper.XaliRedeem.Companion.debug
import mc.xalitoria.redeem.paper.data.db.repository.HoldingRepository
import mc.xalitoria.redeem.paper.data.db.repository.PendingRecord
import mc.xalitoria.redeem.paper.data.db.repository.PendingRewardRepository
import mc.xalitoria.redeem.paper.data.model.Consume
import mc.xalitoria.redeem.paper.data.model.Holding
import mc.xalitoria.redeem.paper.voucher.Show
import mc.xalitoria.redeem.paper.voucher.Status
import mc.xalitoria.redeem.paper.voucher.Voucher
import net.xalitoria.redeem.PendingReward
import net.xalitoria.redeem.StoredReward
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** A voucher as one player sees it: what they hold of it, and whether they can redeem it. */
class Entry(val voucher: Voucher, val holding: Holding, val status: Status)

/** A stored dynamic pending reward as one player sees it. */
class PendingEntry(val stored: StoredReward, val status: Status) {
    val syntheticId: String get() = "$PENDING_PREFIX${stored.id}"

    companion object {
        const val PENDING_PREFIX = "pending:"
    }
}

/**
 * What redeeming goes through. The database is the truth ([HoldingRepository.consume] and [PendingRewardRepository.claim]
 * are single guarded statements, so two servers cannot use the last voucher or claim the same pending reward twice), while
 * online players' holdings and pending rewards are cached in memory for menus and placeholders on region threads.
 */
class RedeemService(private val plugin: XaliRedeem) {
    /** The vouchers that are loaded, by name. A reload replaces the map. */
    @Volatile
    var vouchers: Map<String, Voucher> = emptyMap()

    val rewards = RewardRunner(plugin)

    private val holdings = ConcurrentHashMap<UUID, Map<String, Holding>>()
    private val pending = ConcurrentHashMap<UUID, List<StoredReward>>()
    private val pendingById = ConcurrentHashMap<Long, StoredReward>()

    /** What players who are not online here hold, as the database had it when it was last read in the background; for the placeholders. */
    private val offline = OfflineCache<Map<String, Holding>>(plugin.scope, { OFFLINE_MAX_AGE_MILLIS }, { player, e -> plugin.logger.warning("Could not read the vouchers of $player: ${e.message}") }) {
        HoldingRepository.of(it)
    }

    /** Players whose redeem has not finished, so a double click cannot start a second one. */
    private val busy = ConcurrentHashMap.newKeySet<UUID>()

    private val msg get() = plugin.messageConfig.data

    // --- What a player holds -------------------------------------------------------------------------------

    fun holding(player: UUID, voucher: String): Holding = holdings[player]?.get(voucher) ?: Holding.NONE

    fun pendingOf(player: UUID): List<StoredReward> = pending[player].orEmpty()

    fun pendingById(id: Long): StoredReward? = pendingById[id]

    fun pendingCount(player: UUID): Int = pending[player]?.size ?: 0

    /**
     * Everything [player] holds, by voucher, for a placeholder: from memory for a player who is online here, and for anyone else what
     * the database held when it was last read in the background. Null until that first read is done.
     */
    fun held(player: UUID): Map<String, Holding>? = holdings[player] ?: offline.get(player)?.let { it.value ?: emptyMap() }

    /** Called on a timer: drops what was read of players who nobody has asked about lately. */
    fun sweep() = offline.sweep()

    /** Reads what [player] has from the database. Off the region thread. */
    suspend fun load(player: UUID) {
        val read = HoldingRepository.of(player)
        val records = PendingRewardRepository.listUnclaimed(player)
        val stored = records.map { it.toStoredReward() }
        // A player who left while it was read is not kept.
        if (plugin.server.getPlayer(player) != null) {
            holdings[player] = read
            val previous = pending.put(player, stored)
            previous?.forEach { pendingById.remove(it.id) }
            stored.forEach { pendingById[it.id] = it }
        }
    }

    /** A player who is online has changed, or may have changed, in the database (a give or take, perhaps on another server). */
    fun refresh(player: UUID) {
        offline.invalidate(player)
        if (holdings.containsKey(player) || plugin.server.getPlayer(player) != null) {
            plugin.scope.launch { runCatching { load(player) }.onFailure { plugin.logger.fail("Failed to read the vouchers of $player", it) } }
        }
    }

    fun forget(player: UUID) {
        holdings.remove(player)
        pending.remove(player)?.forEach { pendingById.remove(it.id) }
        offline.invalidate(player)
        busy.remove(player)
    }

    /** How many vouchers + pending rewards [player] holds. */
    fun total(player: UUID): Int = (holdings[player]?.values?.sumOf { it.amount } ?: 0) + pendingCount(player)

    // --- Can they redeem -----------------------------------------------------------------------------------

    fun status(player: Player, voucher: Voucher, now: Long = System.currentTimeMillis()): Status {
        heldStatus(holding(player.uniqueId, voucher.id), voucher, now)?.let { return it }
        for (condition in voucher.config.conditions) {
            if (!condition.test(player)) return Status.Blocked(msg.conditions.failure(condition, msg.words))
        }
        return Status.Ready
    }

    fun pendingStatus(player: Player, stored: StoredReward): Status {
        val needed = stored.reward.effectiveRequiredSlots()
        if (needed > 0 && freeSlots(player) < needed) {
            return Status.Blocked(msg.redeem.pendingNeedSpace(needed, stored.reward.title))
        }
        return Status.Ready
    }

    /** What stands in the way of redeeming [voucher] given what is [held] of it: the rules that need no player. Null when nothing does. */
    private fun heldStatus(held: Holding, voucher: Voucher, now: Long): Status? {
        val max = voucher.config.maxRedemptions
        if (voucher.needsHeld && held.amount < 1) return Status.NotHeld
        if (max > 0 && held.redeemed >= max) return Status.LimitReached
        val cooldown = voucher.cooldown.inWholeMilliseconds
        if (cooldown > 0 && held.last > 0 && now < held.last + cooldown) return Status.Cooling(held.last + cooldown - now)
        return null
    }

    /**
     * [status] for a player who is not online, for a placeholder. The conditions of a voucher test a player (a permission, a world,
     * a placeholder), so they cannot be tested and are left out: what is told is what holding, the limit and the cooldown say.
     * Null until what the player holds has been read.
     */
    fun statusOf(player: UUID, voucher: Voucher, now: Long = System.currentTimeMillis()): Status? {
        val held = held(player) ?: return null
        return heldStatus(held[voucher.id] ?: Holding.NONE, voucher, now) ?: Status.Ready
    }

    /** How many vouchers a player who is not online is to see, by the same rules as [entries] but without the conditions; null until read. */
    fun visibleTo(player: UUID): Int? {
        val now = System.currentTimeMillis()
        val held = held(player) ?: return null
        return vouchers.values.count { voucher ->
            when (voucher.config.show) {
                Show.ALWAYS -> true
                Show.HELD -> voucher.free || (held[voucher.id]?.amount ?: 0) > 0
                Show.READY -> (heldStatus(held[voucher.id] ?: Holding.NONE, voucher, now)) == null
            }
        }
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

    /** The dynamic pending rewards [player] holds. */
    fun pendingEntries(player: Player): List<PendingEntry> =
        pendingOf(player.uniqueId).map { PendingEntry(it, pendingStatus(player, it)) }

    // --- Storing & claiming dynamic pending rewards --------------------------------------------------------

    /** Stores an ad-hoc [reward] for [player] in the database and notifies them if online. */
    suspend fun givePending(player: UUID, reward: PendingReward): Long {
        val record = PendingRecord.fromReward(player, reward, System.currentTimeMillis())
        val id = PendingRewardRepository.insert(record)
        debug("Stored pending reward #{0} ({1}) for {2} from {3}", id, reward.title, player, reward.source ?: "api")
        val online = plugin.server.getPlayer(player)
        if (online != null) {
            if (reward.notify) {
                online.scheduler.run(plugin, { online send msg.redeem.pendingReceived(reward.title) }, null)
            }
            refresh(player)
        }
        return id
    }

    /** Claims a single pending reward [rewardId] for [player] with double-checked inventory space and atomic DB claim. */
    fun claimPending(player: Player, rewardId: Long, after: Runnable? = null): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        val cached = pendingById[rewardId]?.takeIf { it.player == player.uniqueId }
        if (cached != null) {
            val needed = cached.reward.effectiveRequiredSlots()
            if (needed > 0 && freeSlots(player) < needed) {
                player send msg.redeem.pendingNeedSpace(needed, cached.reward.title)
                after?.run()
                future.complete(false)
                return future
            }
        }
        if (!busy.add(player.uniqueId)) {
            player send msg.redeem.busy
            future.complete(false)
            return future
        }
        plugin.scope.launch {
            try {
                val claimed = PendingRewardRepository.claim(player.uniqueId, rewardId, System.currentTimeMillis())
                if (claimed == null) {
                    if (holdings.containsKey(player.uniqueId)) load(player.uniqueId)
                    onPlayer(player, after) {
                        player send msg.redeem.pendingNotFound
                        future.complete(false)
                    }
                    return@launch
                }
                val stored = claimed.toStoredReward()
                if (holdings.containsKey(player.uniqueId)) load(player.uniqueId)
                completePending(player, stored, after, future)
            } catch (e: Exception) {
                plugin.logger.fail("Failed to claim pending reward #$rewardId for ${player.name}", e)
                onPlayer(player, after) {
                    player send msg.commands.unexpectedError
                    future.complete(false)
                }
            }
        }
        return future
    }

    private fun completePending(player: Player, stored: StoredReward, after: Runnable?, future: CompletableFuture<Boolean>) {
        val needed = stored.reward.effectiveRequiredSlots()
        val scheduled = player.scheduler.run(plugin, {
            try {
                if (needed > 0 && freeSlots(player) < needed) {
                    player send msg.redeem.pendingNeedSpace(needed, stored.reward.title)
                    unclaimPending(player.uniqueId, stored.id)
                    future.complete(false)
                } else {
                    debug("{0} claims pending reward #{1} ({2})", player.name, stored.id, stored.reward.title)
                    rewards.runPending(player, stored.reward)
                    player send msg.redeem.redeemed(stored.reward.title)
                    busy.remove(player.uniqueId)
                    future.complete(true)
                }
            } catch (e: Exception) {
                plugin.logger.fail("Failed to deliver pending reward #${stored.id} to ${player.name}", e)
                player send msg.commands.unexpectedError
                busy.remove(player.uniqueId)
                future.complete(false)
            } finally {
                after?.run()
            }
        }, {
            unclaimPending(player.uniqueId, stored.id)
            future.complete(false)
        })
        if (scheduled == null) {
            unclaimPending(player.uniqueId, stored.id)
            future.complete(false)
        }
    }

    private fun unclaimPending(player: UUID, rewardId: Long) {
        plugin.scope.launch {
            try {
                PendingRewardRepository.unclaim(player, rewardId)
                if (holdings.containsKey(player)) load(player)
            } catch (e: Exception) {
                plugin.logger.fail("Failed to unclaim pending reward #$rewardId for $player", e)
            } finally {
                busy.remove(player)
            }
        }
    }

    /** Claims as many ready pending rewards as fit in [player]'s inventory. */
    fun claimAllPending(player: Player, after: Runnable? = null) {
        val list = pendingOf(player.uniqueId)
        if (list.isEmpty()) {
            player send msg.redeem.pendingNoneReady
            after?.run()
            return
        }
        if (!busy.add(player.uniqueId)) {
            player send msg.redeem.busy
            return
        }
        plugin.scope.launch {
            var claimedCount = 0
            try {
                val records = PendingRewardRepository.listUnclaimed(player.uniqueId)
                for (record in records) {
                    val needed = record.effectiveRequiredSlots()
                    val claimed = PendingRewardRepository.claim(player.uniqueId, record.id) ?: continue
                    val stored = claimed.toStoredReward()
                    val done = CompletableFuture<Boolean>()
                    val scheduled = player.scheduler.run(plugin, {
                        if (needed > 0 && freeSlots(player) < needed) {
                            done.complete(false)
                        } else {
                            rewards.runPending(player, stored.reward)
                            done.complete(true)
                        }
                    }, { done.complete(false) })
                    if (scheduled == null) {
                        PendingRewardRepository.unclaim(player.uniqueId, record.id)
                        break
                    }
                    val ok = done.join()
                    if (ok) {
                        claimedCount++
                    } else {
                        PendingRewardRepository.unclaim(player.uniqueId, record.id)
                        break
                    }
                }
                if (holdings.containsKey(player.uniqueId)) load(player.uniqueId)
                val finalCount = claimedCount
                onPlayer(player, after) {
                    if (finalCount > 0) player send msg.redeem.pendingClaimedAll(finalCount)
                    else player send msg.redeem.pendingNoneReady
                }
            } catch (e: Exception) {
                plugin.logger.fail("Failed to claim all pending rewards for ${player.name}", e)
                onPlayer(player, after) { player send msg.commands.unexpectedError }
            }
        }
    }

    // --- Redeeming -----------------------------------------------------------------------------------------

    /**
     * Redeems the voucher (or `pending:<id>` dynamic reward) called [id] for [player], from their own thread, and tells
     * them how it went. [after] runs on their thread once it is over, whichever way it went (the menu draws itself again there).
     */
    fun redeem(player: Player, id: String, after: Runnable? = null) {
        if (id.startsWith(PendingEntry.PENDING_PREFIX, ignoreCase = true)) {
            val rewardId = id.substring(PendingEntry.PENDING_PREFIX.length).toLongOrNull()
            if (rewardId == null) {
                player send msg.redeem.pendingNotFound
                after?.run()
                return
            }
            claimPending(player, rewardId, after)
            return
        }
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

    private fun freeSlots(player: Player): Int = player.inventory.storageContents.count { it == null || it.type.isAir }

    private companion object {
        const val OFFLINE_MAX_AGE_MILLIS = 30_000L
    }
}
