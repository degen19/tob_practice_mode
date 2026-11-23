package org.rsmod.content.raids.tob.bloat.xarpus

/**
 * The 5s gear's numbers on Xarpus (scratchpad xarpus/xnums.mjs, from the sim's item stats): melee on Zeal at 118 with
 * Ultor (|0) or Lightbearer (|1), ranged on Rigour in elite void at 99 or 112.
 */
object XarpusNumbers {
    /** Effective level, (attack bonus + 64), max hit. */
    class Roll(val ea: Int, val ab: Int, val mx: Int)

    val MELEE =
        mapOf(
            "Elder maul|0" to Roll(155, 249, 70), "Elder maul|1" to Roll(155, 249, 67),
            "Bandos godsword|0" to Roll(155, 274, 66), "Bandos godsword|1" to Roll(155, 274, 63),
            "Scythe of vitur|0" to Roll(155, 267, 52), "Scythe of vitur|1" to Roll(155, 267, 49),
            "Dragon claws|0" to Roll(155, 199, 47), "Dragon claws|1" to Roll(155, 199, 44),
        )

    val HALB = mapOf(0 to Roll(155, 252, 63), 1 to Roll(155, 252, 60))

    private val RANGED =
        mapOf(
            "Tonalztics of Ralos|99" to Roll(138, 237, 30), "Tonalztics of Ralos|112" to Roll(156, 237, 34),
            "Zaryte crossbow|99" to Roll(138, 242, 45), "Zaryte crossbow|112" to Roll(156, 242, 51),
        )

    /** Levels between 99 and 112 (a regular potion wearing off): the nearer of the two exported. */
    fun ranged(weapon: String, lvl: Int): Roll = RANGED.getValue("$weapon|${if (lvl >= 112) 112 else 99}")
}
