package org.rsmod.content.raids.tob.bloat.sote

import org.rsmod.content.raids.tob.bloat.sote.SoteEngine.Spec

/**
 * One player's Sotetseg plan (verzikSim engine/sote.js defaultSetup / defaultSpecs / defaultMazes, with cli/sote.mjs's
 * supplies): prefire, start spec, surge, specs by phase, how they cross each maze, and what they bring.
 */
class SotePlan(
    val prefire: String,
    val startSpec: Int,
    val surge: Boolean,
    val specs: List<Spec>,
    val mazes: List<String>,
    val pnecks: Int = 2,
    val sharks: Int = 4,
    val rests: Int = 4,
    val restores: Int = 3,
) {
    companion object {
        private val A = listOf(Spec("maul", horn = 1), Spec("maul"), Spec("none"))
        private val B = listOf(Spec("maul"), Spec("none"), Spec("maul"))
        private val C = listOf(Spec("zcb"), Spec("none"), Spec("zcb", backup = true))
        private val E = listOf(Spec("none"), Spec("maul"), Spec("maul"))
        private val PNECKS = listOf("pneck", "pneck")

        /** How a team crosses the mazes: phoenix necklaces (the default), or short of them, or running. */
        val WAYS = listOf("pneck", "shark", "noneck", "redemption", "run")

        /**
         * The team for a scale. 5s and 4s: the sim's defaults - 5s: P1 horns and mauls P1, mauls P2; P2 mauls P1 and P3;
         * P3 / P4 ZCB in P1 and back up in P3; P5 mauls P2 and P3; P1 / P2 prefire a Twisted bow, the rest a ZCB, the P1
         * ZCB players start on 95 and surge with P1; 4s the same without P4. Trios to solos: Blert's most common team
         * among the 50 fastest (2026-11-27) - trios: a Shadow Barrage prefire mauls P2 and P3; a Twisted bow mauls P1 and
         * ZCB specs P3; a ZCB prefire mauls P2 and P3. Duos: a Twisted bow and a Tumeken's shadow, both mauling every phase
         * (the bow backs up in P1 if both miss). Solos: Tumeken's shadow, a maul landed each phase. Start spec and surges
         * as the sim's solver tunes them for those plans (verzikSim room/sote).
         *
         * [way] - pneck: everyone stalls both mazes on a phoenix necklace (two each). shark: one necklace - a shark skip,
         * then the necklace (sim defaultMazes, no locator orb). noneck: shark skips both. redemption: one necklace, then a
         * redemption skip with a locator orb (up to 4s). run: everyone runs both. From trios up only the last player is
         * short of necklaces (one skipper at most, away from the others); in duos both are. Solos always redemption skip.
         */
        fun of(team: Int, way: String = "pneck"): List<SotePlan> {
            require(way in WAYS) { "no Sotetseg way $way" }
            val base =
                when (team) {
                    5 -> listOf(big(0, A), big(1, B), big(2, C), big(3, C), big(4, E))
                    4 -> listOf(big(0, A), big(1, B), big(2, C), big(3, E))
                    3 -> listOf(
                        SotePlan("Shadow Barrage", 100, false, listOf(Spec("none"), Spec("maul"), Spec("maul")), PNECKS),
                        SotePlan("Twisted bow", 100, true, listOf(Spec("maul"), Spec("none"), Spec("zcb")), PNECKS),
                        SotePlan("ZCB auto", 100, true, listOf(Spec("none"), Spec("maul"), Spec("maul")), PNECKS),
                    )
                    2 -> listOf(
                        SotePlan("Twisted bow", 100, true, listOf(Spec("maul", duoBackup = true), Spec("maul"), Spec("maul")), PNECKS),
                        SotePlan("Tumeken's shadow", 100, true, List(3) { Spec("maul") }, PNECKS),
                    )
                    1 -> return listOf(SotePlan("Tumeken's shadow", 95, true, List(3) { Spec("maul", untilHit = true) }, listOf("redemption", "redemption"), rests = 0))
                    else -> error("no Sotetseg plan for $team")
                }
            if (way == "pneck") return base
            val (mazes, pnecks) =
                when (way) {
                    "shark" -> listOf("shark", "pneck") to 1
                    "noneck" -> listOf("shark", "shark") to 0
                    "redemption" -> listOf("pneck", "redemption") to 1
                    else -> listOf("run", "run") to 2
                }
            return base.mapIndexed { i, p -> if (way == "run" || team == 2 || i == team - 1) p.crossing(mazes, pnecks) else p }
        }

        private fun SotePlan.crossing(mazes: List<String>, pnecks: Int) = SotePlan(prefire, startSpec, surge, specs, mazes, pnecks, sharks, rests, restores)

        private fun big(i: Int, specs: List<Spec>): SotePlan {
            val zcb1 = specs[0].spec == "zcb"
            return SotePlan(if (i < 2) "Twisted bow" else "ZCB auto", if (zcb1) 95 else 100, i == 0 || zcb1, specs, PNECKS)
        }
    }
}
