package org.rsmod.content.raids.tob.bloat.sote

import org.rsmod.content.raids.tob.bloat.maiden.SimRng

/** `java ... SoteCheckKt seed...`: one raid per seed and its log (for checking against the JS sim's: sote/slog.mjs). */
fun main(args: Array<String>) {
    for (seed in args) {
        val log = ArrayList<String>()
        val e = SoteEngine(SotePlan.FIVE, SimRng(seed), log = log)
        while (!e.over && e.t < 1000) e.tick()
        println("== $seed end ${e.end} kill ${e.kill} mazes ${e.mazes.joinToString(",")} hp ${e.ps.joinToString(",") { it.hp.toString() }}")
        for (l in log) println(l)
    }
}
