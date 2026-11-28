package org.rsmod.content.raids.tob.bloat

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.instances.DEFAULT_EMPTY_INSTANCE_RECLAIM_TICKS
import org.rsmod.api.instances.DEFAULT_INSTANCE_GRACE_TICKS
import org.rsmod.api.instances.InstanceAccess
import org.rsmod.api.instances.InstanceArea
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.instances.InstanceSpec
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onEvent
import org.rsmod.content.raids.tob.bloat.verzik.VerzikReplayRoom
import org.rsmod.content.raids.tob.bloat.verzik.VerzikReplays
import org.rsmod.content.raids.tob.bloat.verzik.VerzikRules
import org.rsmod.content.raids.tob.solo.TobHud
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * `::verzik [2-5] [median | top10 | slow | fail | time m:ss.t | seed vN]` - a private Verzik room playing one of
 * verzikSim's raids at that scale (5s by default) with bots; `::verzik blert <2-5> [rank]` one of Blert's 50 fastest
 * rooms, 1:1. You watch from the arena. `::verzik stats | stop`.
 * `VERZIK_SELFTEST=3:1,5:2,sim:4:v12,sim:5:median` (or `all`) plays them with no one watching and logs how far anything
 * drifted from the raid (tiles and her hitpoints).
 */
class VVerzikScript
@Inject
constructor(
    private val instances: InstanceManager,
    private val npcRepo: NpcRepository,
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
    private val worldRepo: WorldRepository,
    private val collision: CollisionFlagMap,
    private val mapClock: MapClock,
    private val random: GameRandom,
    private val protectedAccess: ProtectedAccessLauncher,
    private val bots: RaidBots,
    private val eventBus: EventBus,
) : PluginScript() {
    private val logger = InlineLogger()
    private val rooms = HashMap<String, VerzikReplayRoom>()
    private val look by lazy { RaidLook(worldRepo) }

    override fun ScriptContext.startup() {
        onEvent<GameLifecycle.StartCycle> { tickRooms() }
        onCommand("verzik") {
            desc = "ToB Verzik with bots: ::verzik [2-5] [median | top10 | slow | fail | time m:ss.t | seed vN] | blert <2-5> [rank] | stats | stop"
            invalidArgs = "Usage: ::verzik [2-5] [median | top10 | slow | fail | time m:ss.t | seed vN] | blert <2-5> [rank] | stats | stop"
            cheat { verzik() }
        }
        System.getenv("VERZIK_SELFTEST")?.let { spec ->
            val queue = ArrayDeque(selfTests(spec))
            onEvent<GameLifecycle.StartCycle> {
                if (mapClock.cycle >= 20 && SELF_TEST !in rooms && queue.isNotEmpty()) open(SELF_TEST, null, queue.removeFirst())
            }
        }
    }

    private fun selfTests(spec: String): List<VerzikReplays.Raid> =
        if (spec.isBlank() || spec == "all") (2..5).flatMap { VerzikReplays.blert[it].orEmpty() }
        else
            spec.split(',').mapNotNull { s ->
                val p = s.split(':')
                if (p[0] == "sim") VerzikReplays.simRaid(p[1].toInt(), p.getOrNull(2)?.let { if (it.startsWith("v")) "seed" else it }, p.getOrNull(2)?.removePrefix("v")?.let { "v$it" }, 0)
                else VerzikReplays.pick(p[0].toInt(), p.getOrNull(1)?.toInt() ?: 1, 0)
            }

    private fun Cheat.verzik() {
        val pick = random.of(0, 9999)
        var k = 0
        val first = args.getOrNull(0)?.lowercase()
        when (first) {
            "stop" -> return rooms[player.username]?.let { close(it); leave(player) } ?: player.mes("You have no Verzik room open.")
            "stats" -> return player.mes(VerzikReplays.stats())
            "blert" -> return blert()
        }
        val scale = first?.toIntOrNull()?.takeIf { it in 2..5 }?.also { k = 1 } ?: 5
        val how = args.getOrNull(k)?.lowercase()
        val raid = VerzikReplays.simRaid(scale, how, args.getOrNull(k + 1), pick) ?: return player.mes(USAGE)
        open(player.username, player, raid) ?: return player.mes("No instance space free; try again shortly.")
        player.mes("Sim ${scale}s ${raid.uuid.removePrefix("sim-")}: ${if (raid.failed) "failed" else raid.time} (P2 t${raid.markAt("P2")}, P3 t${raid.markAt("P3")}).")
    }

    private fun Cheat.blert() {
        val scale = args.getOrNull(1)?.toIntOrNull()?.takeIf { it in 2..5 } ?: return player.mes("::verzik blert <2-5> [rank 1-50]")
        val raid = VerzikReplays.pick(scale, args.getOrNull(2)?.toIntOrNull(), random.of(0, 9999)) ?: return player.mes("No such Blert raid.")
        open(player.username, player, raid) ?: return player.mes("No instance space free; try again shortly.")
        player.mes("Blert ${scale}s #${raid.rank}: ${raid.time} (P2 t${raid.markAt("P2")}, P3 t${raid.markAt("P3")}).")
    }

    private fun session() =
        instances.createServerOwned(
            key = INSTANCE_KEY,
            spec =
                InstanceSpec(
                    fee = 0,
                    maxPlayers = 6,
                    reclaimTicks = DEFAULT_EMPTY_INSTANCE_RECLAIM_TICKS,
                    graceTicks = DEFAULT_INSTANCE_GRACE_TICKS,
                    area = InstanceArea.copyRegions(centerRegionId = VerzikRules.REGION),
                    settingsRowId = -1,
                ),
            access = InstanceAccess.Private,
            currentTick = mapClock.cycle,
        )

    private fun open(key: String, player: Player?, raid: VerzikReplays.Raid): VerzikReplayRoom? {
        rooms.remove(key)?.let { close(it) }
        val session = session() ?: return null
        fun at(x: Int, y: Int) = checkNotNull(instances.resolveCoord(session, CoordGrid(x, y, 0)))
        val room = VerzikReplayRoom(session, raid, ::at, instances, npcRepo, locRepo, objRepo, worldRepo, collision)
        for (i in 0 until raid.players) {
            val bot = bots.spawn("Player ${i + 1}", room.spot(i), emptyList(), emptyList()) ?: continue
            room.bots += bot
        }
        room.start()
        rooms[key] = room
        if (player != null) {
            room.watchers += player
            room.throne(false)
            TobHud.open(player, eventBus)
            protectedAccess.launch(player) { telejump(at(VerzikRules.WATCH_X, VerzikRules.WATCH_Y), TeleportType.Exempt) }
            player.npcViewDistance = 32
        }
        logger.info { "vverzik: ${raid.uuid} (${raid.scale}s) for $key, instance ${session.id}" }
        return room
    }

    private fun tickRooms() {
        for ((key, room) in rooms.toList()) {
            try {
                room.tick()
            } catch (e: Exception) {
                logger.error(e) { "vverzik: room tick failed" }
            }
            for (w in room.watchers) if (w.isSlotAssigned) TobHud.update(w, room.progress(), room.bots)
            if (key == SELF_TEST && room.finished) {
                val r = room.raid
                logger.info { "vverzik selftest: ${r.uuid} ${r.scale}s #${r.rank} ${if (r.failed) "failed" else r.time}: ${room.checked} ticks, bot drift ${room.botDrift}, npc drift ${room.npcDrift}, hp drift ${room.hpDrift}" }
                rooms.remove(key)
                close(room)
            }
        }
    }

    private fun close(room: VerzikReplayRoom) {
        for (b in room.bots) {
            look.forget(b)
            bots.remove(b)
        }
        room.bots.clear()
        for (w in room.watchers) if (w.isSlotAssigned) TobHud.close(w, eventBus)
        room.watchers.clear()
        room.remove()
        instances.end(room.session)
    }

    private fun leave(player: Player) {
        rooms.remove(player.username)
        player.npcViewDistance = null
        protectedAccess.launch(player) { telejump(EXIT, TeleportType.Exempt) }
    }

    private companion object {
        const val INSTANCE_KEY = "vverzik"
        const val SELF_TEST = "*selftest"
        const val USAGE = "::verzik [2-5] [median | top10 | slow | fail | time m:ss.t | seed vN] | blert <2-5> [rank] | stats | stop"
        val EXIT = CoordGrid(3222, 3218, 0)
    }
}
