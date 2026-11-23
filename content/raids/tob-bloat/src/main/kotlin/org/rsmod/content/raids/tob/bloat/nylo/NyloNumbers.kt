package org.rsmod.content.raids.tob.bloat.nylo

/** The 5s gear's numbers on the Nylo boss (scratchpad nylo/nnums.mjs, from the sim's item stats and its own functions). */
object NyloNumbers {
    class Roll(val ea: Int, val ab: Int, val mx: Int)

    val MELEE = mapOf("Scythe of vitur" to Roll(155, 267, 52), "Dragon claws" to Roll(155, 199, 47), "Bandos godsword" to Roll(155, 274, 66))
    val HALB = Roll(155, 252, 63)
    val RANGED = mapOf("Twisted bow" to Roll(156, 202, 40), "Ascension crossbows" to Roll(156, 156, 34), "Zaryte crossbow" to Roll(156, 242, 51))
    /** Their 2-tick ranged weapon (the best of blowpipe, Ascension crossbows, darts they have). */
    const val FAST = "Ascension crossbows"
    const val FAST_CODE = "AX"
    const val TBOW_ACC = 1.4
    const val TBOW_DMG = 2.15
    /** [chance, max] - his magic defence doesn't change. */
    val SHADOW = 0.9896600850343922 to 70
    val AYAK = 0.9761121110825405 to 42
    /** Zorya's tome: an auto (0), a spec / buffed auto (1). */
    val TOME = listOf(0.9754894200833729 to 40, 0.9901955610904141 to 64)
    /** Their defence rolls against his styles, and in their takeoff gear. */
    val PDEF = mapOf("melee" to 52080, "ranged" to 24955, "magic" to 23850)
    val PDEF_OFF = mapOf("melee" to 42005, "ranged" to 20305, "magic" to 11400)
    /** The team's expected damage a tick (for "likely to die"). */
    const val DPT = 39.63877728850769
    val WEAPONS = setOf("Scythe of vitur", "Dragon claws", "Crystal halberd", "Bandos godsword", "Zaryte crossbow", "Twisted bow", "Ascension crossbows", "Tumeken's shadow", "Zorya's tome", "Eye of ayak")
}
