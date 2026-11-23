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
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.content.raids.tob.bloat.maiden.MaidenIndex
import org.rsmod.content.raids.tob.bloat.maiden.MaidenLiveRoom
import org.rsmod.content.raids.tob.bloat.maiden.MaidenRooms

/**
 * `::maiden` - a private Maiden room playing the sim's 5s plan live with five bots: you watch from the middle of the
 * room. `::maiden median | top10 | slow | fail | time 1:01.2 | seed r12` picks a raid by how it comes out,
 * `::maiden stats` sums them up, `::maiden again` runs a random one, `::maiden stop` leaves, `::maiden log` logs ticks.
 */
class VMaidenScript
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
    private val live: MaidenRooms,
) : PluginScript() {
    private val logger = InlineLogger()
    private val rooms = HashMap<String, MaidenLiveRoom>()

    override fun ScriptContext.startup() {
        onEvent<GameLifecycle.StartCycle> { tickRooms() }
        onCommand("maiden") {
            desc = "ToB Maiden, 5s: the sim's plan run live with bots - ::maiden help"
            invalidArgs = "Usage: ::maiden help"
            cheat { maiden() }
        }
        System.getenv("MAIDEN_SELFTEST")?.let {
            // seeds, comma-separated: run one after another (each logs its result against the sim's)
            val seeds = ArrayDeque(it.ifBlank { "r1" }.split(','))
            onEvent<GameLifecycle.StartCycle> {
                if (mapClock.cycle >= SELF_TEST_AT && SELF_TEST !in rooms && seeds.isNotEmpty()) open(SELF_TEST, null, seeds.removeFirst())?.log = System.getenv("MAIDEN_LOG") != null
            }
        }
    }

    private fun Cheat.maiden() {
        val pick = random.of(0, 9999)
        val arg = args.getOrNull(0)?.lowercase()
        val chosen: MaidenIndex.Run? =
            when (arg) {
                null, "again" -> null
                "stop" -> return rooms[player.username]?.let { close(it); leave(player) } ?: player.mes("You have no Maiden room open.")
                "log" -> return rooms[player.username]?.let { it.log = !it.log } ?: Unit
                "cam" -> {
                    val room = rooms[player.username] ?: return player.mes("Open a Maiden room first.")
                    val n = args.drop(1).mapNotNull { it.toIntOrNull() }
                    if (n.size == 1 && n[0] == 0) return Camera.camReset(player)
                    if (n.size != 6 && n.size != 8) return player.mes("::maiden cam eyeX eyeY eyeHeight lookX lookY lookHeight [rate rate2] (room tiles; 0 resets)")
                    cam = (n + if (n.size == 6) listOf(cam[6], cam[7]) else emptyList()).toIntArray()
                    return aim(player, room)
                }
                "stats" -> return player.mes(MaidenIndex.stats())
                "median" -> MaidenIndex.median()
                "top10", "fast" -> MaidenIndex.top10(pick)
                "slow" -> MaidenIndex.slow10(pick)
                "fail", "failed" -> MaidenIndex.failed(pick)
                "time" -> MaidenIndex.time(args.getOrNull(1) ?: "", pick) ?: return player.mes("::maiden time 1:01.2")
                "seed" -> MaidenIndex.seed(args.getOrNull(1) ?: return player.mes("::maiden seed <seed>"))
                else -> return player.mes("::maiden [again | median | top10 | slow | fail | time 1:01.2 | seed <s> | stats | stop]")
            }
        val seed = chosen?.seed ?: "live${System.nanoTime() % 1_000_000}"
        open(player.username, player, seed) ?: return player.mes("No instance space free; try again shortly.")
        if (chosen != null) player.mes("Raid ${chosen.seed}: ${chosen.time} (procs t${chosen.procs}, ${chosen.leaks} leaked).")
    }

    private fun open(owner: String, player: Player?, seed: String): MaidenLiveRoom? {
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
                        area = InstanceArea.copyRegions(centerRegionId = MaidenRules.REGION),
                        settingsRowId = -1,
                    ),
                access = InstanceAccess.Private,
                currentTick = mapClock.cycle,
            ) ?: return null
        val base = checkNotNull(instances.resolveCoord(session, CoordGrid(MaidenRules.ROOM_X, MaidenRules.ROOM_Y, 0)))
        val max = MaidenRules.HP.getValue(5)
        val maidenAt = base.translate(MaidenRules.MAIDEN_X, MaidenRules.ROOM_H - 1 - MaidenRules.MAIDEN_Y)
        val npc = Npc(MaidenRules.PHASES[0], maidenAt)
        npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        npc.moveRestrict = MoveRestrict.PassThru
        npc.ignoreCombatInteractions = true
        instances.attachNpc(session.id, npc)
        npc.baseHitpointsLvl = max
        npc.hitpoints = max
        val room = MaidenLiveRoom(session, base, npc, seed, npcRepo, worldRepo, npcHits, playerHits, bots)
        for (p in room.engine.ps) {
            // outside the barrier, in line with the tile they come in on
            val bot = bots.spawn(p.name, room.tile(MaidenRules.OUTSIDE_X, p.pos[1]), emptyList(), emptyList()) ?: continue
            bot.varMoveSpeed = MoveSpeed.Run
            room.bots += bot
        }
        rooms[owner] = room
        live.rooms += room
        if (player != null) {
            val watch = room.tile(WATCH_X, WATCH_Y)
            protectedAccess.launch(player) {
                telejump(watch, TeleportType.Exempt)
                aim(player, room)
            }
            // the whole room stays drawn (the default NPC range is 15 tiles; she's 20 away)
            player.npcViewDistance = NPC_VIEW_DISTANCE
            player.mes("Maiden (5s, the sim's plan, live) - seed $seed. Starts in a few ticks.")
        }
        logger.info { "vmaiden: room for $owner (instance ${session.id}, seed $seed)" }
        return room
    }

    private fun tickRooms() {
        for ((owner, room) in rooms.entries.toList()) {
            try {
                room.tick()
                if (room.finished && owner == SELF_TEST) {
                    logger.info { "vmaiden selftest done ${room.seed}: dead t${room.engine.dead} procs ${room.engine.proc}/${room.engine.proc50}/${room.engine.proc30} leaks ${room.engine.leaks} off-spot ${room.offSpot} (index: ${MaidenIndex.seed(room.seed).let { "dead t${it.dead} procs ${it.procs} leaks ${it.leaks}" }})" }
                    close(room)
                    rooms.remove(owner)
                }
            } catch (e: Exception) {
                logger.error(e) { "vmaiden: room tick failed" }
            }
        }
    }

    private fun close(room: MaidenLiveRoom) {
        live.rooms -= room
        for (b in room.bots) bots.remove(b)
        room.bots.clear()
        room.remove()
        instances.end(room.session)
    }

    /** The watching camera: fixed above the room (eye and look-at in room tiles, [cam]), not on your character. */
    private fun aim(player: Player, room: MaidenLiveRoom) {
        val c = cam
        Camera.camMoveTo(player, room.tile(c[0], c[1]), c[2], c[6], c[7])
        Camera.camLookAt(player, room.tile(c[3], c[4]), c[5], c[6], c[7])
    }

    private var cam = intArrayOf(CAM_EYE_X, CAM_EYE_Y, CAM_EYE_H, CAM_LOOK_X, CAM_LOOK_Y, CAM_LOOK_H, CAM_RATE, CAM_RATE2)

    private fun leave(player: Player) {
        Camera.camReset(player)
        rooms.remove(player.username)
        player.npcViewDistance = null
        protectedAccess.launch(player) { telejump(EXIT, TeleportType.Exempt) }
    }

    private companion object {
        const val INSTANCE_KEY = "vmaiden"
        const val SELF_TEST = "selftest"
        const val SELF_TEST_AT = 20
        const val NPC_VIEW_DISTANCE = 32
        /** Where you watch from: south of the room, clear of the bots and the crab lanes (the camera looks north over it). */
        /** The watching camera: south of the room and high, looking at its middle (::maiden cam tunes it). */
        const val CAM_EYE_X = 12
        const val CAM_EYE_Y = 22
        const val CAM_EYE_H = 1350
        const val CAM_LOOK_X = 11
        const val CAM_LOOK_Y = 12
        const val CAM_LOOK_H = 0
        /** A cut, not a pan (the quests' cutscenes cut with these). */
        const val CAM_RATE = 232
        const val CAM_RATE2 = 100
        const val WATCH_X = 13
        const val WATCH_Y = 19
        val EXIT = CoordGrid(3222, 3218, 0)
    }
}
