package org.rsmod.content.raids.tob.bloat.sote

import org.rsmod.content.raids.tob.bloat.maiden.SimRng

/** `java ... SoteCheckKt team seed...`: one raid per seed and its log, for checking against the sim's (tools/sote-parity). */
fun main(args: Array<String>) {
    val team = args[0].toInt()
    for (seed in args.drop(1)) {
        val log = ArrayList<String>()
        val e = SoteEngine(SotePlan.of(team), SimRng(seed), log = log)
        while (!e.over && e.t < 1000) e.tick()
        println("== $seed end ${e.end} kill ${e.kill} mazes ${e.mazes.joinToString(",")} hp ${e.ps.joinToString(",") { it.hp.toString() }}")
        for (l in log) println(l)
    }
}
