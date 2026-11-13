package mc.xalitoria.redeem.paper.data.config

import com.charleskorn.kaml.YamlComment
import com.xalitoria.util.config.DatabaseConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MainConfig(
    val debug: Boolean = false,
    val language: String = "en-us",
    @YamlComment(
        "The voucher menu. It is an XaliMenu menu, so it needs XaliMenu; without it (or with this off) /redeem lists the vouchers in",
        "chat, where each one can be clicked to redeem it, and everything else works the same.",
    )
    val menu: Menu = Menu(),
    @YamlComment("Lines per page of /redeem list.")
    @SerialName("per-page")
    val perPage: Int = 8,
    val database: DatabaseConfig = DatabaseConfig(
        tablePrefix = "xaliredeem_",
        sqlite = DatabaseConfig.Sqlite("xaliredeem.db"),
        mysql = DatabaseConfig.Mysql(database = "xaliredeem"),
    ),
) {
    @Serializable
    data class Menu(
        @YamlComment("Use XaliMenu for the voucher menu. Turning this on while the server runs needs /xmenu reload to read the menu.")
        val enabled: Boolean = true,
        @YamlComment("The name of the menu in XaliMenu: plugins/XaliMenu/menus/<name>.conf.")
        val name: String = "redeem",
        @YamlComment(
            "Writes that file when it is missing, so it can be edited there. It lists the vouchers a page at a time; its item is the",
            "template of each voucher, which reads the voucher's text from the %xaliredeem_...% placeholders.",
        )
        @SerialName("write-menu")
        val writeMenu: Boolean = true,
    )

    fun validate() {
        require(perPage >= 1) { "per-page must be at least 1" }
        require(menu.name.isNotBlank() && menu.name.none { it.isWhitespace() || it == '/' || it == '\\' }) {
            "menu.name must be a menu's file name without spaces or slashes"
        }
    }
}
