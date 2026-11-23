package org.rsmod.content.raids.tob.bloat.verzik

import com.fasterxml.jackson.databind.ObjectMapper
import org.rsmod.content.raids.tob.bloat.maiden.MaidenIndex
import java.util.zip.GZIPInputStream

/**
 * The 5s Verzik raids the room plays: the sim's own raids (verzikSim engine run_p1 -> run_p2 -> run_reds -> run_p3, seeds
 * v1..v300), each as its timeline - every attack (who, what, when it lands, its damage, which crab), her events, her HP
 * and the phase points - exported by scratchpad verz/vexport.mjs from the sim's tick log. The setup is the chart
 * template's 5-man with the Verz Solver's best P1 chart (verz/solve5.mjs).
 */
object VerzikRaids {
    class Attack(val t: Int, val i: Int, val code: String, val lands: Int?, val dmg: Int, val crab: String)

    class Boss(val t: Int, val kind: String, val target: Int)

    class Hp(val t: Int, val hp: Int, val phase: String)

    class Raid(val seed: String, val end: Int?, val marks: List<Pair<Int, String>>, val attacks: List<Attack>, val boss: List<Boss>, val hp: List<Hp>) {
        val time get() = end?.let { MaidenIndex.fmt(it) } ?: "failed"

        fun markAt(name: String): Int? = marks.firstOrNull { it.second == name }?.first
    }

    val raids: List<Raid> by lazy {
        val stream = VerzikRaids::class.java.getResourceAsStream("/verzik/verzik5.json.gz") ?: return@lazy emptyList()
        val root = GZIPInputStream(stream).use { ObjectMapper().readTree(it) }
        root.map { r ->
            Raid(
                r["seed"].asText(),
                r["end"].takeIf { !it.isNull }?.asInt(),
                r["marks"].map { it[0].asInt() to it[1].asText() },
                r["atk"].map { Attack(it[0].asInt(), it[1].asInt(), it[2].asText(), it[3].takeIf { x -> !x.isNull }?.asInt(), it[4].asInt(), it[5].asText()) },
                r["boss"].map { Boss(it[0].asInt(), it[1].asText(), it[2].asInt()) },
                r["hp"].map { Hp(it[0].asInt(), it[1].asInt(), it[2].asText()) },
            )
        }
    }

    private val wins get() = raids.filter { it.end != null }.sortedBy { it.end }

    fun median(): Raid? = wins.let { it.getOrNull(it.size / 2) }

    fun top10(pick: Int): Raid? = wins.let { w -> w.take(maxOf(1, w.size / 10)).let { if (it.isEmpty()) null else it[Math.floorMod(pick, it.size)] } }

    fun slow10(pick: Int): Raid? = wins.let { w -> w.takeLast(maxOf(1, w.size / 10)).let { if (it.isEmpty()) null else it[Math.floorMod(pick, it.size)] } }

    fun failed(pick: Int): Raid? = raids.filter { it.end == null }.let { if (it.isEmpty()) null else it[Math.floorMod(pick, it.size)] }

    fun time(text: String, pick: Int): Raid? {
        val want = MaidenIndex.parse(text) ?: return null
        val w = wins
        val best = w.minOfOrNull { kotlin.math.abs(it.end!! - want) } ?: return null
        return w.filter { kotlin.math.abs(it.end!! - want) == best }.let { it[Math.floorMod(pick, it.size)] }
    }

    fun seed(seed: String): Raid? = raids.firstOrNull { it.seed == seed }

    fun random(pick: Int): Raid? = raids.getOrNull(Math.floorMod(pick, maxOf(1, raids.size)))

    fun stats(): String {
        val w = wins
        return "${raids.size} raids: ${100 * w.size / maxOf(1, raids.size)}% successful - fastest ${w.firstOrNull()?.time}, median ${median()?.time}, slowest ${w.lastOrNull()?.time}"
    }
}
