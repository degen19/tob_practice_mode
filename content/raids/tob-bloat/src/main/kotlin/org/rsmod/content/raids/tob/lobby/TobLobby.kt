package org.rsmod.content.raids.tob.lobby

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.enums.NamedEnums
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.instances.DEFAULT_INSTANCE_GRACE_TICKS
import org.rsmod.api.instances.InstanceAccess
import org.rsmod.api.instances.InstanceArea
import org.rsmod.api.instances.InstanceSpec
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.content.raids.tob.bloat.RaidBots
import org.rsmod.content.raids.tob.solo.SoloDeps
import org.rsmod.content.raids.tob.solo.SoloRaids
import org.rsmod.content.raids.tob.solo.TobRoom
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.util.PathingEntityCommon
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * The loading lobby, a shared hall everyone logs into, laid out as OpenScim's: a dark disc in a black void. The six bosses
 * stand on pedestals round it with the castle door at north and the spawn at south, Verzik's pillars on the ring between
 * them; Mr. Mordaut and his desk (the bot table) sit on a gold circle in the middle with bots wandering round it; the
 * party board and the gear rack stand by the door. The disc is floor decoration over Verzik's arena floor (tiled, so the
 * client has tiles to walk on), shaded in bands out from the middle; tob_lobby_edges holds the rim's clipped tiles.
 */
