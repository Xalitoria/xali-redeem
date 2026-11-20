package mc.xalitoria.redeem.paper.external

import mc.xalitoria.redeem.paper.XaliRedeem
import mc.xalitoria.redeem.paper.voucher.Status
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/**
 * `%xaliredeem_...%`, answered from memory (what the plugin knows of players who are online, and what it last read of the others
 * in the background), never from the database. `<id>` is a voucher's name. The ones about a voucher are empty for a voucher that
 * does not exist.
 *
 * A player who is not online has no thread to test a voucher's conditions on, so for one the conditions are left out of `count`,
 * `ready`, `status` and `cooldown`: they say what holding, the limit and the cooldown allow. The first request for such a player
 * answers with nothing, as it reads what they hold.
 *
 * - `total`: how many vouchers the player holds. `count`: how many vouchers they can see.
 * - `amount_<id>`, `redeemed_<id>`: how many of it they hold, and how often they redeemed it.
 * - `ready_<id>`: `true` if they can redeem it now. `status_<id>`: what stands in the way, or "Click to redeem" (colored text).
 * - `cooldown_<id>`: seconds before they can redeem it again, 0 if they can.
 * - `name_<id>`, `description_<id>_<line>`: the voucher's text, with its colors. The line counts from 1, and is empty past the last.
 */
class RedeemExpansion(private val plugin: XaliRedeem) : PlaceholderExpansion() {
    override fun getIdentifier() = "xaliredeem"
    override fun getAuthor() = "audizian"
    override fun getVersion() = plugin.pluginMeta.version
    override fun persist() = true

    override fun onRequest(offline: OfflinePlayer?, params: String): String? {
        val service = plugin.service
        val player = offline?.player
        val key = params.substringBefore('_').lowercase()
        val rest = params.substringAfter('_', "")
        when (key) {
            "total" -> return if (offline == null) "0" else service.held(offline.uniqueId)?.values?.sumOf { it.amount }?.toString().orEmpty()
            "count" -> return when {
                offline == null -> "0"
                player != null -> service.entries(player).size.toString()
                else -> service.visibleTo(offline.uniqueId)?.toString().orEmpty()
            }
        }
        if (key == "description") {
            val voucher = service.vouchers[rest.substringBeforeLast('_').lowercase()] ?: return ""
            val line = rest.substringAfterLast('_').toIntOrNull() ?: return null
            return voucher.config.description.getOrNull(line - 1).orEmpty()
        }
        val voucher = service.vouchers[rest.lowercase()] ?: return when (key) {
            "name", "amount", "redeemed", "ready", "status", "cooldown" -> ""
            else -> null
        }
        // Where the player is not online here, what is known of them is what was read in the background: empty until that is done.
        val status by lazy {
            when {
                offline == null -> null
                player != null -> service.status(player, voucher)
                else -> service.statusOf(offline.uniqueId, voucher)
            }
        }
        return when (key) {
            "name" -> voucher.name
            "amount" -> if (offline == null) "0" else service.held(offline.uniqueId)?.let { (it[voucher.id]?.amount ?: 0).toString() }.orEmpty()
            "redeemed" -> if (offline == null) "0" else service.held(offline.uniqueId)?.let { (it[voucher.id]?.redeemed ?: 0).toString() }.orEmpty()
            "ready" -> (status == Status.Ready).toString()
            "status" -> status?.let(service::describe).orEmpty()
            "cooldown" -> when (val now = status) {
                null -> if (offline != null) "" else "0"
                is Status.Cooling -> ((now.remainingMillis + 999) / 1000).toString()
                else -> "0"
            }
            else -> null
        }
    }
}
