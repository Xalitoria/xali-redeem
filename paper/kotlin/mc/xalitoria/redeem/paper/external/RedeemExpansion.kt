package mc.xalitoria.redeem.paper.external

import mc.xalitoria.redeem.paper.XaliRedeem
import mc.xalitoria.redeem.paper.voucher.Status
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/**
 * `%xaliredeem_...%`, answered from memory (what the plugin knows of players who are online), never from the database.
 * `<id>` is a voucher's name. The ones about a voucher are empty for a voucher that does not exist.
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
            "total" -> return if (offline == null) "0" else service.total(offline.uniqueId).toString()
            "count" -> return if (player == null) "0" else service.entries(player).size.toString()
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
        return when (key) {
            "name" -> voucher.name
            "amount" -> offline?.let { service.holding(it.uniqueId, voucher.id).amount }?.toString() ?: "0"
            "redeemed" -> offline?.let { service.holding(it.uniqueId, voucher.id).redeemed }?.toString() ?: "0"
            "ready" -> (player != null && service.status(player, voucher) == Status.Ready).toString()
            "status" -> player?.let { service.describe(service.status(it, voucher)) }.orEmpty()
            "cooldown" -> when (val status = player?.let { service.status(it, voucher) }) {
                is Status.Cooling -> ((status.remainingMillis + 999) / 1000).toString()
                else -> "0"
            }
            else -> null
        }
    }
}
