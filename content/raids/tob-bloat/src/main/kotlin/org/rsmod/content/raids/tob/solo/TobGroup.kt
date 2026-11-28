package org.rsmod.content.raids.tob.solo

/**
 * A party going into the Theatre: its seats (each a person or a bot, in seat order, Player 1 first), the spellbook and
 * procedure every bot seat plays, and the practice switches the group runs with. The lobby's Build Team and the castle
 * door both make one; each room's crew reads it.
 */
data class TobGroup(
    val leader: String,
    val seats: List<TobSeat>,
    val procedure: TobProcedure = TobProcedure.Standard,
    val settings: GroupSettings = GroupSettings(),
) {
    val size: Int
        get() = seats.size

    val roles: List<NyloRole>
        get() = seats.map { it.role }

    val bots: List<TobSeat>
        get() = seats.filter { it.person == null }

    val people: List<String>
        get() = seats.mapNotNull { it.person }

    init {
        require(seats.size in MIN_SIZE..MAX_SIZE) { "a group is $MIN_SIZE to $MAX_SIZE seats, not ${seats.size}" }
    }

    companion object {
        const val MIN_SIZE = 1
        const val MAX_SIZE = 5

        /** All bots, one per role in [roles]: what `::watch`, `::maiden3/4` and the bot batches play. */
        fun botTeam(leader: String, roles: List<NyloRole>): TobGroup = TobGroup(leader, roles.map { TobSeat(it) })

        /** The roles a standard team of [size] plays, Player 1 first (sizes without a crew yet still seat these). */
        fun standardRoles(size: Int): List<NyloRole> =
            when (size) {
                1 -> listOf(NyloRole.Mage)
                2 -> listOf(NyloRole.Mage, NyloRole.Melee)
                3 -> NyloRole.TRIO
                4 -> NyloRole.QUAD
                else -> NyloRole.QUAD + NyloRole.Ranger
            }
    }
}

/** One seat: its role, the person sitting it (null: a bot plays it) and, for a bot, the spellbook it walks in on. */
data class TobSeat(val role: NyloRole, val person: String? = null, val book: SeatBook = SeatBook.Standard)

/**
 * A seat's spellbook. [Standard] is today's: freezers on Ancients, the rest on Lunars at Maiden and Arceuus at the
 * Nylocas. [LunarSwap] puts a freezer on the Lunars, barraging through Spellbook Swap, with Death Charge on it too.
 */
enum class SeatBook {
    Standard,
    LunarSwap,
}

/** What the bots follow: the team's standard (money-raid) play, or a procedure a player saved at the bot table. */
sealed interface TobProcedure {
    data object Standard : TobProcedure

    data class Saved(val owner: String, val name: String) : TobProcedure
}

/** The practice switches as a group runs them (the rotten potato's), defaulting to the server's own. */
data class GroupSettings(
    val noDeaths: Boolean = false,
    val infiniteHp: Boolean = false,
    val infiniteSupplies: Boolean = false,
    val infinitePrayer: Boolean = false,
    val alwaysMaxHit: Boolean = false,
    val infiniteSpec: Boolean = false,
    val nyloWaveLabels: Boolean = true,
    val nyloDeathIndicators: Boolean = true,
)
