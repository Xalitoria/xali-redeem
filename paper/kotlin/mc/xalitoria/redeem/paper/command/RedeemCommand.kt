package mc.xalitoria.redeem.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.xalitoria.util.message.fail
import com.xalitoria.util.message.send
import com.xalitoria.util.message.toComponent
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kotlinx.coroutines.launch
import mc.xalitoria.redeem.paper.XaliRedeem
import mc.xalitoria.redeem.paper.XaliRedeem.Companion.debug
import mc.xalitoria.redeem.paper.data.constants.Permissions
import mc.xalitoria.redeem.paper.data.db.repository.HoldingRepository
import mc.xalitoria.redeem.paper.data.db.repository.PendingRewardRepository
import mc.xalitoria.redeem.paper.voucher.Voucher
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.max

/**
 * `/xaliredeem`, also `/redeem`: what a player does with their vouchers, and the admin commands. Everything that reads the
 * database runs off the region thread and answers when it has the result.
 */
class RedeemCommand(private val plugin: XaliRedeem) {
    val label = LABEL
    val aliases = listOf("redeem", "xredeem", "vouchers", "xvouchers", "voucher", "xvoucher")
    val description = "Redeem vouchers"

    private val msg get() = plugin.messageConfig.data
    private val service get() = plugin.service

    fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(label)
        .requires { it.sender.hasPermission(Permissions.USE) }
        .executes { run(it) { sender -> home(sender) } }
        .then(Commands.literal("help").executes { run(it) { sender -> sender send msg.commands.help(label, sender.hasPermission(Permissions.ADMIN)) } })
        .then(Commands.literal("list")
            .executes { run(it) { sender -> withSelf(sender) { player -> list(player, 1) } } }
            .then(page().executes { run(it) { sender -> withSelf(sender) { player -> list(player, it.getArgument("page", Int::class.java)) } } })
        )
        .then(Commands.literal("menu").executes { run(it) { sender -> withSelf(sender) { player -> openMenu(player, listInstead = false) } } })
        .then(Commands.literal("all").executes { run(it) { sender -> withSelf(sender) { player -> service.claimAllPending(player) } } })
        .then(admin())
        // Last, so that the words above are the commands and any other word is a voucher or pending:<id>.
        .then(Commands.argument("voucher", StringArgumentType.word())
            .suggests { context, builder ->
                val player = context.source.sender as? Player
                val names = if (player == null) service.vouchers.keys else {
                    service.entries(player).map { it.voucher.id } + service.pendingEntries(player).map { it.syntheticId }
                }
                names.filter { it.startsWith(builder.remaining, ignoreCase = true) }.forEach(builder::suggest)
                builder.buildFuture()
            }
            .executes { run(it) { sender -> withSelf(sender) { player -> service.redeem(player, it.getArgument("voucher", String::class.java)) } } }
        )

