package org.rsmod.content.raids.tob.solo

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.util.Wearpos
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.content.raids.tob.bloat.RaidBots
import org.rsmod.content.raids.tob.bloat.xarpus.XarpusPlan
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionNpc
import org.rsmod.map.CoordGrid

/**
 * Bots playing Xarpus as Blert's 50 fastest rooms of their scale do.
 *
 * - Exhumeds: the nearest free bot stands on each one as it comes up (a heal only goes while nobody's on it) and steps
 *   off on the tick it can't heal any more; the free ones spread to cover the room ([cover]). Four ticks
 *   before he rises they wait under his pool on the middle of each side, and on the tick he rises they step out to the
 *   tile beside it (south, west, north, east, then south again) and attack the tick after. Thralls go up just before.
 * - Specs: the scale's plan ([XarpusPlan.BY_SCALE], the sim's Solver) played on what happens - opening specs, mauls again
 *   by how many opening mauls landed (read off his Defence), backups in turn down to the Defence target, ZCBs held for
 *   the screech, claws once no Defence spec is to come, the halberd for his last hitpoints, Lightbearer cycles.
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
        Ralos(NyloLoadout.RALOS, 1, 62, 500),
        ;

        val lands: Int
            get() = if (this == Ralos) RALOS_LANDS else if (this == Zcb) ZCB_LANDS else MELEE_LANDS

        val lowersDefence: Boolean
            get() = this == Maul || this == Bgs || this == Ralos

        companion object {
            fun of(spec: String): Move = when (spec) {
                "maul" -> Maul
                "bgs" -> Bgs
                "ralos" -> Ralos
                "zcb" -> Zcb
                else -> Claws
            }
        }
    }

    private inner class Bot(val player: Player, val i: Int, val src: XarpusPlan.Player) {
        val open: Move? = src.open.takeIf { it != "none" }?.let { Move.of(it) }
        var openDone = open == null
        var remaulDue = false
        var used = 0
        var attacks = 0
        var lbLeft = src.lbCycles
        var lastSpec = -1
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
    private val size = members.size
    private val plan = XarpusPlan.BY_SCALE.getValue(size.coerceIn(1, XarpusPlan.BY_SCALE.size))
    private val crew = members.mapIndexed { i, p -> Bot(p, i, plan.players[i % plan.players.size]) }

    /** A spec on its way: who, what, the tick it lands on and his Defence as it went. */
    private class Flight(val b: Bot, val move: Move, val lands: Int, val defence: Int, val role: String)

    private val flights = ArrayList<Flight>()
    private val maulers = crew.filter { it.open == Move.Maul }
    private var openLanded = -1
    private var maulsLanded = 0
    private var remaulers: List<Bot>? = if (maulers.isEmpty()) emptyList() else null

    private class Backup(val b: Bot, val move: Move) {
        var state = "wait"
    }

    private val backups = plan.backups.mapNotNull { (k, spec) -> crew.getOrNull(k)?.let { Backup(it, Move.of(spec)) } }

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
        val b = crew.firstOrNull { it.player === bot } ?: return
        VarPlayerIntMapSetter.set(bot, "varp.sa_energy", b.src.startSpec * SPEC_UNIT)
        if (b.src.lbCycles > 0) bot.invAdd(bot.inv, NyloLoadout.LIGHTBEARER, 1, strict = false)
    }

    override fun tick() {
        if (room.dead >= 0) return
        for (b in crew) thrall(b)
        trace { "t$t ${room.debug()} rise t${room.riseAt} spit t${room.nextSpit} " + crew.joinToString(" ") { "${it.player.username}@${here(it).x - X},${here(it).z - Y} d${it.player.actionDelay - clock.cycle} s${it.spec}" } }
        if (room.phase == 0) return exhumeds()
        landings()
        planDefence()
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
        val up = room.exhumedsNow.filter { t < it.gone }
        for (b in crew) if (b.cover != null && b.cover !in up) b.cover = null
        for (e in up.sortedBy { it.born }) {
            if (crew.any { it.cover === e }) continue
            val free = crew.filter { it.cover == null }
            val b = free.firstOrNull { quarterOf(it) == room.quarter(e.tile) } ?: free.minByOrNull { cheb(here(it), e.tile) } ?: break
            b.cover = e
        }
        for (b in crew) b.cover?.let { walk(b, it.tile) }
        val free = crew.filter { it.cover == null }
        if (free.isEmpty()) return
        val next = room.nextExhumedAt
        val fixed = crew.mapNotNull { b -> b.cover?.takeIf { next < 0 || it.gone <= next }?.tile }
        val key = listOf(free.map { it.i }, fixed, up.map { it.tile })
        if (key != spreadKey) {
            spreadKey = key
            spread = cover(free, fixed, up.map { it.tile }.toSet())
        }
        for ((b, to) in free.zip(spread)) walk(b, to)
    }

    /**
     * The quarter a free bot keeps to (0 NE, 1 SE, 2 SW, 3 NW), or -1: in 5s the first four each own one and the fifth
     * floats; in 4s each keeps its own while nobody's on an exhumed, then the three left spread as they need.
     */
    private fun quarterOf(b: Bot): Int =
        when {
            size == 5 && b.i < 4 -> b.i
            size == 4 && crew.none { it.cover != null } -> b.i
            else -> -1
        }

    private var spreadKey: Any? = null
    private var spread: List<CoordGrid> = emptyList()

    /**
     * Where the free bots stand for the next exhumed (the owner: 5s a quarter each and a floater into the quarter whose
     * player just took one, 4s a quarter each then spread, trios and duos spread, a solo back to the middle): the tiles
     * (in each one's quarter, [quarterOf]) that leave the fewest heals if it comes up on any tile of the room, counting
     * the bots whose exhumed sinks before it as free where they stand. From where they are, each moved to its best tile
     * in turn until none improves.
     */
    private fun cover(bots: List<Bot>, fixed: List<CoordGrid>, up: Set<CoordGrid>): List<CoordGrid> {
        val spawns = SPAWNS.filter { it !in up }
        fun total(pts: List<CoordGrid>): Int = spawns.sumOf { s -> cost(pts.minOf { cheb(it, s) }) }
        val allowed = bots.map { b -> quarterOf(b).let { q -> if (q < 0) ARENA else ARENA.filter { room.quarter(it) == q } } }
        val cur = bots.mapIndexed { i, b -> here(b).let { h -> allowed[i].minBy { cheb(it, h) } } }.toMutableList()
        var best = total(fixed + cur)
        repeat(COVER_ROUNDS) {
            var moved = false
            for (i in cur.indices) {
                val keep = cur[i]
                var pick = keep
                for (c in allowed[i]) {
                    cur[i] = c
                    val v = total(fixed + cur)
                    if (v < best) {
                        best = v
                        pick = c
                    }
                }
                cur[i] = pick
                if (pick != keep) moved = true
            }
            if (!moved) return cur
        }
        return cur
    }

    /** A bot reaching a tile d away heals him on each tick from the exhumed's third it's not there: a run of 2 a tick. */
    private fun cost(d: Int): Int = HEAL_COST * max(0, (d + 1) / 2 - HEAL_FROM) + d

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
        val (move, role) = next(b)
        val p = b.player
        for ((slot, obj) in move.set.worn) {
            val id = obj.asRSCM(RSCMType.OBJ)
            if (p.worn[slot.slot]?.id != id) bots.equip(p, slot.slot, id)
        }
        if (b.lbLeft > 0 && b.spec < MAX_SPEC) NyloLoadout.LIGHTBEARER.asRSCM(RSCMType.OBJ).let { if (p.worn[Wearpos.Ring.slot]?.id != it) bots.equip(p, Wearpos.Ring.slot, it) }
        if (move == Move.Zcb) NyloLoadout.RUBY_BOLTS.asRSCM(RSCMType.OBJ).let { if (p.worn[Wearpos.Quiver.slot]?.id != it) bots.equip(p, Wearpos.Quiver.slot, it) }
        VarPlayerIntMapSetter.set(p, "varp.com_mode", move.stance)
        if (move.cost > 0) {
            VarPlayerIntMapSetter.set(p, "varp.sa_attack", 1)
            b.lastSpec = b.spec - move.cost
            flights += Flight(b, move, t + move.lands, room.xarpus.defenceLvl, role)
        }
        if (move.cost > 0 || current(p) !== room.xarpus || b.attacks == 0) bots.attack(p, room.xarpus)
        b.attacks++
        trace { "t$t ${p.username} $move${if (role.isNotEmpty()) " ($role)" else ""} (Defence ${room.xarpus.defenceLvl}, hp ${room.xarpus.hitpoints}, spec ${b.spec})" }
        room.recordEvent(5, "\"player\":{\"name\":\"${p.username}\"},\"attack\":{\"type\":${move.blert},\"weapon\":{\"id\":${p.worn[Wearpos.RightHand.slot]?.id ?: -1}}}", here(b))
    }

    /* ---- the plan: the sim's rules (verzikSim engine/xarpus.js), on what the room does */

    /** Specs that have landed: mauls counted off his Defence (each that lands takes 35% of it), Lightbearer cycles. */
    private fun landings() {
        for (b in crew) {
            if (b.lbLeft > 0 && b.lastSpec >= 0 && b.spec > b.lastSpec) {
                b.lbLeft--
                trace { "t$t ${b.player.username} regens to ${b.spec} (${b.lbLeft} Lightbearer cycles left)" }
            }
            b.lastSpec = b.spec
        }
        val landed = flights.filter { it.lands < t }
        if (landed.isEmpty()) return
        flights -= landed.toSet()
        for ((_, group) in landed.groupBy { it.lands }) {
            val mauls = group.filter { it.move == Move.Maul }
            if (mauls.isEmpty()) continue
            val before = group.maxOf { it.defence }.coerceAtLeast(1)
            val now = room.xarpus.defenceLvl.coerceAtLeast(1)
            val hit = Math.round(ln(now.toDouble() / before) / ln(1 - MAUL_DRAIN)).toInt().coerceIn(0, mauls.size)
            maulsLanded += hit
            val open = mauls.count { it.role == "open" }
            if (open > 0) openLanded = (if (openLanded < 0) 0 else openLanded) + min(hit, open)
            trace { "t$t ${mauls.size} maul(s) land, $hit hit: Defence $before -> $now" }
        }
    }

    private fun canSpec(b: Bot): Boolean = b.used < if (size == 1) SOLO_DEF_SPECS else DEF_SPECS

    private fun defFlying(): Boolean = flights.any { it.move.lowersDefence }

    private fun remaulOpen(): Boolean = remaulers == null || crew.any { it.remaulDue } || flights.any { it.role == "remaul" }

    private fun settled(): Boolean = maulers.all { it.openDone } && !remaulOpen()

    private fun startDefDone(): Boolean = crew.all { it.openDone } && !remaulOpen() && !defFlying()

    private fun defOpen(): Boolean =
        crew.any { !it.openDone } || remaulOpen() || defFlying() || (room.xarpus.defenceLvl > plan.defTarget && backups.any { it.state == "wait" || it.state == "armed" })

    private fun planDefence() {
        if (remaulers == null && maulers.all { it.openDone } && flights.none { it.role == "open" && it.move == Move.Maul }) {
            val r = (plan.remaul[openLanded.coerceAtLeast(0)] ?: emptyList()).mapNotNull { crew.getOrNull(it) }.filter { canSpec(it) }
            remaulers = r
            for (b in r) b.remaulDue = true
            trace { "t$t ${openLanded.coerceAtLeast(0)} of ${maulers.size} opening mauls landed: ${r.joinToString { it.player.username }} maul again" }
        }
        if (remaulers == null || backups.any { it.state == "armed" } || defFlying() || !settled() || crew.any { !it.openDone }) return
        val next = backups.firstOrNull { it.state == "wait" } ?: return
        val b = next.b
        if (room.xarpus.defenceLvl <= plan.defTarget) {
            for (x in backups) if (x.state == "wait") x.state = "skip"
            return
        }
        if (!canSpec(b) || b.spec < next.move.cost || (next.move == Move.Maul && maulsLanded >= MAUL_BACKUP_UNTIL)) {
            next.state = "skip"
            return
        }
        next.state = "armed"
        trace { "t$t backup: ${b.player.username} ${next.move} (Defence ${room.xarpus.defenceLvl})" }
    }

    /** What it swings now, and the spec's part in the plan (open, remaul, backup; "" for the rest). */
    private fun next(b: Bot): Pair<Move, String> {
        val hp = room.xarpus.hitpoints
        defence(b)?.let { return it }
        b.ownDps()?.let { return it to "" }
        if (plan.chally && hp <= room.xarpus.baseHitpointsLvl * plan.challyAt / 100 && b.spec >= Move.Halberd.cost) return Move.Halberd to ""
        return Move.Scythe to ""
    }

    private fun defence(b: Bot): Pair<Move, String>? {
        val open = b.open
        if (!b.openDone && open != null) {
            if (open == Move.Maul) return if (b.spec >= open.cost) spent(b, open, "open") else null
            if (b.attacks < 1 || !settled()) return null
            if (room.xarpus.defenceLvl <= plan.defTarget) {
                b.openDone = true
                return null
            }
            return if (b.spec >= open.cost) spent(b, open, "open") else null
        }
        if (b.remaulDue) return if (b.spec >= Move.Maul.cost) spent(b, Move.Maul, "remaul") else null
        val backup = backups.firstOrNull { it.b === b && it.state == "armed" } ?: return null
        if (room.xarpus.defenceLvl <= plan.defTarget || !canSpec(b)) {
            backup.state = "skip"
            return null
        }
        if (b.spec < backup.move.cost) return null
        backup.state = "done"
        return spent(b, backup.move, "backup")
    }

    private fun spent(b: Bot, move: Move, role: String): Pair<Move, String> {
        b.used++
        when (role) {
            "open" -> b.openDone = true
            "remaul" -> b.remaulDue = false
        }
        return move to role
    }

    private fun ownDefDone(b: Bot): Boolean =
        b.openDone && !b.remaulDue && !(remaulers == null && plan.remaul.values.any { b.i in it }) &&
            !(room.xarpus.defenceLvl > plan.defTarget && backups.any { it.b === b && (it.state == "wait" || it.state == "armed") })

    private fun Bot.ownDps(): Move? {
        if (!ownDefDone(this)) return null
        val hp = room.xarpus.hitpoints
        return when (src.dps) {
            "zcb" -> when {
                hp < plan.zcbBelow -> Move.Claws.takeIf { !defOpen() && spec >= it.cost }
                !startDefDone() -> null
                plan.holdZcb -> Move.Zcb.takeIf { room.phase == 3 && spec >= it.cost }
                else -> Move.Zcb.takeIf { settled() && spec >= it.cost }
            }
            "claws" -> Move.Claws.takeIf { !defOpen() && spec >= it.cost }
            else -> null
        }
    }

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
        const val COVER_ROUNDS = 4
        const val HEAL_FROM = 3
        const val HEAL_COST = 100
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
        const val SPEC_UNIT = 10
        const val MAX_SPEC = 1000
        const val MELEE_LANDS = 1
        const val ZCB_LANDS = 2
        const val RALOS_LANDS = 3
        const val MAUL_DRAIN = 0.35
        const val MAUL_BACKUP_UNTIL = 2
        const val DEF_SPECS = 2
        const val SOLO_DEF_SPECS = 3
        const val DEATH_TICKS = 4
        val TRACING = System.getenv("TOB_TRIO_TRACE") != null

        /** Under his pool on the middle of each side, and the tile beside it they step out to (Blert, from his SW). */
        val UNDER = arrayOf(intArrayOf(2, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 2), intArrayOf(3, 1))
        val SPOTS = arrayOf(intArrayOf(2, -1), intArrayOf(-1, 2), intArrayOf(2, 5), intArrayOf(5, 2), intArrayOf(4, -1))

        /** The room's floor, and the tiles an exhumed can come up on (any but under his 5x5). */
        val ARENA: List<CoordGrid> = (3163..3177).flatMap { x -> (4380..4394).map { z -> CoordGrid(x, z, 1) } }
        val SPAWNS: List<CoordGrid> = ARENA.filter { it.x !in X until X + 5 || it.z !in Y until Y + 5 }

        /** Every tile touching his 5x5. */
        val RING: List<IntArray> =
            (-1..5).flatMap { x -> (-1..5).map { y -> intArrayOf(x, y) } }
                .filter { (x, y) -> (x == -1 || x == 5 || y == -1 || y == 5) && !((x == -1 || x == 5) && (y == -1 || y == 5)) }
    }
}