class TobLobby
@Inject
constructor(private val d: SoloDeps, private val raids: SoloRaids, private val bots: RaidBots) : PluginScript() {
    private val logger = InlineLogger()
    private var base: CoordGrid? = null
    private val wanderers = ArrayList<Player>()

    override fun ScriptContext.startup() {
        onEvent<GameLifecycle.StartCycle> {
            val at = base
            if (at == null) {
                if (d.mapClock.cycle >= BUILD_AT) build()
            } else {
                tick(at)
            }
        }
        onPlayerLogin {
            val at = base ?: return@onPlayerLogin
            if (player.accountId < 0) return@onPlayerLogin
            PathingEntityCommon.telejump(player, d.collision, at.translate(SPAWN.first, SPAWN.second))
        }
        for ((npc, room) in ENTRANCES) onOpNpc1(npc) { enter(room) }
        onOpNpc1(TM19) { mes("TM 19: Behind that door, a random room, role and scale. I'll brief you once it's open.") }
        onOpLoc1(CASTLE_DOOR) { if (inLobby(player)) mes("The castle door is locked for now: the random raid isn't ready yet.") }
        onOpLoc1(NOTICE_BOARD) { if (inLobby(player)) mes("The party board isn't up yet: ::party invite / request for now.") }
        onOpLoc1(GEAR_RACK) { raids.supplies?.invoke(this) }
    }

    private fun ProtectedAccess.enter(room: TobRoom) {
        val go = raids.travel ?: return
        go(player, room, this)
    }

    private fun inLobby(player: Player): Boolean {
        val at = base ?: return false
        val dx = player.coords.x - at.x
        val dz = player.coords.z - at.z
        return player.coords.level == at.level && dx in 0 until SIZE && dz in 0 until SIZE
    }

    private fun build() {
        val template =
            RegionTemplate.create {
                for (x in 0 until SIZE_ZONES) {
                    for (z in 0 until SIZE_ZONES) {
                        this[x, z, 0] = ZoneKey(FLOOR_ZONE_X + x % 2, FLOOR_ZONE_Z + z % 2, 0)
                    }
                }
            }
        val session =
            d.instances.createServerOwned(
                key = "tob_lobby",
                spec =
                    InstanceSpec(
                        fee = 0,
                        maxPlayers = MAX_PLAYERS,
                        reclaimTicks = Int.MAX_VALUE / 2,
                        graceTicks = DEFAULT_INSTANCE_GRACE_TICKS,
                        area = InstanceArea.template(template),
                        settingsRowId = -1,
                    ),
                access = InstanceAccess.Private,
                currentTick = d.mapClock.cycle,
            )
        val region = session?.let { d.instances.regionOf(it) }
        if (region == null) {
            base = NO_LOBBY
            logger.error { "tob lobby: no instance space" }
            return
        }
        val at = CoordGrid(region.southWest.x, region.southWest.z, 0)
        base = at
        raids.lobby = at.translate(SPAWN.first, SPAWN.second)
        lay(at)
        for ((npc, spot) in BOSS_SPOTS) {
            val boss = place(npc, at, spot)
            loc(PEDESTALS.getValue(boss.visType.size.coerceIn(PEDESTAL_SIZES)), at, spot, LocAngle.West)
        }
        loc(SYMBOL, at, CENTRE, LocAngle.West)
        place(TEACHER, at, CENTRE).faceSquare(at.translate(SPAWN.first, SPAWN.second))
        place(TM19, at, TM19_SPOT)
        for (deg in PILLAR_DEGREES) loc(PILLAR, at, ring(deg, PILLAR_RING), LocAngle.West)
        loc(CASTLE_DOOR, at, DOOR_SPOT, DOOR_ANGLE)
        loc(NOTICE_BOARD, at, BOARD_SPOT, STATION_ANGLE)
        loc(GEAR_RACK, at, RACK_SPOT, STATION_ANGLE)
        for ((i, name) in BOT_NAMES.withIndex()) {
            val spot = ring(i * 360 / BOT_NAMES.size, BOT_RING)
            bots.spawn(name, at.translate(spot.first, spot.second), emptyList(), emptyList())?.let { wanderers += it }
        }
        logger.info { "tob lobby at ${at.x},${at.z}: ${BOSS_SPOTS.size} bosses, ${wanderers.size} bots" }
        System.getenv("TOB_LOBBY_DUMP")?.let { dump(at) }
    }

    /** The disc tile by tile: its shade band inside, a clipped rim tile on the edge, black off it (and no walking there). */
    private fun lay(at: CoordGrid) {
        val edges = NamedEnums.tob_lobby_edges
        for (x in 0 until SIZE) {
            for (z in 0 until SIZE) {
                val out = Math.hypot(x + 0.5 - DISC_CENTRE, z + 0.5 - DISC_CENTRE)
                val tile = at.translate(x, z)
                val edge = edges.getOrNull(x * EDGE_KEY + z)
                val type =
                    when {
                        edge != null -> edge.internalName
                        out < DISC_RADIUS -> FLOORS[(out / DISC_RADIUS * FLOORS.size).toInt().coerceAtMost(FLOORS.size - 1)]
                        else -> VOID
                    }
                d.locRepo.add(tile, type, Int.MAX_VALUE, LocAngle.West, LocShape.GroundDecor)
                if (out > DISC_RADIUS - WALK_MARGIN) d.collision.add(tile.x, tile.z, tile.level, CollisionFlag.BLOCK_WALK)
            }
        }
    }

    /** An NPC centred on [spot] (its south-west tile is offset by half its size), facing the middle. */
    private fun place(type: String, at: CoordGrid, spot: Pair<Int, Int>): Npc {
        val size = ServerCacheManager.getNpc(type.asRSCM(RSCMType.NPC))?.size ?: 1
        val npc = Npc(type, at.translate(spot.first - size / 2, spot.second - size / 2))
        d.npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        if (spot != CENTRE) npc.faceSquare(at.translate(CENTRE.first, CENTRE.second))
        return npc
    }

    private fun loc(type: String, at: CoordGrid, spot: Pair<Int, Int>, angle: LocAngle) {
        val def = ServerCacheManager.getObject(type.asRSCM(RSCMType.LOC))
        val turned = angle == LocAngle.North || angle == LocAngle.South
        val w = (if (turned) def?.length else def?.width) ?: 1
        val l = (if (turned) def?.width else def?.length) ?: 1
        d.locRepo.add(at.translate(spot.first - w / 2, spot.second - l / 2), type, Int.MAX_VALUE, angle, LocShape.CentrepieceStraight)
    }

    private fun tick(at: CoordGrid) {
        if (at == NO_LOBBY) return
        val cycle = d.mapClock.cycle
        if (cycle % POTATO_CHECK == 0) {
            val tile = at.translate(POTATO_SPOT.first, POTATO_SPOT.second)
            if (d.objRepo.findAll(tile).none { it.type == POTATO.asRSCM(RSCMType.OBJ) }) d.objRepo.add(POTATO, tile, Int.MAX_VALUE)
        }
        for (bot in wanderers) {
            if (!bot.isSlotAssigned) continue
            if (d.random.of(WANDER_ODDS) == 0) {
                val spot = ring(d.random.of(360), BOT_RING_MIN + d.random.of(BOT_RING - BOT_RING_MIN + 1))
                bots.walk(bot, at.translate(spot.first, spot.second))
            }
        }
        if (cycle % PUN_EVERY == 0 && wanderers.isNotEmpty()) {
            val bot = wanderers[d.random.of(wanderers.size)]
            if (bot.isSlotAssigned) bot.say(PUNS[d.random.of(PUNS.size)])
        }
    }

    private fun dump(at: CoordGrid) {
        for (z in SIZE - 1 downTo 0) {
            val row = (0 until SIZE).joinToString("") { x -> if (d.collision[at.x + x, at.z + z, 0] == 0) "." else "#" }
            logger.info { "tob lobby map z=${"%02d".format(z)} $row" }
        }
        for ((type, _) in BOSS_SPOTS + listOf(TEACHER to CENTRE, TM19 to TM19_SPOT)) {
            val def = ServerCacheManager.getNpc(type.asRSCM(RSCMType.NPC))
            logger.info { "tob lobby npc $type: ${def?.name} size ${def?.size} op1 ${def?.actions?.getOpOrNull(0)}" }
        }
        for (type in listOf(CASTLE_DOOR, NOTICE_BOARD, GEAR_RACK, PILLAR, SYMBOL)) {
            val def = ServerCacheManager.getObject(type.asRSCM(RSCMType.LOC))
            logger.info { "tob lobby loc $type: ${def?.name} ${def?.width}x${def?.length} op1 ${def?.actions?.getOpOrNull(0)}" }
        }
    }

    companion object {
        const val BUILD_AT = 5
        const val SIZE_ZONES = 5
        const val SIZE = SIZE_ZONES * 8
        const val MAX_PLAYERS = 2000
        const val FLOOR_ZONE_X = 395
        const val FLOOR_ZONE_Z = 538
        const val DISC_CENTRE = SIZE / 2 + 0.5
        const val DISC_RADIUS = 16.5
        const val WALK_MARGIN = 0.4
        const val EDGE_KEY = 64
        const val BOSS_RING = 10.5
        const val PILLAR_RING = 14.5
        const val BOT_RING = 7
        const val BOT_RING_MIN = 4
        const val WANDER_ODDS = 12
        const val PUN_EVERY = 25
        const val POTATO_CHECK = 10
        const val POTATO = "obj.rotten_potato"
        const val TEACHER = "npc.tob_lobby_teacher"
        const val TM19 = "npc.tob_lobby_tm19"
        const val CASTLE_DOOR = "loc.tob_surface_castle_entrance"
        const val NOTICE_BOARD = "loc.tob_surface_notice_board"
        const val GEAR_RACK = "loc.tob_lobby_gear_rack"
        const val PILLAR = "loc.tob_dungeon_verzik_pillar"
        const val SYMBOL = "loc.tob_lobby_symbol"
        const val VOID = "loc.tob_lobby_void"
        val FLOORS = listOf("loc.tob_lobby_floor_0", "loc.tob_lobby_floor_1", "loc.tob_lobby_floor_2", "loc.tob_lobby_floor_3", "loc.tob_lobby_floor_4", "loc.tob_lobby_floor_5")
        val PEDESTAL_SIZES = 3..7
        val PEDESTALS =
            mapOf(
                3 to "loc.tob_lobby_pedestal_3",
                4 to "loc.tob_lobby_pedestal_4",
                5 to "loc.tob_lobby_pedestal_5",
                6 to "loc.tob_lobby_pedestal_6",
                7 to "loc.tob_lobby_pedestal_7",
            )

        /** The castle door's front faces east at angle 1 (Ver Sinhaza): turned once more it faces in from the north. */
        val DOOR_ANGLE = LocAngle.East

        /** The weapon rack's front faces west at angle 0 (Keldagrim): three turns face it south, to the middle. */
        val STATION_ANGLE = LocAngle.South
        val NO_LOBBY = CoordGrid(0, 0, 0)
        val CENTRE = SIZE / 2 to SIZE / 2

        /** The tile [radius] out from the middle at [degrees] clockwise from north. */
        fun ring(degrees: Number, radius: Number): Pair<Int, Int> {
            val a = Math.toRadians(degrees.toDouble())
            val r = radius.toDouble()
            return CENTRE.first + Math.round(r * Math.sin(a)).toInt() to CENTRE.second + Math.round(r * Math.cos(a)).toInt()
        }

        val ENTRANCES =
            listOf(
                "npc.tob_lobby_maiden" to TobRoom.MAIDEN,
                "npc.tob_lobby_bloat" to TobRoom.BLOAT,
                "npc.tob_lobby_nylocas" to TobRoom.NYLOCAS,
                "npc.tob_lobby_sotetseg" to TobRoom.SOTETSEG,
                "npc.tob_lobby_xarpus" to TobRoom.XARPUS,
                "npc.tob_lobby_verzik" to TobRoom.VERZIK,
            )

        /** Clockwise from the door: Maiden north-east round to Verzik north-west, the spawn's gap at south. */
        val BOSS_SPOTS = ENTRANCES.zip(listOf(45, 90, 135, 225, 270, 315)) { (npc, _), deg -> npc to ring(deg, BOSS_RING) }
        val PILLAR_DEGREES = listOf(22.5, 67.5, 112.5, 157.5, 202.5, 247.5, 292.5, 337.5)
        val DOOR_SPOT = ring(0, 15)
        val TM19_SPOT = ring(14, 13)
        val SPAWN = ring(180, 13)
        val BOARD_SPOT = ring(354, 9.5)
        val RACK_SPOT = ring(14, 9.5)
        val POTATO_SPOT = CENTRE.first + 3 to CENTRE.second - 4

        val BOT_NAMES = listOf("Tick Perfect", "Bloat Goat", "Nylo Ninja", "Sote Note", "Scythe Guy", "Verz Herder")

        val PUNS =
            listOf(
                "Maiden? I hardly know her.",
                "Bloat's not so bad once you get wind of him.",
                "The Nylocas waved at me, so I waved back.",
                "Sotetseg's maze? I could do it with my eyes closed. That's the problem.",
                "Xarpus gave me a dirty look, so I stopped hitting him.",
                "Verzik's a real web designer.",
                "I'd tell you a Bloat joke, but it stinks.",
                "Bloat's walking in circles again. Relatable.",
                "Mr. Mordaut says no lost ticks or no lunch.",
                "I don't always freeze, but when I do it's on tick.",
                "Verzik keeps bouncing me. Rude.",
                "Five-man scale? I can barely handle a duo.",
                "I came for the theatre. Nobody warned me about the blood.",
                "My scythe's so sharp it cut my raid time.",
            )
    }
}
