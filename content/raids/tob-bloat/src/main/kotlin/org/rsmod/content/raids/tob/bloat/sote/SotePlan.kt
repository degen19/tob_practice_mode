package org.rsmod.content.raids.tob.bloat.sote

import org.rsmod.content.raids.tob.bloat.sote.SoteEngine.Spec

/** One player's Sotetseg plan (verzikSim engine/sote.js defaultSetup / defaultSpecs): prefire, start spec, surge, specs by phase. */
class SotePlan(val prefire: String, val startSpec: Int, val surge: Boolean, val specs: List<Spec>, val pnecks: Int = 2) {
    companion object {
        private val A = listOf(Spec("maul", horn = 1), Spec("maul"), Spec("none"))
        private val B = listOf(Spec("maul"), Spec("none"), Spec("maul"))
        private val C = listOf(Spec("zcb"), Spec("none"), Spec("zcb", backup = true))
        private val E = listOf(Spec("none"), Spec("maul"), Spec("maul"))

        /**
         * 5s (the sim's default): P1 horns and mauls P1, mauls P2; P2 mauls P1 and P3; P3 / P4 ZCB in P1 and back up in
         * P3; P5 mauls P2 and P3. P1 / P2 prefire a Twisted bow, the rest a ZCB; the P1 ZCB players start on 95; P1, P3
         * and P4 surge. Everyone stalls both mazes on a phoenix necklace.
         */
        val FIVE =
            listOf(
                SotePlan("Twisted bow", 100, true, A),
                SotePlan("Twisted bow", 100, false, B),
                SotePlan("ZCB auto", 95, true, C),
                SotePlan("ZCB auto", 95, true, C),
                SotePlan("ZCB auto", 100, false, E),
            )
    }
}
