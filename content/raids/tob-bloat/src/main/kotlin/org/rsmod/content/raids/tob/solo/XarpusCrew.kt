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
 * - The acid: see [place] - the rag dance, under him for a solo.
 * - The screech: a scythe due on his last tick before it tick fixes (the plan's claw spec or scratch, else a tick lost,
 *   two a quarter of the time - the sim's); then each bot swings from the quarter that's safe when it lands ([stare]).
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
        Scratch(NyloLoadout.CLAWS, 1, 19),
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
        var holdTill = -1
        var clean: CoordGrid? = null
        var thrallAt = -THRALL_COOLDOWN
        var cover: XarpusSolo.Exhumed? = null

        /** The exhumed it walks to, to take it over from whoever holds it ([handoffs]). */
        var relief: XarpusSolo.Exhumed? = null

        /** The exhumed it goes to once it's relieved of (or done with) its own. */
        var then: XarpusSolo.Exhumed? = null
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
        for (b in crew) {
            if (b.cover != null && b.cover !in up) b.cover = b.then?.takeIf { it in up }.also { b.then = null }
            if (b.relief != null && b.relief !in up) b.relief = null
            if (b.then != null && b.then !in up) b.then = null
        }
        relieve()
        for (e in up.sortedBy { it.born }) if (crew.none { it.cover === e || it.relief === e || it.then === e }) assign(e)
        handoffs()
        for (b in crew) (b.cover ?: b.relief)?.let { walk(b, it.tile) }
        val free = crew.filter { it.cover == null && it.relief == null }
        if (free.isEmpty()) return
        val next = room.nextExhumedAt
        val fixed = crew.mapNotNull { b -> b.cover?.takeIf { next < 0 || it.gone <= next }?.tile }
        val key = listOf(free.map { it.i }, fixed, up.map { it.tile }, free.map { quarterOf(it) })
        if (key != spreadKey) {
            spreadKey = key
            spread = cover(free, fixed, up.map { it.tile }.toSet())
        }
        for ((b, to) in free.zip(spread)) walk(b, to)
    }

    /**
     * Who takes a new exhumed: whichever way gets someone on it soonest - a free bot running to it, a busy one going
     * once its own sinks, or a free one relieving a busy one that's nearer, which goes on to it (the owner's: the 5s
     * floater relieves the quarter's player on its far exhumed and that player takes the new one). Ties: straight
     * runs first, then the quarter's owner, then the floater, then the orb order.
     */
    private fun assign(e: XarpusSolo.Exhumed) {
        class Way(val arrive: Int, val rank: Int, val go: () -> Unit)
        val ways = ArrayList<Way>()
        val free = crew.filter { it.cover == null && it.relief == null }
        for (f in free) ways += Way(runTicks(here(f), e.tile), if (owns(f, e)) 0 else if (floats(f)) 1 else 2 + f.i) { f.cover = e }
        for (b in crew) {
            val held = b.cover ?: continue
            if (b.then != null || crew.any { it.relief === held }) continue
            val after = runTicks(held.tile, e.tile)
            if (held.gone <= t + after) ways += Way(held.gone - t + after, 10 + b.i) { b.then = e }
            for (f in free) {
                val r = runTicks(here(f), held.tile)
                if (t + r >= held.gone) continue
                ways += Way(r + after, 20 + b.i * 8 + f.i) {
                    f.relief = held
                    b.then = e
                    trace { "t$t ${f.player.username} relieves ${b.player.username}, who takes the new one" }
                }
            }
        }
        ways.minWithOrNull(compareBy<Way> { max(it.arrive, HEAL_FROM) }.thenBy { it.rank })?.go?.invoke()
    }

    /**
     * A relief takes over on the tick it gets there, and the one it relieves steps off that same tick (an exhumed only
     * needs someone on it each tick - never two).
     */
    private fun handoffs() {
        for (f in crew) {
            val held = f.relief ?: continue
            val b = crew.firstOrNull { it !== f && it.cover === held }
            if (b == null) {
                f.cover = held
                f.relief = null
                continue
            }
            if (runTicks(here(f), held.tile) > 1) continue
            f.cover = held
            f.relief = null
            b.cover = b.then
            b.then = null
            trace { "t$t ${f.player.username} takes over from ${b.player.username}" }
        }
    }

    /**
     * The quarter a free bot keeps to (0 NE, 1 SE, 2 SW, 3 NW), or -1: in 5s the first four each own one and the fifth
     * floats; in 4s each keeps its own while nobody's on an exhumed, then the three left spread as they need.
     */
    private fun quarterOf(b: Bot): Int =
        when {
            size == 5 && b.i < 4 -> b.i
            floats(b) -> lastBusyQuarter()
            size == 4 && crew.none { it.cover != null } -> b.i
            else -> -1
        }

    private fun floats(b: Bot): Boolean = size == 5 && b.i == 4

    private fun owns(b: Bot, e: XarpusSolo.Exhumed): Boolean = size == 5 && b.i < 4 && room.quarter(e.tile) == b.i

    /** 5s: the floater heads for the quarter of the newest exhumed whose owner is on it (-1: none, it spreads). */
    private fun lastBusyQuarter(): Int =
        crew.filter { it.i < 4 && it.cover?.let { e -> owns(it, e) } == true }.maxByOrNull { it.cover!!.born }?.i ?: -1

    /**
     * 5s: an owner whose exhumed has sunk walks to the one the floater holds in its quarter and takes it over as it gets
     * there ([handoffs]) - then the floater is free to go to the next quarter.
     */
    private fun relieve() {
        if (size != 5) return
        val floater = crew[4]
        val held = floater.cover ?: return
        val owner = crew.firstOrNull { owns(it, held) } ?: return
        if (owner.cover != null || floater.then != null) return
        if (owner.relief === held || owner.relief != null) return
        owner.relief = held
        trace { "t$t ${owner.player.username} goes to relieve the floater" }
    }

    private var spreadKey: Any? = null
    private var spread: List<CoordGrid> = emptyList()

    /**
     * Where the free bots stand for the next exhumed (the owner: 5s a quarter each and a floater into the quarter whose
     * player just took one, 4s a quarter each then spread, trios and duos spread, a solo back to the middle): the
     * safespot tiles ([SAFESPOTS]; any tile for a solo) in each one's quarter ([quarterOf]) that leave the fewest heals
     * if it comes up on any tile of the room, counting the bots whose exhumed sinks before it as free where they stand.
     * From where they are, each moved to its best tile in turn until none improves.
     */
    private fun cover(bots: List<Bot>, fixed: List<CoordGrid>, up: Set<CoordGrid>): List<CoordGrid> {
        val spawns = SPAWNS.filter { it !in up }
        fun total(pts: List<CoordGrid>): Int = spawns.sumOf { s -> cost(pts.minOf { cheb(it, s) }) }
        val spots = if (size == 1) ARENA else SAFESPOTS.map { tile(it) }
        val allowed = bots.map { b -> quarterOf(b).let { q -> if (q < 0) spots else spots.filter { room.quarter(it) == q } } }
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

    /** Run ticks to a tile: two tiles a tick (ties in these go to the higher in the orb order: the crew's order). */
    private fun runTicks(a: CoordGrid, b: CoordGrid): Int = (cheb(a, b) + 1) / 2

    /** A bot reaching a tile d away heals him on each tick from the exhumed's third it's not there: a run of 2 a tick. */
    private fun cost(d: Int): Int = HEAL_COST * max(0, (d + 1) / 2 - HEAL_FROM) + d

    /* ---- the acid */

    /**
     * The rag dance (the owner's): each bot has a ragged tile of its own - the middle of its side, its home - and a clean
     * one beside it. A swing that goes on his scan (the spit tick) goes from the ragged one, so his acid lands on it
     * again; the rest go from the clean one, where nothing lands. Between swings a bot waits a tile straight out from
     * the one it swings from next, and walks straight in on the swing tick. On a spit tick it stands on its dump two
     * out - straight out from the clean tile when it swings the tick after - so the acid and the bounces land on that
     * band. In phase 3 it moves out of his stare ([stare]). A solo's dump is the tile under him next to home. True when
     * it walked.
     */
    private fun place(b: Bot): Boolean {
        val here = here(b)
        val home = home(b)
        val due = t + max(0, b.player.actionDelay - clock.cycle)
        if (room.phase == 3) return stare(b, here, due)
        if (due == t) return false
        val spit = room.nextSpit
        val clean = clean(b, home)
        val to =
            when {
                room.phase != 2 -> out(clean)
                spit == t -> if (due == t + 1) out(clean, 2) else dump(b, home)
                spit == due -> out(home)
                else -> out(clean)
            }
        if (here == to) return false
        walk(b, to)
        return true
    }

    /** The tile [k] straight out from a tile beside him: a swing from there walks straight in to it. */
    private fun out(c: CoordGrid, k: Int = 1): CoordGrid = outward(c).let { n -> CoordGrid(c.x + k * n[0], c.z + k * n[1], c.level) }

    /** Its clean swing tile: beside its home along his side, without acid and not another's. */
    private fun clean(b: Bot, home: CoordGrid): CoordGrid {
        val cur = b.clean
        if (cur != null && cur !in room.pooled) return cur
        val n = outward(home)
        val taken = crew.filter { it !== b }.flatMap { listOfNotNull(it.home, it.clean) }.toSet()
        val pick = listOf(1, -1).map { l -> CoordGrid(home.x + l * n[1], home.z + l * n[0], home.level) }
            .firstOrNull { it !in room.pooled && it !in taken && besideHim(it) } ?: home
        b.clean = pick
        return pick
    }

    /**
     * Phase 3: he never turns back to the quarter he's staring at, so a swing that lands after his next turn is safe
     * from that quarter - and one before it from any other. Each bot stands, beside him, in the quarter its next swing
     * is safe from (walking there between swings; standing in his stare costs nothing, only hitting does), on a clean
     * tile - the ones beside the middles the rag dance left clean - so a turn never costs a swing. A crew tick's swing is judged on the stare after the room's step before it: it lands after
     * the turn when it's past the turn's tick.
     */
    private fun stare(b: Bot, here: CoordGrid, due: Int): Boolean {
        val afterTurn = due > room.nextTurnAt
        val safe = { c: CoordGrid -> (room.quarter(c) == room.gaze) == afterTurn }
        if (!safe(b.home) || b.home in room.pooled) {
            val homes = crew.filter { it !== b }.map { it.home }.toSet()
            RING.map { tile(it) }.filter { safe(it) }
                .minWithOrNull(compareBy<CoordGrid> { it in room.pooled }.thenBy { it in homes }.thenBy { cheb(here, it) }.thenBy { abs(it.x - here.x) + abs(it.z - here.z) })
                ?.let {
                    trace { "t$t ${b.player.username} for its t$due swing (turn t${room.nextTurnAt}): ${it.x - X},${it.z - Y}" }
                    b.home = it
                }
        }
        if (here == b.home) return false
        if (due == t && safe(here) && besideHim(here)) return false
        walk(b, b.home)
        return true
    }

    private fun besideHim(c: CoordGrid): Boolean = RING.any { tile(it) == c }

    /** Its home tile: its own for phase 2 (it rags it); phase 3 moves it ([stare]). */
    private fun home(b: Bot): CoordGrid = b.home

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
        if (!b.ready || t <= room.riseAt || t < b.holdTill) return
        var (move, role) = next(b)
        if (move == Move.Scythe && room.screechDue() == t) {
            // the tick fix: a scythe thrown on his last tick before the screech would land on his stare
            move =
                when {
                    b.src.fix == "claw" && b.spec >= Move.Claws.cost -> Move.Claws
                    b.src.fix == "claw" || b.src.fix == "scratch" -> Move.Scratch
                    else -> {
                        b.holdTill = t + if (room.rand(LOSE_TWO_IN) == 0) 2 else 1
                        trace { "t$t ${b.player.username} tick fixes: waits till t${b.holdTill}" }
                        return
                    }
                }
            role = ""
            trace { "t$t ${b.player.username} tick fixes with $move" }
        }
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

        /** The tick fix by waiting: 2 ticks lost one time in this many, else 1 (the sim's). */
        const val LOSE_TWO_IN = 4
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
        val SPOTS = arrayOf(intArrayOf(2, -1), intArrayOf(-1, 2), intArrayOf(2, 5), intArrayOf(5, 2), intArrayOf(2, -1))

        /**
         * The P3 safespot tiles, two a quarter beside the middle of each side of his 5x5: where the free players of a
         * duo, trio or 4s wait (the owner's chart: each square is the nearest one's within 3 ticks, ties by orb order).
         */
        val SAFESPOTS =
            arrayOf(
                intArrayOf(1, 5), intArrayOf(-1, 3), intArrayOf(3, 5), intArrayOf(5, 3),
                intArrayOf(5, 1), intArrayOf(3, -1), intArrayOf(1, -1), intArrayOf(-1, 1),
            )

        /** The room's floor, and the tiles an exhumed can come up on (any but under his 5x5). */
        val ARENA: List<CoordGrid> = (3163..3177).flatMap { x -> (4380..4394).map { z -> CoordGrid(x, z, 1) } }
        val SPAWNS: List<CoordGrid> = ARENA.filter { it.x !in X until X + 5 || it.z !in Y until Y + 5 }

        /** Every tile touching his 5x5. */
        val RING: List<IntArray> =
            (-1..5).flatMap { x -> (-1..5).map { y -> intArrayOf(x, y) } }
                .filter { (x, y) -> (x == -1 || x == 5 || y == -1 || y == 5) && !((x == -1 || x == 5) && (y == -1 || y == 5)) }
    }
}
