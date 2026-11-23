package org.rsmod.content.raids.tob.solo

import org.rsmod.api.player.righthand
import com.github.michaelbull.logging.InlineLogger
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.instances.DEFAULT_EMPTY_INSTANCE_RECLAIM_TICKS
import org.rsmod.api.instances.DEFAULT_INSTANCE_GRACE_TICKS
import org.rsmod.api.instances.InstanceAccess
import org.rsmod.api.instances.InstanceArea
import org.rsmod.api.instances.InstanceSpec
import org.rsmod.api.instances.RegionLocal
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.forcedWalk
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid
import org.rsmod.map.util.Bounds
import org.rsmod.plugin.module.PluginModule
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class SoloRaidModule : PluginModule() {
    override fun bind() {
        addSetBinding<org.rsmod.api.npc.hit.NpcHitLandHook>(SoloLand::class.java)
        addSetBinding<org.rsmod.api.npc.hit.NpcHitpointsFloorHook>(SoloFloor::class.java)
        addSetBinding<org.rsmod.api.death.PlayerDeathOverrideHook>(SoloDeath::class.java)
    }
}

/**
 * `::tob <room>` - the Theatre of Blood played solo, room by room, as in the game: you come in at the room's barrier
 * (with a supply chest beside you), Pass starts the room, and once its boss is dead you walk on out to the next room.
 * The supply chest also takes you to any room. `::tob stop` leaves.
 */
