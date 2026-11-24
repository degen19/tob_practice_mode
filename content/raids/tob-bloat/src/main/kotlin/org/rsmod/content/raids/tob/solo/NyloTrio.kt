package org.rsmod.content.raids.tob.solo

import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.content.raids.tob.bloat.RaidBots
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.map.CoordGrid
import kotlin.math.abs
import kotlin.math.max

/**
 * Tests: three bots clearing the Nylocas waves, one per style as Blert's trio guide splits the room (mage, ranger,
 * melee). Each kills only its own colour: aggressive ones first, then small ones (a big one only adds splits to the
 * room cap), the newest first (older ones go off on their own soon enough). Their hits are rolled here, at a steady
 * player's pace and accuracy, and land through the room's own hit rules; the room stops at Vasilias's spawn.
 */
class NyloTrio(
    private val room: NyloSolo,
    private val roles: List<Pair<Player, Role>>,
    private val bots: RaidBots,
    private val npcHits: NpcHitModifier,
) {
    /**
     * [speed]/[max]: the single-target weapon (Eye of Ayak, blowpipe, scythe); [aoeSpeed]/[aoeMax]: the clump weapon
     * (ice barrage, black chinchompa; melee has none), used on three or more of its colour within a tile of each other.
     */
    enum class Role(
        val style: Char,
        val type: HitType,
        val speed: Int,
        val range: Int,
        val max: Int,
        val delay: Int,
        val anim: String,
        val aoeSpeed: Int,
        val aoeMax: Int,
    ) {
        Mage('G', HitType.Magic, 3, 7, 40, 2, "seq.human_caststrike", 5, 30),
        Ranger('R', HitType.Ranged, 2, 7, 26, 2, "seq.human_bow", 4, 24),
        Melee('M', HitType.Melee, 5, 1, 45, 1, "seq.scythe_of_vitur_attack", 0, 0),
    }

    private val next = HashMap<Player, Int>()

    fun tick() {
        val targets = room.targets()
        for ((bot, role) in roles) {
            if (!bot.isSlotAssigned) continue
            val here = room.real(bot.coords)
            val mine = targets.filter { it.style == role.style }
            val target =
                mine.minWithOrNull(
                    compareBy<NyloSolo.Target>({ !it.aggro }, { it.big }, { -it.born }, { dist(here, room.real(it.npc.coords), if (it.big) 2 else 1) }),
                ) ?: continue
            val at = room.real(target.npc.coords)
            val size = if (target.big) 2 else 1
            val d = dist(here, at, size)
            if (d > role.range || d == 0) {
                bots.walk(bot, room.at(approach(here, at, size, role.range)))
                continue
            }
            if (room.t < (next[bot] ?: 0)) continue
            bot.faceNpc(target.npc)
            bot.anim(role.anim)
            val clump = mine.filter { o -> dist(at, room.real(o.npc.coords), if (o.big) 2 else 1) <= 1 }
            if (role.aoeSpeed > 0 && clump.size >= AOE_CLUMP) {
                next[bot] = room.t + role.aoeSpeed
                for (o in clump) {
                    val dmg = if (room.chance(ACCURACY)) room.rand(0, role.aoeMax) else 0
                    o.npc.queueHit(bot, role.delay, role.type, dmg, npcHits)
                }
                continue
            }
            next[bot] = room.t + role.speed
            val hits = if (role == Role.Melee) listOf(1.0, 0.5, 0.25) else listOf(1.0)
            for (share in hits) {
                val dmg = if (room.chance(ACCURACY)) room.rand(0, (role.max * share).toInt()) else 0
                target.npc.queueHit(bot, role.delay, role.type, dmg, npcHits)
            }
        }
    }

    /** Chebyshev distance from a tile to a nylocas of [size] (0: standing in it). */
    private fun dist(a: CoordGrid, b: CoordGrid, size: Int): Int =
        max(max(b.x - a.x, a.x - (b.x + size - 1)).coerceAtLeast(0), max(b.z - a.z, a.z - (b.z + size - 1)).coerceAtLeast(0))

    /** A tile [range] or fewer from the target, a step toward it from where the bot stands. */
    private fun approach(from: CoordGrid, to: CoordGrid, size: Int, range: Int): CoordGrid {
        if (range <= 1) {
            val spots = listOf(CoordGrid(to.x - 1, to.z, 0), CoordGrid(to.x + size, to.z, 0), CoordGrid(to.x, to.z - 1, 0), CoordGrid(to.x, to.z + size, 0))
            return spots.minBy { abs(it.x - from.x) + abs(it.z - from.z) }
        }
        val dx = Integer.signum(to.x - from.x)
        val dz = Integer.signum(to.z - from.z)
        return CoordGrid(from.x + dx * 2, from.z + dz * 2, 0)
    }

    private companion object {
        const val ACCURACY = 0.9
        const val AOE_CLUMP = 3
    }
}
