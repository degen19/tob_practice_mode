package org.rsmod.content.raids.tob.bloat.verzik

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.zip.GZIPInputStream
import org.rsmod.content.raids.tob.bloat.maiden.MaidenIndex

/**
 * Verzik raids to play out, by scale (2-5): [blert], Blert's 50 fastest rooms as recorded (`tools/blert/verzik.py
 * export`), and [sim], verzikSim's raids with their movement from Blert (`resources/verzik/simreplay.mjs`). Each holds
 * every player's tile, gear, overhead and attack each tick, every NPC's type, tile and hitpoints, and her attacks;
 * ticks count from the room's first tick, tiles are the room's real coordinates.
 */
object VerzikReplays {
    class Attack(val t: Int, val player: Int, val name: String, val anim: Int, val weapon: Int, val target: Int)

    class Spell(val t: Int, val player: Int, val name: String, val anim: Int, val gfx: Int, val target: Int)

    class Her(val t: Int, val npc: Int, val name: String, val target: Int)

    class Drop(val t: Int, val x: Int, val y: Int, val dropped: Boolean)

    class Dawn(val t: Int, val player: Int, val damage: Int)

    class Use(val t: Int, val player: Int, val kind: String)

    class Step(val t: Int, val type: Int, val x: Int, val y: Int, val hp: Int, val base: Int)

    class Track(val spawn: Int, val death: Int?, val steps: List<Step>) {
        val type0 get() = steps.first().type
    }

    class Raid(
        val uuid: String,
        val scale: Int,
        val end: Int,
        val rank: Int,
        val failed: Boolean,
        private val tiles: List<IntArray>,
        private val hitpoints: List<IntArray>,
        val gear: List<List<Triple<Int, Int, Int>>>,
        val overheads: List<List<Pair<Int, String>>>,
        val npcs: List<Track>,
        val attacks: List<Attack>,
        val spells: List<Spell>,
        val her: List<Her>,
        val marks: List<Pair<Int, String>>,
        val drops: List<Drop>,
        val dawns: List<Dawn>,
        val yellows: List<Pair<Int, List<Pair<Int, Int>>>>,
        val uses: List<Use>,
    ) {
        val players get() = tiles.size
        val time get() = MaidenIndex.fmt(end)

        fun x(player: Int, t: Int): Int = tiles[player][2 * t.coerceIn(0, end)]

        fun y(player: Int, t: Int): Int = tiles[player][2 * t.coerceIn(0, end) + 1]

        /** The player's hitpoints on tick [t] (Blert has them for the players who recorded), -1 when not known. */
        fun hp(player: Int, t: Int): Int = hitpoints.getOrNull(player)?.getOrNull(t) ?: -1

        fun markAt(name: String): Int? = marks.firstOrNull { it.second == name }?.first
    }

    val blert: Map<Int, List<Raid>> by lazy { load("/verzik/blert.json.gz") }

    val sim: Map<Int, List<Raid>> by lazy { load("/verzik/sim.json.gz") }

    private fun load(resource: String): Map<Int, List<Raid>> {
        val stream = VerzikReplays::class.java.getResourceAsStream(resource) ?: return emptyMap()
        val root = GZIPInputStream(stream).use { ObjectMapper().readTree(it) }
        return root.fields().asSequence().associate { (scale, list) -> scale.toInt() to list.mapIndexed { k, r -> raid(r, k + 1) } }
    }

    private fun raid(r: JsonNode, rank: Int): Raid =
        Raid(
            r["uuid"].asText(),
            r["scale"].asInt(),
            r["end"].asInt(),
            rank,
            r["failed"]?.asBoolean() ?: false,
            r["tiles"].map { row -> IntArray(row.size()) { row[it].asInt() } },
            r["hp"].map { row -> IntArray(row.size()) { row[it].asInt() } },
            r["gear"].map { p -> p.map { Triple(it[0].asInt(), it[1].asInt(), it[2].asInt()) } },
            r["prayers"].map { p -> p.map { it[0].asInt() to it[1].asText() } },
            r["npcs"].map { n ->
                Track(
                    n["spawn"].asInt(),
                    n["death"].takeIf { !it.isNull }?.asInt(),
                    n["track"].map { Step(it[0].asInt(), it[1].asInt(), it[2].asInt(), it[3].asInt(), it[4].asInt(), it[5].asInt()) },
                )
            },
            r["attacks"].map { Attack(it[0].asInt(), it[1].asInt(), it[2].asText(), it[3].asInt(), it[4].asInt(), it[5].asInt()) },
            r["spells"].map { Spell(it[0].asInt(), it[1].asInt(), it[2].asText(), it[3].asInt(), it[4].asInt(), it[5].asInt()) },
            r["her"].map { Her(it[0].asInt(), it[1].asInt(), it[2].asText(), it[3].asInt()) },
            r["marks"].map { it[0].asInt() to it[1].asText() },
            r["drops"].map { Drop(it[0].asInt(), it[1].asInt(), it[2].asInt(), it[3].asInt() == 1) },
            r["dawns"].map { Dawn(it[0].asInt(), it[1].asInt(), it[2].asInt()) },
            r["yellows"].map { y -> y[0].asInt() to y[1].map { it[0].asInt() to it[1].asInt() } },
            r["uses"]?.map { Use(it[0].asInt(), it[1].asInt(), it[2].asText()) }.orEmpty(),
        )

    fun pick(scale: Int, rank: Int?, pick: Int): Raid? {
        val list = blert[scale].orEmpty()
        if (list.isEmpty()) return null
        return if (rank != null) list.getOrNull(rank - 1) else list[Math.floorMod(pick, list.size)]
    }

    /** A sim raid at [scale]: `median`, `top10`, `slow` (slowest 10%), `fail`, `time m:ss.t`, `seed vN`, or any. */
    fun simRaid(scale: Int, how: String?, arg: String?, pick: Int): Raid? {
        val all = sim[scale].orEmpty()
        val wins = all.filter { !it.failed }
        fun some(l: List<Raid>) = if (l.isEmpty()) null else l[Math.floorMod(pick, l.size)]
        return when (how) {
            null, "again" -> some(all)
            "median" -> wins.getOrNull(wins.size / 2)
            "top10", "fast" -> some(wins.take(maxOf(1, wins.size / 10)))
            "slow" -> some(wins.takeLast(maxOf(1, wins.size / 10)))
            "fail", "failed" -> some(all.filter { it.failed })
            "time" -> MaidenIndex.parse(arg ?: "")?.let { want -> wins.minByOrNull { kotlin.math.abs(it.end - want) } }
            "seed" -> all.firstOrNull { it.uuid == "sim-$arg" }
            else -> null
        }
    }

    fun stats(): String =
        (2..5).joinToString("; ") { s ->
            val b = blert[s].orEmpty()
            val m = sim[s].orEmpty()
            val wins = m.filter { !it.failed }
            "${s}s: sim ${wins.size}/${m.size} killed, median ${wins.getOrNull(wins.size / 2)?.time}; Blert fastest ${b.firstOrNull()?.time}, median ${b.getOrNull(b.size / 2)?.time}"
        }
}
