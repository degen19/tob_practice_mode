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
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onEvent
import org.rsmod.content.raids.tob.bloat.verzik.VerzikBlert
import org.rsmod.content.raids.tob.bloat.verzik.VerzikLiveRoom
import org.rsmod.content.raids.tob.bloat.verzik.VerzikRaids
import org.rsmod.content.raids.tob.bloat.verzik.VerzikReplayRoom
import org.rsmod.content.raids.tob.bloat.verzik.VerzikRules
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * `::verzik` - a private Verzik room playing one of the sim's 5s raids (P1 chart, P2, reds, P3) with five bots; you
 * watch from the arena. `::verzik median | top10 | slow | fail | time 4:44.4 | seed v12 | stats | stop`.
 * `::verzik blert <2-5> [rank]` plays one of Blert's 50 fastest Verzik rooms at that scale instead, 1:1.
 * `VERZIK_SELFTEST=3:1,5:2` (or `all`) plays Blert rooms with no one watching and logs how far anything drifted.
 */
class VVerzikScript
@Inject
constructor(
    private val instances: InstanceManager,
    private val npcRepo: NpcRepository,
    private val locRepo: LocRepository,
    private val objRepo: org.rsmod.api.repo.obj.ObjRepository,
    private val worldRepo: WorldRepository,
    private val collision: CollisionFlagMap,
    private val mapClock: MapClock,
    private val random: GameRandom,
    private val npcHits: NpcHitModifier,
    private val protectedAccess: ProtectedAccessLauncher,
    private val bots: RaidBots,
) : PluginScript() {
    private val logger = InlineLogger()
    private val rooms = HashMap<String, VerzikLiveRoom>()
    private val replays = HashMap<String, VerzikReplayRoom>()
    private val look by lazy { RaidLook(worldRepo) }

    override fun ScriptContext.startup() {
        onEvent<GameLifecycle.StartCycle> { tickRooms() }
        onCommand("verzik") {
            desc = "ToB Verzik, 5s: one of the sim's raids played with bots - ::verzik [median | top10 | slow | fail | time m:ss.t | seed s | stats | stop]"
            invalidArgs = "Usage: ::verzik [median | top10 | slow | fail | time m:ss.t | seed s | stats | stop]"
            cheat { verzik() }
        }
        System.getenv("VERZIK_SELFTEST")?.let { spec ->
            val queue = ArrayDeque(selfTests(spec))
            onEvent<GameLifecycle.StartCycle> {
                if (mapClock.cycle >= 20 && SELF_TEST !in replays && queue.isNotEmpty()) openReplay(SELF_TEST, null, queue.removeFirst())
            }
        }
    }

    private fun selfTests(spec: String): List<VerzikBlert.Raid> =
        if (spec.isBlank() || spec == "all") (2..5).flatMap { VerzikBlert.raids[it].orEmpty() }
        else spec.split(',').mapNotNull { s -> s.split(':').let { VerzikBlert.pick(it[0].toInt(), it.getOrNull(1)?.toInt() ?: 1, 0) } }

    private fun Cheat.verzik() {
        val pick = random.of(0, 9999)
        val raid: VerzikRaids.Raid? =
            when (args.getOrNull(0)?.lowercase()) {
                null, "again" -> VerzikRaids.random(pick)
                "stop" -> return (rooms[player.username]?.let { close(it) } ?: replays[player.username]?.let { closeReplay(it) })?.let { leave(player) } ?: player.mes("You have no Verzik room open.")
                "stats" -> return player.mes("${VerzikRaids.stats()}. Blert: ${VerzikBlert.stats()}")
                "median" -> VerzikRaids.median()
                "top10", "fast" -> VerzikRaids.top10(pick)
                "slow" -> VerzikRaids.slow10(pick)
                "fail", "failed" -> VerzikRaids.failed(pick)
                "time" -> VerzikRaids.time(args.getOrNull(1) ?: "", pick)
                "seed" -> VerzikRaids.seed(args.getOrNull(1) ?: return player.mes("::verzik seed <seed>"))
                "blert" -> return blert()
                else -> return player.mes("::verzik [median | top10 | slow | fail | time m:ss.t | seed s | blert 2-5 [rank] | stats | stop]")
            }
        raid ?: return player.mes("No such raid.")
        open(player, raid) ?: return player.mes("No instance space free; try again shortly.")
        player.mes("Raid ${raid.seed}: ${raid.time} (P1 ends t${raid.markAt("P1 dies")}, reds t${raid.markAt("Reds")}).")
    }

    private fun Cheat.blert() {
        val scale = args.getOrNull(1)?.toIntOrNull()?.takeIf { it in 2..5 } ?: return player.mes("::verzik blert <2-5> [rank 1-50]")
        val raid = VerzikBlert.pick(scale, args.getOrNull(2)?.toIntOrNull(), random.of(0, 9999)) ?: return player.mes("No such Blert raid.")
        rooms.remove(player.username)?.let { close(it) }
        openReplay(player.username, player, raid) ?: return player.mes("No instance space free; try again shortly.")
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

    private fun openReplay(key: String, player: Player?, raid: VerzikBlert.Raid): VerzikReplayRoom? {
        replays.remove(key)?.let { closeReplay(it) }
        val session = session() ?: return null
        fun at(x: Int, y: Int) = checkNotNull(instances.resolveCoord(session, CoordGrid(x, y, 0)))
        val room = VerzikReplayRoom(session, raid, ::at, instances, npcRepo, locRepo, objRepo, worldRepo, collision)
        for (i in 0 until raid.players) {
            val bot = bots.spawn("Player ${i + 1}", room.spot(i), emptyList(), emptyList()) ?: continue
            room.bots += bot
        }
        room.start()
        replays[key] = room
        if (player != null) {
            room.watchers += player
            room.throne(false)
            protectedAccess.launch(player) { telejump(at(VerzikRules.WATCH_X, VerzikRules.WATCH_Y), TeleportType.Exempt) }
            player.npcViewDistance = 32
        }
        logger.info { "vverzik: blert ${raid.scale}s #${raid.rank} (${raid.uuid}) for $key, instance ${session.id}" }
        return room
    }

    private fun closeReplay(room: VerzikReplayRoom) {
        for (b in room.bots) {
            look.forget(b)
            bots.remove(b)
        }
        room.bots.clear()
        room.remove()
        instances.end(room.session)
    }

    private fun open(player: Player, raid: VerzikRaids.Raid): VerzikLiveRoom? {
        rooms.remove(player.username)?.let { close(it) }
        replays.remove(player.username)?.let { closeReplay(it) }
        val session = session() ?: return null
        fun at(x: Int, y: Int) = checkNotNull(instances.resolveCoord(session, CoordGrid(x, y, 0)))
        val room = VerzikLiveRoom(session, raid, ::at, instances, npcRepo, worldRepo, npcHits, protectedAccess, look)
        for (i in 0 until 5) {
            val bot = bots.spawn("Player ${i + 1}", room.spot(i, "P1"), emptyList(), emptyList()) ?: continue
            room.bots += bot
        }
        room.start()
        rooms[player.username] = room
        protectedAccess.launch(player) { telejump(at(VerzikRules.WATCH_X, VerzikRules.WATCH_Y), TeleportType.Exempt) }
        player.npcViewDistance = 32
        logger.info { "vverzik: room for ${player.username} (instance ${session.id}, raid ${raid.seed})" }
        return room
    }

    private fun tickRooms() {
        for (room in rooms.values.toList()) {
            try {
                room.tick()
            } catch (e: Exception) {
                logger.error(e) { "vverzik: room tick failed" }
            }
        }
        for ((key, room) in replays.toList()) {
            try {
                room.tick()
            } catch (e: Exception) {
                logger.error(e) { "vverzik: replay tick failed" }
            }
            if (key == SELF_TEST && room.finished) {
                val r = room.raid
                logger.info { "vverzik selftest: blert ${r.scale}s #${r.rank} ${r.time} (${r.uuid}): ${room.checked} ticks, bot drift ${room.botDrift}, npc drift ${room.npcDrift}" }
                replays.remove(key)
                closeReplay(room)
            }
        }
    }

    private fun close(room: VerzikLiveRoom) {
        for (b in room.bots) {
            look.forget(b)
            bots.remove(b)
        }
        room.bots.clear()
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
        val EXIT = CoordGrid(3222, 3218, 0)
    }
}
