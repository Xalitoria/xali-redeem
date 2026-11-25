package net.xalitoria.redeem

import org.bukkit.inventory.ItemStack
import java.util.Collections

/**
 * An ad-hoc reward stored for a player in XaliRedeem, without needing a static voucher file in `vouchers/`.
 *
 * Plugins (such as XaliCrates on full inventories or dynamic rewards) build a [PendingReward] via [builder] and hand it
 * to [XaliRedeemApi.giveReward]. The items, icon, commands and messages are persisted in XaliRedeem's database so the
 * player can claim them later from `/redeem` or the redeem menu once they have inventory space.
 *
 * Only JDK and Bukkit types appear in the public surface, so plugins with their own Kotlin runtime can call it safely.
 */
class PendingReward internal constructor(
    /** Display name shown in `/redeem` and the redeem menu. Colors, hex and MiniMessage work. */
    val title: String,
    /** Description lines shown under the title in the menu. */
    val description: List<String>,
    /** Optional icon shown in the menu; when null, the first item in [items] (or a chest) is used. */
    private val iconItem: ItemStack?,
    /** Exact Bukkit items granted when the player claims this reward. */
    private val rewardItems: List<ItemStack>,
    /** Console commands run without a leading slash (`{player}` and `{uuid}` are substituted). */
    val commands: List<String>,
    /** Commands run by the claiming player as themselves. */
    val playerCommands: List<String>,
    /** Messages sent to the player when claimed. */
    val messages: List<String>,
    /** Messages broadcast to all online players when claimed. */
    val broadcasts: List<String>,
    /** Empty inventory slots required before claiming (`-1` auto-computes from [items]). */
    val requiredSlots: Int,
    /** Optional origin tag (e.g. `xalicrates:master`) shown in admin listings. */
    val source: String?,
    /** Optional expiry timestamp in epoch milliseconds (`0` means it never expires). */
    val expiresAt: Long,
    /** Whether to send a chat notification if the player is currently online. */
    val notify: Boolean,
) {
    val icon: ItemStack? get() = iconItem?.clone()

    val items: List<ItemStack> get() = Collections.unmodifiableList(rewardItems.map { it.clone() })

    /** Effective empty inventory slots required when claiming this reward. */
    fun effectiveRequiredSlots(): Int = if (requiredSlots >= 0) requiredSlots else rewardItems.size

    class Builder(private val title: String) {
        private val description = ArrayList<String>()
        private var icon: ItemStack? = null
        private val items = ArrayList<ItemStack>()
        private val commands = ArrayList<String>()
        private val playerCommands = ArrayList<String>()
        private val messages = ArrayList<String>()
        private val broadcasts = ArrayList<String>()
        private var requiredSlots: Int = -1
        private var source: String? = null
        private var expiresAt: Long = 0L
        private var notify: Boolean = true

        fun description(lines: List<String>): Builder {
            description.clear()
            description.addAll(lines)
            return this
        }

        fun addDescription(line: String): Builder {
            description.add(line)
            return this
        }

        fun icon(item: ItemStack?): Builder {
            this.icon = item?.clone()
            return this
        }

        fun items(stacks: List<ItemStack>): Builder {
            items.clear()
            for (stack in stacks) items.add(stack.clone())
            return this
        }

        fun addItem(stack: ItemStack): Builder {
            items.add(stack.clone())
            return this
        }

        fun commands(list: List<String>): Builder {
            commands.clear()
            commands.addAll(list)
            return this
        }

        fun addCommand(command: String): Builder {
            commands.add(command)
            return this
        }

        fun playerCommands(list: List<String>): Builder {
            playerCommands.clear()
            playerCommands.addAll(list)
            return this
        }

        fun addPlayerCommand(command: String): Builder {
            playerCommands.add(command)
            return this
        }

        fun messages(list: List<String>): Builder {
            messages.clear()
            messages.addAll(list)
            return this
        }

        fun addMessage(message: String): Builder {
            messages.add(message)
            return this
        }

        fun broadcasts(list: List<String>): Builder {
            broadcasts.clear()
            broadcasts.addAll(list)
            return this
        }

        fun addBroadcast(broadcast: String): Builder {
            broadcasts.add(broadcast)
            return this
        }

        fun requiredSlots(slots: Int): Builder {
            this.requiredSlots = slots
            return this
        }

        fun source(tag: String?): Builder {
            this.source = tag
            return this
        }

        fun expiresAt(epochMillis: Long): Builder {
            this.expiresAt = epochMillis
            return this
        }

        fun notifyPlayer(notify: Boolean): Builder {
            this.notify = notify
            return this
        }

        fun build(): PendingReward = PendingReward(
            title = title,
            description = Collections.unmodifiableList(ArrayList(description)),
            iconItem = icon?.clone(),
            rewardItems = items.map { it.clone() },
            commands = Collections.unmodifiableList(ArrayList(commands)),
            playerCommands = Collections.unmodifiableList(ArrayList(playerCommands)),
            messages = Collections.unmodifiableList(ArrayList(messages)),
            broadcasts = Collections.unmodifiableList(ArrayList(broadcasts)),
            requiredSlots = requiredSlots,
            source = source,
            expiresAt = expiresAt,
            notify = notify,
        )
    }

    companion object {
        @JvmStatic
        fun builder(title: String): Builder = Builder(title)
    }
}
