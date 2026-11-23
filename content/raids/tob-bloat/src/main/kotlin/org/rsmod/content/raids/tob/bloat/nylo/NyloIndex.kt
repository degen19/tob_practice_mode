package org.rsmod.content.raids.tob.bloat.nylo

import org.rsmod.content.raids.tob.bloat.maiden.MaidenIndex
import org.rsmod.content.raids.tob.bloat.maiden.SimRng

/**
 * 1000 headless Nylo boss fights (seeds n1..n1000, the 5s plan), so a room can replay one picked by its boss time (his
 * spawn to the room's end): the median, the fastest or slowest 10%, a given time. The live room plays the same fight.
 */
object NyloIndex {
    class Run(val seed: String, val boss: Int, val forms: String) {
        val time get() = MaidenIndex.fmt(boss)
    }

    const val RAIDS = 1000

    fun play(seed: String): NyloEngine {
        val e = NyloEngine(NyloPlan.FIVE, SimRng(seed))
        while (!e.over && e.t < e.spawn + 599) e.tick()
        return e
    }

    private fun of(seed: String, e: NyloEngine) =
        Run(seed, (e.end ?: (e.spawn + 600)) - e.i1, e.sched.takeWhile { it.start <= (e.kill ?: 0) }.joinToString("") { it.form.take(2) })

    val runs: List<Run> by lazy { (1..RAIDS).map { "n$it".let { s -> of(s, play(s)) } }.sortedBy { it.boss } }

    fun median(): Run = runs[runs.size / 2]

    fun top10(pick: Int): Run = runs.take(RAIDS / 10).let { it[Math.floorMod(pick, it.size)] }

    fun slow10(pick: Int): Run = runs.takeLast(RAIDS / 10).let { it[Math.floorMod(pick, it.size)] }

    fun time(text: String, pick: Int): Run? {
        val want = MaidenIndex.parse(text) ?: return null
        val best = runs.minOf { kotlin.math.abs(it.boss - want) }
        return runs.filter { kotlin.math.abs(it.boss - want) == best }.let { it[Math.floorMod(pick, it.size)] }
    }

    fun seed(seed: String): Run = runs.firstOrNull { it.seed == seed } ?: of(seed, play(seed))

    fun stats(): String = "$RAIDS fights: boss time fastest ${runs.first().time}, median ${median().time}, slowest ${runs.last().time}"
}

/** `java ... NyloStats`: the index's spread. */
object NyloStats {
    @JvmStatic
    fun main(args: Array<String>) = println(NyloIndex.stats())
}
