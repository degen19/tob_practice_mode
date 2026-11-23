package org.rsmod.content.raids.tob.bloat.maiden

/**
 * A batch of headless raids (seeds r1..rN), so a room can replay one picked by its result: the median, the fastest
 * 10%, a given time, a failed one. Each is played as the room plays it - the engine with the bots' movement
 * ([MaidenMover]) - so a raid picked here comes out the same in the room. Built the first time it's asked for.
 */
object MaidenIndex {
    class Run(val seed: String, val dead: Int, val leaks: Int, val healed: Int, val procs: String) {
        /** The room's time: her death plus the 8 ticks the room takes to end (t94 -> 1:01.2). */
        val ticks get() = dead + MaidenEngine.DEATH_TICKS
        val success get() = leaks < FAIL_LEAKS
        val time get() = fmt(ticks)
    }

    const val RAIDS = 1000
    /** A raid with this many crabs leaked or more is a failed one (a whole wave got loose). */
    const val FAIL_LEAKS = 10

    val runs: List<Run> by lazy {
        (1..RAIDS).map { k ->
            val seed = "r$k"
            of(seed, MaidenMover.play(seed))
        }
    }

    private fun of(seed: String, e: MaidenEngine) = Run(seed, e.dead ?: MaidenMover.MAX_T, e.leaks, e.healed, "${e.proc}/${e.proc50}/${e.proc30}")

    private val wins get() = runs.filter { it.success }.sortedBy { it.ticks }

    fun median(): Run? = wins.let { it.getOrNull(it.size / 2) }

    /** One of the fastest 10% of the successful raids. */
    fun top10(pick: Int): Run? = wins.let { w -> w.take(maxOf(1, w.size / 10)).let { it[Math.floorMod(pick, it.size)] } }

    /** One of the slowest 10% of the successful raids. */
    fun slow10(pick: Int): Run? = wins.let { w -> w.takeLast(maxOf(1, w.size / 10)).let { it[Math.floorMod(pick, it.size)] } }

    fun failed(pick: Int): Run? = runs.filter { !it.success }.let { if (it.isEmpty()) null else it[Math.floorMod(pick, it.size)] }

    /** A successful raid with this room time ("1:01.2"), else the closest. */
    fun time(text: String, pick: Int): Run? {
        val want = parse(text) ?: return null
        val best = wins.minOfOrNull { kotlin.math.abs(it.ticks - want) } ?: return null
        return wins.filter { kotlin.math.abs(it.ticks - want) == best }.let { it[Math.floorMod(pick, it.size)] }
    }

    fun seed(seed: String): Run = runs.firstOrNull { it.seed == seed } ?: of(seed, MaidenMover.play(seed))

    fun stats(): String {
        val w = wins
        val pct = 100 * w.size / runs.size
        return "$RAIDS raids: $pct% successful (under $FAIL_LEAKS leaks) - fastest ${w.firstOrNull()?.time}, median ${median()?.time}, slowest ${w.lastOrNull()?.time}"
    }

    fun fmt(ticks: Int): String {
        val tenths = ticks * 6
        return "%d:%02d.%d".format(tenths / 600, tenths / 10 % 60, tenths % 10)
    }

    /** "1:01.2" (or "61.2") -> ticks. */
    fun parse(text: String): Int? {
        val m = Regex("""^(?:(\d+):)?(\d+)(?:\.(\d))?$""").find(text.trim()) ?: return null
        val tenths = (m.groupValues[1].ifEmpty { "0" }.toInt() * 60 + m.groupValues[2].toInt()) * 10 + m.groupValues[3].ifEmpty { "0" }.toInt()
        return Math.round(tenths / 6.0).toInt()
    }
}
