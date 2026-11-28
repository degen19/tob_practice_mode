package org.rsmod.content.raids.tob.solo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * A team's plan for Bloat, the same file the offline sim plays (`tools/bloat-sim/procedures`): when they go in, and
 * per player their gate, entry tick, stalls, necklaces, swing ticks and specs. See the sim's README for each field.
 */
class BloatProcedure(val name: String, val entryX: Int, val east: Boolean, val players: List<Seat>) {
    class Seat(
        val name: String,
        val gateWest: Boolean,
        val enter: Int,
        val stall: Boolean,
        val salve: Boolean,
        val necks: Int,
        val firstSwing: Int,
        val walkSwings: List<Int>?,
        val walkSides: List<String>,
        val walkSpecs: Map<Int, String>,
        val downSpecs: List<DownSpec>,
        val downStart: List<Int>,
        val rewalkAttack: Boolean,
    )

    class DownSpec(val weapon: String, val at: Int, val down: Int?)

    companion object {
        private val MAPPER = ObjectMapper()

        /** The plan for a team of [size]: `TOB_BLOAT_PROCEDURE` (a file) if set, else the shipped one. */
        fun forTeam(size: Int): BloatProcedure {
            val text = System.getenv("TOB_BLOAT_PROCEDURE")?.let { java.io.File(it).readText() }
                ?: checkNotNull(BloatProcedure::class.java.getResourceAsStream(file(size))) { "missing ${file(size)}" }.bufferedReader().readText()
            return parse(text)
        }

        private fun file(size: Int): String = "/tob/bloat/procedure_${size.coerceIn(1, 5)}.json"

        fun parse(text: String): BloatProcedure {
            val root = MAPPER.readTree(text)
            val entry = root.path("entry")
            val seats = root.path("players").map { p -> seat(p) }
            return BloatProcedure(root.path("name").asText("Bloat"), entry.path("x").asInt(17), entry.path("heading").asText("east") == "east", seats)
        }

        private fun seat(p: JsonNode): Seat {
            val stall = p.path("stall").asBoolean(true)
            return Seat(
                name = p.path("name").asText(),
                gateWest = p.path("gate").asText("east") == "west",
                enter = p.path("enter").asInt(0),
                stall = stall,
                salve = p.path("salve").asText(if (stall) "always" else "never") == "always",
                necks = p.path("necks").asInt(6),
                firstSwing = p.path("first_swing").asInt(14),
                walkSwings = p.get("walk_swings")?.map { it.asInt() },
                walkSides = p.path("walk_sides").map { it.asText() },
                walkSpecs = p.path("walk_specs").associate { it.path("tick").asInt() to it.path("weapon").asText() },
                downSpecs = p.path("down_specs").map { DownSpec(it.path("weapon").asText(), it.path("at").asInt(), it.get("down")?.asInt()) },
                downStart = p.get("down_start")?.map { it.asInt() } ?: listOf(0),
                rewalkAttack = p.path("rewalk_attack").asBoolean(true),
            )
        }
    }
}
