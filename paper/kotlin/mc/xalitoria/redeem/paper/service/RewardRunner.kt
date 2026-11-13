package mc.xalitoria.redeem.paper.service

import com.xalitoria.util.bukkit.runConsoleCommand
import com.xalitoria.util.message.send
import com.xalitoria.util.message.toComponent
import mc.xalitoria.redeem.paper.XaliRedeem
import mc.xalitoria.redeem.paper.XaliRedeem.Companion.debug
import mc.xalitoria.redeem.paper.external.Placeholders
import mc.xalitoria.redeem.paper.voucher.ItemReward
import mc.xalitoria.redeem.paper.voucher.Voucher
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Does what a voucher's `rewards` say. Called on the player's own thread. */
class RewardRunner(private val plugin: XaliRedeem) {
    /** [text] with what is known about the redeeming filled in, and PlaceholderAPI's placeholders when it is installed. */
    fun fill(player: Player, voucher: Voucher, text: String): String {
        val result = text
            .replace("{player}", player.name)
            .replace("{uuid}", player.uniqueId.toString())
            .replace("{voucher}", voucher.id)
            .replace("{name}", voucher.name)
        return if (plugin.hasPlaceholders) Placeholders.parse(player, result) else result
    }

    fun run(player: Player, voucher: Voucher) {
        val rewards = voucher.config.rewards
        val server = plugin.server

        // Console commands belong to the global region on Folia; they are queued in order.
        for (command in rewards.commands) {
            val line = fill(player, voucher, command).removePrefix("/")
            debug("Console runs for {0}: {1}", player.name, line)
            plugin.runConsoleCommand(line)
        }
        for (command in rewards.playerCommands) {
            val line = fill(player, voucher, command).removePrefix("/")
            debug("{0} runs: {1}", player.name, line)
            player.performCommand(line)
        }

        val leftover = player.inventory.addItem(*rewards.items.map { stack(player, voucher, it) }.toTypedArray())
        // The conditions asked for room, but a voucher without the condition (or an inventory that filled meanwhile) must not lose its items.
        for (stack in leftover.values) player.world.dropItemNaturally(player.location, stack)

        for (line in rewards.message) player send fill(player, voucher, line)
        if (rewards.broadcast.isNotEmpty()) {
            val lines = rewards.broadcast.map { fill(player, voucher, it).toComponent() }
            for (viewer in server.onlinePlayers) lines.forEach(viewer::sendMessage)
        }
    }

    private fun stack(player: Player, voucher: Voucher, reward: ItemReward): ItemStack {
        val stack = ItemStack(reward.type(), reward.amount)
        if (reward.name != null || reward.lore.isNotEmpty()) stack.editMeta { meta ->
            reward.name?.let { meta.customName(fill(player, voucher, it).toComponent().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)) }
            if (reward.lore.isNotEmpty()) {
                meta.lore(reward.lore.map { fill(player, voucher, it).toComponent().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE) })
            }
        }
        return stack
    }
}
