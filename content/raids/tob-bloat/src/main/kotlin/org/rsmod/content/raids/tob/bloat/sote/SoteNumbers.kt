package org.rsmod.content.raids.tob.bloat.sote

/** The 5s gear's numbers on Sotetseg (scratchpad sote/snums.mjs, from the sim's item stats). */
object SoteNumbers {
    class Roll(val ea: Int, val ab: Int, val mx: Int)

    class Ranged(val roll: Int, val mx: Int)

    val MELEE = mapOf("Scythe of vitur" to Roll(155, 267, 52), "Elder maul" to Roll(155, 249, 70), "Bandos godsword" to Roll(155, 274, 66), "Dragon claws" to Roll(155, 199, 47))
    val HALB = Roll(155, 252, 63)
    /** At 112 Ranged on Rigour in elite void; the Twisted bow's multipliers against her Magic (250) in. */
    val RANGED = mapOf("Twisted bow" to Ranged(44116, 86), "Zaryte crossbow" to Ranged(37752, 51))
    /** Their defence rolls against her styles. */
    val PDEF = mapOf("mage" to 9196, "range" to 52080, "crush" to 52080)
}
