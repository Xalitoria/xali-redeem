package mc.xalitoria.redeem.paper.voucher

/** Whether a player can redeem a voucher now, and if not, what stands in the way. */
sealed interface Status {
    data object Ready : Status

    /** A voucher that has to be held, and the player holds none. */
    data object NotHeld : Status

    data object LimitReached : Status

    data class Cooling(val remainingMillis: Long) : Status

    /** A condition of the voucher does not hold; [reason] is what the player is told. */
    data class Blocked(val reason: String) : Status
}
