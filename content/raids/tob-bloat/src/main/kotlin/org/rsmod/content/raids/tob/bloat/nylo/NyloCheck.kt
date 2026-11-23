package org.rsmod.content.raids.tob.bloat.nylo

import org.rsmod.content.raids.tob.bloat.maiden.SimRng

/** `java ... NyloCheckKt seed...`: one fight per seed (for checking against the JS sim's: nylo/nlog.mjs). */
fun main(args: Array<String>) {
    for (seed in args) {
        val e = NyloEngine(NyloPlan.FIVE, SimRng(seed))
        while (!e.over && e.t < e.spawn + 599) e.tick()
        println("$seed kill ${e.kill} over ${e.overkill} end ${e.end} def ${e.def} hp ${e.ps.joinToString(",") { it.hp.toString() }} st ${e.stVeng},${e.stWrong},${e.stN2late},${e.stUnder}")
    }
}
