package mc.xalitoria.redeem.paper.voucher

import com.charleskorn.kaml.YamlComment
import com.xalitoria.util.kotlin.parseDuration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Server
import kotlin.time.Duration

/** Who sees a voucher in the menu and in `/redeem list`. */
enum class Show {
    /** Every player, redeemable or not. */
    ALWAYS,

    /** Players who hold one, and everyone for a free voucher. */
    HELD,

    /** Players who can redeem it right now. */
    READY,
}

/** One item that redeeming gives. It is given where there is room and dropped at the player's feet where there is not. */
@Serializable
data class ItemReward(
    val material: String,
    val amount: Int = 1,
    val name: String? = null,
    val lore: List<String> = [],
) {
    fun type(): Material = Material.matchMaterial(material) ?: throw IllegalArgumentException("'$material' is not an item")

    fun validate() {
        // Whether a material is an item comes from the server's registry, which a unit test does not have.
        val server: Server? = Bukkit.getServer()
        if (server != null) require(type().isItem) { "'$material' is not an item" } else type()
        require(amount in 1..MAX_AMOUNT) { "an item's amount is from 1 to $MAX_AMOUNT" }
    }

    private companion object {
        const val MAX_AMOUNT = 2304
    }
}

/**
 * What redeeming does, in order: the commands, the items, then the messages. `{player}`, `{uuid}`, `{voucher}` (its name here)
 * and `{name}` (its display name) are filled in, and PlaceholderAPI's placeholders when it is installed.
 */
@Serializable
data class Rewards(
    @YamlComment("Commands the console runs, without the slash: \"give {player} diamond 3\", \"lp user {player} parent add vip\".")
    val commands: List<String> = [],
    @SerialName("player-commands")
    @YamlComment("Commands the player runs as themselves.")
    val playerCommands: List<String> = [],
    val items: List<ItemReward> = [],
    @YamlComment("Told to the player. Colors, hex and MiniMessage work.")
    val message: List<String> = [],
    @YamlComment("Told to everyone on the server.")
    val broadcast: List<String> = [],
) {
    fun isEmpty(): Boolean = commands.isEmpty() && playerCommands.isEmpty() && items.isEmpty() && message.isEmpty() && broadcast.isEmpty()
}

/** A voucher as it is written in `vouchers/<name>.yml`; the file's name is the voucher's. */
@Serializable
data class VoucherConfig(
    @YamlComment("What players see. Colors, hex and MiniMessage work.")
    val name: String,
    val description: List<String> = [],
    @YamlComment("The item the menu draws. Any item XaliMenu knows: diamond, head:<player>, nexo:<id>, itemsadder:<id>...")
    val material: String = "paper",
    @YamlComment(
        "A voucher players hold: they get it with /redeem admin give (a shop, a vote reward, a crate or an event runs that command),",
        "and redeeming uses one up. With free: true nobody holds anything, and anyone the conditions let through can redeem it,",
        "which together with cooldown and max-redemptions makes a daily reward or a one-time gift.",
    )
    val free: Boolean = false,
    @YamlComment("ALWAYS: everyone sees it. HELD: only players who hold one (everyone, for a free voucher). READY: only players who can redeem it now.")
    val show: Show = Show.HELD,
    @YamlComment("How long a player waits after redeeming it before they can redeem it again, such as 1d. 0s for no wait.")
    val cooldown: String = "0s",
    @SerialName("max-redemptions")
    @YamlComment("How often one player can redeem it, ever. 0 for no limit.")
    val maxRedemptions: Int = 0,
    @YamlComment("All of these must hold. Types: inventory-space, permission, permission-any, no-permission, world, playtime, placeholder.")
    val conditions: List<Condition> = [],
    val rewards: Rewards = Rewards(),
)

/** A voucher that has been read and checked, with the name it is redeemed by. */
class Voucher(val id: String, val config: VoucherConfig) {
    val cooldown: Duration = parseDuration("cooldown", config.cooldown)

    init {
        require(ID.matches(id)) { "'$id' is not a voucher name: use lower case letters, digits, '_' and '-' (up to 64) in the file's name" }
        require(id !in RESERVED) { "'$id' is the name of a command; call the file something else" }
        require(config.maxRedemptions >= 0) { "max-redemptions must not be negative" }
        config.conditions.forEach { it.validate() }
        config.rewards.items.forEach { it.validate() }
    }

    val name: String get() = config.name
    val free: Boolean get() = config.free
    val needsHeld: Boolean get() = !config.free

    companion object {
        private val ID = Regex("[a-z0-9_-]{1,64}")

        /** Names of the subcommands, which a voucher cannot take since `/redeem <voucher>` redeems it. */
        val RESERVED = setOf("list", "menu", "help", "admin", "give", "take", "reload")
    }
}
