package org.rsmod.content.raids.tob.solo

/**
 * The Nylocas bots' learned target choice (`tob/nylo_policy.tsv`): per role, a weight for each of [FEATURES] and each
 * weapon, trained in the stand-alone Nylocas sim; and each weapon's chance to kill a nylocas with so many hitpoints
 * left in one hit, from the damage the server's bots deal.
 */
class NyloPolicy(val weights: Map<Char, DoubleArray>, private val kills: Map<String, DoubleArray>) {
    fun killP(weapon: NyloCrew.Weapon, big: Boolean, hp: Int): Double {
        if (hp <= 0) return 1.0
        val table = kills["${weapon.name.lowercase()}:${if (big) 1 else 0}"] ?: return 0.0
        return table[(hp - 1).coerceAtMost(table.size - 1)]
    }

    companion object {
        val FEATURES =
            listOf(
                "bias", "own", "nVic", "bigVic", "nWrong", "big", "aggro", "biting", "life", "walk", "swap", "same", "stall",
                "standsAtCheck", "cleanup", "cleanGone", "speed", "kills", "inLane", "claimed", "bigSplitStall", "wave", "young",
                "split", "crowd", "mustLast",
            )

        const val FILE = "/tob/nylo_policy.tsv"

        /** `TOB_NYLO_POLICY` (tests): a weights file to play instead of the one shipped. */
        val LOADED: NyloPolicy? by lazy {
            val text = System.getenv("TOB_NYLO_POLICY")?.let { java.io.File(it).readText() }
                ?: NyloPolicy::class.java.getResourceAsStream(FILE)?.bufferedReader()?.readText() ?: return@lazy null
            parse(text)
        }

        fun parse(text: String): NyloPolicy {
            val size = FEATURES.size + NyloCrew.Weapon.entries.size
            val weights = HashMap<Char, DoubleArray>()
            val kills = HashMap<String, DoubleArray>()
            for (line in text.lineSequence()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val f = line.split('\t')
                when (f[0]) {
                    "w" -> {
                        val row = weights.getOrPut(f[1][0]) { DoubleArray(size) }
                        val feature = FEATURES.indexOf(f[2])
                        val weapon = NyloCrew.Weapon.entries.indexOfFirst { it.name.lowercase() == f[2] }
                        val at = if (feature >= 0) feature else if (weapon >= 0) FEATURES.size + weapon else continue
                        row[at] = f[3].toDouble()
                    }
                    "kill" -> kills["${f[1]}:${f[2]}"] = f[3].split(',').map { it.toDouble() }.toDoubleArray()
                }
            }
            return NyloPolicy(weights, kills)
        }
    }
}
