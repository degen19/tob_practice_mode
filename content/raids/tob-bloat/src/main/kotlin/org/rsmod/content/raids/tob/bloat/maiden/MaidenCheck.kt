package org.rsmod.content.raids.tob.bloat.maiden

/** `java ... MaidenCheckKt seed...`: one raid per seed, the sim's log lines (for checking against the JS sim). */
fun main(args: Array<String>) {
    for (seed in args) {
        val log = ArrayList<String>()
        val e = MaidenEngine(5, MaidenPlans.FIVE_STACK, SimRng(seed), MaidenPlans.FIVE_TARGET, log = log)
        while (!e.over && e.t < 600) e.tick()
        println("== $seed dead ${e.dead} procs ${e.proc}/${e.proc50}/${e.proc30} healed ${e.healed} leaks ${e.leaks}")
        for (l in log) println(l)
    }
}
