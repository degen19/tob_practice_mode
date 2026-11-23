package org.rsmod.content.raids.tob.bloat.xarpus

import org.rsmod.content.raids.tob.bloat.maiden.MaidenIndex
import org.rsmod.content.raids.tob.bloat.maiden.SimRng

/**
 * 1000 headless Xarpus raids (seeds x1..x1000, the 5s plan), so a room can replay one picked by its time: the median,
 * the fastest or slowest 10%, a given time. The live room plays the same raid for a seed (the bots stand still).
 */
object XarpusIndex {
    class Run(val seed: String, val end: Int, val heals: Int, val screech: Int?) {
        val time get() = MaidenIndex.fmt(end)
    }

    const val RAIDS = 1000

    fun play(seed: String): XarpusEngine {
        val e = XarpusEngine(5, XarpusPlan.FIVE, SimRng(seed))
        while (!e.over && e.t < e.exh + MAX_TICKS) e.tick()
        return e
    }

    private fun of(seed: String, e: XarpusEngine) = Run(seed, e.end ?: (e.exh + MAX_TICKS), e.heals, e.screech)

    val runs: List<Run> by lazy { (1..RAIDS).map { "x$it".let { s -> of(s, play(s)) } }.sortedBy { it.end } }

    fun median(): Run = runs[runs.size / 2]

    fun top10(pick: Int): Run = runs.take(RAIDS / 10).let { it[Math.floorMod(pick, it.size)] }

    fun slow10(pick: Int): Run = runs.takeLast(RAIDS / 10).let { it[Math.floorMod(pick, it.size)] }

    fun time(text: String, pick: Int): Run? {
        val want = MaidenIndex.parse(text) ?: return null
        val best = runs.minOf { kotlin.math.abs(it.end - want) }
        return runs.filter { kotlin.math.abs(it.end - want) == best }.let { it[Math.floorMod(pick, it.size)] }
    }

    fun seed(seed: String): Run = runs.firstOrNull { it.seed == seed } ?: of(seed, play(seed))

    fun stats(): String = "$RAIDS raids: fastest ${runs.first().time}, median ${median().time}, slowest ${runs.last().time} (the room's time, exhumeds included)"

    const val MAX_TICKS = 3000
}