class SoloRaidScript
@Inject
constructor(
    private val raids: SoloRaids,
    private val d: SoloDeps,
    private val protectedAccess: ProtectedAccessLauncher,
    private val bots: org.rsmod.content.raids.tob.bloat.RaidBots,
) : PluginScript() {
    private val auto = HashSet<Player>()
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        raids.travel = { p, r -> travel(p, r) }
        if (System.getenv("TOB_NODEATH") != null) org.rsmod.api.player.PracticeSettings.noDeaths = true
        onEvent<GameLifecycle.StartCycle> { tickRooms() }
        // ToB practice is at the game's own experience rates: no boosted XP
        onPlayerLogin {
            player.xpRate = 1.0
            player.globalXpRate = 1.0
        }
        System.getenv("TOB_SELFTEST")?.let { list ->
            val rooms = ArrayDeque(list.split(',').mapNotNull { TobRoom.parse(it.trim()) })
            org.rsmod.api.player.PracticeSettings.noDeaths = true
            onEvent<GameLifecycle.StartCycle> {
                if (d.mapClock.cycle != SELF_TEST_AT || rooms.isEmpty()) return@onEvent
                val bot = bots.spawn("Solo", EXIT, SELF_TEST_GEAR, emptyList()) ?: return@onEvent
                auto += bot
                travel(bot, rooms.removeFirst())
                selfTestQueue = rooms
            }
        }
        onCommand("tob") {
            desc = "Theatre of Blood, solo: ::tob maiden | bloat | nylo | sote | xarpus | verzik | stop"
            invalidArgs = "Usage: ::tob <room> | stop"
            cheat { tob() }
        }
        for (loc in ROOM_LOCS) onOpLoc1(loc) { roomLoc(loc, it.loc.coords, 1) }
        onOpNpc1("npc.verzik_initial") { (raids.forPlayer(player) as? VerzikSolo)?.begin(this) }
        onOpNpc2("npc.verzik_initial") { (raids.forPlayer(player) as? VerzikSolo)?.begin(this) }
        onOpNpc3("npc.verzik_initial") { (raids.forPlayer(player) as? VerzikSolo)?.begin(this) }
    }

    private fun Cheat.tob() {
        val arg = args.getOrNull(0)?.lowercase() ?: return player.mes("::tob maiden | bloat | nylo | sote | xarpus | verzik | stop")
        when (arg) {
            "stop" -> {
                val room = raids.byOwner[player.username] ?: return player.mes("You're not in the Theatre.")
                close(room)
                protectedAccess.launch(player) { telejump(EXIT, TeleportType.Exempt) }
                player.npcViewDistance = null
                player.mes("You leave the Theatre.")
            }
            "where" -> {
                val room = raids.byOwner[player.username] ?: return player.mes("You're not in the Theatre.")
                val r = room.real(player.coords)
                player.mes("${room.id.label}: you're on ${r.x}, ${r.z} (level ${r.level}); room tick ${room.t}.")
            }
            "map" -> raids.byOwner[player.username]?.let { dumpMap(it) }
            "mode" -> raids.switchMode(player)
            "start" -> raids.byOwner[player.username]?.start()
            "auto" -> if (!auto.remove(player)) { auto += player; player.mes("Autopilot on.") } else player.mes("Autopilot off.")
            "hp" -> raids.byOwner[player.username]?.autoTarget()?.let { it.hitpoints = args.getOrNull(1)?.toIntOrNull() ?: it.hitpoints }
            else -> {
                val room = TobRoom.parse(arg) ?: return player.mes("::tob maiden | bloat | nylo | sote | xarpus | verzik | stop")
                travel(player, room)
            }
        }
    }

    /** Takes [player] to the corridor outside [room]'s barrier (a fresh copy of the room). */
    fun travel(player: Player, room: TobRoom) {
        raids.byOwner[player.username]?.let { close(it) }
        val area =
            if (room.allLevels) {
                val zx = (room.regions.minOf { it ushr 8 }) shl 3
                val zz = (room.regions.minOf { it and 0xFF }) shl 3
                val w = (room.regions.maxOf { it ushr 8 } - room.regions.minOf { it ushr 8 } + 1) * 8
                val h = (room.regions.maxOf { it and 0xFF } - room.regions.minOf { it and 0xFF } + 1) * 8
                val template =
                    RegionTemplate.createLarge {
                        copyAllLevels(zx, zz) {
                            zoneWidth = w
                            zoneLength = h
                            regionZoneX = 0
                            regionZoneZ = 0
                        }
                    }
                InstanceArea.template(template, RegionLocal(0, 0, 0, 0, 0), EXIT)
            } else {
                InstanceArea.copyRegions(regionIds = room.regions)
            }
        val session =
            d.instances.createServerOwned(
                key = "tob_solo_${room.name.lowercase()}",
                spec =
                    InstanceSpec(
                        fee = 0,
                        maxPlayers = 5,
                        reclaimTicks = DEFAULT_EMPTY_INSTANCE_RECLAIM_TICKS,
                        graceTicks = DEFAULT_INSTANCE_GRACE_TICKS,
                        area = area,
                        settingsRowId = -1,
                    ),
                access = InstanceAccess.Private,
                currentTick = d.mapClock.cycle,
            ) ?: return player.mes("No instance space free; try again shortly.")
        val region = checkNotNull(d.instances.regionOf(session))
        val origin = CoordGrid((room.regions.minOf { it ushr 8 }) * 64, (room.regions.minOf { it and 0xFF }) * 64, 0)
        val copyBase = CoordGrid(region.southWest.x, region.southWest.z, 0)
        val solo =
            when (room) {
                TobRoom.MAIDEN -> MaidenSolo(player, session, d, origin, copyBase)
                TobRoom.BLOAT -> BloatSolo(player, session, d, origin, copyBase)
                TobRoom.NYLOCAS -> NyloSolo(player, session, d, origin, copyBase)
                TobRoom.SOTETSEG -> SoteSolo(player, session, d, origin, copyBase)
                TobRoom.XARPUS -> XarpusSolo(player, session, d, origin, copyBase)
                TobRoom.VERZIK -> VerzikSolo(player, session, d, origin, copyBase)
            }
        raids.byOwner[player.username] = solo
        solo.build()
        if (System.getenv("TOB_MAPDUMP") != null) dumpMap(solo)
        val (chestAt, angle) = solo.chest
        d.locRepo.add(solo.at(chestAt), CHEST, Int.MAX_VALUE, angle, LocShape.CentrepieceStraight)
        player.npcViewDistance = NPC_VIEW
        protectedAccess.launch(player) { telejump(solo.at(solo.entry), TeleportType.Exempt) }
        player.mes("${room.label}. Pass the barrier when you're ready; the supply chest is beside you.")
        logger.info { "tob solo: ${player.username} -> $room (instance ${session.id})" }
    }

    private fun close(room: SoloRoom) {
        raids.byOwner.values.remove(room)
        room.resets++
        room.cleanup()
        d.instances.end(room.session)
    }

    private fun tickRooms() {
        if (raids.byOwner.isEmpty()) return
        for (room in raids.byOwner.values.toList()) {
            try {
                if (!room.owner.isSlotAssigned) {
                    close(room)
                    continue
                }
                room.tick()
                if (room.owner in auto) autopilot(room)
                val limit = System.getenv("TOB_ROOM_TICKS")?.toIntOrNull()
                if (room.owner in auto && limit != null && room.t >= limit && selfTestQueue.isNotEmpty()) {
                    travel(room.owner, selfTestQueue.removeFirst())
                    continue
                }
                if (room.owner in auto && room.t % 5 == 0) logger.info { "tobtest ${room.id} t${room.t} ${room.debug()} | player ${room.real(room.owner.coords)} hp ${room.owner.hitpoints}" }
                val p = room.player() ?: continue
                if (room.cleared && room.walkOn && !room.inArena(room.real(p.coords)) && !p.isDelayed) {
                    val next = room.id.next
                    if (next == null) {
                        room.leftAfterClear()
                    } else if (room.owner in auto && selfTestQueue.isNotEmpty()) {
                        travel(p, selfTestQueue.removeFirst())
                    } else {
                        p.mes("You head on to ${next.label}.")
                        travel(p, next)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "tob solo: room tick failed (${room.id})" }
            }
        }
    }

    private var selfTestQueue = ArrayDeque<TobRoom>()

    /** Tests: the server plays the room's owner - starts the room, attacks what the room says, walks out when clear. */
    private fun autopilot(room: SoloRoom) {
        val p = room.owner
        if (p.isDelayed || !p.isSlotAssigned || d.mapClock.cycle - room.builtAt < 4) return
        if (room.t < 0) {
            org.rsmod.game.entity.util.PathingEntityCommon.telejump(p, d.collision, room.at(room.autoStart))
            SoloRespawn.save(p)
            room.start()
            return
        }
        if (room.cleared) {
            org.rsmod.game.entity.util.PathingEntityCommon.telejump(p, d.collision, room.at(room.entry))
            return
        }
        room.autoAct(p)
        val target = room.autoTarget() ?: return
        // tests: bleed the target so a room's phases come round quickly
        val drain = System.getenv("TOB_DRAIN")?.toIntOrNull() ?: 0
        if (drain > 0 && room.t % 5 == 0 && target.hitpoints > 2) target.hitpoints = (target.hitpoints - target.baseHitpointsLvl * drain / 100).coerceAtLeast(1)
        if (p.interaction == null && target.isSlotAssigned && target.hitpoints > 0) bots.attack(p, target)
    }

    private suspend fun ProtectedAccess.roomLoc(loc: String, at: CoordGrid, op: Int) {
        val room = raids.forPlayer(player) ?: return
        room.opLoc(loc, room.real(at), op, this)
    }

    private fun dumpMap(room: SoloRoom) {
        val id = room.id
        for (level in if (id.allLevels) listOf(0, 3) else listOf(id.level)) {
            for (y in id.maxY downTo id.minY) {
                val row = (id.minX..id.maxX).joinToString("") { x -> if (room.blocked(CoordGrid(x, y, level))) "#" else "." }
                if (row.contains('.')) logger.info { "tobmap ${id.name} L$level y=$y x${id.minX} $row" }
            }
        }
    }

    companion object {
        const val CHEST = "loc.tob_midway_chest_closed"
        const val NPC_VIEW = 32
        const val SELF_TEST_AT = 30
        val SELF_TEST_GEAR =
            listOf(
                dev.openrune.util.Wearpos.Hat.slot to "obj.torva_helm",
                dev.openrune.util.Wearpos.RightHand.slot to "obj.scythe_of_vitur",
                dev.openrune.util.Wearpos.Torso.slot to "obj.torva_chest",
                dev.openrune.util.Wearpos.Legs.slot to "obj.torva_legs",
            )
        val EXIT = CoordGrid(3676, 3219, 0)
        val ROOM_LOCS =
            listOf(
                "loc.tob_dungeon_xarpus_arena_door_exit",
                "loc.tob_skeleton_with_weapon",
                "loc.tob_sotetseg_darkrealm_exit",
                "loc.tob_dungeon_verzik_entrance_door",
                "loc.tob_dungeon_verzik_throne_empty",
            )
    }
}

