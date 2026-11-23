package org.rsmod.content.raids.tob.bloat

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.types.MoveRestrict
import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.instances.DEFAULT_EMPTY_INSTANCE_RECLAIM_TICKS
import org.rsmod.api.instances.DEFAULT_INSTANCE_GRACE_TICKS
import org.rsmod.api.instances.InstanceAccess
import org.rsmod.api.instances.InstanceArea
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.instances.InstanceSpec
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.player.hit.modifier.PlayerHitModifier
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.output.Camera
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.vars.varMoveSpeed
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onEvent
import org.rsmod.content.raids.tob.bloat.nylo.NyloIndex
import org.rsmod.content.raids.tob.bloat.nylo.NyloLiveRoom
import org.rsmod.content.raids.tob.bloat.nylo.NyloRules
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * `::nylo` - a private Nylo boss room playing the sim's 5s plan live with five bots; you watch from south of the stack.
 * `::nylo median | top10 | slow | time 3:01.2 | seed x12 | stats | stop | log`, as `::maiden`.
 */
class VNyloScript
@Inject
constructor(
    private val instances: InstanceManager,
    private val npcRepo: NpcRepository,
    private val worldRepo: WorldRepository,
    private val mapClock: MapClock,
    private val random: GameRandom,
    private val npcHits: NpcHitModifier,
    private val playerHits: PlayerHitModifier,
    private val protectedAccess: ProtectedAccessLauncher,
    private val bots: RaidBots,
) : PluginScript() {
    private val logger = InlineLogger()
    private val rooms = HashMap<String, NyloLiveRoom>()
    private val look by lazy { RaidLook(worldRepo) }

    override fun ScriptContext.startup() {
        onEvent<GameLifecycle.StartCycle> { tickRooms() }
        onCommand("nylo") {
            desc = "ToB Nylo boss, 5s: the sim's plan run live with bots - ::nylo [median | top10 | slow | time m:ss.t | seed s | stats | stop]"
            invalidArgs = "Usage: ::nylo [median | top10 | slow | time m:ss.t | seed s | stats | stop]"
            cheat { xarpus() }
        }
        System.getenv("NYLO_SELFTEST")?.let {
            val seeds = ArrayDeque(it.ifBlank { "n1" }.split(','))
            onEvent<GameLifecycle.StartCycle> {
                if (mapClock.cycle >= 20 && SELF_TEST !in rooms && seeds.isNotEmpty()) open(SELF_TEST, null, seeds.removeFirst())
            }
        }
    }

    private fun Cheat.xarpus() {
        val pick = random.of(0, 9999)
        val chosen: NyloIndex.Run? =
            when (args.getOrNull(0)?.lowercase()) {
                null, "again" -> null
                "stop" -> return rooms[player.username]?.let { close(it); leave(player) } ?: player.mes("You have no Nylo boss room open.")
                "log" -> return rooms[player.username]?.let { it.log = !it.log } ?: Unit
                "stats" -> return player.mes(NyloIndex.stats())
                "median" -> NyloIndex.median()
                "top10", "fast" -> NyloIndex.top10(pick)
                "slow" -> NyloIndex.slow10(pick)
                "time" -> NyloIndex.time(args.getOrNull(1) ?: "", pick) ?: return player.mes("::nylo time 0:33.6")
                "seed" -> NyloIndex.seed(args.getOrNull(1) ?: return player.mes("::nylo seed <seed>"))
                else -> return player.mes("::nylo [median | top10 | slow | time m:ss.t | seed s | stats | stop]")
            }
        val seed = chosen?.seed ?: "live${System.nanoTime() % 1_000_000}"
        open(player.username, player, seed) ?: return player.mes("No instance space free; try again shortly.")
        if (chosen != null) player.mes("Fight ${chosen.seed}: boss time ${chosen.time} (forms ${chosen.forms}).")
    }

    private fun open(owner: String, player: Player?, seed: String): NyloLiveRoom? {
        rooms.remove(owner)?.let { close(it) }
        val session =
            instances.createServerOwned(
                key = INSTANCE_KEY,
                spec =
                    InstanceSpec(
                        fee = 0,
                        maxPlayers = 6,
                        reclaimTicks = DEFAULT_EMPTY_INSTANCE_RECLAIM_TICKS,
                        graceTicks = DEFAULT_INSTANCE_GRACE_TICKS,
                        area = InstanceArea.copyRegions(centerRegionId = NyloRules.REGION),
                        settingsRowId = -1,
                    ),
                access = InstanceAccess.Private,
                currentTick = mapClock.cycle,
            ) ?: return null
        fun at(x: Int, y: Int) = checkNotNull(instances.resolveCoord(session, CoordGrid(x, y, 0)))
        val npc = Npc(NyloRules.SPAWNING, at(NyloRules.X, NyloRules.Y))
        npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        npc.moveRestrict = MoveRestrict.PassThru
        npc.ignoreCombatInteractions = true
        instances.attachNpc(session.id, npc)
        val room = NyloLiveRoom(session, npc, seed, ::at, npcRepo, npcHits, playerHits, bots, look)
        for (p in room.engine.ps) {
            val bot = bots.spawn(p.name, room.outsideTile(p.i), emptyList(), emptyList()) ?: continue
            bot.varMoveSpeed = MoveSpeed.Run
            room.bots += bot
        }
        rooms[owner] = room
        if (player != null) {
            protectedAccess.launch(player) {
                telejump(at(NyloRules.WATCH_X, NyloRules.WATCH_Y), TeleportType.Exempt)
                // a fixed camera from behind the entry, over the room: the team coming in through the barrier, then the fight
                Camera.camMoveTo(player, at(NyloRules.CAM_EYE_X, NyloRules.CAM_EYE_Y), NyloRules.CAM_EYE_H, CAM_RATE, CAM_RATE2)
                Camera.camLookAt(player, at(NyloRules.CAM_LOOK_X, NyloRules.CAM_LOOK_Y), 0, CAM_RATE, CAM_RATE2)
            }
            player.npcViewDistance = 32
            player.mes("Nylo boss (5s, the sim's plan, live) - seed $seed.")
        }
        logger.info { "vnylo: room for $owner (instance ${session.id}, seed $seed)" }
        return room
    }

    private fun tickRooms() {
        for ((owner, room) in rooms.entries.toList()) {
            try {
                room.tick()
                if (room.finished && owner == SELF_TEST) {
                    logger.info { "vnylo selftest done ${room.seed}: end t${room.engine.end} kill t${room.engine.kill} (index: boss ${NyloIndex.seed(room.seed).boss}, here ${room.engine.end?.let { it - room.engine.i1 }})" }
                    close(room)
                    rooms.remove(owner)
                }
            } catch (e: Exception) {
                logger.error(e) { "vnylo: room tick failed" }
            }
        }
    }

    private fun close(room: NyloLiveRoom) {
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
        Camera.camReset(player)
        protectedAccess.launch(player) { telejump(EXIT, TeleportType.Exempt) }
    }

    private companion object {
        /** The camera's cut (the cutscenes' rates; 0/0 draws nothing). */
        const val CAM_RATE = 232
        const val CAM_RATE2 = 100
        const val INSTANCE_KEY = "vnylo"
        const val SELF_TEST = "selftest"
        val EXIT = CoordGrid(3222, 3218, 0)
    }
}
