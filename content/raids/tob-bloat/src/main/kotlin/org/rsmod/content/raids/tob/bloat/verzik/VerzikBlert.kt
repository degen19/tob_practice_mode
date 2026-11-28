package org.rsmod.content.raids.tob.bloat.verzik

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.zip.GZIPInputStream
import org.rsmod.content.raids.tob.bloat.maiden.MaidenIndex

/**
 * The 50 fastest Verzik rooms on Blert at each scale (2-5), as recorded: every player's tile, gear, overhead and attack
 * each tick, every NPC's type, tile and hitpoints, and her attacks. Exported by `tools/blert/verzik.py export`; ticks
 * count from the room's first tick, tiles are the room's real coordinates.
 */
object VerzikBlert {
    class Attack(val t: Int, val player: Int, val name: String, val anim: Int, val weapon: Int, val target: Int)

    class Spell(val t: Int, val player: Int, val name: String, val anim: Int, val gfx: Int, val target: Int)

    class Her(val t: Int, val npc: Int, val name: String, val target: Int)

    class Step(val t: Int, val type: Int, val x: Int, val y: Int, val hp: Int, val base: Int)

    class Track(val spawn: Int, val death: Int?, val steps: List<Step>) {
        val type0 get() = steps.first().type
    }

    class Raid(
        val uuid: String,
        val scale: Int,
        val end: Int,
        val rank: Int,
        private val tiles: List<IntArray>,
        val gear: List<List<Triple<Int, Int, Int>>>,
        val overheads: List<List<Pair<Int, String>>>,
        val npcs: List<Track>,
        val attacks: List<Attack>,
        val spells: List<Spell>,
        val her: List<Her>,
        val marks: List<Pair<Int, String>>,
    ) {
        val players get() = tiles.size
        val time get() = MaidenIndex.fmt(end)

        fun x(player: Int, t: Int): Int = tiles[player][2 * t.coerceIn(0, end)]

        fun y(player: Int, t: Int): Int = tiles[player][2 * t.coerceIn(0, end) + 1]

        fun markAt(name: String): Int? = marks.firstOrNull { it.second == name }?.first
    }

    val raids: Map<Int, List<Raid>> by lazy {
        val stream = VerzikBlert::class.java.getResourceAsStream("/verzik/blert.json.gz") ?: return@lazy emptyMap()
        val root = GZIPInputStream(stream).use { ObjectMapper().readTree(it) }
        root.fields().asSequence().associate { (scale, list) -> scale.toInt() to list.mapIndexed { k, r -> raid(r, k + 1) } }
    }

    private fun raid(r: JsonNode, rank: Int): Raid =
        Raid(
            r["uuid"].asText(),
            r["scale"].asInt(),
            r["end"].asInt(),
            rank,
            r["tiles"].map { row -> IntArray(row.size()) { row[it].asInt() } },
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
        )

    fun pick(scale: Int, rank: Int?, pick: Int): Raid? {
        val list = raids[scale].orEmpty()
        if (list.isEmpty()) return null
        return if (rank != null) list.getOrNull(rank - 1) else list[Math.floorMod(pick, list.size)]
    }

    fun stats(): String =
        (2..5).joinToString("; ") { s ->
            val l = raids[s].orEmpty()
            "${s}s: ${l.size} raids, fastest ${l.firstOrNull()?.time}, median ${l.getOrNull(l.size / 2)?.time}"
        }
}
