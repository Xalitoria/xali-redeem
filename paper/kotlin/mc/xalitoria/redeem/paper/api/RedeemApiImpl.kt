package mc.xalitoria.redeem.paper.api

import kotlinx.coroutines.launch
import mc.xalitoria.redeem.paper.XaliRedeem
import mc.xalitoria.redeem.paper.data.db.repository.HoldingRepository
import mc.xalitoria.redeem.paper.data.db.repository.PendingRewardRepository
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.xalitoria.redeem.PendingReward
import net.xalitoria.redeem.StoredReward
import net.xalitoria.redeem.XaliRedeemApi
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Implementation of [XaliRedeemApi] registered with Bukkit's services manager.
 */
class RedeemApiImpl(private val plugin: XaliRedeem) : XaliRedeemApi {
    private val service get() = plugin.service

    override fun getVouchers(): Set<String> = service.vouchers.keys.toSet()

    override fun hasVoucher(voucherId: String): Boolean = voucherId.lowercase() in service.vouchers

    override fun giveVoucher(player: UUID, voucherId: String, amount: Int): CompletableFuture<Int> = future {
        val id = voucherId.lowercase()
        val held = HoldingRepository.give(player, id, amount)
        service.refresh(player)
        held
    }

    override fun takeVoucher(player: UUID, voucherId: String, amount: Int): CompletableFuture<Int> = future {
        val id = voucherId.lowercase()
        val taken = HoldingRepository.take(player, id, amount)
        service.refresh(player)
        taken
    }

    override fun getVoucherAmount(player: UUID, voucherId: String): CompletableFuture<Int> = future {
        HoldingRepository.get(player, voucherId.lowercase()).amount
    }

    override fun giveReward(player: UUID, reward: PendingReward): CompletableFuture<Long> = future {
        service.givePending(player, reward)
    }

    override fun giveItem(player: UUID, item: ItemStack, title: String?, source: String?): CompletableFuture<Long> {
        val displayTitle = title ?: itemDisplayName(item)
        val reward = PendingReward.builder(displayTitle)
            .icon(item)
            .addItem(item)
            .source(source)
            .build()
        return giveReward(player, reward)
    }

    override fun giveItems(player: UUID, items: List<ItemStack>, title: String, source: String?): CompletableFuture<Long> {
        val builder = PendingReward.builder(title)
            .items(items)
            .source(source)
        items.firstOrNull()?.let { builder.icon(it) }
        return giveReward(player, builder.build())
    }

    override fun getPendingRewards(player: UUID): CompletableFuture<List<StoredReward>> = future {
        PendingRewardRepository.listUnclaimed(player).map { it.toStoredReward() }
    }

    override fun getPendingCount(player: UUID): Int = service.pendingCount(player)

    override fun claimPendingReward(player: Player, rewardId: Long): CompletableFuture<Boolean> =
        service.claimPending(player, rewardId)

    override fun removePendingReward(player: UUID, rewardId: Long): CompletableFuture<Boolean> = future {
        val removed = PendingRewardRepository.remove(player, rewardId)
        if (removed) service.refresh(player)
        removed
    }

    override fun openMenu(player: Player): Boolean {
        val hook = plugin.menu
        if (hook == null || !plugin.config.data.menu.enabled) return false
        return hook.open(player)
    }

    private fun <T> future(block: suspend () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        plugin.scope.launch {
            try {
                result.complete(block())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    private fun itemDisplayName(item: ItemStack): String {
        val custom = item.itemMeta?.customName()
        if (custom != null) return PlainTextComponentSerializer.plainText().serialize(custom)
        return item.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
