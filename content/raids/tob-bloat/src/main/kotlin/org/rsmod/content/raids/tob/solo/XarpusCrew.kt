package org.rsmod.content.raids.tob.solo

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.util.Wearpos
import kotlin.math.abs
import kotlin.math.max
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.content.raids.tob.bloat.RaidBots
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionNpc
import org.rsmod.map.CoordGrid

/**
 * Bots playing Xarpus as Blert's 50 fastest rooms of their scale do.
 *
 * - Exhumeds: the nearest free bot stands on each one as it comes up (a heal only goes while nobody's on it), and waits
 *   on his pool for the next ([WAIT]). Four ticks
 *   before he rises they wait under his pool on the middle of each side, and on the tick he rises they step out to the
 *   tile beside it (south, west, north, east, then south again) and attack the tick after. Thralls go up just before.
 * - Specs, by scale: everyone mauls first in trios and up except the 4s' and 5s' scythers, who BGS once the mauls are in
 *   (while his Defence is still above 20); a solo mauls while it has the energy and his Defence is up; a duo's second
 *   player scythes, then mauls. The rest hold a ZCB for the screech (4s, 5s, a trio's second), claw (a trio's third) or
 *   halberd his last hitpoints (solos, duos).
 * - The acid: see [place] - the dump-tile dance, under him for a solo.
 */
