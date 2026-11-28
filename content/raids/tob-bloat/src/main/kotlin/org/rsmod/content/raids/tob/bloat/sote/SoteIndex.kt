package org.rsmod.content.raids.tob.bloat.sote

import org.rsmod.content.raids.tob.bloat.maiden.MaidenIndex
import org.rsmod.content.raids.tob.bloat.maiden.SimRng

/**
 * 1000 headless Sotetseg raids per team size and way across the mazes (seeds s1..s1000, the sim's plan), so a room can replay one
 * picked by its time: the median, the fastest or slowest 10%, a given time, or one in Blert's top 50. The live room
 * plays the same raid.
 */
object SoteIndex {
    class Run(val seed: String, val end: Int, val mazes: String, val deaths: Int) {
        val time get() = MaidenIndex.fmt(end)
    }

    const val RAIDS = 1000

    /** Blert's 50 fastest regular Sotetseg rooms by team size (best and 50th, in ticks; leaderboard of 2026-11-27). */
    private val TOP50 = mapOf(1 to 432..477, 2 to 210..222, 3 to 142..162, 4 to 135..146, 5 to 126..137)

    fun play(team: Int, way: String, seed: String): SoteEngine {
        val e = SoteEngine(SotePlan.of(team, way), SimRng(seed))
        while (!e.over && e.t < 1000) e.tick()
        return e
    }

    private fun of(seed: String, e: SoteEngine) = Run(seed, e.end ?: 1000, e.mazes.joinToString("/"), e.ps.count { it.dead })

    private val runs = HashMap<Pair<Int, String>, List<Run>>()

    fun runs(team: Int, way: String): List<Run> =
        synchronized(runs) { runs.getOrPut(team to way) { (1..RAIDS).map { "s$it".let { s -> of(s, play(team, way, s)) } }.sortedBy { it.end } } }

    fun median(team: Int, way: String): Run = runs(team, way).let { it[it.size / 2] }

    fun top10(team: Int, way: String, pick: Int): Run = runs(team, way).take(RAIDS / 10).let { it[Math.floorMod(pick, it.size)] }

    fun slow10(team: Int, way: String, pick: Int): Run = runs(team, way).takeLast(RAIDS / 10).let { it[Math.floorMod(pick, it.size)] }

    /** A raid whose time is in Blert's top 50 for the team size (the nearest to that range if none is). */
    fun top50(team: Int, way: String, pick: Int): Run {
        val band = TOP50.getValue(team)
        val inBand = runs(team, way).filter { it.end in band }
        if (inBand.isNotEmpty()) return inBand[Math.floorMod(pick, inBand.size)]
        return runs(team, way).minBy { if (it.end < band.first) band.first - it.end else it.end - band.last }
    }

    fun time(team: Int, way: String, text: String, pick: Int): Run? {
        val want = MaidenIndex.parse(text) ?: return null
        val best = runs(team, way).minOf { kotlin.math.abs(it.end - want) }
        return runs(team, way).filter { kotlin.math.abs(it.end - want) == best }.let { it[Math.floorMod(pick, it.size)] }
    }

    fun seed(team: Int, way: String, seed: String): Run = runs(team, way).firstOrNull { it.seed == seed } ?: of(seed, play(team, way, seed))

    fun stats(team: Int, way: String = "pneck"): String {
        val r = runs(team, way)
        val band = TOP50.getValue(team)
        return "$team-man ($way), $RAIDS raids: fastest ${r.first().time}, top 10% ${r[RAIDS / 10].time}, median ${median(team, way).time}, slowest ${r.last().time}; " +
            "in Blert's top 50 (${MaidenIndex.fmt(band.first)}-${MaidenIndex.fmt(band.last)}) ${r.count { it.end in band }}; a death in ${r.count { it.deaths > 0 }}"
    }
}

/** `java ... SoteStats 5 2/run ...`: the index's spread for each team size (and way; all sizes if none is given). */
object SoteStats {
    @JvmStatic
    fun main(args: Array<String>) {
        val teams = if (args.isEmpty()) listOf("5", "4", "3", "2", "1") else args.toList()
        for (arg in teams) arg.split('/').let { println(SoteIndex.stats(it[0].toInt(), it.getOrNull(1) ?: "pneck")) }
    }
}
