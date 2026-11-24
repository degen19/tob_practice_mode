package org.rsmod.content.raids.tob.solo

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.player.clearInteractionRoute
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.content.raids.tob.bloat.RaidBots
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionNpc
import org.rsmod.map.CoordGrid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bot teammates for the Maiden, playing the sim's trio procedure line for line (`runMaiden` with `procedureOpts(3)`):
 * each bot works through its role's lists - the prefires, then a list per proc - and each smart line picks its target
 * the way the sim's does (`tfz` the mage's freezes by spawns, `tdps` a DPS's crab or a straggler, `tdin` the melee's
 * Dinh's timed and placed by value, `tpile` the mage's pile barrage when it's worth it). Positions are the sim's room
 * tiles (x east, y south, a crab by its north-west tile), so every rule reads as it does there.
 *
 * The melee also carries an Obligator, which the sim doesn't have: [MaidenCrewParams] switches on its uses and holds
 * the values a search tunes.
 */
class MaidenCrew(
    override val room: MaidenSolo,
    members: List<Pair<Player, NyloRole>>,
    private val bots: RaidBots,
    private val clock: MapClock,
    private val barrage: Int,
    private val rush: Int,
    private val params: MaidenCrewParams = MaidenCrewParams(),
) : BotCrew {
    enum class Move(val set: GearSet, val stance: Int, val cost: Int = 0) {
        Tbow(NyloLoadout.TBOW, 1),
        Blowpipe(NyloLoadout.BLOWPIPE, 1),
        Ralos(NyloLoadout.RALOS, 1, 500),
        Zcb(NyloLoadout.ZCB, 1, 750),
        Scythe(NyloLoadout.SCYTHE, 1),
        Obligator(NyloLoadout.OBLIGATOR, 0),
        Dinh(NyloLoadout.DINH, 0, 500),
        Barrage(NyloLoadout.BARRAGE, 0),
        Rush(NyloLoadout.BARRAGE, 0),
        Tome(NyloLoadout.TOME, 0),
        ;

        val spec: Boolean
            get() = cost > 0
    }

    /** A line's attack: its speed (ticks to the next), style, whether it goes at a crab, and its spec cost (in %). */
    private class Atk(val speed: Int, val style: Char, val crab: Boolean = false, val spec: Int = 0)

    private inner class Bot(val player: Player, val role: NyloRole, val i: Int) {
        val dps: Int? = DPS_SLOT[role]
        val lists = LISTS.getValue(role)
        var seq: List<String> = lists[0]
        var step = 0
        var dinDone = false
        var din2Done = false
        var surged = false
        var sw50 = 0
        var dinPos: IntArray? = null
        var force: String? = null
        val tfzD = HashMap<Int, MutableSet<String>>()
        val dpsD = HashMap<Int, MutableSet<String>>()
        var order: Order? = null
        val spec: Int
            get() = player.vars["varp.sa_energy"] / 10

        val next: Int
            get() = t + max(0, player.actionDelay - clock.cycle)

        fun raw(): String = seq[min(step, seq.size - 1)]

        fun owns(weapon: String): Boolean = weapon in OWNS.getValue(role)
    }

    private class Order(val move: Move, val target: Npc, val at: Int, var walkTo: CoordGrid?, val issued: Int)

    private class Meta(val wave: Int, val idx: Int) {
        var chunk = ""
        var entered = 0
        var rushed = false
        var scyBy = -1
    }

    private class Crab(val npc: Npc, val id: String, val wave: Int, val x: Int, val y: Int, val hp: Int, val frozenTo: Int, val immuneTo: Int, val born: Int, val meta: Meta) {
        val key = "$id@$wave"
        val base = id.trimEnd('a', 'b')

        fun copy(x: Int = this.x, y: Int = this.y, hp: Int = this.hp) = Crab(npc, id, wave, x, y, hp, frozenTo, immuneTo, born, meta)
    }

    private class WavePlan(val fz: List<String>, val left: List<String>, val rush: Boolean) {
        var lord: List<String>? = null
    }

    private class DinOpt(val key: String, val pos: IntArray, val n: Int, val value: Double)

    private class Inflight(val land: Int, val mean: Double, val variance: Double, val crab: Npc?)

    private val leaks = HashSet<Npc>()
    private val crew = members.mapIndexed { i, (p, r) -> Bot(p, r, i) }
    private val mage = crew.first { it.role == NyloRole.Mage }
    private val metas = HashMap<Npc, Meta>()
    private val spawned = HashMap<Int, MutableSet<String>>()
    private val plans = HashMap<Int, WavePlan>()
    private val inflight = ArrayList<Inflight>()
    private val procAt = ArrayList<Int>()
    private var crabs: List<Crab> = emptyList()
    private var t = 0
    private var curT = 0

    private val swappedAt = HashMap<Player, Int>()
    private val thralled = HashSet<Player>()
    private val opened = HashSet<Player>()

    override fun starts(): List<CoordGrid> = crew.indices.map { START_TILES[it % START_TILES.size] }

    override fun doneAt(): Int = room.deadAt

    override fun summary(): String =
        "Maiden dead at room tick ${room.deadAt} (${raidTime(room.deadAt)}), procs at ${procAt.joinToString("/")}, crabs leaked ${room.leaked}, healed ${room.healed}, bled ${room.bled}"

    override fun prepare(bot: Player) {
        if (crew.first { it.player === bot }.role == NyloRole.Mage) return
        VarPlayerIntMapSetter.set(bot, "varbit.vengeance_rebound", 1)
        VarPlayerIntMapSetter.set(bot, "varbit.death_charge_scroll_used", 1)
        VarPlayerIntMapSetter.set(bot, "varbit.death_charge_charges", DEATH_CHARGES)
        VarPlayerIntMapSetter.set(bot, "varbit.arceuus_death_charge_active", 1)
        VarPlayerIntMapSetter.set(bot, "varbit.spellbook", ARCEUUS)
        VarPlayerIntMapSetter.set(bot, "varbit.spellbook_swap_book", 1)
    }

    /* ---- the tick */

    override fun tick() {
        if (room.deadAt >= 0) {
            if (TRACING && procAt.size < TALLIED) {
                procAt += -1
                trace { "tally ${room.tally}" }
            }
            return
        }
        t = room.t - 1
        while (procAt.size < room.phase) {
            procAt += t - 1
            onProc(procAt.size)
            trace { "t$t proc ${procAt.size}: def ${her.defenceLvl} party ${room.party().size} " + crew.joinToString { "${it.role} hp ${it.player.hitpoints} at ${room.real(it.player.coords)}" } }
        }
        snapshot()
        inflight.removeAll { it.land < t }
        for (b in crew) thrown(b)
        for (b in crew) {
            val p = b.player
            if (!p.isSlotAssigned || p.hitpoints <= 0) continue
            if (b.role != NyloRole.Mage) spells(b)
            if (dodge(b)) continue
            val o = b.order
            if (o != null) {
                if (o.target.isSlotAssigned && o.target.hitpoints > 0 && t - o.at <= ORDER_TICKS) {
                    o.walkTo?.let { dest -> if (room.real(p.coords) == dest || t - o.at >= WALK_TICKS) fire(b, o) }
                    continue
                }
                b.order = null
            }
            if (p.actionDelay > clock.cycle + if (b.dps != null) params.lookahead else 0) {
                if (params.prePosition) position(b)
                continue
            }
            decide(b)
        }
    }

    /**
     * Off her blood: a bot standing in a splat or a trail steps to the nearest clean tile (beside her if it was), and
     * its order goes on from there - unless that order's swing is due within [MaidenCrewParams.dodgeLead] ticks.
     */
    private fun dodge(b: Bot): Boolean {
        val p = b.player
        val here = room.real(p.coords)
        if (!room.bloody(here) || b.order?.walkTo != null) return false
        if (b.order != null && p.actionDelay <= clock.cycle + params.dodgeLead) return false
        val beside = room.besideHer(here)
        val to =
            (-DODGE..DODGE).flatMap { dx -> (-DODGE..DODGE).map { dz -> CoordGrid(here.x + dx, here.z + dz, here.level) } }
                .filter { it != here && room.inArena(it) && !room.under(it) && !room.bloody(it) && (!beside || room.besideHer(it)) }
                .minByOrNull { maxOf(abs(it.x - here.x), abs(it.z - here.z)) } ?: return false
        trace { "t$t ${b.role} steps off blood to ${to.x - ROOM_X},${ROOM_Y - to.z}" }
        val o = b.order
        if (o != null) {
            b.order = Order(o.move, o.target, t, to, o.issued)
        }
        bots.walk(p, room.at(to))
        return true
    }

    /**
     * Between swings a DPS walks to where the crab its next swing goes for (its own of the wave, or a straggler) will be
     * when that swing is due, so the swing doesn't wait on the run - players stand where their crab walks in; the sim
     * swings from anywhere.
     */
    private fun position(b: Bot) {
        if (b.dps == null || b.order != null) return
        val due = b.next
        if (due <= t) return
        val c = ownCrab(b, due) ?: nextCrab(b, due) ?: return
        var x = c.x
        var y = c.y
        for (k in t + 1..due) {
            if (k <= c.frozenTo || adjacent(x, y)) continue
            val sw = y + 1
            x -= 1
            if (sw < SWROW) y++ else if (sw > SWROW) y--
        }
        val here = room.real(b.player.coords)
        val me = intArrayOf(here.x - ROOM_X, ROOM_Y - here.z)
        if (maxOf(x - me[0], me[0] - (x + 1), y - me[1], me[1] - (y + 1), 0) == 1 && (me[0] in x..x + 1 || me[1] in y..y + 1)) return
        val sides = listOf(x to y - 1, x + 1 to y - 1, x to y + 2, x + 1 to y + 2, x - 1 to y, x - 1 to y + 1, x + 2 to y, x + 2 to y + 1)
        val (sx, sy) =
            sides.filter { (sx, sy) -> sx in 0..ROOM_W && sy in 0..ROOM_H && !(sx in MT_X..MX1 && sy in MT_Y..MY1) }
                .minByOrNull { (sx, sy) -> maxOf(abs(sx - me[0]), abs(sy - me[1])) } ?: return
        val dest = CoordGrid(sx + ROOM_X, ROOM_Y - sy, here.level)
        if (room.bloody(dest)) return
        trace { "t$t ${b.role} moves to $sx,$sy for ${c.key} (swing t$due)" }
        bots.walk(b.player, room.at(dest))
    }

    /**
     * The DPS's own crab of the wave (as the plan hands them out), seen on the spawn: still out, and still short of
     * her when the swing on [due] lands.
     */
    private fun ownCrab(b: Bot, due: Int): Crab? {
        val w = latestWave() ?: return null
        val plan = planOf(w)
        val done = b.dpsD[w] ?: emptySet<String>()
        val ids =
            when {
                plan.left.size == 1 -> plan.left
                w == 30 -> listOfNotNull(plan.left.getOrNull(b.dps ?: return null))
                else -> plan.left.sortedBy { x -> crabRef("$x@$w")?.let { reachOf(it) } ?: -1 }
            }
        return ids.filter { it !in done }.firstNotNullOfOrNull { id -> crabRef("$id@$w")?.takeIf { reachOf(it) > due } }
    }

    /** Whether the bot stands beside the crab (on a side, where it can swing at it). */
    private fun beside(b: Bot, c: Crab): Boolean {
        val r = room.real(b.player.coords)
        val x = r.x - ROOM_X
        val y = ROOM_Y - r.z
        val gx = maxOf(c.x - x, x - (c.x + 1), 0)
        val gy = maxOf(c.y - y, y - (c.y + 1), 0)
        return maxOf(gx, gy) == 1 && !(gx == 1 && gy == 1)
    }

    /** The crab the bot's next swing (on [due]) would go for, as things stand - a dry run of its pick. */
    private fun nextCrab(b: Bot, due: Int): Crab? {
        val snap = crabs.associate { it.meta to it.meta.scyBy }
        val dd = b.dpsD.mapValues { HashSet(it.value) }
        val keep = curT
        curT = due
        lead = due - t
        val a = dpsSwing(b, due)
        lead = 0
        curT = keep
        for ((m, v) in snap) m.scyBy = v
        b.dpsD.clear()
        b.dpsD.putAll(dd)
        return if (a.startsWith("scy")) crabRef(a.split(':')[1]) else null
    }

    /** Ticks a bot has to run before the swing being picked (a dry run ahead of it). */
    private var lead = 0

    /** Everyone on to the list for the proc that just came (a ZCB they didn't get to is kept for after their freezes). */
    private fun onProc(n: Int) {
        for (b in crew) {
            val next = b.lists[n]
            b.seq = if (n == 1) next else carry(b, next)
            b.step = 0
        }
    }

    private fun carry(b: Bot, next: List<String>): List<String> {
        val rest = b.seq.drop(b.step)
        if ("zcbs" !in rest || "zcbs" in next) return next
        val at = next.indexOfLast { it == "tfz" } + 1
        val out = next.toMutableList()
        out.addAll(at, (if ("surge" in rest && !b.surged) listOf("surge") else emptyList()) + "zcbs")
        return out
    }

    private fun snapshot() {
        val wave = WAVES.getOrElse(room.phase - 1) { WAVES[0] }
        val views = room.crabViews()
        if (TRACING) {
            for (v in views.filter { it.arrived && it.npc !in leaks }) {
                leaks += v.npc
                val r = room.real(v.npc.coords)
                trace { "t$t leak ${v.spot}@${metas[v.npc]?.wave} hp ${v.npc.hitpoints} at ${r.x - ROOM_X},${ROOM_Y - r.z - 1} frozen to ${roomTick(v.frozenUntil)}" }
            }
        }
        crabs =
            views.filter { !it.arrived }.map { v ->
                val m = metas.getOrPut(v.npc) { Meta(wave, room.rand(MAX_IDX)) }
                val r = room.real(v.npc.coords)
                val frozenTo = roomTick(v.frozenUntil)
                var x = r.x - ROOM_X
                var y = ROOM_Y - r.z - 1
                if (t > frozenTo && t >= v.born && !adjacent(x, y)) {
                    val sw = y + 1
                    x -= 1
                    if (sw < SWROW) y++ else if (sw > SWROW) y--
                }
                val chunk = "${x / CHUNK}:${Math.floorDiv(y + 1 - CHUNK_Y0, CHUNK)}"
                if (chunk != m.chunk) {
                    m.chunk = chunk
                    m.entered = t
                }
                spawned.getOrPut(m.wave) { HashSet() } += v.spot
                val immune = v.npc.vars["varn.freeze_immunity_end_clock"]
                Crab(v.npc, v.spot, m.wave, x, y, v.npc.hitpoints, frozenTo, roomTick(immune), v.born - 1, m)
            }
        if (TRACING) trace { "t$t her $hp " + crabs.joinToString(" ") { "${it.key}:${it.hp}@${it.x},${it.y}${if (it.frozenTo >= t) "f${it.frozenTo}" else ""}" } }
    }

    private fun roomTick(clockEnd: Int): Int = if (clockEnd <= 0) -1 else t + (clockEnd - clock.cycle) - 1

    /** An order went off once the bot's attack delay moves past the clock it was given on. */
    private fun thrown(b: Bot) {
        val o = b.order ?: return
        if (o.walkTo != null || b.player.actionDelay <= o.issued) return
        b.order = null
        trace { "t$t ${b.role} threw ${o.move} at ${crabs.firstOrNull { it.npc === o.target }?.key ?: "her"} (ordered t${o.at})" }
        if (b.seq === b.lists[0] && b.step >= PREFIRES) opened += b.player
        val land = t - 1 + LAND_DELAY.getOrDefault(o.move, 1)
        val (mean, sd) = MEAN_HIT[o.move] ?: (0.0 to 0.0)
        val onHer = o.target === her
        inflight += Inflight(land, if (onHer) mean else 0.0, if (onHer) sd * sd else 0.0, o.target.takeIf { !onHer })
    }

    private fun spells(b: Bot) {
        val p = b.player
        if (p in opened && p !in thralled) {
            if (p.vars["varbit.spellbook"] == ARCEUUS) {
                bots.spell(p, GREATER_GHOST)
                thralled += p
            } else if (swappedAt[p] == null) {
                bots.spell(p, SPELLBOOK_SWAP, SWAP_TO_ARCEUUS)
                swappedAt[p] = t
            }
            return
        }
        if (p.vars["varbit.vengeance_rebound"] == 0 && p.vars["varbit.vengeance_timelimit"] == 0 && p.vars["varbit.spellbook"] == LUNARS) bots.spell(p, VENGEANCE)
    }

    private val her: Npc
        get() = room.boss

    private val full: Int
        get() = her.baseHitpointsLvl

    private val hp: Int
        get() = her.hitpoints

    /** The proc coming next, as the sim counts it: 70, 50, 30, then 0. */
    private val phase: Int
        get() = NEXT_PROC[room.phase]

    private val proc70: Int?
        get() = procAt.getOrNull(0)

    private val proc50: Int?
        get() = procAt.getOrNull(1)

    private val lastProc: Int?
        get() = procAt.lastOrNull()

    private fun latestWave(): Int? = WAVES.getOrNull(room.phase - 1)

    /* ---- a bot's next attack */

    private fun decide(b: Bot) {
        val tt = b.next
        curT = tt
        val stand = if (b.role == NyloRole.Mage && b.force == null) standFor(b, tt) else null
        val a = stand ?: obligate(b, nextAct(b, tt), tt)
        trace { "t$tt ${b.role}@${room.real(b.player.coords).let { "${it.x - ROOM_X},${ROOM_Y - it.z}" }} $a${if (a.startsWith("dinhs")) " from ${b.dinPos?.joinToString(",")}" else ""}" }
        perform(b, a, tt)
    }

    /**
     * The sim's freezer stand, as it plays out there: the mage's first freeze goes on the tick after each proc. With a
     * freeze first on the next proc's list and nothing left to freeze now, the mage won't start an attack that leaves it
     * on cooldown on the tick after the hit that likely procs her (a 1 in 4 chance, from what's in flight and the
     * scythes due): it stands, or preloads a barrage on a pile worth it when that's done before the others' next hits.
     */
    private fun standFor(b: Bot, tt: Int): String? {
        if (room.phase >= WAVES.size) return null
        val cur = b.raw()
        if (cur == "tfz" && !tfzSpent(b, tt)) return null
        val speed = atkOf(cur).speed
        if (speed <= 1) return null
        val withMine = procTick(tt, true) ?: return null
        if (tt + speed <= withMine + 1 - params.procSlack) return null
        val others = crew.filter { it !== b }.minOf { it.next }
        if (tt + atkOf("tfz").speed <= others + 2 && pileWorth(tt, false).second >= params.preloadWorth) {
            b.force = "ibs:pile"
            return null
        }
        val bare = procTick(tt, false) ?: return null
        return if (tt + speed <= bare + 1 - params.procSlack) null else "wait"
    }

    /**
     * The melee's Obligator (not in the sim): a thrust pierces 3 tiles in a line, so from the right tile it takes a
     * frozen crab and what's behind it - more crabs of a pile, or her when the crab stands against her. A scythe on a
     * frozen crab goes as a thrust when its best line reaches [MaidenCrewParams.oblTargets] targets; with
     * [MaidenCrewParams.oblHer] a scythe on her goes through a frozen crab against her instead.
     */
    private fun obligate(b: Bot, a: String, tt: Int): String {
        if (!b.owns("obligator")) return a
        val k = a.substringBefore(':')
        val (c, line) =
            when (k) {
                "scyk", "scyc", "scys" -> crabRef(a.split(':')[1])?.takeIf { it.frozenTo >= tt + 1 }?.let { it to bestLine(b, it) } ?: return a
                "scythe" ->
                    crabs.filter { it.frozenTo >= tt + 1 }
                        .mapNotNull { q -> bestLine(b, q)?.takeIf { (params.oblHer && it.her && it.n >= 2) || (params.oblPileHp in 1..it.crabHp && it.n - (if (it.her) 1 else 0) >= 2) }?.let { q to it } }
                        .maxByOrNull { it.second.crabHp } ?: return a
                else -> return a
            }
        if (line == null || (k != "scythe" && line.n < params.oblTargets)) return a
        return "obl:${c.key}:${line.x},${line.y}"
    }

    private class Line(val x: Int, val y: Int, val n: Int, val her: Boolean, val crabHp: Int)

    /** The tile beside [c] whose thrust through it hits the most (crabs, and her), nearest the bot on a tie. */
    private fun bestLine(b: Bot, c: Crab): Line? {
        val here = room.real(b.player.coords)
        val me = intArrayOf(here.x - ROOM_X, ROOM_Y - here.z)
        val starts =
            listOf(
                Triple(c.x + 2, c.y, -1 to 0), Triple(c.x + 2, c.y + 1, -1 to 0), Triple(c.x - 1, c.y, 1 to 0), Triple(c.x - 1, c.y + 1, 1 to 0),
                Triple(c.x, c.y - 1, 0 to 1), Triple(c.x + 1, c.y - 1, 0 to 1), Triple(c.x, c.y + 2, 0 to -1), Triple(c.x + 1, c.y + 2, 0 to -1),
            )
        var best: Line? = null
        var bestD = 0
        for ((x, y, d) in starts) {
            if (x !in 0..ROOM_W || y !in 0..ROOM_H || (x in MT_X..MX1 && y in MT_Y..MY1)) continue
            if (crabs.any { x in it.x..it.x + 1 && y in it.y..it.y + 1 }) continue
            val tiles = (1..OBL_REACH).map { x + d.first * it to y + d.second * it }
            val hit = crabs.filter { q -> tiles.any { (tx, ty) -> tx in q.x..q.x + 1 && ty in q.y..q.y + 1 } }.take(OBL_MAX)
            val her = tiles.any { (tx, ty) -> tx in MT_X..MX1 && ty in MT_Y..MY1 } && hit.size < OBL_MAX
            val n = hit.size + if (her) 1 else 0
            val dist = maxOf(abs(x - me[0]), abs(y - me[1]))
            if (best == null || n > best.n || (n == best.n && dist < bestD)) {
                best = Line(x, y, n, her, hit.sumOf { min(it.hp, PILE_CRAB_CAP * 2) })
                bestD = dist
            }
        }
        return best
    }

    /** The tick of the first landing likely (1 in 4) to take her past her next proc; with [mine], a tbow from the mage now. */
    private fun procTick(tt: Int, mine: Boolean): Int? {
        val lands = ArrayList<Triple<Int, Double, Double>>()
        for (h in inflight) if (h.land >= tt && h.mean > 0) lands += Triple(h.land, h.mean, h.variance)
        for (d in crew) {
            if (d.dps == null || !d.owns(SCYTHE)) continue
            var f = max(d.next, tt)
            while (f + 1 <= tt + PROC_HORIZON) {
                lands += Triple(f + 1, SCYTHE_MEAN, SCYTHE_SD * SCYTHE_SD)
                f += 5
            }
        }
        if (mine) lands += Triple(tt + TBOW_LAND, TBOW_MEAN, TBOW_SD * TBOW_SD)
        val margin = full * params.standPermille / 1000.0
        var mean = 0.0
        var v = 0.0
        for ((land, m, va) in lands.sortedBy { it.first }) {
            mean += m
            v += va
            if ((hp - mean - RISK_Z * sqrt(v) - margin) * 100 < full * phase) return land
        }
        return null
    }

    private fun fallback(b: Bot, a: String): String = if (atkOf(a).style == 'm' && b.owns(SCYTHE)) "scythe" else "tbr"

    private fun nextAct(b: Bot, tt: Int): String {
        b.force?.let {
            b.force = null
            if (b.step < b.seq.size - 1) b.step++
            return it
        }
        while (true) {
            val last = b.step >= b.seq.size - 1
            var a = b.raw()
            if (a == "surge") {
                if (!b.surged && b.spec < 100) drink(b)
                if (!last) {
                    b.step++
                    continue
                }
                return if (b.owns(SCYTHE) && b.dps != null) "scythe" else "tbr"
            }
            if (a == "scythe" && b.dps != null) a = "tdps"
            if (a == "tdin" && b.dinDone) {
                if (!last) {
                    b.step++
                    continue
                }
                return dpsSwing(b, tt)
            }
            if (a == "tpile") {
                val id = if (phase == 0) "old" else "pile"
                return if (pileWorth(tt, true, id).second * 2 >= params.pileWorth) "ibs:$id" else if (phase == 0) "scythe" else "tbr"
            }
            if (a == "tfz" || a == "tdps") {
                val r = if (a == "tfz") tfzPick(b, tt) else dpsSwing(b, tt)
                if (r == "wait" || r == "tbr" || r == "ibs:old" || r?.startsWith("aya:") == true) return r
                if (r == null) {
                    if (!last) {
                        b.step++
                        continue
                    }
                    return fallback(b, a)
                }
                a = r
            }
            if (a == "tdin2") {
                if (!b.dinDone) {
                    a = "tdin"
                } else {
                    val pk = if (!b.din2Done && b.spec >= DINH_COST) dinPick(0, inflightKeys(tt)) else null
                    if (pk == null || pk.n < params.secondDinhMin) return dpsSwing(b, tt)
                    b.din2Done = true
                    b.dinPos = pk.pos
                    a = "dinhs:${pk.key}"
                }
            }
            if (a == "tdin") {
                val r = dinhTimed(b, tt)
                if (!r.startsWith("dinhs")) return r
                a = r
            }
            val atk = atkOf(a)
            val why = invalid(a, tt)
            val has = if (atk.style == 'f') b.role == NyloRole.Mage else weaponOf(a)?.let { b.owns(it) } ?: true
            val energy = atk.spec == 0 || b.spec >= atk.spec
            if (has && energy && why == null) {
                if (!last) b.step++
                if (proc50 != null && !b.dinDone && atk.style == 'm' && (a == "scythe" || a.startsWith("scyk") || a.startsWith("scyc"))) b.sw50++
                return a
            }
            if (has && !energy && why == null) {
                specFallback(b, a)?.let {
                    if (!last) b.step++
                    return it
                }
            }
            if (!last) {
                b.step++
                continue
            }
            return fallback(b, a)
        }
    }

    /**
     * The sim's value-timed Dinh's (`tdin`): after at least one scythe since the 50s, another scythe first whenever the
     * Dinh's would still be worth as much a swing later (crabs that reach her by then are lost to it), up to 30 ticks
     * after the 50s; then where it hits the most. Anything but a Dinh's is the swing that goes first.
     */
    private fun dinhTimed(b: Bot, tt: Int): String {
        if (b.sw50 < 1) {
            b.sw50 = 1
            return dpsSwing(b, tt)
        }
        val snap = crabs.associate { it.meta to it.meta.scyBy }
        val dd = b.dpsD.mapValues { HashSet(it.value) }
        val sw0 = dpsSwing(b, tt)
        val swK = if (sw0.startsWith("sc") && sw0.contains(':')) sw0.split(':')[1] else null
        val strag = HashSet<String>()
        swK?.let { strag += it }
        strag += inflightKeys(tt)
        latestWave()?.let { w -> strag += planOf(w).left.map { "$it@$w" } }
        val now = dinPick(0, strag)
        val later = dinPick(5, strag)
        val cap = proc50 != null && tt + 5 > proc50!! + params.dinhCap
        trace { "t$tt dinh now ${now?.key}:${now?.value?.toInt()}:${now?.n} later ${later?.key}:${later?.value?.toInt()}:${later?.n} skip $strag sw $sw0 crabs ${crabs.joinToString(" ") { "${it.key}:${it.hp}@${it.x},${it.y}${if (it.frozenTo >= tt) "f" else ""}" }}" }
        if (!cap && now != null && later != null && later.value * 100 >= now.value * params.dinhHoldPct) {
            b.sw50++
            return sw0
        }
        for ((m, s) in snap) m.scyBy = s
        b.dpsD.clear()
        b.dpsD.putAll(dd)
        swK?.let { strag -= it }
        val tg = dinPick(0, strag)
        b.dinDone = true
        b.dinPos = tg?.pos
        return if (tg != null) "dinhs:${tg.key}" else "scythe"
    }

    private fun specFallback(b: Bot, a: String): String? {
        if (a != "zcbs" || b.spec < DINH_COST || phase == 0 || !b.owns(DINH_WEAPON)) return null
        val pk = dinPick(0) ?: return null
        if (pk.n < params.secondDinhMin) return null
        b.dinPos = pk.pos
        return "dinhs:${pk.key}"
    }

    private fun invalid(a: String, tt: Int): String? {
        val k = a.substringBefore(':')
        val ref = a.split(':').getOrNull(1) ?: return null
        if (k == "ibs" || k == "ir") {
            val grp = ref in GROUPS
            val c = if (grp) barragePrim(ref, tt) else crabRef(ref)
            if (c == null) return "gone"
            if (grp && ref != "old" && ref != "pile" && aoe3(c).none { tt > it.immuneTo && tt > it.frozenTo }) return "nothing to freeze"
            return null
        }
        if (k == "dinhs" && ref == "maiden") return null
        if (k == "dinhs" || k.startsWith("scy")) return if (crabRef(ref) == null) "gone" else null
        return null
    }

    /* ---- the wave plan and the mage's freezes */

    private fun planOf(w: Int): WavePlan =
        plans.getOrPut(w) {
            val here = { id: String -> spawned[w]?.contains(id) == true }
            val out = listOf("S1", "N1", "S2", "N2").filter(here)
            var rushIt = false
            val fz =
                when {
                    out.size == 4 -> listOf("S1", "S2")
                    !here("S1") && !here("N1") && here("S2") -> {
                        rushIt = true
                        listOf("S2") + listOfNotNull("N2".takeIf(here))
                    }
                    else -> {
                        val ones = listOf("S1", "N1").filter(here)
                        val twos = listOf("S2", "N2").filter(here)
                        if (ones.isNotEmpty()) listOf(ones[0]) + twos.take(1) else twos
                    }
                }
            WavePlan(fz, listOf("N1", "N2", "S1", "S2").filter { it in out && it !in fz }, rushIt)
        }

    private fun ofWave(w: Int, ids: List<String>): List<Crab> = crabs.filter { it.wave == w && it.base in ids }

    private fun waveHas(w: Int, re: Regex): Boolean = spawned[w]?.any { re.matches(it) } == true

    private fun clumpReady(w: Int, which: String, tt: Int): Boolean? {
        val mine = ofWave(w, if (which == "3s") THREES else FOURS)
        if (mine.isEmpty()) return null
        if (mine.any { reachOf(it) <= tt + 1 }) return true
        val lp = lastProc ?: return false
        if (which == "3s") return if (mine.size == 1) tt >= lp + THREES_DUE else mine.all { c -> mine.all { inArea(c, it) } }
        if (ofWave(w, THREES).any { tt > it.frozenTo && tt > it.immuneTo }) return false
        val has3 = waveHas(w, THREE_RE)
        if (!has3) {
            if (w == 30) {
                val d = mage.tfzD[w] ?: return false
                return planOf(w).fz.all { it in d }
            }
            if (tt < lp + FOURS_DUE) return false
        }
        val threes = (if (has3) ofWave(w, THREES) else ofWave(w, listOf("S2"))).filter { tt <= it.frozenTo }
        return if (threes.isNotEmpty()) mine.any { c -> threes.any { inArea(it, c) } } else mine.all { it.y + 1 == SWROW } && mine.all { c -> mine.all { inArea(c, it) } }
    }

    private fun tfzSpent(b: Bot, tt: Int): Boolean {
        val w = latestWave() ?: return false
        val done = b.tfzD[w] ?: return false
        return planOf(w).fz.all { it in done } && listOf("3s", "4s").all { it in done || clumpReady(w, it, tt) == null }
    }

    private fun tfzPick(b: Bot, tt: Int): String? {
        val w = latestWave() ?: return null
        val plan = planOf(w)
        val done = b.tfzD.getOrPut(w) { HashSet() }
        for (which in listOf("3s", "4s")) {
            if (which !in done && clumpReady(w, which, tt) == true) {
                done += which
                return "ibs:$which"
            }
        }
        val no3 = !waveHas(w, THREE_RE)
        val anchorS2 = no3 && !plan.rush && waveHas(w, S2_RE) && waveHas(w, FOUR_RE)
        for ((k, id) in plan.fz.withIndex()) {
            if (id in done) continue
            val c = crabRef("$id@$w")
            if (id == "S2" && anchorS2 && c != null && abs(c.y + 1 - SWROW) > 1 && reachOf(c) > tt + 1) return "wait"
            done += id
            if (c == null || tt <= c.frozenTo) continue
            return "${if (plan.rush && k == 0) "ir" else "ibs"}:$id@$w"
        }
        if (anchorS2 && w != 30 && "S2" in done && "aya" !in done) {
            done += "aya"
            val loose = crabs.filter { tt > it.frozenTo }.maxByOrNull { it.hp }
            return "aya:${loose?.key ?: "maiden"}"
        }
        for (which in listOf("3s", "4s")) {
            if (which in done) continue
            if (clumpReady(w, which, tt) == null) {
                done += which
                continue
            }
            val lp = lastProc ?: return "wait"
            val due = lp + if (which == "3s") THREES_DUE else FOURS_DUE
            if (w == 70 && plan.fz.isEmpty() && which == "3s") {
                if ("tb" !in done) {
                    if (tt < lp + 3) return "wait"
                    done += "tb"
                    return "tbr"
                }
                if ("aya" !in done && tt + 3 <= due) {
                    done += "aya"
                    val loose = crabs.filter { tt > it.frozenTo && it.base !in THREES && it.base !in FOURS }.maxByOrNull { it.hp }
                    return "aya:${loose?.key ?: "maiden"}"
                }
                return "wait"
            }
            if (tt + 5 <= due) {
                if (w != 70) {
                    val c = barragePrim("old", tt)
                    if (c != null && aoe3(c).sumOf { it.hp } >= OLD_PILE_HP) return "ibs:old"
                }
                return "tbr"
            }
            return "wait"
        }
        return null
    }

    /* ---- the DPS */

    /**
     * The sim's trio DPS swing: the wave's 1s / 2s the mage won't freeze (two for them: both on the one reaching her
     * first, then the other), once each; then a straggler about to leak (walking, or thawing out of the pile before
     * their next swing) by jaggers_0's lines; else her.
     */
    private fun dpsSwing(b: Bot, tt: Int): String {
        val w = latestWave() ?: return "scythe"
        val dps = b.dps ?: return "scythe"
        val plan = planOf(w)
        val done = b.dpsD.getOrPut(w) { HashSet() }
        val id: String? =
            when {
                plan.left.size == 1 -> plan.left[0]
                w == 30 -> plan.left.getOrNull(dps)
                else -> {
                    val rch = { x: String -> crabRef("$x@$w")?.let { reachOf(it) } ?: -1 }
                    val lord = plan.lord ?: plan.left.sortedBy(rch).also { plan.lord = it }
                    val dl = crew.firstOrNull { d -> d !== b && d.dps != null && d.raw() in DINH_LINES && d.spec >= DINH_COST && d.sw50 >= 1 && d.next <= tt + 1 }
                    val dk = if (dl != null) dinPick(0) else null
                    lord.withIndex().firstOrNull { (k, x) -> x !in done && rch(x) >= (if (k == 0) tt + 2 else tt + 1) && (k == 0 || dk?.key != "$x@$w") }?.value
                }
            }
        if (id != null && id !in done) {
            val c = crabRef("$id@$w")?.takeIf { reachOf(it) == tt + 1 || canHit(b, it, tt) }
            if (plan.left.size == 1 && !(w == 50 && b.lists[2].any { it.startsWith("tdin") }) && c != null && reachOf(c) >= tt + 5 && !beside(b, c)) return "scythe"
            if (c != null && reachOf(c) == tt + 1 && c.hp >= blue()) {
                done += id
                return "scys:${c.key}:${if (c.hp >= magenta()) "N" else "S"}"
            }
            if (c != null && reachOf(c) > tt + 1) {
                done += id
                return "scyk:${c.key}"
            }
        }
        val mdone = mage.tfzD[w]
        val taken = inflightKeys(tt)
        val planned = { q: Crab ->
            q.wave == w && tt > q.frozenTo &&
                if (q.id in plan.fz) mdone?.contains(q.id) != true else if (q.base in THREES || q.base in FOURS) mdone?.contains(clumpOf(q)) != true else false
        }
        val dpl = crew.firstOrNull { d -> d !== b && d.dps != null && d.raw() in DINH_LINES && d.spec >= DINH_COST && d.sw50 >= 1 }
        val dpk = if (dpl != null) dinPick(0, taken + plan.left.map { "$it@$w" }) else null
        val dinCovers = { q: Crab -> dpk != null && dpk.key == q.key && dpl!!.next <= tt + 1 && reachOf(q) > dpl.next + 1 }
        val red = red()
        val blue = blue()
        val mag = magenta()
        val st0 =
            crabs.asSequence()
                .filter { q ->
                    (tt > q.frozenTo || q.frozenTo <= tt + 5) && !planned(q) && q.key !in taken && !clumpCovers(q, tt) && !dinCovers(q) && !pushPast(q) &&
                        !(q.meta.rushed && mdone?.contains("4s") != true && q.wave == w) &&
                        !(q.wave == w && q.id in plan.left && crew.none { d -> d.dpsD[w]?.contains(q.id) == true }) &&
                        !(q.meta.scyBy >= 0 && q.meta.scyBy != b.i && q.hp <= SHARED_HP)
                }
                .map { it to reachOf(it) }
                .filter { (q, r) -> r in tt + 1..tt + params.straggleTicks && (r == tt + 1 || canHit(b, q, tt)) }
                .sortedWith(compareBy<Pair<Crab, Int>> { if (it.second <= tt + 5) 0 else 1 }.thenByDescending { it.first.hp }.thenBy { it.second })
                .toList()
        for ((q, r) in st0) {
            if (r == tt + 1) {
                val side = if (q.hp >= mag) "N" else if (q.hp >= blue) "S" else continue
                if (q.meta.scyBy < 0) q.meta.scyBy = b.i
                return "scys:${q.key}:$side"
            }
            if (q.hp > red + params.redOffset) {
                if (q.meta.scyBy < 0) q.meta.scyBy = b.i
                return "scyk:${q.key}"
            }
        }
        return "scythe"
    }

    /**
     * Whether a scythe from the bot lands on the crab before it reaches her: the sim swings at once, a bot first runs to
     * it (two tiles a tick, the crab walking in meanwhile).
     */
    private fun canHit(b: Bot, q: Crab, tt: Int): Boolean {
        if (!params.walkCheck) return true
        val me = room.real(b.player.coords).let { intArrayOf(it.x - ROOM_X, ROOM_Y - it.z) }
        val reach = reachOf(q)
        var x = q.x
        var y = q.y
        for (k in 0..WALK_LIMIT) {
            if (tt + k + 1 > reach) return false
            val gap = maxOf(x - me[0], me[0] - (x + 1), y - me[1], me[1] - (y + 1), 0)
            if (gap - 1 <= 2 * (k + max(lead, tt - t))) return true
            if (tt + k >= q.frozenTo && !adjacent(x, y)) {
                val sw = y + 1
                x -= 1
                if (sw < SWROW) y++ else if (sw > SWROW) y--
            }
        }
        return false
    }

    private fun clumpOf(q: Crab): String = if (q.base in THREES) "3s" else "4s"

    /** jaggers_0's lines by her Defence: a full scythe on a crab over red; leaking as it lands, the cleave from its south over blue, north over magenta. */
    private fun red(): Int = RED_STEPS.indexOfFirst { her.defenceLvl < it }.let { if (it < 0) 21 else 26 - it }

    private fun blue(): Int = BLUE_STEPS.indexOfFirst { her.defenceLvl < it }.let { if (it < 0) 15 else 18 - it }

    private fun magenta(): Int = MAG_STEPS.indexOfFirst { her.defenceLvl < it }.let { if (it < 0) 35 else 40 - it }

    private fun pushPast(q: Crab): Boolean {
        if (phase == 0) return true
        return phase == 30 && q.frozenTo > 0 && q.hp < 50 && crabs.any { it !== q && inArea(it, q) }
    }

    private fun clumpCovers(q: Crab, tt: Int): Boolean {
        val w = latestWave() ?: return false
        if (q.wave == w) return false
        val done = mage.tfzD[w]
        val lim = reachOf(q)
        for (which in listOf("3s", "4s")) {
            if (done?.contains(which) == true) continue
            for (c in ofWave(w, if (which == "3s") THREES else FOURS)) {
                var x = c.x
                var y = c.y
                var k = maxOf(tt, c.frozenTo, c.born)
                var n = 0
                while (n < WALK_LIMIT && !adjacent(x, y)) {
                    if (abs(x - q.x) <= 1 && abs(y - q.y) <= 1 && k + 2 < lim && ((k > q.frozenTo && k > q.immuneTo) || q.hp <= 25)) return true
                    val sw = y + 1
                    x -= 1
                    if (sw < SWROW) y++ else if (sw > SWROW) y--
                    k++
                    n++
                }
            }
        }
        return false
    }

    private fun inflightKeys(tt: Int): Set<String> {
        val npcs = inflight.filter { it.land >= tt && it.crab != null }.map { it.crab } + crew.mapNotNull { it.order?.target?.takeIf { n -> n !== her } }
        return crabs.filter { it.npc in npcs }.map { it.key }.toSet()
    }

    /* ---- the melee's Dinh's */

    /**
     * The sim's Dinh's pick: on a 1/2/3 the barrages won't cleave, from the side of it whose 11x11 takes in the most crabs
     * (then the most value), or on her from her east side; a crab walking in alone counts at triple value (every point it
     * keeps heals her two), one in the pile or frozen at a fraction. [ahead]: the crabs walked on that many ticks.
     */
    private fun dinPick(ahead: Int, skip: Set<String>? = null): DinOpt? {
        val tt = curT + ahead
        val live =
            crabs.filter { !(skip != null && it.key in skip && it.hp <= 30) }.map { q0 ->
                var q = if (skip != null && q0.key in skip) q0.copy(hp = q0.hp - 30) else q0
                if (ahead > 0) {
                    var x = q.x
                    var y = q.y
                    for (k in 1..ahead) {
                        if (curT + k <= q.frozenTo) continue
                        val sw = y + 1
                        x -= 1
                        if (sw < SWROW) y++ else if (sw > SWROW) y--
                    }
                    q = q.copy(x = x, y = y)
                }
                q
            }.filter { !adjacent(it.x, it.y) }
        val w0 = latestWave()
        val md = mage.tfzD
        val fz0 = w0?.let { planOf(it).fz } ?: emptyList()
        val clumpDone = { q: Crab -> md[q.wave]?.contains("4s") == true }
        val big = { q: Crab -> q.base in THREES || q.base in FOURS }
        val toFreeze = { q: Crab ->
            q.wave == w0 && tt > q.frozenTo && ((q.id in fz0 && md[q.wave]?.contains(q.id) != true) || (big(q) && md[q.wave]?.contains(clumpOf(q)) != true))
        }
        val inPile = { q: Crab -> toFreeze(q) || big(q) || (q.meta.rushed && !clumpDone(q)) || live.any { r -> r !== q && big(r) && inArea(r, q) } }
        val weight = { q: Crab ->
            if (inPile(q)) {
                if (tt > q.frozenTo && big(q)) (if (q.wave != w0) params.walkWeight / 10.0 else W_PILE) else W_PILE
            } else if (tt > q.frozenTo) params.walkWeight / 10.0 else if (q.id == "S2") W_S2 else W_PILE
        }
        val near = { x: Int, y: Int, q: Crab -> abs(q.x - x) <= 5 && abs(q.y + 1 - y) <= 5 }
        val worth = { tgt: Crab?, x: Int, y: Int ->
            val first = if (tgt == null) SPLAT.toDouble() else weight(tgt) * min(tgt.hp, 2 * SPLAT)
            first + live.filter { it !== tgt && near(x, y, it) }.map { weight(it) * min(it.hp, SPLAT) }.sortedDescending().take(DINH_HITS - 1).sum()
        }
        val blocked = { x: Int, y: Int -> x < 0 || y < 0 || x > ROOM_W || y > ROOM_H || (x in MT_X..MX1 && y in MT_Y..MY1) }
        val under = { x: Int, y: Int -> live.any { x >= it.x && x <= it.x + 1 && y >= it.y && y <= it.y + 1 } }
        val opts = ArrayList<DinOpt>()
        for (c in live.filter { it.base in DINH_POOL && !(it.meta.rushed && !clumpDone(it)) }) {
            val sides = if (c.id == "S2") listOf("west") else if (c.id == "S1") listOf("north", "west") else listOf("north", "south", "west", "east")
            var best: DinOpt? = null
            for (sd in sides) {
                val tiles =
                    when (sd) {
                        "north" -> listOf(c.x to c.y - 1, c.x + 1 to c.y - 1)
                        "south" -> listOf(c.x to c.y + 2, c.x + 1 to c.y + 2)
                        "west" -> listOf(c.x - 1 to c.y, c.x - 1 to c.y + 1)
                        else -> listOf(c.x + 2 to c.y, c.x + 2 to c.y + 1)
                    }
                for ((x, y) in tiles) {
                    if (blocked(x, y) || under(x, y)) continue
                    val n = live.count { near(x, y, it) }
                    val sc = worth(c, x, y) + if (c.wave == w0) 0.5 else 0.0
                    if (best == null || n > best.n || (n == best.n && sc > best.value)) best = DinOpt(c.key, intArrayOf(x, y), n, sc)
                }
            }
            best?.let { opts += it }
        }
        var onHer: DinOpt? = null
        for (y in MT_Y..MY1) {
            val x = MX1 + 1
            if (blocked(x, y) || under(x, y)) continue
            val n = live.count { near(x, y, it) }
            val sc = worth(null, x, y)
            if (onHer == null || n > onHer.n || (n == onHer.n && sc > onHer.value)) onHer = DinOpt("maiden", intArrayOf(x, y), n, sc)
        }
        onHer?.let { opts += it }
        val maxN = opts.maxOfOrNull { it.n } ?: 0
        return opts.filter { it.n >= maxN - 1 }.maxByOrNull { it.value }
    }

    /* ---- barrages */

    private fun inArea(a: Crab, b: Crab): Boolean = abs(a.x - b.x) <= 1 && abs(a.y - b.y) <= 1

    private fun chunkOrder(cx: Int, cy: Int, x: Int, y: Int): Int {
        val dc = x / CHUNK - cx / CHUNK
        val dr = Math.floorDiv(y - CHUNK_Y0, CHUNK) - Math.floorDiv(cy - CHUNK_Y0, CHUNK)
        return if (abs(dc) > 1 || abs(dr) > 1) FAR_CHUNK else (1 - dc) * 3 + (dr + 1) + 1
    }

    private fun aoe3(prim: Crab): List<Crab> =
        listOf(prim) +
            crabs.filter { it !== prim && inArea(prim, it) }
                .sortedWith(compareBy<Crab> { chunkOrder(prim.x, prim.y + 1, it.x, it.y + 1) }.thenByDescending { it.meta.entered }.thenBy { it.meta.idx })
                .take(BARRAGE_CAP - 1)

    private fun barragePrim(id: String, tt: Int): Crab? {
        if (id == "old" || id == "pile") {
            val w = latestWave()
            return crabs.filter { id == "pile" || it.wave != w }.maxByOrNull { c ->
                val n = aoe3(c)
                n.count { tt > it.immuneTo && tt > it.frozenTo } * 30 + n.sumOf { it.hp }
            }
        }
        if (id == "3s" || id == "4s") {
            val w = latestWave() ?: return null
            return ofWave(w, if (id == "3s") THREES else FOURS).maxByOrNull { c ->
                val n = aoe3(c)
                n.count { tt > it.immuneTo && tt > it.frozenTo } * 100 + n.size
            }
        }
        return crabRef(id)
    }

    /**
     * The sim's `pileWorth`: what a barrage on the best pile keeps from healing her (up to 20 a crab) - with [horizon],
     * only crabs that reach her before the next proc's clump barrage could cleave them (her HP to that proc at the rate
     * it's gone since the last one, at least 22 a tick, plus 12).
     */
    private fun pileWorth(tt: Int, horizon: Boolean, id: String = "pile"): Pair<Crab?, Int> {
        val c = barragePrim(id, tt) ?: return null to 0
        val since = if (phase == 30) proc50 else if (phase == 50) proc70 else null
        val rate = if (since != null && tt - since >= 5) maxOf(PROC_RATE, (full * (phase + 20) / 100.0 - hp) / (tt - since)) else PROC_RATE
        val eta = if (phase > 0) tt + maxOf(0.0, hp - full * phase / 100.0) / rate + 12 else Double.MAX_VALUE
        return c to aoe3(c).filter { !horizon || reachOf(it) <= eta }.sumOf { min(it.hp, PILE_CRAB_CAP) }
    }

    /* ---- crabs */

    private fun crabRef(ref: String): Crab? {
        if (ref.contains('@')) return crabs.firstOrNull { it.key == ref }
        val w = WAVES.lastOrNull { spawned[it]?.contains(ref) == true } ?: return null
        return crabs.firstOrNull { it.id == ref && it.wave == w }
    }

    private fun adjacent(x: Int, y: Int): Boolean {
        val gx = maxOf(MT_X - (x + 1), x - MX1, 0)
        val gy = maxOf(MT_Y - (y + 1), y - MY1, 0)
        return maxOf(gx, gy) <= 1 && !(gx == 1 && gy == 1)
    }

    /** The tick the crab reaches her: walking a tile a tick (west, and a row toward hers) once its freeze ends. */
    private fun reachOf(c: Crab): Int {
        var x = c.x
        var y = c.y
        var n = 0
        while (!adjacent(x, y) && n < WALK_LIMIT) {
            val sw = y + 1
            x -= 1
            if (sw < SWROW) y++ else if (sw > SWROW) y--
            n++
        }
        return maxOf(t + 1, c.frozenTo + 1, c.born + 1) + n - 1
    }

    /* ---- carrying it out */

    private fun drink(b: Bot) {
        if (bots.useItem(b.player, NyloLoadout.SURGE.asRSCM(RSCMType.OBJ))) b.surged = true
    }

    private fun perform(b: Bot, a: String, tt: Int) {
        val k = a.substringBefore(':')
        val ref = a.split(':').getOrNull(1)
        val crab = ref?.let { if (k == "ibs" || k == "ir") barragePrim(it, tt) else crabRef(it) }
        when (k) {
            "wait" -> hold(b)
            "tbr" -> act(b, Move.Tbow, her)
            "bp" -> act(b, Move.Blowpipe, her)
            "ralos" -> act(b, Move.Ralos, her)
            "zcbs" -> act(b, Move.Zcb, her)
            "scyk", "scyc", "scys" -> crab?.let { act(b, Move.Scythe, it.npc) } ?: hold(b)
            "ibs" -> crab?.let { act(b, Move.Barrage, it.npc) } ?: hold(b)
            "ir" -> crab?.let {
                it.meta.rushed = true
                act(b, Move.Rush, it.npc)
            } ?: hold(b)
            "aya" -> act(b, Move.Tome, crab?.npc ?: her)
            "obl" -> {
                val (x, y) = a.split(':')[2].split(',').map { it.toInt() }
                crab?.let { act(b, Move.Obligator, it.npc, CoordGrid(x + ROOM_X, ROOM_Y - y, 0)) } ?: hold(b)
            }
            "dinhs" -> {
                val target = if (ref == "maiden") her else crab?.npc
                val here = room.real(b.player.coords)
                val pos = b.dinPos?.let { CoordGrid(it[0] + ROOM_X, ROOM_Y - it[1], 0) }?.takeIf { maxOf(abs(it.x - here.x), abs(it.z - here.z)) <= params.dinhWalk }
                b.dinPos = null
                if (target == null) hold(b) else act(b, Move.Dinh, target, pos)
            }
            else -> act(b, Move.Scythe, her)
        }
    }

    private fun hold(b: Bot) {
        b.player.clearInteractionRoute()
        b.order = null
    }

    private fun act(b: Bot, move: Move, target: Npc, walkTo: CoordGrid? = null) {
        val p = b.player
        val o = Order(move, target, t, walkTo?.takeIf { room.real(p.coords) != it }, max(clock.cycle, p.actionDelay))
        b.order = o
        val dest = o.walkTo
        if (dest == null) fire(b, o) else bots.walk(p, room.at(dest))
    }

    private fun fire(b: Bot, o: Order) {
        val p = b.player
        o.walkTo = null
        trace { "t$t ${b.role} fires ${o.move} at ${crabs.firstOrNull { it.npc === o.target }?.key ?: "her"} from ${room.real(p.coords).let { "${it.x - ROOM_X},${ROOM_Y - it.z}" }} energy ${b.spec} delay ${p.actionDelay - clock.cycle} weapon ${p.worn[3]?.id}" }
        for ((slot, obj) in o.move.set.worn) {
            val id = obj.asRSCM(RSCMType.OBJ)
            if (p.worn[slot.slot]?.id != id) bots.equip(p, slot.slot, id)
        }
        if (o.move != Move.Barrage && o.move != Move.Rush) VarPlayerIntMapSetter.set(p, "varp.com_mode", o.move.stance)
        if (o.move.spec) VarPlayerIntMapSetter.set(p, "varp.sa_attack", 1)
        when (o.move) {
            Move.Barrage -> bots.cast(p, o.target, barrage)
            Move.Rush -> bots.cast(p, o.target, rush)
            else -> if (current(p) !== o.target || o.move.spec) bots.attack(p, o.target)
        }
    }

    private fun current(p: Player): Npc? = (p.interaction as? InteractionNpc)?.target

    private fun atkOf(a: String): Atk = ATTACKS[a.substringBefore(':')] ?: ATTACKS.getValue("scythe")

    private fun weaponOf(a: String): String? = WEAPON[a.substringBefore(':')]

    private inline fun trace(text: () -> String) {
        if (TRACING) println("maiden ${crew[0].player.username} ${text()}")
    }

    private companion object {
        val TRACING = System.getenv("TOB_TRIO_TRACE") != null
        const val SPELLBOOK_SWAP = "component.magic_spellbook:spellbook_swap"
        const val SWAP_TO_ARCEUUS = 4
        const val GREATER_GHOST = "component.magic_spellbook:resurrect_greater_ghost"
        const val VENGEANCE = "component.magic_spellbook:vengeance"
        const val LUNARS = 2
        const val ARCEUUS = 3
        const val DEATH_CHARGES = 2
        const val PREFIRES = 2
        val START_TILES = listOf(CoordGrid(3184, 4445, 0), CoordGrid(3184, 4447, 0), CoordGrid(3184, 4448, 0))

        /** The sim's room: x east from 3160, y south from 4458 (a 2x2 by its north-west tile); her 6x6 at (2, 9). */
        const val ROOM_X = 3160
        const val ROOM_Y = 4458
        const val ROOM_W = 27
        const val ROOM_H = 23
        const val MT_X = 2
        const val MT_Y = 9
        const val MX1 = 7
        const val MY1 = 14
        const val SWROW = 14
        const val CHUNK = 8
        const val CHUNK_Y0 = 3
        const val FAR_CHUNK = 99
        const val MAX_IDX = 32768
        const val WALK_LIMIT = 60

        val WAVES = listOf(70, 50, 30)
        val NEXT_PROC = listOf(70, 50, 30, 0)
        val THREES = listOf("N3", "S3")
        val FOURS = listOf("N4", "S4")
        val THREE_RE = Regex("[NS]3")
        val FOUR_RE = Regex("[NS]4[ab]?")
        val S2_RE = Regex("S2")
        val GROUPS = setOf("3s", "4s", "old", "pile")
        val DINH_POOL = setOf("N1", "N2", "N3", "S1", "S2", "S3")
        val DINH_LINES = setOf("tdin", "dinhs")
        const val THREES_DUE = 11
        const val FOURS_DUE = 16
        const val OLD_PILE_HP = 100
        const val PILE_CRAB_CAP = 20
        const val PROC_RATE = 22.0
        const val BARRAGE_CAP = 9
        const val SPLAT = 21
        const val DINH_HITS = 10
        const val DINH_COST = 50
        const val SHARED_HP = 60
        const val W_PILE = 0.3
        const val W_S2 = 1.2
        const val ORDER_TICKS = 6
        const val WALK_TICKS = 3
        const val TALLIED = 4
        const val OBL_REACH = 3
        const val OBL_MAX = 3
        const val DODGE = 2
        const val RISK_Z = 0.674
        const val SCYTHE_MEAN = 45.1
        const val TBOW_MEAN = 43.5
        const val TBOW_SD = 25.0
        const val TBOW_LAND = 3
        const val PROC_HORIZON = 12
        const val SCYTHE_SD = 17.6
        const val SCYTHE = "scythe"
        const val DINH_WEAPON = "dinh"
        val RED_STEPS = listOf(13, 52, 92, 132, 173)
        val BLUE_STEPS = listOf(53, 110, 168)
        val MAG_STEPS = listOf(13, 52, 92, 133, 173)

        val DPS_SLOT = mapOf(NyloRole.Ranger to 0, NyloRole.Melee to 1)

        /** The sim's trio lists (ROLE_DEFAULT[3] as `procedureOpts(3)` plays them): the prefires, then the 70s, 50s and 30s. */
        val LISTS =
            mapOf(
                NyloRole.Mage to listOf(
                    listOf("tbr", "ralos", "bp", "tbr"),
                    listOf("tfz", "tfz", "tfz", "tfz", "tfz", "surge", "zcbs", "tbr"),
                    listOf("tfz", "tfz", "tfz", "tfz", "tfz", "tpile"),
                    listOf("tfz", "tfz", "tfz", "tfz", "tfz", "tpile", "scythe"),
                ),
                NyloRole.Ranger to listOf(
                    listOf("tbr", "ralos", "scythe"),
                    listOf("tdps"),
                    listOf("tdps", "surge", "zcbs", "tdps"),
                    listOf("tdps"),
                ),
                NyloRole.Melee to listOf(
                    listOf("tbr", "ralos", "scythe"),
                    listOf("tdps"),
                    listOf("tdin", "surge", "tdin2", "tdps"),
                    listOf("tdps", "zcbs", "tdps"),
                ),
            )

        val OWNS =
            mapOf(
                NyloRole.Mage to setOf("scythe", "tbow", "ralos", "bp", "zcb"),
                NyloRole.Ranger to setOf("scythe", "tbow", "ralos", "bp", "zcb"),
                NyloRole.Melee to setOf("scythe", "tbow", "ralos", "bp", "zcb", "dinh", "obligator"),
            )

        val WEAPON =
            mapOf(
                "tbr" to "tbow", "bp" to "bp", "ralos" to "ralos", "zcbs" to "zcb", "scythe" to "scythe", "scyk" to "scythe",
                "scyc" to "scythe", "scys" to "scythe", "tdps" to "scythe", "tdin" to "scythe", "tdin2" to "scythe", "dinhs" to "dinh",
            )

        /** Style: r ranged, m melee, f a freeze, g magic, - no attack. */
        val ATTACKS =
            mapOf(
                "tbr" to Atk(5, 'r'), "bp" to Atk(2, 'r'), "ralos" to Atk(6, 'r', spec = 50), "zcbs" to Atk(5, 'r', spec = 75),
                "scythe" to Atk(5, 'm'), "scyk" to Atk(5, 'm', true), "scyc" to Atk(5, 'm', true), "scys" to Atk(5, 'm', true),
                "tdps" to Atk(5, 'm', true), "tdin" to Atk(5, 'm'), "tdin2" to Atk(5, 'm'), "dinhs" to Atk(5, 'm', true, 50), "obl" to Atk(5, 'm', true),
                "tfz" to Atk(5, 'f', true), "ibs" to Atk(5, 'f', true), "ir" to Atk(5, 'f', true), "aya" to Atk(3, 'g', true),
                "tpile" to Atk(5, 'r'), "wait" to Atk(1, '-'), "surge" to Atk(0, '-'),
            )

        val LAND_DELAY = mapOf(Move.Tbow to 3, Move.Blowpipe to 2, Move.Zcb to 3, Move.Ralos to 3, Move.Scythe to 1, Move.Obligator to 1, Move.Dinh to 1, Move.Barrage to 2)
        val MEAN_HIT = mapOf(Move.Tbow to (TBOW_MEAN to TBOW_SD), Move.Scythe to (SCYTHE_MEAN to SCYTHE_SD), Move.Blowpipe to (16.1 to 9.8))
    }
}

