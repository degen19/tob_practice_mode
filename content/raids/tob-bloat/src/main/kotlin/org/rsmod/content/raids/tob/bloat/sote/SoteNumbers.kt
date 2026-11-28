package org.rsmod.content.raids.tob.bloat.sote

/**
 * The bots' gear's numbers on Sotetseg, from the sim's item stats for its default players (verzikSim cli/sote.mjs):
 * Torva / Oathplate on Zeal with an Ultor ring (or Lightbearer), Masori with the quiver and rupture on Rigour at 112,
 * Ancestral on Augury.
 */
object SoteNumbers {
    class Roll(val ea: Int, val ab: Int, val mx: Int)

    class Ranged(val roll: Int, val mx: Int)

    val MELEE = mapOf("Scythe of vitur" to Roll(155, 267, 52), "Elder maul" to Roll(155, 249, 70), "Bandos godsword" to Roll(155, 274, 66), "Dragon claws" to Roll(155, 199, 47))

    /** On Lightbearer instead of the Ultor ring. */
    val MELEE_LB = mapOf("Scythe of vitur" to Roll(155, 267, 49), "Elder maul" to Roll(155, 249, 67), "Bandos godsword" to Roll(155, 274, 63), "Dragon claws" to Roll(155, 199, 44))
    val HALB = Roll(155, 252, 63)
    val HALB_LB = Roll(155, 252, 60)

    /** The Twisted bow's multipliers against her Magic (250) in. */
    val RANGED = mapOf("Twisted bow" to Ranged(59043, 81), "Zaryte crossbow" to Ranged(47854, 48))

    /** Magic prefires (Ancestral, Augury, 99 Magic) against her Magic (250, +30), whatever her Defence: roll and max. */
    val MAGE = mapOf("Tumeken's shadow" to Ranged(72050, 60), "Shadow ancient sceptre" to Ranged(27641, 38))

    /** Their defence rolls against her styles. */
    val PDEF = mapOf("mage" to 9196, "range" to 52080, "crush" to 52080)
}