    private fun admin(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("admin")
        .requires { it.sender.hasPermission(Permissions.ADMIN) }
        .executes { run(it) { sender -> sender send msg.commands.help(label, true) } }
        .then(Commands.literal("give")
            .then(playerArgument().then(voucherArgument()
                .executes { run(it) { sender -> give(sender, it.getArgument("player", String::class.java), it.getArgument("voucher", String::class.java), 1) } }
                .then(amount().executes { run(it) { sender -> give(sender, it.getArgument("player", String::class.java), it.getArgument("voucher", String::class.java), it.getArgument("amount", Int::class.java)) } })
            ))
        )
        .then(Commands.literal("take")
            .then(playerArgument().then(voucherArgument()
                .executes { run(it) { sender -> take(sender, it.getArgument("player", String::class.java), it.getArgument("voucher", String::class.java), 1) } }
                .then(amount().executes { run(it) { sender -> take(sender, it.getArgument("player", String::class.java), it.getArgument("voucher", String::class.java), it.getArgument("amount", Int::class.java)) } })
            ))
        )
        .then(Commands.literal("balance")
            .then(playerArgument().executes { run(it) { sender -> balance(sender, it.getArgument("player", String::class.java)) } })
        )
        .then(Commands.literal("pending")
            .then(playerArgument().executes { run(it) { sender -> pending(sender, it.getArgument("player", String::class.java)) } })
        )
        .then(Commands.literal("reset")
            .then(playerArgument().then(voucherArgument().executes { run(it) { sender -> reset(sender, it.getArgument("player", String::class.java), it.getArgument("voucher", String::class.java)) } }))
        )
        .then(Commands.literal("open")
            .then(playerArgument().executes { run(it) { sender -> openFor(sender, it.getArgument("player", String::class.java)) } })
        )
        .then(Commands.literal("reload").executes { run(it) { sender -> reload(sender) } })
        .then(Commands.literal("debug").executes {
            run(it) { sender -> sender send msg.commands.debug.get(plugin.toggleDebug()) }
        })

    // --- Arguments ------------------------------------------------------------------------------------------

    private fun playerArgument(): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("player", StringArgumentType.word()).suggests { _, builder ->
            plugin.server.onlinePlayers.map { it.name }.filter { it.startsWith(builder.remaining, ignoreCase = true) }.forEach(builder::suggest)
            builder.buildFuture()
        }

    private fun voucherArgument(): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("voucher", StringArgumentType.word()).suggests { _, builder ->
            service.vouchers.keys.filter { it.startsWith(builder.remaining, ignoreCase = true) }.forEach(builder::suggest)
            builder.buildFuture()
        }

    private fun amount(): RequiredArgumentBuilder<CommandSourceStack, Int> = Commands.argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))

    private fun page(): RequiredArgumentBuilder<CommandSourceStack, Int> = Commands.argument("page", IntegerArgumentType.integer(1))

    /** Runs [block] for the sender of [context], and tells them if it goes wrong; Brigadier is told it went well either way. */
    private fun run(context: CommandContext<CommandSourceStack>, block: (CommandSender) -> Unit): Int {
        val sender = context.source.sender
        try {
            block(sender)
        } catch (e: Exception) {
            plugin.logger.fail("Failed to run a command of ${sender.name}", e)
            sender fail msg.commands.unexpectedError
        }
        return Command.SINGLE_SUCCESS
    }

    /** Runs [block] with the sender as a player, or tells a console that it has to be one. */
    private fun withSelf(sender: CommandSender, block: (Player) -> Unit) {
        if (sender is Player) block(sender) else sender fail msg.commands.playerOnly
    }

    /** Runs [block] off the region thread, and reports what goes wrong to [sender]. */
    private fun async(sender: CommandSender, block: suspend () -> Unit) {
        plugin.scope.launch {
            try {
                block()
            } catch (e: Exception) {
                plugin.logger.fail("Failed to run a command of ${sender.name}", e)
                sender fail msg.commands.unexpectedError
            }
        }
    }

    /** A player the plugin can name: online, a UUID, or a name this server has seen. */
    private class Target(val uuid: UUID, val name: String)

    private fun resolve(name: String): Target? {
        plugin.server.getPlayerExact(name)?.let { return Target(it.uniqueId, it.name) }
        runCatching { UUID.fromString(name) }.getOrNull()?.let { return Target(it, name) }
        val known = plugin.server.getOfflinePlayerIfCached(name) ?: return null
        return Target(known.uniqueId, known.name ?: name)
    }

    /** The voucher [id], or tells [sender] there is none and returns null. */
    private fun voucher(sender: CommandSender, id: String): Voucher? =
        service.vouchers[id.lowercase()] ?: run { sender send msg.redeem.unknownVoucher(id); null }

    // --- What a player does ---------------------------------------------------------------------------------

    /** `/redeem`: the menu where there is one, the list where there is not. */
    private fun home(sender: CommandSender) = withSelf(sender) { player -> openMenu(player, listInstead = true) }

    /**
     * Opens the voucher menu for [player] once what they hold has been read. With no menu (the option is off, XaliMenu is not
     * installed or has not read the file) [listInstead] decides between listing in chat and saying so.
     */
    private fun openMenu(player: Player, listInstead: Boolean) {
        val hook = plugin.menu
        if (hook == null || !plugin.config.data.menu.enabled) {
            if (listInstead) list(player, 1) else player send msg.commands.menuOff
            return
        }
        plugin.scope.launch {
            try {
                service.load(player.uniqueId)
            } catch (e: Exception) {
                plugin.logger.fail("Failed to read the vouchers of ${player.name}", e)
            }
            player.scheduler.run(plugin, {
                if (!hook.open(player)) {
                    debug("The menu '{0}' is not loaded in XaliMenu; listing instead", plugin.config.data.menu.name)
                    if (listInstead) list(player, 1) else player send msg.commands.menuOff
                }
            }, null)
        }
    }

    /** The vouchers and pending rewards [player] can see, a page at a time, each one a click away from being redeemed. */
    private fun list(player: Player, page: Int) {
        val perPage = plugin.config.data.perPage
        data class Row(val id: String, val name: String, val amount: Int, val status: String)
        val rows = buildList {
            for (p in service.pendingEntries(player)) {
                val count = p.stored.reward.items.sumOf { it.amount }.coerceAtLeast(1)
                add(Row(p.syntheticId, p.stored.reward.title, count, service.describe(p.status)))
            }
            for (entry in service.entries(player).sortedBy { it.voucher.id }) {
                add(Row(entry.voucher.id, entry.voucher.name, entry.holding.amount, service.describe(entry.status)))
            }
        }
        val pages = max(1, (rows.size + perPage - 1) / perPage)
        val shown = page.coerceIn(1, pages)
        player send msg.list.header(shown, pages)
        if (rows.isEmpty()) player send msg.list.empty
        for (row in rows.drop((shown - 1) * perPage).take(perPage)) {
            player.sendMessage(
                msg.list.entry(row.name, row.amount, row.status).toComponent()
                    .clickEvent(ClickEvent.runCommand("/$label ${row.id}"))
                    .hoverEvent(HoverEvent.showText(msg.list.clickToRedeem.toComponent()))
            )
        }
    }

    // --- Admin ----------------------------------------------------------------------------------------------

    private fun give(sender: CommandSender, name: String, id: String, amount: Int) = async(sender) {
        val voucher = voucher(sender, id) ?: return@async
        if (voucher.free) return@async sender send msg.admin.freeVoucher(voucher.name)
        val target = resolve(name) ?: return@async sender fail msg.commands.playerNotFound(name)
        val held = HoldingRepository.give(target.uuid, voucher.id, amount)
        debug("{0} gave {1} x {2} to {3}", sender.name, amount, voucher.id, target.name)
        sender send msg.admin.given(target.name, amount, voucher.name, held)
        plugin.server.getPlayer(target.uuid)?.let { it send msg.admin.received(amount, voucher.name) }
        service.refresh(target.uuid)
    }

    private fun take(sender: CommandSender, name: String, id: String, amount: Int) = async(sender) {
        val voucher = voucher(sender, id) ?: return@async
        if (voucher.free) return@async sender send msg.admin.freeVoucher(voucher.name)
        val target = resolve(name) ?: return@async sender fail msg.commands.playerNotFound(name)
        val taken = HoldingRepository.take(target.uuid, voucher.id, amount)
        if (taken == 0) return@async sender send msg.admin.noneToTake(target.name, voucher.name)
        debug("{0} took {1} x {2} from {3}", sender.name, taken, voucher.id, target.name)
        sender send msg.admin.taken(target.name, taken, voucher.name, HoldingRepository.get(target.uuid, voucher.id).amount)
        service.refresh(target.uuid)
    }

    private fun balance(sender: CommandSender, name: String) = async(sender) {
        val target = resolve(name) ?: return@async sender fail msg.commands.playerNotFound(name)
        val now = System.currentTimeMillis()
        val rows = HoldingRepository.of(target.uuid).filterValues { it.amount > 0 || it.redeemed > 0 }.toSortedMap()
        sender send msg.admin.balanceHeader(target.name)
        if (rows.isEmpty()) sender send msg.admin.balanceEmpty
        for ([id, held] in rows) {
            val last = if (held.last > 0) msg.admin.ago(msg.words.duration((now - held.last) / 1000)) else msg.admin.never
            sender send msg.admin.balanceEntry(service.vouchers[id]?.id ?: id, held.amount, held.redeemed, last)
        }
    }

    private fun pending(sender: CommandSender, name: String) = async(sender) {
        val target = resolve(name) ?: return@async sender fail msg.commands.playerNotFound(name)
        val records = PendingRewardRepository.listUnclaimed(target.uuid)
        sender send msg.admin.pendingHeader(target.name)
        if (records.isEmpty()) sender send msg.admin.pendingEmpty
        for (record in records) {
            sender send msg.admin.pendingEntry(record.id, record.title, record.itemBytes.size, record.source ?: "api")
        }
    }

    private fun reset(sender: CommandSender, name: String, id: String) = async(sender) {
        val voucher = voucher(sender, id) ?: return@async
        val target = resolve(name) ?: return@async sender fail msg.commands.playerNotFound(name)
        if (!HoldingRepository.resetUsage(target.uuid, voucher.id)) return@async sender send msg.admin.nothingToReset(target.name, voucher.name)
        sender send msg.admin.reset(target.name, voucher.name)
        service.refresh(target.uuid)
    }

    private fun openFor(sender: CommandSender, name: String) {
        val player = plugin.server.getPlayerExact(name) ?: return run { sender fail msg.admin.notOnline(name) }
        openMenu(player, listInstead = true)
        sender send msg.admin.opened(player.name)
    }

    private fun reload(sender: CommandSender) {
        try {
            sender send msg.commands.reload(plugin.reload())
        } catch (e: Exception) {
            plugin.logger.fail("Failed to reload", e)
            sender fail msg.commands.reloadFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        const val LABEL = "xaliredeem"

        /** The most a give or take moves at once. */
        private const val MAX_AMOUNT = 100_000
    }
}
