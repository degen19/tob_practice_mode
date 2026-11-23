package org.rsmod.content.raids.tob.solo

import org.rsmod.api.player.output.mes
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.death.PlayerDeathOverrideHook
import org.rsmod.api.player.deathResetTimers
import org.rsmod.api.player.disablePrayers
import org.rsmod.api.player.stat.statRestoreAll
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.npc.hit.NpcHitLandHook
import org.rsmod.api.npc.hit.NpcHitpointsFloorHook
import org.rsmod.api.player.hit.modifier.PlayerHitModifier
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.hit.Hit
import org.rsmod.game.queue.WorldQueueList
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * The rooms of the raid, in order, with the map they're copied from (real tiles). [lineX]/[lineY] is the room's exit
 * line, the tiles just through its Formidable passage: the next room's instance cycle starts from the tick a player
 * stands on it (the same line the client's instance timer starts from).
 */
enum class TobRoom(val label: String, val regions: List<Int>, val level: Int, val minX: Int, val minY: Int, val maxX: Int, val maxY: Int, val lineX: IntRange, val lineY: IntRange, val allLevels: Boolean = false) {
    MAIDEN("The Maiden of Sugadinti", listOf(12613), 0, 3136, 4416, 3199, 4479, 3175..3178, 4421..4421),
    BLOAT("The Pestilent Bloat", listOf(13125), 0, 3264, 4416, 3327, 4479, 3268..3268, 4446..4449),
    NYLOCAS("The Nylocas", listOf(13122), 0, 3264, 4224, 3327, 4287, 3304..3304, 4274..4277),
    SOTETSEG("Sotetseg", listOf(13123, 13379), 0, 3264, 4288, 3391, 4351, 3278..3281, 4292..4292, allLevels = true),
    XARPUS("Xarpus", listOf(12612), 1, 3136, 4352, 3199, 4415, 3169..3171, 4400..4400),
    VERZIK("The Final Challenge", listOf(12611), 0, 3136, 4288, 3199, 4351, IntRange.EMPTY, IntRange.EMPTY);

    fun onLine(real: CoordGrid): Boolean = real.x in lineX && real.z in lineY

    val next: TobRoom?
        get() = entries.getOrNull(ordinal + 1)

    companion object {
        fun parse(s: String): TobRoom? =
            when (s.lowercase()) {
                "maiden", "m", "1" -> MAIDEN
                "bloat", "b", "2" -> BLOAT
                "nylo", "nylocas", "n", "3" -> NYLOCAS
                "sote", "sotetseg", "s", "4" -> SOTETSEG
                "xarp", "xarpus", "x", "5" -> XARPUS
                "verzik", "verz", "v", "6" -> VERZIK
                else -> null
            }
    }
}

/** What every room needs from the server. */
@Singleton
class SoloDeps
@Inject
constructor(
    val instances: InstanceManager,
    val npcRepo: NpcRepository,
    val locRepo: LocRepository,
    val worldRepo: WorldRepository,
    val collision: CollisionFlagMap,
    val random: GameRandom,
    val playerHits: PlayerHitModifier,
    val queues: WorldQueueList,
    val mapClock: MapClock,
    val raids: SoloRaids,
    val players: PlayerList,
    val eventBus: org.rsmod.events.EventBus,
)

/** Every live solo room, by owner. */
@Singleton
class SoloRaids {
    val byOwner = HashMap<String, SoloRoom>()

    /** Players playing Entry mode (by username); everyone else plays Normal mode. */
    private val entryMode = HashSet<String>()

    fun isEntry(player: Player): Boolean = player.username in entryMode

    /** Flips the player's mode; returns true if they're now on Entry mode. */
    /** Switches the player between Entry and Normal mode and rebuilds the room they're at (outside its barrier). */
    fun switchMode(player: Player) {
        val entry = toggleEntry(player)
        player.mes(if (entry) "The Theatre is now on Entry mode: weaker bosses, unlimited deaths." else "The Theatre is now on Normal mode.")
        val room = byOwner[player.username] ?: return
        travel?.invoke(player, room.id)
    }

    fun toggleEntry(player: Player): Boolean = if (!entryMode.add(player.username)) { entryMode.remove(player.username); false } else true

    /** Set by [SoloRaidScript]: take a player to a room (closing the one they're in). */
    var travel: ((Player, TobRoom) -> Unit)? = null

    fun isLive(room: SoloRoom): Boolean = byOwner[room.owner.username] === room

    /** The map clock on the tick each player last stood on a room's exit line, by username (see [TobRoom.lineX]). */
    val lineTicks = HashMap<String, Int>()

    /** Ticks of every room cleared so far this raid, by username (the "Total" in a wave's message). */
    val raidTicks = HashMap<String, Int>()

    fun forPlayer(player: Player): SoloRoom? = byOwner[player.username]?.takeIf { it.contains(player.coords) }

    fun forNpc(npc: Npc): SoloRoom? = byOwner.values.firstOrNull { it.owns(npc) }
}

/** Hits on a solo room's NPCs go through the room first (shields, immunities, halving). */
class SoloLand @Inject constructor(private val raids: SoloRaids) : NpcHitLandHook {
    override fun land(npc: Npc, hit: Hit): Int = raids.forNpc(npc)?.land(npc, hit) ?: hit.damage
}

/** A room's bosses never die off a hit: the room plays their phases and deaths itself. */
class SoloFloor @Inject constructor(private val raids: SoloRaids) : NpcHitpointsFloorHook {
    override fun floor(npc: Npc, hit: Hit): Int = raids.forNpc(npc)?.floor(npc) ?: 0
}

/** Dying in a solo room: back outside the barrier as you went in, and the room starts over. */
class SoloDeath @Inject constructor(private val raids: SoloRaids) : PlayerDeathOverrideHook {
    override suspend fun ProtectedAccess.override(): Boolean {
        val room = raids.forPlayer(player) ?: return false
        if (org.rsmod.api.player.PracticeSettings.noDeaths) {
            player.statMap.setCurrentLevel("stat.hitpoints", 1.toByte())
            return true
        }
        SoloRespawn.respawn(this, room)
        return true
    }
}

private var Player.specialEnergy by org.rsmod.api.player.vars.intVarp("varp.sa_energy")

/** Where a room's barrier sends you, and the setup handed back after a death. */
object SoloRespawn {
    private val setups = java.util.WeakHashMap<Player, Pair<List<org.rsmod.game.inv.InvObj?>, List<org.rsmod.game.inv.InvObj?>>>()

    fun save(player: Player) {
        setups[player] = player.worn.objs.toList() to player.inv.objs.toList()
    }

    suspend fun respawn(access: ProtectedAccess, room: SoloRoom) {
        with(access) {
            stopAction()
            combatClearQueue()
            player.clearQueue("queue.hit")
            delay(2)
            anim("seq.human_death")
            delay(4)
            combatClearQueue()
            player.clearQueue("queue.hit")
            resetAnim()
            player.disablePrayers()
            player.deathResetTimers()
            player.statRestoreAll(dev.openrune.ServerCacheManager.getStats().values.map { dev.openrune.rscm.RSCM.getReverseMapping(dev.openrune.rscm.RSCMType.STAT, it.id) })
            setups[player]?.let { (worn, inv) ->
                for (slot in worn.indices) player.worn[slot] = worn[slot]
                for (slot in inv.indices) player.inv[slot] = inv[slot]
            }
            player.specialEnergy = 1000
            player.runEnergy = org.rsmod.api.config.constants.run_max_energy
            player.rebuildAppearance()
            telejump(room.at(room.entry), org.rsmod.api.player.hook.TeleportType.Exempt)
            room.resets++
            room.reset()
            mes("You died. ${room.id.label} starts over; you're back outside with the setup you went in with.")
        }
    }
}

/** Collision lookups on a real tile of a room. */
fun SoloRoom.blocked(real: CoordGrid): Boolean {
    val c = at(real)
    return d.collision[c.x, c.z, c.level] and (org.rsmod.routefinder.flag.CollisionFlag.BLOCK_WALK or org.rsmod.routefinder.flag.CollisionFlag.LOC) != 0
}
