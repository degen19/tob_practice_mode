package org.rsmod.content.raids.tob.bloat.xarpus

/** A Xarpus plan (verzikSim engine/xarpus.js defaultXarpusPlan): each player's specs, the re-mauls and the backups. */
class XarpusPlan(
    val players: List<Player>,
    /** Opening mauls landed -> the players (indexes) who maul again. */
    val remaul: Map<Int, List<Int>>,
    /** In order: (player index, spec). */
    val backups: List<Pair<Int, String>>,
    val defTarget: Int = 20,
    val holdZcb: Boolean = true,
    /** The exhumeds' heals (each raid: this +-2). */
    val heals: Int = 1,
) {
    class Player(
        val open: String,
        val dps: String,
        val fix: String = "tick",
        val lbCycles: Int = 0,
        val startSpec: Int = 100,
        /** divine / regular / "" (not potted). */
        val rangePot: String = "",
    )

    companion object {
        /**
         * 5s: P1-P3 open with an Elder maul (P1's tick fix a claw spec; P2 / P3 three Lightbearer cycles), P4 / P5 open
         * with a Ralos Spec (95% start, divine ranging, two Lightbearer cycles). Re-mauls: none landed - all three
         * again; one - P1 and P2. Backups: P4 then P5 Ralos. ZCBs held for the screech.
         */
        val FIVE =
            XarpusPlan(
                players =
                    listOf(
                        Player("maul", "zcb", fix = "claw"),
                        Player("maul", "zcb", lbCycles = 3),
                        Player("maul", "zcb", lbCycles = 3),
                        Player("ralos", "zcb", lbCycles = 2, startSpec = 95, rangePot = "divine"),
                        Player("ralos", "zcb", lbCycles = 2, startSpec = 95, rangePot = "divine"),
                    ),
                remaul = mapOf(0 to listOf(0, 1, 2), 1 to listOf(0, 1), 2 to emptyList(), 3 to emptyList()),
                backups = listOf(3 to "ralos", 4 to "ralos"),
            )
    }
}
