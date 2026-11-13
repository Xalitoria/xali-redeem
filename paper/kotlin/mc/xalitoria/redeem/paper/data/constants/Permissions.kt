package mc.xalitoria.redeem.paper.data.constants

object Permissions {
    /** `/redeem`, `list` and redeeming a voucher. A voucher's own conditions decide who may redeem it. */
    const val USE = "xaliredeem.use"

    /** `/redeem admin`: give and take vouchers, reset what a player has used, open the menu for someone, reload. */
    const val ADMIN = "xaliredeem.admin"
}
