package net.xalitoria.redeem

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * The public API of XaliRedeem, registered in Bukkit's services manager.
 *
 * Supports both static configured vouchers (`vouchers/<name>.yml`) and dynamic [PendingReward] entries that are not
 * defined in XaliRedeem's config (such as full-inventory overflow items or custom rewards handed off by another plugin).
 *
 * Uses JDK and Bukkit types only so plugins carrying their own Kotlin runtime can call it without linkage issues.
 */
interface XaliRedeemApi {
    /** The ids of all currently loaded static vouchers. */
    fun getVouchers(): Set<String>

    /** Whether a static voucher with [voucherId] is loaded. */
    fun hasVoucher(voucherId: String): Boolean

    /** Gives [amount] of a static voucher to [player]; completes with the player's new balance. */
    fun giveVoucher(player: UUID, voucherId: String, amount: Int): CompletableFuture<Int>

    /** Takes up to [amount] of a static voucher from [player]; completes with how many were taken. */
    fun takeVoucher(player: UUID, voucherId: String, amount: Int): CompletableFuture<Int>

    /** Reads how many of [voucherId] [player] currently holds. */
    fun getVoucherAmount(player: UUID, voucherId: String): CompletableFuture<Int>

    /** Stores an ad-hoc [PendingReward] for [player] in XaliRedeem; completes with its persisted id. */
    fun giveReward(player: UUID, reward: PendingReward): CompletableFuture<Long>

    /** Convenience method to store a single overflow [item] for [player] (with optional [title] and [source]). */
    fun giveItem(player: UUID, item: ItemStack, title: String?, source: String?): CompletableFuture<Long>

    /** Convenience method to store multiple overflow [items] for [player] as one claimable reward. */
    fun giveItems(player: UUID, items: List<ItemStack>, title: String, source: String?): CompletableFuture<Long>

    /** Lists all unclaimed, unexpired [StoredReward]s for [player] from the database. */
    fun getPendingRewards(player: UUID): CompletableFuture<List<StoredReward>>

    /** Cached count of pending dynamic rewards for an online player (0 if offline or not loaded yet). */
    fun getPendingCount(player: UUID): Int

    /** Claims the pending reward [rewardId] for [player]; completes with true if claimed. */
    fun claimPendingReward(player: Player, rewardId: Long): CompletableFuture<Boolean>

    /** Removes an unclaimed pending reward without granting it; completes with true if removed. */
    fun removePendingReward(player: UUID, rewardId: Long): CompletableFuture<Boolean>

    /** Opens the redeem menu (or chat list fallback) for [player]; returns true if the menu was opened. */
    fun openMenu(player: Player): Boolean

    companion object {
        @JvmStatic
        fun find(): XaliRedeemApi? = Bukkit.getServicesManager().load(XaliRedeemApi::class.java)

        @JvmStatic
        fun get(): XaliRedeemApi = find()
            ?: error("XaliRedeem is not enabled; add it as a dependency of your plugin")
    }
}
