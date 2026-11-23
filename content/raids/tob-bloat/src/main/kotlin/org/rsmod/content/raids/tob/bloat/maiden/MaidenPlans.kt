package org.rsmod.content.raids.tob.bloat.maiden

/** The players' lists (verzikSim engine/maiden.js): the prefires (seq), then each phase's list. */
object MaidenPlans {
    class PlayerPlan(
        val seq: List<String>,
        val s70: List<String>,
        val s50: List<String>,
        val s30: List<String>,
        val side: String? = null,
        val startSpec: Int = 100,
    )

    /**
     * 5s: the stacked procedure (tested Nov 18) - short 70s freezes so those crabs come out of immunity as the 50s
     * sceptre barrages go out and get frozen again with the new wave. P1 / P2 freeze from the east, P3 freezes then
     * melees north, P4 (south, the S1 player) bursts the 4s, P5 (north) halberds the 70s N1. Her attacks on P5.
     */
    val FIVE_STACK =
        listOf(
            PlayerPlan(listOf("tbr", "bp", "ralos", "tbr"), listOf("ibs:S1", "ibl:N3", "tbr", "tbr", "zcbs", "tbr"), listOf("ibs:N1", "ibs:N2", "ibs:3s", "tbr"), listOf("ibs:N1", "ibs:N2", "ibs:3s", "scythe", "chally")),
            PlayerPlan(listOf("tbr", "bp", "ralos", "tbr"), listOf("ibl:S2", "ibl:S3", "tbr"), listOf("ibs:S1", "ibs:S2@70", "tbr", "zcbs", "tbr"), listOf("ibs:S1", "ibs:S2", "ibs:4s", "scythe", "chally")),
            PlayerPlan(listOf("tbr", "maul", "bp", "tbr"), listOf("ibl:N2", "scyc:N1@70:25|scythe", "tbr"), listOf("tbr", "tbr", "ibs:4s+11", "zcbs", "scythe"), listOf("scythe", "chally")),
            PlayerPlan(listOf("zcbs", "scythe"), listOf("ibu:N4", "ibu:4s", "scythe", "s1if:58", "scythe"), listOf("chally:S1@70", "scyc:S1@70:0", "scythe"), listOf("scythe", "chally"), side = "S"),
            PlayerPlan(listOf("tba", "scythe"), listOf("chally:N1@70", "scythe", "scythe", "tbr", "scythe"), listOf("scythe", "scythe", "scythe", "zcbs", "scythe"), listOf("scythe", "chally"), side = "N"),
        )

    /** Her attacks go on this player (index) in the 5s plan. */
    const val FIVE_TARGET = 4
}
