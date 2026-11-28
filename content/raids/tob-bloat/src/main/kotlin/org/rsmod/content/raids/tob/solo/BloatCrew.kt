package org.rsmod.content.raids.tob.solo

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.util.Wearpos
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.content.raids.tob.bloat.BloatRoom
import org.rsmod.content.raids.tob.bloat.BloatRules
import org.rsmod.content.raids.tob.bloat.RaidBots
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid
import java.util.PriorityQueue

/**
 * Bot teammates for Bloat, playing a [BloatProcedure] as the fastest Blert teams do (the plan and the choices are the
 * offline sim's, `tools/bloat-sim`). They go in when he walks past the gates the way the plan says, each through its
 * gate on its tick; stallers swing in salve, put a phoenix necklace on and Humidify, so his flies' hits bunch onto one
 * necklace; non-stallers keep theirs on and path round the tank out of his sight to the tile they'll swing from next.
 * Everyone steps off a tile a hand is about to land on. They act only by clicking, so the game's own combat, stalls and
 * necklaces decide what happens.
 */
class BloatCrew(
    override val room: BloatSolo,
    private val members: List<Pair<Player, BloatProcedure.Seat>>,
    private val bots: RaidBots,
    private val plan: BloatProcedure,
) : BotCrew {
    private class Bot(val player: Player, val seat: BloatProcedure.Seat) {
        var nextAttack = 0
        var lastSwing = -1
        var stallCast = -1
        var gateClicked = false
        var downSpecs: Map<Int, String> = emptyMap()
        var minHp = Int.MAX_VALUE
    }

    private val team = members.map { (p, s) -> Bot(p, s) }
    private var killedAt = -1
    private var leaderClicked = false

    private val bloat: BloatRoom
        get() = room.room

    override fun starts(): List<CoordGrid> =
        members.mapIndexed { i, (_, seat) ->
            val y = GATE_ROWS[i % GATE_ROWS.size]
            real(if (seat.gateWest) WEST_WAIT else EAST_WAIT, y)
        }

    override val walksIn: Boolean
        get() = true

    override val keepsHp: Boolean
        get() = true

    override fun prepare(bot: Player) {
        VarPlayerIntMapSetter.set(bot, "varbit.spellbook", LUNARS)
        for (rune in LUNAR_RUNES) {
            val id = rune.asRSCM(RSCMType.OBJ)
            val slot = (0 until bot.inv.size).firstOrNull { bot.inv[it]?.id == id } ?: continue
            bot.inv[slot] = org.rsmod.game.inv.InvObj(rune, RUNES)
        }
    }

    override fun doneAt(): Int = killedAt

    override fun summary(): String {
        val low = team.minOf { it.minHp }
        return "Bloat ${if (killedAt >= 0) "killed at room tick $killedAt (${raidTime(killedAt)})" else "alive"}, downs ${bloat.walks - if (bloat.mode == BloatRoom.Mode.Walk) 1 else 0}, lowest hp $low, pnecks used ${team.sumOf { pnecksUsed(it) }}"
    }

    /** Before the room starts: the first in clicks its gate as he comes to the plan's tile heading the plan's way. */
    override fun enter(): Boolean {
        if (room.t >= 0 || leaderClicked) return room.t < 0
        val (x, y) = bloat.ahead(1)
        val east = (y == BloatRules.ROOM_MIN && bloat.heading == 1) || (y == SOUTH_NORTH_MAX && bloat.heading == -1)
        if (x != plan.entryX || (y != BloatRules.ROOM_MIN && y != SOUTH_NORTH_MAX) || east != plan.east) return true
        val first = team.firstOrNull { it.seat.enter == 0 } ?: team.first()
        clickGate(first)
        leaderClicked = true
        return true
    }

    override fun tick() {
        val t = bloat.t
        if (killedAt < 0 && (!room.boss.isSlotAssigned || room.boss.hitpoints <= 0)) killedAt = room.t
        if (killedAt >= 0) return
        val (bx, by) = bloat.sw
        val down = bloat.down
        val landing = HashMap<Int, HashSet<Pair<Int, Int>>>()
        for ((at, tiles) in bloat.falling) landing.getOrPut(at) { HashSet() }.addAll(tiles)
        val stallers = team.filter { it.seat.stall && inside(it) }.map { local(it) }
        for (b in team) {
            val p = b.player
            if (!p.isSlotAssigned || p.hitpoints <= 0) continue
            b.minHp = minOf(b.minHp, p.hitpoints)
            val (x, y) = local(b)
            if (x >= GATE_EAST || x <= GATE_WEST) {
                if (t >= b.seat.enter && !b.gateClicked) clickGate(b)
                continue
            }
            if (p.isDelayed) continue
            act(b, t, x to y, bx, by, down, landing, stallers)
        }
    }

    private fun act(b: Bot, t: Int, pos: Pair<Int, Int>, bx: Int, by: Int, down: Int?, landing: Map<Int, Set<Pair<Int, Int>>>, stallers: List<Pair<Int, Int>>) {
        val seat = b.seat
        val p = b.player
        val inDown = down != null
        val k = if (inDown) DOWN_TICKS - 1 - down!! else -1
        if (inDown && k == 0) {
            val n = bloat.walks
            b.downSpecs = seat.downSpecs.filter { it.down == null || it.down == n }.associate { it.at to it.weapon }
            b.nextAttack = maxOf(b.nextAttack, t + seat.downStart[minOf(n, seat.downStart.size) - 1])
        }
        val rewalk = !inDown && bloat.walks > 1
        val next = seat.walkSwings?.takeIf { !inDown && bloat.walks == 1 }?.let { s -> s.filter { it > b.lastSwing }.minOrNull() ?: Int.MAX_VALUE }
        val ready = t >= b.nextAttack
        var weapon: String? =
            when {
                inDown -> if (ready) b.downSpecs[k] ?: SCYTHE else null
                t in seat.walkSpecs && ready -> seat.walkSpecs.getValue(t)
                ready && t >= seat.firstSwing && !(rewalk && !seat.rewalkAttack) && (next == null || next <= t) -> SCYTHE
                else -> null
            }
        if (inDown && b.downSpecs[k] != null && !ready) b.downSpecs = b.downSpecs - k + ((k + 1) to b.downSpecs.getValue(k))
        if (weapon != null && weapon != SCYTHE && energy(p) < SPEC_COST.getValue(weapon)) weapon = if (inDown || t >= seat.firstSwing) SCYTHE else null

        val hiding = (t < seat.firstSwing && !inDown) || (rewalk && !seat.rewalkAttack) || (next != null && next - t > 4 && t >= b.nextAttack - 1)
        val soon = landing.filterKeys { it in t + 1..t + 3 }.values.flatten().toSet()
        val target =
            when {
                next != null && next < Int.MAX_VALUE && !seat.stall && t >= seat.firstSwing && !inDown -> sneak(b, t, next, pos, landing, stallers)
                hiding -> hideTile(pos, soon, if (next == null) 5 else minOf(next - t, 8))
                !seat.stall && !inDown -> planTile(b, t, pos, landing, stallers, maxOf(b.nextAttack, t), "front")
                else -> goalTile(pos, bx, by, soon, if (seat.stall) null else stallers)
            }
        val moving = hiding || !seat.stall || inDown || t < seat.firstSwing || t >= b.nextAttack || pos in soon
        val swinging = weapon != null && inReach(pos, bx, by) || weapon != null && moving && inReach(step(pos, target, landing[t + 1].orEmpty(), bx, by), bx, by)
        if (swinging) {
            swing(b, weapon!!, t, inDown)
        } else {
            if (moving && target != pos) walk(b, step(pos, target, landing[t + 1].orEmpty(), bx, by))
            else if (pos in landing[t + 1].orEmpty()) walk(b, step(pos, pos, landing[t + 1].orEmpty(), bx, by))
            if (pnecks(p) > 0 && !worn(p, PNECK)) equip(p, PNECK)
        }
        if (t == b.stallCast) {
            bots.spell(p, HUMIDIFY)
            room.recordEvent(SPELL, "\"player\":{\"name\":\"${p.username}\"},\"spell\":{\"type\":\"HUMIDIFY\"}")
        }
    }

    private fun swing(b: Bot, weapon: String, t: Int, inDown: Boolean) {
        val p = b.player
        val salve = inDown || pnecks(p) == 0 || (b.seat.salve && p.hitpoints >= SAFE_SALVE_HP)
        equip(p, if (salve) SALVE else PNECK)
        equip(p, WEAPONS.getValue(weapon), Wearpos.RightHand.slot)
        if (weapon != SCYTHE) bots.spec(p)
        bots.attack(p, room.boss)
        if (TRACE) println("bloatcrew t$t ${p.username} swings $weapon down ${bloat.down} at ${local(b)}")
        room.recordEvent(ATTACK, "\"player\":{\"name\":\"${p.username}\"},\"attack\":{\"type\":\"$weapon\"}")
        b.nextAttack = t + SPEED.getValue(weapon)
        b.lastSwing = t
        if (b.seat.stall && !inDown) b.stallCast = t + 1
    }

    /* ---- where to stand (the sim's choices) */

    private fun planTile(b: Bot, t: Int, pos: Pair<Int, Int>, landing: Map<Int, Set<Pair<Int, Int>>>, stallers: List<Pair<Int, Int>>, ready: Int, side: String): Pair<Int, Int> {
        val (fbx, fby) = bloat.ahead(ready - t)
        val (ax, ay) = bloat.ahead(ready - t + 1)
        val hx = ax - fbx
        val hy = ay - fby
        val (nbx, nby) = bloat.sw
        val hands = landing.filterKeys { it in ready..ready + 1 }.values.flatten().toSet()
        val reach = 2 * (ready - t + 1)
        var best: Pair<Int, Int>? = null
        var bestCost: List<Int>? = null
        for (c in OUTER) {
            if (!inReach(c, fbx, fby) || under(c, fbx, fby)) continue
            if (maxOf(kotlin.math.abs(c.first - pos.first), kotlin.math.abs(c.second - pos.second)) > reach) continue
            val d = steps(pos, c)
            if (d > reach) continue
            val seenNow = BloatRules.rayClear(nbx + 2, nby + 2, c.first, c.second)
            val bounce = stallers.any { BloatRules.rayClear(it.first, it.second, c.first, c.second) }
            var ahead = -((c.first - fbx - 2) * hx + (c.second - fby - 2) * hy)
            if (side == "back") ahead = -ahead else if (side == "any") ahead = 0
            val cost = listOf(if (c in hands) 1 else 0, ahead, if (c in RING) 0 else 1, if (seenNow && bounce) 1 else 0, if (seenNow) 1 else 0, d)
            if (bestCost == null || compare(cost, bestCost) < 0) {
                best = c
                bestCost = cost
            }
        }
        return best ?: goalTile(pos, nbx, nby, hands, stallers)
    }

    private fun sneak(b: Bot, t: Int, swingAt: Int, pos: Pair<Int, Int>, landing: Map<Int, Set<Pair<Int, Int>>>, stallers: List<Pair<Int, Int>>): Pair<Int, Int> {
        val (bx, by) = bloat.sw
        val idx = b.seat.walkSwings?.indexOf(swingAt) ?: -1
        val side = b.seat.walkSides.getOrNull(idx) ?: "front"
        val goal = planTile(b, t, pos, landing, stallers, swingAt, side)
        val seen = OUTER.filter { BloatRules.rayClear(bx + 2, by + 2, it.first, it.second) }.toSet()
        val left = swingAt - t
        val path = route(pos, goal, bx, by, seen).let { if (it.size - 1 > 2 * (left + 1)) route(pos, goal, bx, by, emptySet()) else it }
        var wait = goal
        for ((i, c) in path.withIndex()) if (c !in seen && path.size - 1 - i <= 2 * (left - 1)) wait = c
        return wait
    }

    private fun hideTile(pos: Pair<Int, Int>, danger: Set<Pair<Int, Int>>, lead: Int): Pair<Int, Int> {
        val (fx, fy) = bloat.ahead(lead)
        val (bx, by) = bloat.sw
        val (nx, ny) = bloat.ahead(1)
        val near = OUTER.filter { maxOf(kotlin.math.abs(it.first - pos.first), kotlin.math.abs(it.second - pos.second)) <= 6 }.ifEmpty { OUTER }
        return near.minWith(
            compareBy<Pair<Int, Int>>({ if (it in danger) 1 else 0 }, { if (BloatRules.rayClear(bx + 2, by + 2, it.first, it.second) || BloatRules.rayClear(nx + 2, ny + 2, it.first, it.second)) 1 else 0 }, { if (under(it, bx, by)) 1 else 0 }, { kotlin.math.abs(it.first - fx - 2) + kotlin.math.abs(it.second - fy - 2) + if (it in RING) 0 else 2 }),
        )
    }

    private fun goalTile(pos: Pair<Int, Int>, bx: Int, by: Int, danger: Set<Pair<Int, Int>>, away: List<Pair<Int, Int>>?): Pair<Int, Int> {
        val cheb = { a: Pair<Int, Int>, c: Pair<Int, Int> -> maxOf(kotlin.math.abs(a.first - c.first), kotlin.math.abs(a.second - c.second)) }
        if (away != null && away.isNotEmpty()) {
            val cand = OUTER.filter { inReach(it, bx, by) && !under(it, bx, by) }
            val safe = cand.filter { it !in danger }.ifEmpty { cand }.ifEmpty { return pos }
            return safe.minWith(compareBy({ if (away.minOf { a -> cheb(a, it) } > 2) 0 else 1 }, { cheb(it, pos) }, { -away.minOf { a -> cheb(a, it) } }))
        }
        val cand = RING.filter { under(it, bx, by) || inReach(it, bx, by) }.ifEmpty { RING.toList() }
        val safe = cand.filter { it !in danger }.ifEmpty { cand }
        return safe.minWith(compareBy({ cheb(it, pos) }, { kotlin.math.abs(it.first - bx - 2) + kotlin.math.abs(it.second - by - 2) }))
    }

    /** Up to 2 tiles along the route, ending the tick off any tile a hand lands on next tick. */
    private fun step(pos: Pair<Int, Int>, goal: Pair<Int, Int>, landingNext: Set<Pair<Int, Int>>, bx: Int, by: Int): Pair<Int, Int> {
        val path = route(pos, goal, bx, by, OUTER.filter { BloatRules.rayClear(bx + 2, by + 2, it.first, it.second) }.toSet())
        var next = path[minOf(2, path.size - 1)]
        if (path.size > 2 && path[1] !in landingNext && next in landingNext) next = path[1]
        if (next !in landingNext) return next
        val opts = (pos.first - 2..pos.first + 2).flatMap { x -> (pos.second - 2..pos.second + 2).map { x to it } }
            .filter { (x, y) -> x in BloatRules.ROOM_MIN..BloatRules.ROOM_MAX && y in BloatRules.ROOM_MIN..BloatRules.ROOM_MAX && !BloatRules.inTank(x, y) && (x to y) !in landingNext }
        return opts.minByOrNull { maxOf(kotlin.math.abs(it.first - goal.first), kotlin.math.abs(it.second - goal.second)) } ?: next
    }

    /** Tiles from [from] to [to] round the tank (8-way, corners not cut); tiles under him or in [avoid] cost more, off the lane a little. */
    private fun route(from: Pair<Int, Int>, to: Pair<Int, Int>, bx: Int, by: Int, avoid: Set<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (from == to) return listOf(from)
        val best = HashMap<Pair<Int, Int>, Double>()
        val prev = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        val queue = PriorityQueue<Pair<Double, Pair<Int, Int>>>(compareBy { it.first })
        best[from] = 0.0
        queue += 0.0 to from
        while (queue.isNotEmpty()) {
            val (c, cur) = queue.poll()
            if (cur == to) break
            if (c > best.getValue(cur)) continue
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val n = (cur.first + dx) to (cur.second + dy)
                if (n.first !in BloatRules.ROOM_MIN..BloatRules.ROOM_MAX || n.second !in BloatRules.ROOM_MIN..BloatRules.ROOM_MAX || BloatRules.inTank(n.first, n.second)) continue
                if (dx != 0 && dy != 0 && (BloatRules.inTank(cur.first + dx, cur.second) || BloatRules.inTank(cur.first, cur.second + dy))) continue
                val w = 1.0 + (if (under(n, bx, by)) 5.0 else 0.0) + (if (n in avoid) 6.0 else 0.0) + (if (n in RING) 0.0 else 0.5)
                if (c + w < (best[n] ?: Double.MAX_VALUE)) {
                    best[n] = c + w
                    prev[n] = cur
                    queue += (c + w) to n
                }
            }
        }
        if (to !in prev) return listOf(from, to)
        val path = arrayListOf(to)
        while (path.last() != from) path += prev.getValue(path.last())
        return path.asReversed()
    }

    /** Fewest steps from [from] to [to] round the tank (8-way, corners not cut). */
    private fun steps(from: Pair<Int, Int>, to: Pair<Int, Int>): Int {
        if (from == to) return 0
        val dist = hashMapOf(from to 0)
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val n = (cur.first + dx) to (cur.second + dy)
                if (n in dist || n.first !in BloatRules.ROOM_MIN..BloatRules.ROOM_MAX || n.second !in BloatRules.ROOM_MIN..BloatRules.ROOM_MAX || BloatRules.inTank(n.first, n.second)) continue
                if (dx != 0 && dy != 0 && (BloatRules.inTank(cur.first + dx, cur.second) || BloatRules.inTank(cur.first, cur.second + dy))) continue
                dist[n] = dist.getValue(cur) + 1
                if (n == to) return dist.getValue(n)
                queue += n
            }
        }
        return Int.MAX_VALUE
    }

    /* ---- clicks and state */

    private fun clickGate(b: Bot) {
        val here = room.real(b.player.coords)
        val (tile, gate) = room.findLocs(SoloRaidScript.BARRIER).minByOrNull { (at, _) -> kotlin.math.abs(at.x - here.x) + kotlin.math.abs(at.z - here.z) } ?: return
        bots.opLoc(b.player, gate.id, room.at(tile))
        b.gateClicked = true
    }

    private fun walk(b: Bot, to: Pair<Int, Int>) = bots.walk(b.player, room.at(real(to.first, to.second)))

    private fun equip(p: Player, obj: String, slot: Int = Wearpos.Front.slot) {
        val id = obj.asRSCM(RSCMType.OBJ)
        if (p.worn[slot]?.id == id) return
        bots.equip(p, slot, id)
    }

    private fun worn(p: Player, obj: String): Boolean = p.worn[Wearpos.Front.slot]?.id == obj.asRSCM(RSCMType.OBJ)

    private fun pnecks(p: Player): Int {
        val id = PNECK.asRSCM(RSCMType.OBJ)
        return (0 until p.inv.size).count { p.inv[it]?.id == id } + if (p.worn[Wearpos.Front.slot]?.id == id) 1 else 0
    }

    private fun pnecksUsed(b: Bot): Int = (b.seat.necks - pnecks(b.player)).coerceAtLeast(0)

    private fun energy(p: Player): Int = p.vars["varp.sa_energy"] / 10

    private fun inside(b: Bot): Boolean = local(b).let { (x, y) -> BloatRules.inRoom(x, y) }

    private fun local(b: Bot): Pair<Int, Int> = room.real(b.player.coords).let { (it.x - BloatRules.ROOM_X) to (it.z - BloatRules.ROOM_Y) }

    private fun real(x: Int, y: Int): CoordGrid = CoordGrid(BloatRules.ROOM_X + x, BloatRules.ROOM_Y + y, 0)

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    companion object {
        fun under(c: Pair<Int, Int>, bx: Int, by: Int): Boolean = c.first in bx..bx + 4 && c.second in by..by + 4

        /** Melee reach: under his inner edge or beside his 5x5 (not diagonal to a corner). */
        fun inReach(c: Pair<Int, Int>, bx: Int, by: Int): Boolean {
            val (x, y) = c
            if (under(c, bx, by)) return true
            return (x in bx..bx + 4 && (y == by - 1 || y == by + 5)) || (y in by..by + 4 && (x == bx - 1 || x == bx + 5))
        }

        /** The bots' kit for Bloat (the Blert top teams': scythe, salve, pnecks, Lunars to Humidify). */
        val GEAR: List<Pair<Int, String>> =
            listOf(
                Wearpos.Hat.slot to "obj.torva_helm",
                Wearpos.Back.slot to "obj.infernal_cape",
                Wearpos.Front.slot to SALVE,
                Wearpos.RightHand.slot to SCYTHE_OBJ,
                Wearpos.Torso.slot to "obj.torva_chest",
                Wearpos.Legs.slot to "obj.torva_legs",
                Wearpos.Hands.slot to "obj.ferocious_gloves",
                Wearpos.Feet.slot to "obj.primordial_boots",
                Wearpos.Ring.slot to "obj.ultor_ring",
            )

        fun pack(seat: BloatProcedure.Seat): List<String> {
            val specs = (seat.walkSpecs.values + seat.downSpecs.map { it.weapon }).distinct().map { WEAPONS.getValue(it) }
            return specs + List(seat.necks) { PNECK } + List(VIALS) { VIAL } + LUNAR_RUNES
        }

        private val RING: Set<Pair<Int, Int>> = (12..19).flatMap { x -> (12..19).map { x to it } }.filter { !BloatRules.inTank(it.first, it.second) }.toSet()
        private val OUTER: List<Pair<Int, Int>> = (8..23).flatMap { x -> (8..23).map { x to it } }.filter { !BloatRules.inTank(it.first, it.second) }

        private const val SCYTHE = "SCYTHE"
        private const val SCYTHE_OBJ = "obj.scythe_of_vitur"
        private const val SALVE = "obj.nzone_salve_amulet_e"
        private const val PNECK = "obj.jewl_necklace_of_phoenix"
        private const val VIAL = "obj.vial_empty"
        private const val HUMIDIFY = "component.magic_spellbook:humidify"
        private val LUNAR_RUNES = listOf("obj.astralrune", "obj.waterrune", "obj.firerune")
        private val WEAPONS = mapOf(SCYTHE to SCYTHE_OBJ, "CLAW_SPEC" to "obj.dragon_claws", "BGS_SPEC" to "obj.bgs", "CHALLY_SPEC" to "obj.crystal_halberd")
        private val SPEED = mapOf(SCYTHE to 5, "CLAW_SPEC" to 4, "BGS_SPEC" to 6, "CHALLY_SPEC" to 7)
        private val SPEC_COST = mapOf("CLAW_SPEC" to 50, "BGS_SPEC" to 50, "CHALLY_SPEC" to 30)
        private const val VIALS = 6
        private const val RUNES = 1000
        private const val ATTACK = 5
        private val TRACE = System.getenv("TOB_TRIO_TRACE") != null
        private const val SPELL = 11
        private const val LUNARS = 2
        private const val DOWN_TICKS = BloatRules.DOWN_TICKS
        private const val SAFE_SALVE_HP = 35
        private const val SOUTH_NORTH_MAX = 19
        private const val GATE_EAST = 24
        private const val GATE_WEST = 7
        private const val EAST_WAIT = 25
        private const val WEST_WAIT = 6
        private val GATE_ROWS = listOf(14, 15, 16, 17)
    }
}