/**
 * The Maiden crew's choices (see [MaidenCrew]): the defaults are the sim's, plus the server's own (walking, the
 * Obligator); `parse` reads `name=value,...` and keeps the default for the rest.
 */
data class MaidenCrewParams(
    val standPermille: Int = 15,
    val walkCheck: Boolean = true,
    val prePosition: Boolean = true,
    val lookahead: Int = 0,
    val oblTargets: Int = 9,
    val oblHer: Boolean = false,
    val oblPileHp: Int = 0,
    val pileWorth: Int = 45,
    val preloadWorth: Int = 15,
    val dinhHoldPct: Int = 97,
    val dinhCap: Int = 30,
    val secondDinhMin: Int = 4,
    val straggleTicks: Int = 6,
    val redOffset: Int = 0,
    val walkWeight: Int = 30,
    val dinhWalk: Int = 2,
    val dodgeLead: Int = 1,
    val procSlack: Int = 0,
) {
    companion object {
        fun parse(text: String): MaidenCrewParams {
            val values = text.split(',').mapNotNull { it.split('=').takeIf { kv -> kv.size == 2 }?.let { (k, v) -> k.trim() to v.trim() } }.toMap()
            val d = MaidenCrewParams()
            fun int(name: String, default: Int) = values[name]?.toDoubleOrNull()?.toInt() ?: default
            fun flag(name: String, default: Boolean) = int(name, if (default) 1 else 0) != 0
            return MaidenCrewParams(
                standPermille = int("standPermille", d.standPermille),
                walkCheck = flag("walkCheck", d.walkCheck),
                prePosition = flag("prePosition", d.prePosition),
                lookahead = int("lookahead", d.lookahead),
                oblTargets = int("oblTargets", d.oblTargets),
                oblHer = flag("oblHer", d.oblHer),
                oblPileHp = int("oblPileHp", d.oblPileHp),
                pileWorth = int("pileWorth", d.pileWorth),
                preloadWorth = int("preloadWorth", d.preloadWorth),
                dinhHoldPct = int("dinhHoldPct", d.dinhHoldPct),
                dinhCap = int("dinhCap", d.dinhCap),
                secondDinhMin = int("secondDinhMin", d.secondDinhMin),
                straggleTicks = int("straggleTicks", d.straggleTicks),
                redOffset = int("redOffset", d.redOffset),
                walkWeight = int("walkWeight", d.walkWeight),
                dinhWalk = int("dinhWalk", d.dinhWalk),
                dodgeLead = int("dodgeLead", d.dodgeLead),
                procSlack = int("procSlack", d.procSlack),
            )
        }
    }
}