class XarpusCrew(
    override val room: XarpusSolo,
    members: List<Player>,
    private val bots: RaidBots,
    private val clock: MapClock,
) : BotCrew {
    /** [blert]: Blert's attack type, for the room's recording. */
    private enum class Move(val set: GearSet, val stance: Int, val blert: Int, val cost: Int = 0) {
        Scythe(NyloLoadout.SCYTHE, 1, 38),
        Maul(NyloLoadout.MAUL, 0, 85, 500),
        Bgs(NyloLoadout.BGS, 1, 11, 500),
        Claws(NyloLoadout.CLAWS, 1, 20, 500),
        Halberd(NyloLoadout.HALBERD, 1, 14, 300),
        Zcb(NyloLoadout.ZCB, 1, 58, 750),
    }

    /** A bot's plan: its first Defence spec, the one after, and its damage spec. */
    private class Role(val open: Move?, val second: Move?, val dps: Move?)

    private inner class Bot(val player: Player, val i: Int, val role: Role) {
        var opened = role.open == null
        var seconded = false
        var dpsDone = false
        var thrallAt = -THRALL_COOLDOWN
        var cover: XarpusSolo.Exhumed? = null
        var lastSwing = -1
        var home: CoordGrid = tile(SPOTS[i % SPOTS.size])
        var dumped: CoordGrid = home
        val spec: Int
            get() = player.vars["varp.sa_energy"]
        val ready: Boolean
            get() = player.actionDelay <= clock.cycle
    }

    private val logger = com.github.michaelbull.logging.InlineLogger()
    private val crew = members.mapIndexed { i, p -> Bot(p, i, roleFor(members.size, i)) }
    private val size = members.size

    /** The tick the room steps next: its counter moves on as each step ends, so a walk now is where it sees them then. */
    private val t: Int
        get() = room.t

    /** Blert: the team stands just inside the barrier on the room's first tick. */
    override fun starts(): List<CoordGrid> = crew.map { START }

    override val keepsHp: Boolean
        get() = false

    override fun doneAt(): Int = if (room.cleared) room.dead + DEATH_TICKS else -1

    override fun summary(): String =
        "Xarpus ${size}-man: dead ${raidTime(room.dead + DEATH_TICKS)} (t${room.dead}), rose t${room.riseAt}, screech t${room.screechAt}, " +
            "heals ${room.heals}, " + crew.joinToString { "${it.player.username} took ${room.taken[it.player] ?: 0}" }

    override fun prepare(bot: Player) {
        VarPlayerIntMapSetter.set(bot, "varbit.spellbook", ARCEUUS)
    }

    override fun tick() {
        if (room.dead >= 0) return
        for (b in crew) thrall(b)
        trace { "t$t ${room.debug()} rise t${room.riseAt} spit t${room.nextSpit} " + crew.joinToString(" ") { "${it.player.username}@${here(it).x - X},${here(it).z - Y} d${it.player.actionDelay - clock.cycle} s${it.spec}" } }
        if (room.phase == 0) return exhumeds()
        for (b in crew) {
            if (place(b)) continue
            swing(b)
        }
    }

    /** Blert: the thrall goes up just before he rises, and again after a swing once it's gone. */
    private fun thrall(b: Bot) {
        val p = b.player
        val rise = room.riseAt
        if (rise < 0 || t < rise - THRALL_LEAD || t - b.thrallAt < THRALL_COOLDOWN) return
        if (p.vars["varbit.arceuus_resurrection_active"] == 1) return
        if (t > rise && p.actionDelay - clock.cycle < THRALL_GAP) return
        b.thrallAt = t
        bots.spell(p, GREATER_GHOST)
        trace { "t$t ${p.username} casts its thrall" }
    }

    /* ---- the exhumeds */

    private fun exhumeds() {
        val rise = room.riseAt
        if (rise >= 0 && t >= rise - WAIT_UNDER) {
            for (b in crew) {
                val at = if (t >= rise) SPOTS[b.i % SPOTS.size] else UNDER[b.i % UNDER.size]
                walk(b, tile(at))
            }
            return
        }
        val up = room.exhumedsNow
        for (b in crew) if (b.cover !in up) b.cover = null
        for (e in up.sortedBy { it.born }) {
            if (crew.any { it.cover === e }) continue
            val b = crew.filter { it.cover == null }.minByOrNull { cheb(here(it), e.tile) } ?: break
            b.cover = e
        }
        for (b in crew) walk(b, b.cover?.tile ?: tile(WAIT[b.i % WAIT.size]))
    }

    /* ---- the acid */

    /**
     * Blert's dance: each bot holds a home tile beside him and swings from it; on the tick he spits it stands on its
     * dump tile, two straight out from home (a solo: the tile under him next to home, so he throws nothing), and is back
     * home the tick after. The acid and its bounces land on the dump tiles; the tiles beside him stay clean. A swing
     * due on the spit tick goes from home instead. In phase 3 home moves out of the quarter he's staring at. True when
     * it walked this tick.
     */
    private fun place(b: Bot): Boolean {
        val here = here(b)
        val home = home(b)
        val due = t + max(0, b.player.actionDelay - clock.cycle)
        if (room.phase == 2 && room.nextSpit == t && due != t) {
            walk(b, dump(b, home))
            return true
        }
        if (here == home) return false
        val stared = room.phase == 3 && room.quarter(here) == room.gaze
        if (due == t && !stared) return false
        walk(b, home)
        return true
    }

    /** Its home tile, moved along the ring when acid lands on it (or, in phase 3, when he stares its way). */
    private fun home(b: Bot): CoordGrid {
        val cur = b.home
        val gazed = room.phase == 3 && room.quarter(cur) == room.gaze
        if (cur !in room.pooled && !gazed) return cur
        val homes = crew.filter { it !== b }.map { it.home }.toSet()
        // out of his stare first (a spit-back is worth far more than a tick of acid), then clean, then near
        val next = RING.map { tile(it) }
            .filter { it !in homes && (room.phase != 3 || room.quarter(it) != room.gaze) }
            .minWithOrNull(compareBy<CoordGrid> { it in room.pooled }.thenBy { cheb(cur, it) }.thenBy { abs(it.x - cur.x) + abs(it.z - cur.z) })
            ?: return cur
        trace { "t$t ${b.player.username} home ${cur.x - X},${cur.z - Y} -> ${next.x - X},${next.z - Y}" }
        b.home = next
        return next
    }

    private fun dump(b: Bot, home: CoordGrid): CoordGrid {
        val n = outward(home)
        if (size == 1) return CoordGrid(home.x - n[0], home.z - n[1], home.level)
        val lat = intArrayOf(n[1], n[0])
        val others = crew.filter { it !== b }.flatMap { listOf(it.home, it.dumped) }.toSet()
        return DUMP_SHIFTS.map { l -> CoordGrid(home.x + 2 * n[0] + l * lat[0], home.z + 2 * n[1] + l * lat[1], home.level) }
            .firstOrNull { it.x in MIN_X..MAX_X && it.z in MIN_Y..MAX_Y && it !in room.pooled && it !in others }
            ?.also { b.dumped = it } ?: home
    }

    /** The way out from his 5x5 at a tile beside it (a corner's diagonal neighbours count as their side). */
    private fun outward(c: CoordGrid): IntArray {
        val x = c.x - X
        val y = c.z - Y
        return when {
            y < 0 -> intArrayOf(0, -1)
            y > SIZE - 1 -> intArrayOf(0, 1)
            x < 0 -> intArrayOf(-1, 0)
            else -> intArrayOf(1, 0)
        }
    }

    /* ---- the swings */

    private fun swing(b: Bot) {
        if (!b.ready || t <= room.riseAt) return
        val move = next(b)
        val p = b.player
        for ((slot, obj) in move.set.worn) {
            val id = obj.asRSCM(RSCMType.OBJ)
            if (p.worn[slot.slot]?.id != id) bots.equip(p, slot.slot, id)
        }
        if (move == Move.Zcb) NyloLoadout.RUBY_BOLTS.asRSCM(RSCMType.OBJ).let { if (p.worn[Wearpos.Quiver.slot]?.id != it) bots.equip(p, Wearpos.Quiver.slot, it) }
        VarPlayerIntMapSetter.set(p, "varp.com_mode", move.stance)
        if (move.cost > 0) VarPlayerIntMapSetter.set(p, "varp.sa_attack", 1)
        when (move) {
            b.role.open -> b.opened = true
            b.role.second -> if (b.opened && move.cost > 0) b.seconded = true
            b.role.dps -> b.dpsDone = move != Move.Claws && move != Move.Maul
            else -> {}
        }
        if (move.cost > 0 || current(p) !== room.xarpus || b.lastSwing < 0) bots.attack(p, room.xarpus)
        b.lastSwing = t
        trace { "t$t ${p.username} $move (Defence ${room.xarpus.defenceLvl}, hp ${room.xarpus.hitpoints})" }
        room.recordEvent(5, "\"player\":{\"name\":\"${p.username}\"},\"attack\":{\"type\":${move.blert},\"weapon\":{\"id\":${p.worn[Wearpos.RightHand.slot]?.id ?: -1}}}", here(b))
    }

    private fun next(b: Bot): Move {
        val r = b.role
        val def = room.xarpus.defenceLvl
        val hp = room.xarpus.hitpoints
        if (!b.opened && r.open != null) {
            if (r.open == Move.Bgs && !mauled()) return Move.Scythe
            if (r.open == Move.Bgs && def <= DEF_TARGET) {
                b.opened = true
                return Move.Scythe
            }
            if (b.spec >= r.open.cost) return r.open
        }
        if (r.second != null && !b.seconded && b.opened && mauled() && def > DEF_TARGET && b.spec >= r.second.cost) return r.second
        val dps = r.dps ?: return Move.Scythe
        if (b.dpsDone || b.spec < dps.cost) return Move.Scythe
        return when (dps) {
            Move.Zcb -> if (room.phase == 3) dps else Move.Scythe
            Move.Halberd -> if (hp <= room.xarpus.baseHitpointsLvl * CHALLY_AT / 100) dps else Move.Scythe
            Move.Maul -> if (def > SOLO_MAUL_TO) dps else Move.Claws.takeIf { hp <= SOLO_CLAWS_UNDER } ?: Move.Scythe
            Move.Claws -> if (room.phase == 3 || hp <= SOLO_CLAWS_UNDER) dps else Move.Scythe
            else -> dps
        }
    }

    private fun mauled(): Boolean = crew.none { it.role.open == Move.Maul && !it.opened } && t > room.riseAt + MAUL_LANDS

    private inline fun trace(text: () -> String) {
        if (TRACING) logger.info { "xarpus crew ${room.owner.username} ${text()}" }
    }

    private fun current(p: Player): Npc? = (p.interaction as? InteractionNpc)?.target

    /* ---- moving */

    private fun walk(b: Bot, to: CoordGrid) {
        if (here(b) == to) return
        bots.walk(b.player, room.at(to))
    }

    private fun here(b: Bot): CoordGrid = room.real(b.player.coords)

    private fun tile(rel: IntArray): CoordGrid = CoordGrid(X + rel[0], Y + rel[1], 1)

    private fun cheb(a: CoordGrid, b: CoordGrid): Int = max(abs(a.x - b.x), abs(a.z - b.z))

    private companion object {
        const val X = 3168
        const val Y = 4385
        const val WAIT_UNDER = 4
        const val SIZE = 5
        const val MIN_X = 3163
        const val MAX_X = 3177
        const val MIN_Y = 4380
        const val MAX_Y = 4394

        /** Sideways from straight out, in the order a dump tile is tried. */
        val DUMP_SHIFTS = listOf(0, 1, -1, 2, -2)
        const val ARCEUUS = 3
        const val GREATER_GHOST = "component.magic_spellbook:resurrect_greater_ghost"
        const val THRALL_LEAD = 6
        const val THRALL_COOLDOWN = 17
        const val THRALL_GAP = 2
        val START = CoordGrid(3170, 4380, 1)
        const val DEF_TARGET = 20
        const val MAUL_LANDS = 2
        const val CHALLY_AT = 5
        const val SOLO_MAUL_TO = 40
        const val SOLO_CLAWS_UNDER = 600
        const val DEATH_TICKS = 4
        val TRACING = System.getenv("TOB_TRIO_TRACE") != null

        /** Under his pool on the middle of each side, and the tile beside it they step out to (Blert, from his SW). */
        val UNDER = arrayOf(intArrayOf(2, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 2), intArrayOf(3, 1))
        val SPOTS = arrayOf(intArrayOf(2, -1), intArrayOf(-1, 2), intArrayOf(2, 5), intArrayOf(5, 2), intArrayOf(4, -1))

        /** Where they wait for the next exhumed: on his pool, within a run of three ticks of any tile (Blert's solos and duos). */
        val WAIT = arrayOf(intArrayOf(1, 1), intArrayOf(3, 3), intArrayOf(1, 3), intArrayOf(3, 1), intArrayOf(2, 2))

        /** Every tile touching his 5x5. */
        val RING: List<IntArray> =
            (-1..5).flatMap { x -> (-1..5).map { y -> intArrayOf(x, y) } }
                .filter { (x, y) -> (x == -1 || x == 5 || y == -1 || y == 5) && !((x == -1 || x == 5) && (y == -1 || y == 5)) }

        fun roleFor(size: Int, i: Int): Role {
            val m = Move.Maul
            return when (size) {
                1 -> Role(m, m, m)
                2 -> if (i == 0) Role(m, m, Move.Halberd) else Role(null, m, Move.Halberd)
                3 -> listOf(Role(m, Move.Bgs, Move.Claws), Role(m, null, Move.Zcb), Role(m, null, Move.Claws))[i]
                4 -> if (i < 3) Role(m, null, if (i == 2) Move.Claws else Move.Zcb) else Role(Move.Bgs, null, Move.Claws)
                else -> if (i < 3) Role(m, null, Move.Zcb) else Role(Move.Bgs, null, if (i == 3) Move.Claws else Move.Zcb)
            }
        }
    }
}