/** Pass on a barrier in a solo room (the barrier handler hands over here). */
object SoloBarrier {
    suspend fun pass(access: ProtectedAccess, room: SoloRoom, barrier: Bounds) {
        with(access) {
            val here = room.real(player.coords)
            val inside = room.inArena(here)
            if (!inside) {
                if (room.cleared) return mes("The room is clear: head on.")
                if (room.t < 0) {
                    val choice = chatMenu("Is your party ready to fight?", "Yes, let's begin.", "No, don't start yet.")
                    if (choice != 0) return
                    SoloRespawn.save(player)
                }
            } else if (!room.cleared) {
                return mes("You can't leave while the fight goes on.")
            }
            val p = player.coords
            val xs = barrier.x until barrier.x + barrier.width
            val zs = barrier.z until barrier.z + barrier.length
            val dest =
                when {
                    p.x in xs -> CoordGrid(p.x, if (p.z < zs.first) zs.last + 1 else zs.first - 1, p.level)
                    p.z in zs -> CoordGrid(if (p.x < xs.first) xs.last + 1 else xs.first - 1, p.z, p.level)
                    else -> return
                }
            val tiles = maxOf(kotlin.math.abs(dest.x - p.x), kotlin.math.abs(dest.z - p.z))
            if (!inside && room.t < 0) room.start()
            forcedWalk(dest, crossTiles = tiles)
        }
    }
}

/** Names of locs by id, for the room's handlers. */
fun locName(id: Int): String = RSCM.getReverseMapping(RSCMType.LOC, id)
