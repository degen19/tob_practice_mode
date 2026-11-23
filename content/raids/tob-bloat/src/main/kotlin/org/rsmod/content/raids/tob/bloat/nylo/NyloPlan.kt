package org.rsmod.content.raids.tob.bloat.nylo

/**
 * One player's Nylocas boss plan (verzikSim engine/nylo.js normPlan): their opening attack (none / bgs / claw / halb),
 * damage specs in order (claw / halb / tome / zcb), claws asap or only when they gain a tick, start spec, surge.
 */
class NyloPlan(
    val open: String = "none",
    val specs: List<String> = emptyList(),
    val clawMode: String = "asap",
    val startSpec: Int = 100,
    val surge: Boolean = false,
    val tomeTickFix: Boolean = true,
) {
    companion object {
        /**
         * The 5s: the sim's test plan for P1-P4 - P1 95%: scythe, halberd, ZCB in place of their first Twisted bow; P2
         * claws, surge, ZCB; P3 claws, then claws when they gain a tick; P4 BGS, then a tome spec with its tick fix -
         * and P5 the sim's default plan (claws when they gain a tick).
         */
        val FIVE =
            listOf(
                NyloPlan(specs = listOf("halb", "zcb"), startSpec = 95),
                NyloPlan(open = "claw", specs = listOf("zcb"), surge = true),
                NyloPlan(open = "claw", specs = listOf("claw"), clawMode = "gain"),
                NyloPlan(open = "bgs", specs = listOf("tome")),
                NyloPlan(specs = listOf("claw"), clawMode = "gain"),
            )
    }
}
