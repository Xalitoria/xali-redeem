package mc.xalitoria.redeem.paper.voucher

import com.charleskorn.kaml.YamlComment
import com.xalitoria.util.kotlin.parseDuration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.Statistic
import org.bukkit.entity.Player

/**
 * A test on the player that has to hold for a voucher to be redeemed, written in the voucher's `conditions` as an object
 * whose `type` names it. Each may carry a `message` to tell the player instead of the default one. A condition is tested
 * on the player's own thread, at the moment of redeeming and again every time the menu is drawn, so it must be cheap and
 * must not change anything.
 */
@Serializable
sealed interface Condition {
    /** What the player is told when this does not hold; the language file's text for the type when absent. */
    val message: String?

    fun test(player: Player): Boolean

    /** A wrong setting is reported as an [IllegalArgumentException] when the voucher is read. */
    fun validate() {}

    /** The player has at least [slots] empty slots in their inventory (armor and the off hand don't count). */
    @Serializable @SerialName("inventory-space")
    data class InventorySpace(
        @YamlComment("Empty slots needed.")
        val slots: Int = 1,
        override val message: String? = null,
    ) : Condition {
        override fun test(player: Player): Boolean = player.inventory.storageContents.count { it == null || it.isEmpty } >= slots

        override fun validate() = require(slots in 1..36) { "inventory-space: slots is from 1 to 36" }
    }

    /** The player has this permission node. */
    @Serializable @SerialName("permission")
    data class Permission(
        val node: String,
        override val message: String? = null,
    ) : Condition {
        override fun test(player: Player): Boolean = player.hasPermission(node)

        override fun validate() = require(node.isNotBlank()) { "permission: node is empty" }
    }

    /** The player has at least one of these permission nodes. */
    @Serializable @SerialName("permission-any")
    data class PermissionAny(
        val nodes: List<String>,
        override val message: String? = null,
    ) : Condition {
        override fun test(player: Player): Boolean = nodes.any(player::hasPermission)

        override fun validate() = require(nodes.isNotEmpty() && nodes.none { it.isBlank() }) { "permission-any: nodes needs at least one node" }
    }

    /** The player does not have this permission node. */
    @Serializable @SerialName("no-permission")
    data class NoPermission(
        val node: String,
        override val message: String? = null,
    ) : Condition {
        override fun test(player: Player): Boolean = !player.hasPermission(node)

        override fun validate() = require(node.isNotBlank()) { "no-permission: node is empty" }
    }

    /** The player is in one of [worlds], or, with [invert], in none of them. */
    @Serializable @SerialName("world")
    data class World(
        val worlds: List<String>,
        @YamlComment("Turns it round: redeemable everywhere except in these worlds.")
        val invert: Boolean = false,
        override val message: String? = null,
    ) : Condition {
        override fun test(player: Player): Boolean = worlds.any { it.equals(player.world.name, ignoreCase = true) } != invert

        override fun validate() = require(worlds.isNotEmpty()) { "world: worlds needs at least one world" }
    }

    /** The player has played at least [min] on this server. */
    @Serializable @SerialName("playtime")
    data class Playtime(
        @YamlComment("Such as 30m, 2h or 1d.")
        val min: String,
        override val message: String? = null,
    ) : Condition {
        private val millis: Long get() = parseDuration("playtime: min", min).inWholeMilliseconds

        override fun test(player: Player): Boolean = player.getStatistic(Statistic.PLAY_ONE_MINUTE) * TICK_MILLIS >= millis

        override fun validate() { millis }

        /** [min] in seconds, for the message. */
        val seconds: Long get() = millis / 1000

        private companion object {
            const val TICK_MILLIS = 50L
        }
    }

    /**
     * A placeholder (PlaceholderAPI) compared with a value: `value: "%vault_eco_balance%"`, `operator: ">="`, `expected: "100"`.
     * The operators are `==`, `!=`, `>`, `>=`, `<`, `<=` and `contains`. Numbers are compared as numbers, anything else as text
     * without regard to case (and `>` and the like are then never true). It is read every time the menu is drawn, so the
     * placeholder has to be a quick one.
     */
    @Serializable @SerialName("placeholder")
    data class Placeholder(
        val value: String,
        val operator: String = "==",
        val expected: String,
        override val message: String? = null,
    ) : Condition {
        override fun test(player: Player): Boolean = compare(PlaceholderAPI.setPlaceholders(player, value), operator, PlaceholderAPI.setPlaceholders(player, expected))

        override fun validate() = require(operator in OPERATORS) { "placeholder: operator '$operator' is not one of: ${OPERATORS.joinToString(" ")}" }

        companion object {
            val OPERATORS = ["==", "!=", ">", ">=", "<", "<=", "contains"]

            /** Whether `left operator right` holds. */
            fun compare(left: String, operator: String, right: String): Boolean {
                val a = left.trim()
                val b = right.trim()
                val x = a.toBigDecimalOrNull()
                val y = b.toBigDecimalOrNull()
                if (x != null && y != null) {
                    val order = x.compareTo(y)
                    return when (operator) {
                        "==" -> order == 0
                        "!=" -> order != 0
                        ">" -> order > 0
                        ">=" -> order >= 0
                        "<" -> order < 0
                        "<=" -> order <= 0
                        "contains" -> a in b
                        else -> false
                    }
                }
                return when (operator) {
                    "==" -> a.equals(b, ignoreCase = true)
                    "!=" -> !a.equals(b, ignoreCase = true)
                    "contains" -> a.contains(b, ignoreCase = true)
                    else -> false
                }
            }
        }
    }
}
