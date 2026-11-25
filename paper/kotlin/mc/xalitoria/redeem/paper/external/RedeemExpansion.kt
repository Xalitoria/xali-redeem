package mc.xalitoria.redeem.paper.external

import mc.xalitoria.redeem.paper.XaliRedeem
import mc.xalitoria.redeem.paper.service.PendingEntry
import mc.xalitoria.redeem.paper.voucher.Status
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/**
 * `%xredeem_...%`, answered from memory (what the plugin knows of players who are online, and what it last read of the others
 * in the background), never from the database. `<id>` is a voucher's name or `pending:<id>` for a stored dynamic reward.
 */
class RedeemExpansion(private val plugin: XaliRedeem) : PlaceholderExpansion() {
    override fun getIdentifier() = "xredeem"
    override fun getAuthor() = "audizian"
    override fun getVersion() = plugin.pluginMeta.version
    override fun persist() = true

    override fun onRequest(offline: OfflinePlayer?, params: String): String? {
        val service = plugin.service
        val player = offline?.player
        val key = params.substringBefore('_').lowercase()
        val rest = params.substringAfter('_', "")
        when (key) {
            "total" -> return if (offline == null) "0" else {
                val staticTotal = service.held(offline.uniqueId)?.values?.sumOf { it.amount }
                if (staticTotal == null) "" else (staticTotal + service.pendingCount(offline.uniqueId)).toString()
            }
            "pending" -> return if (offline == null) "0" else service.pendingCount(offline.uniqueId).toString()
            "count" -> return when {
                offline == null -> "0"
                player != null -> (service.entries(player).size + service.pendingCount(player.uniqueId)).toString()
                else -> service.visibleTo(offline.uniqueId)?.toString().orEmpty()
            }
        }
        if (key == "description") {
            val targetId = rest.substringBeforeLast('_')
            val line = rest.substringAfterLast('_').toIntOrNull() ?: return null
            if (targetId.startsWith(PendingEntry.PENDING_PREFIX, ignoreCase = true)) {
                val rewardId = targetId.substring(PendingEntry.PENDING_PREFIX.length).toLongOrNull() ?: return ""
                val stored = service.pendingById(rewardId) ?: return ""
                return stored.reward.description.getOrNull(line - 1).orEmpty()
            }
            val voucher = service.vouchers[targetId.lowercase()] ?: return ""
            return voucher.config.description.getOrNull(line - 1).orEmpty()
        }
        if (rest.startsWith(PendingEntry.PENDING_PREFIX, ignoreCase = true)) {
            val rewardId = rest.substring(PendingEntry.PENDING_PREFIX.length).toLongOrNull() ?: return ""
            val stored = service.pendingById(rewardId) ?: return ""
            val status = if (player != null) service.pendingStatus(player, stored) else Status.Ready
            return when (key) {
                "name" -> stored.reward.title
                "amount" -> stored.reward.items.sumOf { it.amount }.coerceAtLeast(1).toString()
                "redeemed" -> "0"
                "ready" -> (status == Status.Ready).toString()
                "status" -> service.describe(status)
                "cooldown" -> "0"
                else -> null
            }
        }
        val voucher = service.vouchers[rest.lowercase()] ?: return when (key) {
            "name", "amount", "redeemed", "ready", "status", "cooldown" -> ""
            else -> null
        }
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
