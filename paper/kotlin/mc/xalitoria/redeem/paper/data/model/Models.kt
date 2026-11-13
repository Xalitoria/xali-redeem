package mc.xalitoria.redeem.paper.data.model

/** What a player has of one voucher; see [mc.xalitoria.redeem.paper.data.db.tables.HoldingTable]. */
data class Holding(val amount: Int = 0, val redeemed: Int = 0, val last: Long = 0) {
    companion object {
        val NONE = Holding()
    }
}

/** The outcome of using up a voucher, see [mc.xalitoria.redeem.paper.data.db.repository.HoldingRepository.consume]. */
sealed interface Consume {
    /** It was used; [previousLast] is the time before it, for putting it back. */
    data class Done(val previousLast: Long) : Consume

    /** The player holds none. */
    data object NoneHeld : Consume

    /** The player has redeemed it as often as it allows. */
    data object LimitReached : Consume

    /** The player has to wait [remainingMillis] more. */
    data class Cooling(val remainingMillis: Long) : Consume
}
