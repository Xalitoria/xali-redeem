package mc.xalitoria.redeem.paper.external

import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.entity.Player

/**
 * PlaceholderAPI, kept in a class of its own so that nothing else loads it: this is only called once the plugin is known
 * to be installed.
 */
object Placeholders {
    fun parse(player: Player, text: String): String = if ('%' in text) PlaceholderAPI.setPlaceholders(player, text) else text
}
