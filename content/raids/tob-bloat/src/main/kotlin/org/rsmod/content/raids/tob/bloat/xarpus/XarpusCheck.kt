package org.rsmod.content.raids.tob.bloat.xarpus

import org.rsmod.content.raids.tob.bloat.maiden.SimRng

/** `java ... XarpusCheckKt seed...`: one raid per seed and its log (for checking against the JS sim's: xarpus/xlog.mjs). */
fun main(args: Array<String>) {
    for (seed in args) {
        val log = ArrayList<String>()
        val e = XarpusEngine(5, XarpusPlan.FIVE, SimRng(seed), log = log)
        while (!e.over && e.t < e.exh + 3000) e.tick()
        println("== $seed end ${e.end} kill ${e.kill} start ${e.startHp} screech ${e.screech}")
        for (l in log) println(l)
    }
}

/** `java ... XarpusStatsKt`: the index's spread. */
object XarpusStats {
    @JvmStatic
    fun main(args: Array<String>) = println(XarpusIndex.stats())
}
