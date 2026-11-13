package mc.xalitoria.redeem.paper.listener

import com.xalitoria.util.message.fail
import kotlinx.coroutines.launch
import mc.xalitoria.redeem.paper.XaliRedeem
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/** Reads what a player holds when they join, so the menu and the placeholders have it, and lets go of it when they leave. */
class PlayerListener(private val plugin: XaliRedeem) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        plugin.scope.launch {
            try {
                plugin.service.load(uuid)
            } catch (e: Exception) {
                plugin.logger.fail("Failed to read the vouchers of ${event.player.name}", e)
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.service.forget(event.player.uniqueId)
    }
}
