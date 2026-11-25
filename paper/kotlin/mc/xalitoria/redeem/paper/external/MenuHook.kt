package mc.xalitoria.redeem.paper.external

import mc.xalitoria.redeem.paper.XaliRedeem
import mc.xalitoria.redeem.paper.service.PendingEntry
import mc.xalitoria.redeem.paper.voucher.Status
import net.xalitoria.menu.Action
import net.xalitoria.menu.Catalog
import net.xalitoria.menu.ItemSource
import net.xalitoria.menu.MenuContext
import net.xalitoria.menu.XaliMenuApi
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * XaliMenu's side of this plugin. It is only made when `menu.enabled` is on and XaliMenu is installed, because it loads XaliMenu's
 * classes; everything else works without it. It adds:
 * - catalog `xaliredeem:vouchers`, listing both static vouchers and dynamic pending rewards (`pending:<id>`)
 * - action `xaliredeem:redeem`, redeeming a voucher or claiming a pending reward
 * - item source `xaliredeem`, resolving `xaliredeem:pending/<id>` to the exact stored icon/item of a pending reward
 */
class MenuHook(private val plugin: XaliRedeem) {
    private val api = XaliMenuApi.get()

    init {
        api.registerCatalog(plugin, CATALOG, VALUES) { VoucherCatalog(plugin) }
        api.registerAction(plugin, ACTION) { settings -> RedeemAction(plugin, settings.requireString("voucher")) }
        api.registerItemSource(plugin, PendingItemSource(plugin))
    }

    /** Whether the menu exists, which it doesn't until XaliMenu has read its folder. */
    fun hasMenu(): Boolean = plugin.config.data.menu.name in api.menus

    /** Opens the menu for [player] on their next tick; false if there is no such menu. */
    fun open(player: Player): Boolean {
        if (!hasMenu()) return false
        api.open(player, plugin.config.data.menu.name)
        return true
    }

    /** Writes the menu into XaliMenu's folder where it is missing; false if XaliMenu keeps no folder to write into. */
    fun writeMenu(): Boolean {
        val directory = plugin.server.pluginManager.getPlugin("XaliMenu")?.dataPath?.resolve("menus") ?: return false
        val file = directory.resolve("${plugin.config.data.menu.name}.conf")
        if (file.exists()) return true
        val text = MenuHook::class.java.getResourceAsStream("/menu/redeem.conf")?.use { String(it.readAllBytes(), Charsets.UTF_8) } ?: return false
        directory.createDirectories()
        file.writeText(text)
        return true
    }

    /** The vouchers and pending dynamic rewards [MenuContext.player] can see, one element each. */
    private class VoucherCatalog(private val plugin: XaliRedeem) : Catalog {
        override fun elements(ctx: MenuContext): List<Map<String, String>> {
            val staticEntries = plugin.service.entries(ctx.player).map { entry ->
                mapOf(
                    "id" to entry.voucher.id,
                    "name" to entry.voucher.name,
                    "material" to entry.voucher.config.material,
                    "amount" to entry.holding.amount.toString(),
                    "redeemed" to entry.holding.redeemed.toString(),
                    "ready" to (entry.status == Status.Ready).toString(),
                    "pending" to "false",
                )
            }
            val dynamicEntries = plugin.service.pendingEntries(ctx.player).map { entry ->
                val stored = entry.stored
                val count = stored.reward.items.sumOf { it.amount }.coerceAtLeast(1)
                mapOf(
                    "id" to entry.syntheticId,
                    "name" to stored.reward.title,
                    "material" to "xaliredeem:pending/${stored.id}",
                    "amount" to count.toString(),
                    "redeemed" to "0",
                    "ready" to (entry.status == Status.Ready).toString(),
                    "pending" to "true",
                )
            }
            return dynamicEntries + staticEntries
        }
    }

    /** Resolves `xaliredeem:pending/<id>` to the stored reward's icon or first item stack. */
    private class PendingItemSource(private val plugin: XaliRedeem) : ItemSource {
        override val prefix: String = "xaliredeem"

        override fun item(id: String): ItemStack? {
            if (!id.startsWith("pending/")) return null
            val rewardId = id.removePrefix("pending/").toLongOrNull() ?: return null
            val stored = plugin.service.pendingById(rewardId) ?: return null
            val icon = stored.reward.icon ?: stored.reward.items.firstOrNull() ?: ItemStack(Material.CHEST)
            return icon.clone()
        }
    }

    /** `{ type = "xaliredeem:redeem", voucher = "{id}" }`: redeems the voucher or pending reward, and draws the menu again. */
    private class RedeemAction(private val plugin: XaliRedeem, private val voucher: String) : Action {
        override fun run(ctx: MenuContext) {
            plugin.service.redeem(ctx.player, ctx.parse(voucher)) { ctx.refresh() }
        }
    }

    companion object {
        const val CATALOG = "xaliredeem:vouchers"
        const val ACTION = "xaliredeem:redeem"

        /** What an element holds, with an example of each, for the editor. */
        private val VALUES = mapOf(
            "id" to "starter",
            "name" to "Starter Crate",
            "material" to "chest",
            "amount" to "2",
            "redeemed" to "0",
            "ready" to "true",
            "pending" to "false",
        )
    }
}
