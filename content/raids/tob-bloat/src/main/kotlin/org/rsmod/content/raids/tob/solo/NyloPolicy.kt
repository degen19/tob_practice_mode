package org.rsmod.content.raids.tob.solo

/**
 * The Nylocas bots' learned target choice (`tob/nylo_policy.tsv`): per role, a weight for each of [FEATURES] and each
 * weapon, trained in the stand-alone Nylocas sim; and each weapon's chance to kill a nylocas with so many hitpoints
 * left in one hit, from the damage the server's bots deal.
 */
class NyloPolicy(private val weights: Map<String, DoubleArray>, private val kills: Map<String, DoubleArray>) {
    /** A role's weights for where the room is: waves 1-19, 20-27, 28 to the end each have their own. */
    fun weights(role: Char, waves: Int): DoubleArray = weights.getValue("$role${PHASE_FROM.indexOfLast { waves >= it }}")

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
                "split", "crowd", "mustLast", "bigLate", "endRank", "endGap", "ownKills", "lateWave", "cleanBig", "aggroLate",
                "onlyMe", "oldness", "oldLate",
            )

        /** A lane mouth to cover for the next wave: what it scores on (weights after the attack ones). */
        val POST_FEATURES = listOf("pbias", "untilWave", "ownLane", "allLane", "bigLane", "postDist", "othersThere", "mine", "pwave", "pcrowd")
        val POST_AT = FEATURES.size + NyloCrew.Weapon.entries.size

        val PHASE_FROM = listOf(0, 20, 28)

        const val FILE = "/tob/nylo_policy.tsv"

        /** `TOB_NYLO_POLICY` (tests): a weights file to play instead of the one shipped. */
        val LOADED: NyloPolicy by lazy {
            val text = System.getenv("TOB_NYLO_POLICY")?.let { java.io.File(it).readText() }
                ?: checkNotNull(NyloPolicy::class.java.getResourceAsStream(FILE)) { "missing $FILE" }.bufferedReader().readText()
            parse(text)
        }

        fun parse(text: String): NyloPolicy {
            val size = FEATURES.size + NyloCrew.Weapon.entries.size + POST_FEATURES.size
            val weights = HashMap<String, DoubleArray>()
            val kills = HashMap<String, DoubleArray>()
            for (line in text.lineSequence()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val f = line.split('\t')
                when (f[0]) {
                    "w" -> {
                        val row = weights.getOrPut(f[1]) { DoubleArray(size) }
                        val feature = FEATURES.indexOf(f[2])
                        val weapon = NyloCrew.Weapon.entries.indexOfFirst { it.name.lowercase() == f[2] }
                        val post = POST_FEATURES.indexOf(f[2])
                        val at =
                            when {
                                feature >= 0 -> feature
                                weapon >= 0 -> FEATURES.size + weapon
                                post >= 0 -> POST_AT + post
                                else -> continue
                            }
                        row[at] = f[3].toDouble()
                    }
                    "kill" -> kills["${f[1]}:${f[2]}"] = f[3].split(',').map { it.toDouble() }.toDoubleArray()
                }
            }
            return NyloPolicy(weights, kills)
        }
    }
}
