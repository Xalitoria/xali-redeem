package mc.xalitoria.redeem.paper.data.config

import com.charleskorn.kaml.YamlComment
import com.xalitoria.util.message.TimeWords
import com.xalitoria.util.message.formatArgs
import com.xalitoria.util.serial.message.State
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mc.xalitoria.redeem.paper.voucher.Condition

/** What every heading starts with: the same colors in every Xali plugin. */
private const val HEAD = "{#a0a>}&lXALI{#fff<}&f&lREDEEM &7>"

private const val PREFIX = "$HEAD "

/** Every message the plugin sends, in `language/<language>.yml`. `{0}`, `{1}`... are filled in by the plugin. */
@Serializable
data class MessageConfig(
    val commands: Commands = Commands(),
    val redeem: Redeem = Redeem(),
    @SerialName("status")
    val statuses: Statuses = Statuses(),
    val conditions: Conditions = Conditions(),
    val list: Listing = Listing(),
    val admin: Admin = Admin(),
    @YamlComment("The units of a time such as \"1 hour 5 minutes\": the words of the language, and what goes between the parts.")
    val words: TimeWords = TimeWords(),
) {
    @Serializable
    data class Commands(
        @SerialName("no-permission")
        val noPermission: String = "You don't have permission to use this command.",
        @SerialName("player-only")
        val playerOnly: String = "You must be a player to use this command.",
        @SerialName("player-not-found")
        private val playerNotFound: String = "No player called {0} has joined.",
        @SerialName("not-a-number")
        private val notANumber: String = "{0} is not a number.",
        private val usage: String = "Usage: {0}",
        @SerialName("menu-off")
        val menuOff: String = "&cThe voucher menu is not turned on; use /redeem list.",
        private val help: String = """
            $HEAD &dHELP
            &d/{0} &r- open the voucher menu (or list your vouchers)
            &d/{0} &rlist [page] &7- your vouchers in chat
            &d/{0} &r<voucher> &7- redeem a voucher
        """.trimIndent(),
        private val adminHelp: String = """
            &d/{0} &radmin give <player> <voucher> [amount] &7- give a player vouchers
            &d/{0} &radmin take <player> <voucher> [amount] &7- take vouchers away
            &d/{0} &radmin balance <player> &7- what a player holds and has used
            &d/{0} &radmin reset <player> <voucher> &7- start a player's cooldown and limit of a voucher again
            &d/{0} &radmin open <player> &7- open the menu for a player
            &d/{0} &radmin reload|debug
        """.trimIndent(),
        private val reload: String = "Configuration reloaded: {0} vouchers.",
        @SerialName("reload-failed")
        private val reloadFailed: String = "The configuration was not reloaded: {0}",
        @SerialName("voucher-error")
        private val voucherError: String = "Voucher not loaded, {0}",
        @YamlComment("Said when /<command> admin debug turns the debug log on or off.")
        val debug: State = State("Debug logging is on.", "Debug logging is off."),
        @SerialName("unexpected-error")
        val unexpectedError: String = "An unexpected error occurred. Please try again.",
    ) {
        fun playerNotFound(name: String): String = playerNotFound.formatArgs(name)
        fun notANumber(text: String): String = notANumber.formatArgs(text)
        fun usage(text: String): String = usage.formatArgs(text)
        fun help(label: String, admin: Boolean): String = help.formatArgs(label) + if (admin) "\n" + adminHelp.formatArgs(label) else ""
        fun reload(vouchers: Int): String = reload.formatArgs(vouchers)
        fun reloadFailed(reason: String): String = reloadFailed.formatArgs(reason)
        fun voucherError(reason: String): String = voucherError.formatArgs(reason)
    }

    @Serializable
    data class Redeem(
        @SerialName("unknown-voucher")
        private val unknownVoucher: String = "&cThere is no voucher called {0}.",
        @SerialName("not-held")
        private val notHeld: String = "&cYou don't have a {0}&c voucher.",
        @SerialName("limit-reached")
        private val limitReached: String = "&cYou have redeemed {0}&c as often as you can.",
        private val cooling: String = "&cYou can redeem {0}&c again in {1}.",
        val busy: String = "&cOne moment, your last redeem is not finished.",
        @SerialName("not-ready")
        private val notReady: String = "&cYou can't redeem {0}&c right now.",
        private val redeemed: String = "$PREFIX&aYou redeemed {0}&a.",
        @SerialName("interrupted")
        private val interrupted: String = "&c{0}&c could not be redeemed and was given back to you.",
    ) {
        fun unknownVoucher(id: String): String = unknownVoucher.formatArgs(id)
        fun notHeld(voucher: String): String = notHeld.formatArgs(voucher)
        fun limitReached(voucher: String): String = limitReached.formatArgs(voucher)
        fun cooling(voucher: String, time: String): String = cooling.formatArgs(voucher, time)
        fun notReady(voucher: String): String = notReady.formatArgs(voucher)
        fun redeemed(voucher: String): String = redeemed.formatArgs(voucher)
        fun interrupted(voucher: String): String = interrupted.formatArgs(voucher)
    }

    /** What the menu and the list say about a voucher. They must not hold a `%`, which the menu takes out of what a catalog gives. */
    @Serializable
    data class Statuses(
        val ready: String = "&aClick to redeem",
        @SerialName("not-held")
        val notHeld: String = "&cYou don't have this one",
        @SerialName("limit-reached")
        val limitReached: String = "&cRedeemed as often as you can",
        private val cooling: String = "&cAvailable in {0}",
        private val blocked: String = "&c{0}",
    ) {
        fun cooling(time: String): String = cooling.formatArgs(time)
        fun blocked(reason: String): String = blocked.formatArgs(reason)
    }

    /** What a player is told when a condition of the voucher does not hold, unless the condition has a `message` of its own. */
    @Serializable
    data class Conditions(
        @SerialName("inventory-space")
        private val inventorySpace: String = "You need {0} free inventory slots.",
        val permission: String = "You can't redeem this.",
        @SerialName("no-permission")
        val noPermission: String = "You can't redeem this.",
        val world: String = "You can't redeem this in this world.",
        private val playtime: String = "You have to have played for {0} first.",
        val placeholder: String = "You don't meet the requirements for this.",
    ) {
        /** The text for [condition], which has its own `message` first. */
        fun failure(condition: Condition, words: TimeWords): String = condition.message ?: when (condition) {
            is Condition.InventorySpace -> inventorySpace.formatArgs(condition.slots)
            is Condition.Permission, is Condition.PermissionAny -> permission
            is Condition.NoPermission -> noPermission
            is Condition.World -> world
            is Condition.Playtime -> playtime.formatArgs(words.duration(condition.seconds))
            is Condition.Placeholder -> placeholder
        }
    }

    @Serializable
    data class Listing(
        private val header: String = "$PREFIX&dYour vouchers &7(page {0} of {1})",
        val empty: String = "&7You have nothing to redeem.",
        private val entry: String = "&d{0} &7x{1} &8- {2}",
        @SerialName("click-to-redeem")
        val clickToRedeem: String = "Click to redeem",
    ) {
        fun header(page: Int, pages: Int): String = header.formatArgs(page, pages)
        fun entry(name: String, held: Int, status: String): String = entry.formatArgs(name, held, status)
    }

    @Serializable
    data class Admin(
        private val given: String = "&aGave {0} {1} x {2}&a; they hold {3}.",
        private val taken: String = "&aTook {1} x {2}&a from {0}; they hold {3}.",
        @SerialName("none-to-take")
        private val noneToTake: String = "&c{0} holds no {1}&c.",
        private val received: String = "$PREFIX&aYou received &f{0} x &r{1}&a. Use &e/redeem&a to redeem.",
        @SerialName("balance-header")
        private val balanceHeader: String = "$PREFIX&d{0}'s vouchers",
        @SerialName("balance-entry")
        private val balanceEntry: String = "&d{0} &7holds &f{1}&7, redeemed &f{2}&7 times, last {3}",
        @SerialName("balance-empty")
        val balanceEmpty: String = "&7Nothing yet.",
        private val reset: String = "&aThe cooldown and limit of {1}&a start again for {0}.",
        @SerialName("nothing-to-reset")
        private val nothingToReset: String = "&c{0} has not used {1}&c.",
        private val opened: String = "&aOpened the menu for {0}.",
        @SerialName("free-voucher")
        private val freeVoucher: String = "&c{0}&c is free: nobody holds it, so there is nothing to give or take.",
        @SerialName("not-online")
        private val notOnline: String = "&c{0} is not online.",
        val never: String = "never",
        @SerialName("ago")
        private val ago: String = "{0} ago",
    ) {
        fun given(player: String, amount: Int, voucher: String, held: Int): String = given.formatArgs(player, amount, voucher, held)
        fun taken(player: String, amount: Int, voucher: String, held: Int): String = taken.formatArgs(player, amount, voucher, held)
        fun noneToTake(player: String, voucher: String): String = noneToTake.formatArgs(player, voucher)
        fun received(amount: Int, voucher: String): String = received.formatArgs(amount, voucher)
        fun balanceHeader(player: String): String = balanceHeader.formatArgs(player)
        fun balanceEntry(voucher: String, held: Int, redeemed: Int, last: String): String = balanceEntry.formatArgs(voucher, held, redeemed, last)
        fun reset(player: String, voucher: String): String = reset.formatArgs(player, voucher)
        fun nothingToReset(player: String, voucher: String): String = nothingToReset.formatArgs(player, voucher)
        fun opened(player: String): String = opened.formatArgs(player)
        fun freeVoucher(voucher: String): String = freeVoucher.formatArgs(voucher)
        fun notOnline(player: String): String = notOnline.formatArgs(player)
        fun ago(time: String): String = ago.formatArgs(time)
    }
}
