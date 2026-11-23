package org.rsmod.content.raids.tob.bloat.maiden

import kotlin.math.floor

/**
 * The sim's combat numbers for the 5s plan's gear (verzikSim engine/maiden.js ranged / melee / barrageOf / autoChance,
 * worked out by scratchpad/maiden/nums.mjs from the sim's own item stats): ranged in elite void on Rigour at 112, melee
 * on Zeal at 118, magic on Augury at 112. Every player wears the same.
 */
object MaidenNumbers {
    /** Effective attack level, (attack bonus + 64), max hit. */
    class Roll(val ea: Int, val ab: Int, val mx: Int)

    private val RANGED =
        mapOf(
            "Twisted bow|0" to Roll(156, 202, 40), "Twisted bow|1" to Roll(159, 202, 41),
            "Toxic blowpipe|0" to Roll(156, 152, 34), "Toxic blowpipe|1" to Roll(159, 152, 34),
            "Zaryte crossbow|0" to Roll(156, 242, 51), "Zaryte crossbow|1" to Roll(159, 242, 52),
            "Tonalztics of Ralos|0" to Roll(156, 237, 34), "Tonalztics of Ralos|1" to Roll(159, 237, 34),
            "Ascension crossbows|0" to Roll(156, 156, 34), "Ascension crossbows|1" to Roll(159, 156, 35),
            "Black chinchompa|0" to Roll(156, 202, 27), "Black chinchompa|1" to Roll(159, 202, 28),
        )

    private val MELEE =
        mapOf(
            "Scythe of vitur|1" to Roll(155, 267, 52), "Scythe of vitur|2" to Roll(155, 144, 52),
            "Elder maul|1" to Roll(155, 142, 70), "Elder maul|2" to Roll(155, 249, 70),
            "Crystal halberd|1" to Roll(155, 252, 63), "Crystal halberd|2" to Roll(155, 119, 63),
            "Dragon claws|1" to Roll(155, 199, 47), "Dragon claws|2" to Roll(155, 110, 47),
            "Bandos godsword|1" to Roll(155, 274, 66), "Bandos godsword|2" to Roll(155, 194, 66),
        )

    /** Freeze spells by list code: [max, accuracy roll]. */
    val MAGIC = mapOf("ib" to (45 to 38591), "ibs" to (43 to 37399), "ibl" to (35 to 38889), "ibu" to (29 to 38889), "aya" to (35 to 38889), "rush" to (45 to 38591))

    /** Her attack's chance to hit in each style's gear. */
    val AUTO = mapOf("range" to 0.9254535993327059, "melee" to 0.9674234945705824, "mage" to 0.9361402542145901)

    /** The Twisted bow against her (Magic 350): accuracy and damage multipliers. */
    const val TBOW_ACC = 1.4
    const val TBOW_DMG = 2.15

    fun hitChance(a: Int, d: Int): Double = if (a > d) 1.0 - (d + 2).toDouble() / (2.0 * (a + 1)) else a.toDouble() / (2.0 * (d + 1))

    /** Every defence bonus 0: the defence roll at Defence level d. */
    fun dRoll(d: Int): Int = (d + 9) * 64

    /** [chance, max] for a ranged attack against Defence d. */
    fun ranged(weapon: String, d: Int, accMult: Double = 1.0, accurate: Boolean = false): Pair<Double, Int> {
        val r = RANGED.getValue("$weapon|${if (accurate) 1 else 0}")
        var roll = floor(r.ea.toDouble() * r.ab * accMult).toInt()
        var mx = r.mx
        if (weapon == "Twisted bow") {
            roll = floor(roll * TBOW_ACC).toInt()
            mx = floor(mx * TBOW_DMG).toInt()
        }
        return hitChance(roll, dRoll(d)) to mx
    }

    /** [chance, max] for a melee attack (idx 1 slash, 2 crush) against Defence d. */
    fun melee(weapon: String, idx: Int, d: Int, accMult: Double = 1.0): Pair<Double, Int> {
        val r = MELEE.getValue("$weapon|$idx")
        return hitChance(floor(r.ea.toDouble() * r.ab * accMult).toInt(), dRoll(d)) to r.mx
    }

    /* Expected damage (mean, variance) - verzikSim engine/expdmg.js */

    private fun landed(m0: Int, bonus: Int = 0): Pair<Double, Double> {
        val m = maxOf(0, m0)
        var s = 0.0
        var s2 = 0.0
        for (x in 0..m) {
            val v = (maxOf(1, x) + bonus).toDouble()
            s += v
            s2 += v * v
        }
        return s / (m + 1) to s2 / (m + 1)
    }

    fun hitMV(ch0: Double, m: Int, bonus: Int = 0): Pair<Double, Double> {
        val ch = ch0.coerceIn(0.0, 1.0)
        val (e, e2) = landed(m, bonus)
        val mean = ch * e
        return mean to ch * e2 - mean * mean
    }

    fun sumMV(vararg parts: Pair<Double, Double>): Pair<Double, Double> = parts.fold(0.0 to 0.0) { (m, v), (a, b) -> m + a to v + b }

    fun scytheMV(ch: Double, mx: Int): Pair<Double, Double> = sumMV(hitMV(ch, mx), hitMV(ch, mx / 2), hitMV(ch, mx / 4))

    fun halbMV(ch1: Double, ch2: Double, m: Int): Pair<Double, Double> = sumMV(hitMV(ch1, m, m / 10), hitMV(ch2, m, m / 10))

    fun constMV(p: Double, v: Int): Pair<Double, Double> = p * v to p * v * v - (p * v) * (p * v)
}
