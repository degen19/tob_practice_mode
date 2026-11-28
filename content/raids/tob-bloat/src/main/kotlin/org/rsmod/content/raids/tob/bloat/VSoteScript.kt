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
import org.rsmod.content.raids.tob.bloat.sote.SoteIndex
import org.rsmod.content.raids.tob.bloat.sote.SoteLiveRoom
import org.rsmod.content.raids.tob.bloat.sote.SoteRules
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * `::sote [1-5]` - a private Sotetseg room playing the sim's plan live with a team of bots (5 unless given); you watch
 * from south of the stack. `::sote [1-5] median | top10 | top50 | slow | time 3:01.2 | seed x12 | stats | stop | log`,
 * as `::maiden`; top50 plays a raid whose time is in Blert's top 50 for the team size.
 * `SOTE_SELFTEST=s1,3/s2,...` plays the seeds in turn (team/seed; 5s if no team is given).
 */
class VSoteScript
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
    private val rooms = HashMap<String, SoteLiveRoom>()
    private val look by lazy { RaidLook(worldRepo) }

    override fun ScriptContext.startup() {
        onEvent<GameLifecycle.StartCycle> { tickRooms() }
        onCommand("sote") {
            desc = "ToB Sotetseg: the sim's plan run live with bots - ::sote [1-5] [median | top10 | top50 | slow | time m:ss.t | seed s | stats | stop]"
            invalidArgs = "Usage: ::sote [1-5] [median | top10 | top50 | slow | time m:ss.t | seed s | stats | stop]"
            cheat { xarpus() }
        }
        System.getenv("SOTE_SELFTEST")?.let {
            val seeds = ArrayDeque(it.ifBlank { "s1" }.split(','))
            onEvent<GameLifecycle.StartCycle> {
                if (mapClock.cycle >= 20 && SELF_TEST !in rooms && seeds.isNotEmpty()) {
                    val entry = seeds.removeFirst().split('/')
                    if (entry.size == 2) open(SELF_TEST, null, entry[0].toInt(), entry[1]) else open(SELF_TEST, null, 5, entry[0])
                }
            }
        }
    }

    private fun Cheat.xarpus() {
        val pick = random.of(0, 9999)
        val team = args.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..5 }
        val rest = if (team != null) args.drop(1) else args.toList()
        val size = team ?: 5
        val chosen: SoteIndex.Run? =
            when (rest.getOrNull(0)?.lowercase()) {
                null, "again" -> null
                "stop" -> return rooms[player.username]?.let { close(it); leave(player) } ?: player.mes("You have no Sotetseg room open.")
                "log" -> return rooms[player.username]?.let { it.log = !it.log } ?: Unit
                "stats" -> return player.mes(SoteIndex.stats(size))
                "median" -> SoteIndex.median(size)
                "top10", "fast" -> SoteIndex.top10(size, pick)
                "top50", "blert" -> SoteIndex.top50(size, pick)
                "slow" -> SoteIndex.slow10(size, pick)
                "time" -> SoteIndex.time(size, rest.getOrNull(1) ?: "", pick) ?: return player.mes("::sote [1-5] time 1:25.2")
                "seed" -> SoteIndex.seed(size, rest.getOrNull(1) ?: return player.mes("::sote [1-5] seed <seed>"))
                else -> return player.mes("::sote [1-5] [median | top10 | top50 | slow | time m:ss.t | seed s | stats | stop]")
            }
        val seed = chosen?.seed ?: "live${System.nanoTime() % 1_000_000}"
        open(player.username, player, size, seed) ?: return player.mes("No instance space free; try again shortly.")
        if (chosen != null) player.mes("Raid ${chosen.seed}: ${chosen.time} (mazes t${chosen.mazes}${if (chosen.deaths > 0) ", ${chosen.deaths} died" else ""}).")
    }

    private fun open(owner: String, player: Player?, team: Int, seed: String): SoteLiveRoom? {
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
                        area = InstanceArea.copyRegions(centerRegionId = SoteRules.REGION),
                        settingsRowId = -1,
                    ),
                access = InstanceAccess.Private,
                currentTick = mapClock.cycle,
            ) ?: return null
        fun at(x: Int, y: Int) = checkNotNull(instances.resolveCoord(session, CoordGrid(x, y, 0)))
        val npc = Npc(SoteRules.NPC, at(SoteRules.X, SoteRules.Y))
        npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        npc.moveRestrict = MoveRestrict.PassThru
        npc.ignoreCombatInteractions = true
        instances.attachNpc(session.id, npc)
        val room = SoteLiveRoom(session, npc, seed, team, ::at, npcRepo, worldRepo, npcHits, playerHits, protectedAccess, bots, look)
        for (p in room.engine.ps) {
            val bot = bots.spawn(p.name, room.outsideTile(p.i), emptyList(), emptyList()) ?: continue
            bot.varMoveSpeed = MoveSpeed.Run
            room.bots += bot
        }
        rooms[owner] = room
        if (player != null) {
            protectedAccess.launch(player) {
                telejump(at(SoteRules.WATCH_X, SoteRules.WATCH_Y), TeleportType.Exempt)
                // a fixed camera from behind the entry, over the room: the team coming in through the barrier, then the fight
                Camera.camMoveTo(player, at(SoteRules.CAM_EYE_X, SoteRules.CAM_EYE_Y), SoteRules.CAM_EYE_H, CAM_RATE, CAM_RATE2)
                Camera.camLookAt(player, at(SoteRules.CAM_LOOK_X, SoteRules.CAM_LOOK_Y), 0, CAM_RATE, CAM_RATE2)
            }
            player.npcViewDistance = 32
            player.mes("Sotetseg ($team-man, the sim's plan, live) - seed $seed.")
        }
        logger.info { "vsote: room for $owner (instance ${session.id}, $team-man, seed $seed)" }
        return room
    }

    private fun tickRooms() {
        for ((owner, room) in rooms.entries.toList()) {
            try {
                room.tick()
                if (room.finished && owner == SELF_TEST) {
                    logger.info { "vsote selftest done ${room.team}/${room.seed}: end t${room.engine.end} (index: end t${SoteIndex.seed(room.team, room.seed).end})" }
                    close(room)
                    rooms.remove(owner)
                }
            } catch (e: Exception) {
                logger.error(e) { "vsote: room tick failed" }
            }
        }
    }

    private fun close(room: SoteLiveRoom) {
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
        const val INSTANCE_KEY = "vsote"
        const val SELF_TEST = "selftest"
        val EXIT = CoordGrid(3222, 3218, 0)
    }
}
