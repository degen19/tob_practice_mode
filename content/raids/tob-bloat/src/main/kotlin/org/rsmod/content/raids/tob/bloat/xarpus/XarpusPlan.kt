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
    /** Chally last hit: a player who'd scythe halberds once his HP is at or under [challyAt]% of his start. */
    val chally: Boolean = false,
    val challyAt: Int = 5,
    /** A planned ZCB becomes claws below this HP. */
    val zcbBelow: Int = 450,
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
        private const val RALOS = "ralos"

        /**
         * The plan each scale plays: the sim's Xarpus Solver (verzikSim engine/xarpussolve.js) at its normal effort,
         * default players with an Elder maul and a BGS, no surge or horn - kept to three maulers. Its 5s pick had a
         * fourth; with a BGS opener in that seat it's the same median in the sim (2:01.8, 4000 raids), and in 4s a
         * fourth mauler is 2.4s slower: its gain is the ZCBs, 95% starts and Lightbearer cycles, not the maul.
         */
        val BY_SCALE: Map<Int, XarpusPlan> =
            mapOf(
                1 to XarpusPlan(listOf(Player("maul", "claws", lbCycles = 2)), remaul = mapOf(0 to listOf(0), 1 to listOf(0)), backups = emptyList()),
                2 to
                    XarpusPlan(
                        listOf(Player("maul", "claws"), Player("maul", "zcb", startSpec = 95)),
                        remaul = mapOf(0 to listOf(0, 1), 1 to listOf(0), 2 to emptyList()),
                        backups = listOf(1 to "maul", 1 to RALOS, 1 to RALOS, 1 to "bgs", 0 to "bgs"),
                        chally = true,
                    ),
                3 to
                    XarpusPlan(
                        listOf(Player("maul", "claws"), Player("maul", "zcb", startSpec = 95), Player("bgs", "zcb", startSpec = 95)),
                        remaul = mapOf(0 to listOf(0, 1, 2), 1 to listOf(0, 1), 2 to emptyList()),
                        backups = listOf(0 to RALOS, 1 to RALOS, 2 to RALOS, 0 to "bgs"),
                        holdZcb = false,
                    ),
                4 to
                    XarpusPlan(
                        listOf(
                            Player("maul", "zcb", fix = "claw", startSpec = 95),
                            Player("maul", "zcb", lbCycles = 3),
                            Player("maul", "zcb", lbCycles = 3),
                            Player("bgs", "zcb", startSpec = 95),
                        ),
                        remaul = mapOf(0 to listOf(0, 1, 2), 1 to listOf(0, 1), 2 to emptyList(), 3 to emptyList()),
                        backups = listOf(3 to RALOS, 1 to RALOS, 2 to RALOS),
                    ),
                5 to
                    XarpusPlan(
                        listOf(
                            Player("maul", "zcb", fix = "claw"),
                            Player("maul", "zcb", lbCycles = 1, startSpec = 95),
                            Player("bgs", "zcb", lbCycles = 1, startSpec = 95),
                            Player("maul", "zcb", lbCycles = 1, startSpec = 95),
                            Player("bgs", "zcb", lbCycles = 1, startSpec = 95),
                        ),
                        remaul = mapOf(0 to listOf(0, 1, 2), 1 to listOf(0, 1), 2 to emptyList(), 3 to emptyList()),
                        backups = listOf(3 to RALOS, 4 to RALOS, 3 to "bgs"),
                    ),
            )

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
