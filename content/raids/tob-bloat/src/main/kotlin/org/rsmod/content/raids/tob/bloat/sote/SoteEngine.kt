package org.rsmod.content.raids.tob.bloat.sote

import org.rsmod.content.raids.tob.bloat.maiden.MaidenNumbers
import org.rsmod.content.raids.tob.bloat.maiden.SimRng
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The sim's Sotetseg (verzikSim engine/sote.js runSote) for the 5s, line for line, so the same seed gives the same
 * raid: prefires, the P1 horn and mauls, ZCBs held for the mauls (and carried past a maze), backups, late-maul swaps,
 * her orbs (prayed, or taken for veng) and their splits, her melee off the flinch, the death ball, both mazes stalled on
 * a phoenix necklace (one player under), Heal Other, thralls, spec regen and surges, and the P3 halberd last hit.
 *
 * The 5s players all cross the mazes on a phoenix necklace (two each, no sharks): the paths for shark skips, redemption
 * skips, running the maze, duos and solos aren't here. Runs a tick at a time ([tick]); [Events] says what happens.
 */
class SoteEngine(
    plans: List<SotePlan>,
    private val rng: SimRng,
    private val events: Events = object : Events {},
    private val log: MutableList<String>? = null,
) {
    interface Events {
        fun attack(t: Int, p: P, act: String, lands: Int) {}

        fun hit(t: Int, p: P, amt: Int) {}

        /** Her attack: an orb (or the death ball) at [p], or a melee. */
        fun herAttack(t: Int, p: P, kind: String) {}

        fun orbLands(t: Int, p: P, style: String, d: Int, prayed: Boolean) {}

        fun veng(t: Int, p: P, amt: Int) {}

        /** A maze procs: [under] goes under, the rest stall from [m] + 1 to [m] + 7; she's back on [back]. */
        fun maze(t: Int, n: Int, m: Int, under: P?, back: Int) {}

        fun stallOut(t: Int, p: P) {}

        fun dead(t: Int) {}
    }

    data class Spec(
        val spec: String = "none",
        val backup: Boolean = false,
        val horn: Int = 0,
        val converted: String? = null,
        val maulFor: Int? = null,
        val fromMaul: Boolean = false,
    )

    inner class P(val i: Int, plan: SotePlan) {
        val name = "Player ${i + 1}"
        val prefire = plan.prefire
        var pnecks = plan.pnecks
        var spec = plan.startSpec
        val surgePlan = plan.surge
        var regen = 0
        val specs = plan.specs.toMutableList()
        val hornDone = BooleanArray(3)
        var hornUntil = -1
        var surged = false
        var zcbCarry = -1
        var clawCarry = -1
        val vengPops = IntArray(3)
        val specDone = IntArray(3)
        val swings = IntArray(3)
        val mauled = IntArray(3)
        val backups = IntArray(3)
        val backOff = BooleanArray(3)
        val dmgSpecs = IntArray(3)
        var thrallNext: Int? = null
        var hp = 99
        var dead = false
        var veng = true
        var vengReady = 0
        var noVengUntil = 0
        var prayOffUntil = 0
        var lastMelee = -9
        var next: Int? = null
        val pending = ArrayList<IntArray>()
        var regenHold = plan.startSpec < 100
        var startRegen = false
        var specced = false
        var entry: String? = null
        var entryScythed = false
        var thrallBack = false
        var healFrom = 0
        var keptForMaze = -1
        var pendingMark: String? = null

        /** What they're holding (the last attack), for the look. */
        var holding = "scythe"
    }

    private class H(val t: Int, val p: P, val amt: Int?, val label: String, val kind: String?, val hit: Boolean = false, val bgs: Boolean = false)

    private class Orb(val t: Int, val p: P, val style: String, val split: Boolean)

    private class Ball(val at: Int, val tgt: P)

    val team = 5
    private val max = SOTE_HP
    private val marks = listOf(floor(max * 2.0 / 3).toInt(), floor(max * 1.0 / 3).toInt(), 0)
    var hp = max
        private set
    var defLvl = SOTE_DEF
        private set
    var phase = 0
        private set
    private var stopAt = marks[0]
    var blocked = false
        private set
    private var attackable = 1
    var nextAttack = FIRST_ATTACK
        private set
    var kill: Int? = null
        private set
    private var anim = DEATH_ANIM
    val mazes = ArrayList<Int>()
    val back = ArrayList<Int>()
    val ps: List<P>
    private val pid: List<P>
    private var hits = ArrayList<H>()
    private var orbs = ArrayList<Orb>()
    private var ball: Ball? = null
    private var skipAt = -1
    private var magicCount = 0
    private val maulHits = IntArray(3)
    private val swapPlanned = BooleanArray(3)
    private var pendingStall: Pair<Int, List<P>>? = null
    var t = 0
        private set

    val end: Int?
        get() = kill?.let { it + anim }

    val over: Boolean
        get() = kill != null

    init {
        ps = plans.mapIndexed { i, pl -> P(i, pl) }
        pid = ps.toMutableList().also { shuffle(it) }
        for (p in ps) {
            var after = 0
            val pf = PREFIRE[p.prefire]
            if (pf != null) {
                hits += H(pf.tick + pf.delay, p, null, "prefire ${p.prefire}", p.prefire)
                after = pf.tick + 5
                p.thrallNext = pf.tick + 1 + rng.randint(1, 2)
                events.attack(pf.tick, p, p.prefire, pf.tick + pf.delay)
            }
            var t = FIRST_ATTACK + ATTACK_EVERY
            while (t < after) t += ATTACK_EVERY
            p.next = t
            val s0 = p.specs[0]
            if (pf != null && after > 0 && after < t && (s0.backup || isDef(s0.spec))) {
                p.next = after
                p.entry = if (s0.backup) "backup" else "maul"
            } else if (pf != null && after > 0 && after < t && s0.spec == "claws") {
                p.next = after
                p.entry = "claw"
            }
            if (p.thrallNext == null) p.thrallBack = true
        }
    }

    private fun <T> shuffle(a: MutableList<T>) {
        for (i in a.size - 1 downTo 1) {
            val j = floor(rng.random() * (i + 1)).toInt()
            val x = a[i]
            a[i] = a[j]
            a[j] = x
        }
    }

    private fun <T> choice(a: List<T>): T = a[floor(rng.random() * a.size).toInt()]

    private fun L(s: String) {
        log?.add(s)
    }

    private fun alive() = ps.filter { !it.dead }

    /* ---- the numbers */

    private fun hitChance(a: Int, d: Int) = MaidenNumbers.hitChance(a, d)

    private fun defRoll(lvl: Int, style: String) = if (style == "mage") (SOTE_MAG + 9) * (30 + 64) else (lvl + 9) * ((if (style == "range") 150 else 70) + 64)

    private fun melee(weapon: String, d: Int, accMult: Double = 1.0): Pair<Double, Int> {
        val r = SoteNumbers.MELEE.getValue(weapon)
        return hitChance(floor(r.ea.toDouble() * r.ab * accMult).toInt(), defRoll(d, "melee")) to r.mx
    }

    private fun scythe(d: Int) = melee("Scythe of vitur", d)

    private class Halb(val avg: Double, val roll: () -> List<Int>)

    private fun halbSpec(d: Int): Halb {
        val r = SoteNumbers.HALB
        val ch = { m: Double -> hitChance(floor(r.ea.toDouble() * r.ab * m).toInt(), defRoll(d, "melee")) }
        val avg = { m: Double -> ch(m) * (r.mx / 2.0 + 0.5 + r.mx / 10) }
        return Halb(avg(1.0) + avg(0.75)) { listOf(ch(1.0), ch(0.75)).map { c -> if (rng.random() < c) max(1, rng.randint(0, r.mx)) + r.mx / 10 else 0 } }
    }

    private fun clawSpec(d: Int, horned: Boolean): List<Int> {
        val (ch, mx) = melee("Dragon claws", d)
        for (k in 0 until 4) {
            if (!(horned && k == 1) && rng.random() >= ch) continue
            return when (k) {
                0 -> rng.randint(mx / 2, mx - 1).let { x -> listOf(x, x / 2, x / 4, x / 4 + 1) }
                1 -> rng.randint(3 * mx / 8, 7 * mx / 8).let { x -> listOf(0, x, x / 2, x / 2 + 1) }
                2 -> rng.randint(mx / 4, 3 * mx / 4).let { x -> listOf(0, 0, x, x + 1) }
                else -> listOf(0, 0, 0, rng.randint(mx / 4, 5 * mx / 4))
            }
        }
        return if (rng.random() < 2.0 / 3) listOf(1, 1, 0, 0) else listOf(0, 0, 0, 0)
    }

    /** A prefire's (or ZCB spec's) chance and max against her Defence now. */
    private fun prefireNumbers(kind: String, d: Int): Pair<Double, Int> {
        val pf = PREFIRE.getValue(kind)
        val r = SoteNumbers.RANGED.getValue(pf.weapon)
        return hitChance(if (pf.spec > 0) r.roll * 2 else r.roll, defRoll(d, "range")) to r.mx
    }

    private fun playerDef(style: String) = SoteNumbers.PDEF.getValue(style)

    /* ---- the rules */

    private fun instanceOf(t: Int) = (t + 1) % 4

    private fun isHerTick(x: Int) = x >= nextAttack && (x - nextAttack) % ATTACK_EVERY == 0

    private fun spend(p: P, n: Int, t: Int) {
        p.spec -= n
        if (p.regenHold) {
            p.regenHold = false
            p.regen = 49
            p.startRegen = true
        }
        p.specced = true
        if (p.surgePlan && !p.surged) {
            val o = p.spec
            p.spec = min(100, p.spec + SURGE)
            p.surged = true
            L("t$t ${p.name} surge $o% -> ${p.spec}%")
        }
    }

    private fun stallSkip(hp: Int, hasPneck: Boolean, runners: Int): Pair<Int, Boolean?> {
        val q = ArrayList<Int>()
        q += mazeDmg(hp)
        repeat(max(0, runners - 1)) { q += splashDmg(hp) }
        repeat(STALL_TICKS - 1) { q += mazeDmg(hp) }
        var cur = hp
        for (d in q) {
            cur -= d
            if (cur <= 0) return cur to null
            if (hasPneck && cur <= PNECK_AT) return cur + PNECK_HEAL to true
        }
        return cur to false
    }

    private fun stallDead(h: Int, p: P, runners: Int) = stallSkip(h, p.pnecks > 0, runners).second == null

    private fun startMaze(t: Int) {
        mazes += t
        L("t$t *** MAZE ${mazes.size} (${if (mazes.size == 1) "66.6" else "33.3"}%) ***")
        blocked = true
        hits = ArrayList()
        orbs = ArrayList()
        ball = null
        skipAt = -1
        for (p in ps) {
            p.pending.clear()
            p.thrallNext = null
        }
        defLvl = SOTE_DEF
        var m = t + 5
        while (instanceOf(m) != 0) m++
        attackable = m + 9
        nextAttack = m + 10
        back += attackable
        val live = alive()
        val under = if (live.size > 1) choice(live) else null
        val stallers = live.filter { it !== under }
        for (p in stallers) {
            p.noVengUntil = m + 8
            p.next = m + 10
            p.thrallBack = true
        }
        val uFirst = m + 10 + UNDER_AFTER[instanceOf(t)]
        if (under != null) {
            under.next = uFirst
            under.healFrom = uFirst + 1
            under.thrallBack = true
            L("t$t ${under.name} goes under (first swing t$uFirst); runners stall t${m + 1}-${m + 7}, off t${m + 8}")
        }
        pendingStall = (m + 8) to stallers
        events.maze(t, mazes.size, m, under, attackable)
    }

    private fun damage(t: Int, amt: Int, label: String) {
        if (blocked || kill != null || amt <= 0) return
        val real = min(amt, hp - stopAt)
        hp -= real
        L("t$t $label $amt${if (real < amt) " (stops at $real)" else ""} -> Sotetseg $hp")
        if (hp > stopAt) return
        if (phase < 2) {
            closePhase(phase)
            phase++
            stopAt = marks[phase]
            startMaze(t)
            return
        }
        kill = t
        closePhase(2)
        L("t$t *** SOTETSEG DIES ***")
        events.dead(t)
    }

    private fun vengHit(t: Int, p: P, dmg: Int, src: String) {
        if (!p.veng || dmg <= 0) return
        p.veng = false
        p.vengPops[mazes.size]++
        events.veng(t, p, floor(dmg * 0.75).toInt())
        damage(t, floor(dmg * 0.75).toInt(), "${p.name} veng ($src $dmg)")
    }

    private fun hurt(t: Int, p: P, d: Int) {
        p.hp -= d
        if (p.hp <= 0) {
            p.dead = true
            L("t$t ${p.name} dies")
        }
    }

    private class PrayPlan(val ticks: Set<Int>, val unprayed: Int)

    private fun prayPlan(p: P, from: Int, offUntil: Int): PrayPlan {
        val xs = orbs.filter { it.p === p && it.t >= from }.map { it.t }.sorted()
        val ticks = HashSet<Int>()
        var unprayed = 0
        var off = offUntil
        for (x in xs) {
            if (x >= off && x !in ticks) ticks += x
            else {
                unprayed++
                off = max(off, x + PRAYER_OFF + 1)
            }
        }
        return PrayPlan(ticks, unprayed)
    }

    private fun specHitPending(q: P) = hits.any { it.p === q && (it.kind == "maul" || it.kind == "zcb" || it.kind == "claw") }

    private fun anyMaulPending() = hits.any { it.kind == "maul" }

    private fun isDef(x: String) = x == "maul" || x == "bgs"

    private fun plannedMaul(q: P, ph: Int) = isDef(q.specs[ph].spec) && !q.specs[ph].backup

    private fun ticksTo50(q: P): Int {
        if (q.spec >= 50) return 0
        val per = 50
        return max(1, per - q.regen) + (ceil((50 - q.spec) / 10.0).toInt() - 1) * per
    }

    private fun maulInTime(q: P, ph: Int, t: Int): Boolean {
        if (q.spec >= 50) return true
        val done = q.swings[ph]
        if (done >= 3) return false
        return t + ticksTo50(q) <= max(q.next ?: t, t) + 5 * (2 - done)
    }

    private fun defAct(want: String): String = if (want == "bgs") "bgs" else "maul"

    private fun dpsPlanned(q: P, ph: Int) = (q.specs[ph].spec == "zcb" || q.specs[ph].spec == "claws") && !q.specs[ph].backup && q.specDone[ph] == 0

    private fun takeMaul(q: P, ph: Int, t: Int, why: String) {
        q.specs[ph] = q.specs[ph].copy(spec = "maul", converted = q.specs[ph].spec)
        L("t$t ${q.name} mauls in P${ph + 1} instead of their ${if (q.specs[ph].converted == "zcb") "ZCB" else "claws"} ($why)")
    }

    private fun planSwaps(ph: Int, t: Int) {
        for (m in alive()) {
            if (!plannedMaul(m, ph) || m.mauled[ph] > 0 || maulInTime(m, ph, t)) continue
            val d = alive().firstOrNull { it !== m && dpsPlanned(it, ph) && it.spec >= 50 && it.specs[ph].converted == null } ?: continue
            takeMaul(d, ph, t, "${m.name}'s maul would come after their 3rd swing")
            m.specs[ph] = m.specs[ph].copy(spec = "claws", fromMaul = true)
            d.specs[ph] = d.specs[ph].copy(maulFor = m.i)
        }
    }

    private fun swapMissed(d: P, ph: Int, t: Int) {
        val m = ps.firstOrNull { it.i == d.specs[ph].maulFor }
        if (m != null && !m.dead && m.specs[ph].fromMaul && m.specDone[ph] == 0 && maulInTime(m, ph, t)) {
            m.specs[ph] = m.specs[ph].copy(spec = "maul", fromMaul = false)
            L("t$t ${d.name}'s maul missed: ${m.name} mauls after all (in time for their 3rd swing)")
            return
        }
        val d2 = alive().firstOrNull { it !== d && dpsPlanned(it, ph) && it.spec >= 50 && it.specs[ph].converted == null }
        if (d2 != null) {
            takeMaul(d2, ph, t, "${d.name}'s maul missed")
            d2.specs[ph] = d2.specs[ph].copy(maulFor = m?.i)
        }
    }

    private fun hornRecipients(c: P, ph: Int): List<P> {
        val others = alive().filter { it !== c }
        val m = others.filter { plannedMaul(it, ph) }
        val zcbNow = { q: P -> if (q.specs[ph].spec == "zcb" && !q.specs[ph].backup) 1 else 0 }
        val rest = others.filter { it !in m }.sortedBy(zcbNow)
        return (m + rest).take(min(c.specs[ph].horn, c.spec / HORN_COST))
    }

    private fun castHorn(c: P, t: Int, ph: Int) {
        val recs = hornRecipients(c, ph)
        val cost = HORN_COST * max(1, recs.size)
        L("t$t ${c.name} Soulflame horn (${c.spec - cost}% left): ${(listOf(c) + recs).joinToString { it.name }} get the buff on their next melee hit (to t${t + HORN_TICKS})")
        spend(c, cost, t)
        c.hornDone[ph] = true
        c.hornUntil = t + HORN_TICKS
        for (q in recs) q.hornUntil = t + HORN_TICKS
        events.attack(t, c, "horn", t)
    }

    private fun hornWait(p: P, ph: Int) = ph > 0 && alive().any { c -> c.specs[ph].horn > 0 && !c.hornDone[ph] && c.spec >= HORN_COST && (c === p || hornRecipients(c, ph).contains(p)) }

    private fun scytheAvg(q: P): Double {
        val (ch, mx) = scythe(defLvl)
        return listOf(mx, mx / 2, mx / 4).fold(0.0) { n, m -> n + ch * (m / 2.0 + 0.5) }
    }

    private fun halbReady(q: P) = q.spec >= 30 && !(q.spec >= 50) && q.zcbCarry != 2 && q.clawCarry != 2 && ((q.specs[2].spec == "none" && !q.specs[2].backup) || q.specDone[2] > 0)

    private fun chooseAct(p: P, t: Int, ph: Int): String {
        var a = chooseAct0(p, t, ph)
        if (a == "scythe" && ph == 2 && halbReady(p)) {
            val next = alive().fold(0.0) { n, q -> n + (if (q === p) halbSpec(defLvl).avg else if (hits.any { it.p === q }) scytheAvg(q) else if (halbReady(q)) halbSpec(defLvl).avg else scytheAvg(q)) }
            if (next >= hp - stopAt) a = "halb"
        }
        p.pendingMark = null
        return a
    }

    private fun chooseAct0(p: P, t: Int, ph: Int): String {
        var sp = p.specs[ph]
        val floorNow = defLvl <= SOTE_DEF_FLOOR
        val lowEnough = !floorNow && defLvl <= BACKUP_DEF
        val enough = floorNow || lowEnough
        if (ph == 2 && sp.spec == "zcb" && hp < CLAW_BELOW) sp = sp.copy(spec = "claws")
        val mauls = { q: P -> plannedMaul(q, ph) && q.mauled[ph] == 0 && maulInTime(q, ph, t) }
        val zcbWasted = ph < 2 && hp - stopAt < min(110, floor(hp * 0.22).toInt()) + ZCB_MARGIN
        if (p.zcbCarry == ph && !alive().any(mauls) && !anyMaulPending() && !zcbWasted) {
            val backupFirst = sp.backup && p.specDone[ph] == 0 && !enough
            if (!backupFirst) {
                if (p.spec < ZCB_SPEC) return "scythe"
                p.zcbCarry = -1
                return "zcbCarry"
            }
        }
        if (p.clawCarry == ph && !alive().any(mauls) && !anyMaulPending()) {
            val backupFirst = sp.backup && p.specDone[ph] == 0 && !enough
            if (!backupFirst) {
                if (p.spec < 50) return "scythe"
                p.clawCarry = -1
                return "clawCarry"
            }
        }
        val spare = spare@{
            if (ph != 2 || p.zcbCarry == 2 || p.clawCarry == 2 || p.spec < 50 || alive().any(mauls) || anyMaulPending() || hornWait(p, ph)) return@spare "scythe"
            "claw"
        }
        if (!sp.backup) {
            if (isDef(sp.spec) && p.mauled[ph] == 0) return if (p.spec >= 50 && !hornWait(p, ph)) defAct(sp.spec) else "scythe"
            if (p.specDone[ph] > 0 || sp.spec == "none") return spare()
            if (sp.spec == "zcb") {
                if (alive().any(mauls) || anyMaulPending() || p.spec < ZCB_SPEC) return "scythe"
                if (zcbWasted) {
                    p.specDone[ph]++
                    p.zcbCarry = ph + 1
                    L("t$t ${p.name} keeps their ZCB spec for the start of P${ph + 2} (P${ph + 1} too close to the maze)")
                    return "scythe"
                }
                return "zcb"
            }
            if (sp.spec == "claws") return if ((p.entry == "claw" && ph == 0 || (!alive().any(mauls) && !anyMaulPending())) && p.spec >= 50) "claw" else "scythe"
            return "scythe"
        }
        if (p.swings[ph] == 0 || alive().any(mauls) || anyMaulPending()) return "scythe"
        val lim = 1
        if (!enough && p.backups[ph] < lim && !p.backOff[ph]) {
            if (specHitPending(p)) return "scythe"
            val open = { q: P -> q.specs[ph].backup && !q.backOff[ph] && q.backups[ph] < 1 }
            if (alive().any { q -> q !== p && q.specs[ph].backup && (specHitPending(q) || (open(q) && (q.backups[ph] < p.backups[ph] || (q.backups[ph] == p.backups[ph] && q.i < p.i)))) }) return "scythe"
            val a = defAct(if (sp.spec == "bgs") "bgs" else "maul")
            if (p.spec >= 50) {
                p.backups[ph]++
                return a
            }
            p.backOff[ph] = true
            p.specDone[ph]++
            return "scythe"
        }
        if (ph == 2 && sp.spec == "zcb" && (p.backups[ph] > 0 || !enough) && p.dmgSpecs[ph] == 0 && p.spec >= ZCB_SPEC) return "zcb"
        if (p.specDone[ph] > 0 || !enough) {
            if (p.specDone[ph] == 0) p.specDone[ph]++
            return spare()
        }
        if (sp.spec == "zcb") {
            if (zcbWasted) {
                p.specDone[ph]++
                return "scythe"
            }
            return if (p.spec >= ZCB_SPEC) "zcb" else "scythe"
        }
        if (sp.spec == "claws") return if (p.spec >= 50) "claw" else "scythe"
        p.specDone[ph]++
        return spare()
    }

    private fun meleeable(p: P, t: Int) = p.pending.isNotEmpty() || (p.lastMelee == t - 1 && isHerTick(t)) || (p.next == t && isHerTick(t + 1))

    private fun lowerDef(t: Int, what: String, by: Int) {
        if (defLvl <= SOTE_DEF_FLOOR) return
        defLvl = max(SOTE_DEF_FLOOR, defLvl - by)
        L("t$t $what lowers her Defence -> $defLvl")
    }

    private fun closePhase(ph: Int) {
        for (q in alive()) {
            val sp = q.specs[ph]
            if (sp.spec == "zcb" && !sp.backup && q.specDone[ph] == 0 && ph < 2) {
                q.zcbCarry = ph + 1
                L("${q.name}'s P${ph + 1} ZCB spec moves to the start of P${ph + 2}")
                continue
            }
            if (sp.spec == "claws" && !sp.backup && q.specDone[ph] == 0 && ph < 2) {
                q.clawCarry = ph + 1
                L("${q.name}'s P${ph + 1} claws spec moves to the start of P${ph + 2}")
            }
        }
    }

    /* ---- the tick */

    fun tick() {
        if (kill != null) return
        t++
        val t = t
        if (blocked && t >= attackable) blocked = false
        pendingStall?.let { (at, runners) ->
            if (t != at) return@let
            for (p in runners.filter { !it.dead }) {
                val nRun = runners.count { !it.dead }
                val before = p.hp
                val (h, pneck) = stallSkip(p.hp, p.pnecks > 0, nRun)
                p.hp = h
                if (pneck == true) p.pnecks--
                if (pneck == null) p.dead = true
                L("t$t ${p.name} out of the stall: $before -> ${if (pneck == null) "DEAD" else h}${if (pneck == true) " (phoenix necklace)" else ""}")
                events.stallOut(t, p)
            }
            pendingStall = null
        }
        if (t == HORN_TICK) for (c in alive()) if (c.specs[0].horn > 0 && c.spec >= HORN_COST) castHorn(c, t, 0)
        if (phase > 0 && !blocked) for (c in alive()) if (c.specs[phase].horn > 0 && !c.hornDone[phase] && c.spec >= HORN_COST && c.swings[phase] >= 1) castHorn(c, t, phase)
        for (p in alive()) {
            if (t < p.vengReady || t < p.noVengUntil) continue
            if (!p.veng) {
                p.veng = true
                p.vengReady = t + VENG_CD
                L("t$t ${p.name} casts veng")
                continue
            }
            val other = alive().firstOrNull { q -> q !== p && !q.veng && t < q.vengReady && t >= q.noVengUntil }
            if (other != null) {
                other.veng = true
                p.vengReady = t + VENG_CD
                L("t$t ${p.name} casts veng on ${other.name}")
            }
        }
        if (!blocked) healOthers(t)
        landHits(t)
        landOrbs(t)
        val b = ball
        if (b != null && t == b.at && kill == null) landBall(t, b)
        if (!blocked && t == nextAttack && alive().isNotEmpty() && t == skipAt) {
            nextAttack += ATTACK_EVERY
            L("t$t SOTETSEG skips her attack (death ball)")
        } else if (!blocked && t == nextAttack && alive().isNotEmpty()) herAttack(t)
        for (p in alive()) {
            if (p.surgePlan && !p.surged && p.specced && p.spec <= 75) {
                val o = p.spec
                p.spec = min(100, p.spec + SURGE)
                p.surged = true
                L("t$t ${p.name} surge (cooldown up) $o% -> ${p.spec}%")
            }
            if (!p.regenHold) {
                if (p.spec < 100) {
                    if (++p.regen >= 50) {
                        val own = p.startRegen
                        p.startRegen = false
                        val o0 = p.spec
                        p.regen = 0
                        p.spec = min(100, p.spec + 10)
                        if (own) L("t$t ${p.name} spec regen $o0% -> ${p.spec}% (95% start - not a Lightbearer cycle)")
                    }
                } else p.regen = 0
            }
            if (!blocked && p.thrallNext == t) {
                hits += H(t + 1, p, rng.randint(0, 3), "thrall", "thrall")
                p.thrallNext = t + 4
            }
        }
        if (!blocked && !swapPlanned[phase]) {
            swapPlanned[phase] = true
            planSwaps(phase, t)
        }
        for (p in pid.filter { !it.dead }) {
            for (x in p.pending.filter { it[0] == t }) if (x[1] > 0) {
                hurt(t, p, x[1])
                vengHit(t, p, x[1], "melee")
            }
            p.pending.removeAll { it[0] == t }
            if (p.dead || t != p.next) continue
            if (p.thrallBack) {
                p.thrallBack = false
                p.thrallNext = t + rng.randint(1, 2)
            }
            act(p, t)
        }
    }

    private fun healOthers(t: Int) {
        val after2 = mazes.size >= 2
        val targeted = orbs.filter { it.t >= t }.map { it.p }.toSet()
        val runnersNext = alive().size - 1
        for (c in alive()) {
            if (t < c.healFrom) continue
            val vengSafe = c.veng && c.hp > ORB_MAX
            if (c.keptForMaze == mazes.size) continue
            val cUnprayed = prayPlan(c, t, c.prayOffUntil).unprayed
            val pairNeed = { q: P -> ORB_MAX * prayPlan(q, t, q.prayOffUntil).unprayed }
            val mazeDanger = { q: P, h: Int -> stallDead(h, q, runnersNext) }
            val cands = alive().filter { q -> q !== c && ((q.veng && q.hp <= ORB_MAX && q in targeted) || (!after2 && mazeDanger(q, q.hp)) || q.hp <= pairNeed(q)) }.sortedBy { it.hp }
            for (q in cands) {
                val give = min(floor(c.hp * 0.75).toInt(), 99 - q.hp)
                val left = c.hp - give
                if (give <= 0 || left <= 0) continue
                val vengGain = q.veng && q.hp <= ORB_MAX && q in targeted && q.hp + give > ORB_MAX
                val mazeGain = !after2 && mazeDanger(q, q.hp) && !mazeDanger(q, q.hp + give)
                val pairGain = q.hp <= pairNeed(q) && q.hp + give > pairNeed(q)
                if (!vengGain && !mazeGain && !pairGain) continue
                if (vengSafe) continue
                if (cUnprayed > 0 && left <= ORB_MAX * cUnprayed) continue
                if (!after2 && (left < 17 || mazeDanger(c, left))) continue
                c.hp = left
                q.hp += give
                if (mazeGain) q.keptForMaze = mazes.size
                L("t$t ${c.name} heals other ${q.name} (+$give): ${c.name} ${c.hp}, ${q.name} ${q.hp}")
                break
            }
        }
    }

    private fun landHits(t: Int) {
        val landing = hits.filter { it.t == t }
        hits = ArrayList(hits.filter { it.t != t })
        for (h in landing) {
            if (kill != null) {
                if (kill == t) {
                    anim = DEATH_ANIM_OVERKILL
                    L("t$t ${h.p.name} ${h.label} lands after the kill (overkill)")
                }
                break
            }
            val p = h.p
            val kind = h.kind
            val before = hp
            if (kind != null && PREFIRE.containsKey(kind)) {
                val (ch, mx) = prefireNumbers(kind, defLvl)
                val pf = PREFIRE.getValue(kind)
                if (rng.random() < ch) {
                    var d = max(1, rng.randint(0, mx))
                    if (pf.weapon == "Zaryte crossbow" && (pf.spec > 0 || rng.random() < 0.066)) {
                        val r = min(110, floor(hp * 0.22).toInt())
                        p.hp -= p.hp / 10
                        damage(t, r, "${p.name} ${h.label} ruby")
                        d = if (pf.spec > 0) 0 else d
                    }
                    damage(t, d, "${p.name} ${h.label}")
                } else L("t$t ${p.name} ${h.label} misses")
            } else if (kind == "zcb") {
                val (ch) = prefireNumbers("ZCB spec", defLvl)
                if (rng.random() < ch) {
                    val r = min(110, floor(hp * 0.22).toInt())
                    p.hp -= p.hp / 10
                    damage(t, r, "${p.name} ZCB spec ruby")
                } else L("t$t ${p.name} ZCB spec misses")
            } else if (kind == "maul") {
                if (h.hit) maulHits[phase]++
                else if (p.specs[phase].converted != null && p.specs[phase].maulFor != null) swapMissed(p, phase, t)
                damage(t, h.amt ?: 0, "${p.name} ${h.label}")
            } else damage(t, h.amt ?: 0, "${p.name} ${h.label}")
            events.hit(t, p, before - hp)
        }
    }

    private fun landOrbs(t: Int) {
        val now = if (kill != null) emptyList() else orbs.filter { it.t == t }
        val mz0 = mazes.size
        for (p in alive()) {
            if (mazes.size != mz0) break
            val mine = now.filter { it.p === p }
            if (mine.isEmpty()) continue
            val pp = prayPlan(p, t, p.prayOffUntil)
            val pairAhead = prayPlan(p, t + 1, t + PRAYER_OFF + 1).unprayed > 0
            val off = t !in pp.ticks || (p.veng && mine.size == 1 && (p.hp > ORB_MAX && !pairAhead))
            val prayStyle = if (off) null else mine.last().style
            var hitHere = false
            for (o in mine) {
                if (p.dead) break
                val prayed = !off && t >= p.prayOffUntil && o.style == prayStyle
                var d = 0
                if (!prayed) {
                    hitHere = true
                    if (rng.random() < hitChance((SOTE_MAG + 9) * (-10 + 64), playerDef(o.style))) d = rng.randint(0, ORB_MAX)
                }
                L("t$t ${if (o.split) "split " else ""}${o.style} orb -> ${p.name}: ${if (prayed) "prayed" else "$d${if (off) (if (p.veng) " (off prayer, veng)" else " (off prayer)") else ""}"} (HP ${p.hp - d})")
                events.orbLands(t, p, o.style, d, prayed)
                if (!prayed && d > 0) {
                    hurt(t, p, d)
                    vengHit(t, p, d, "orb")
                }
            }
            if (hitHere) p.prayOffUntil = t + PRAYER_OFF + 1
            if (mazes.size != mz0) break
            for (o in mine.filter { !it.split }) {
                val others = alive().filter { it !== p }.toMutableList().also { shuffle(it) }.take(2)
                val styles = if (rng.random() < 0.5) listOf("range", "mage") else listOf("mage", "range")
                others.forEachIndexed { j, q -> orbs += Orb(t + ORB_DELAY, q, styles[j], true) }
                if (others.isNotEmpty()) L("t$t orb splits -> ${others.withIndex().joinToString { (j, q) -> "${q.name} (${styles[j]})" }}, landing t${t + ORB_DELAY}")
            }
        }
        orbs = ArrayList(orbs.filter { it.t != t })
    }

    private fun landBall(t: Int, b: Ball) {
        val live = alive()
        val n = live.size
        val tb = BALL[min(5, n)] ?: BALL.getValue(1)
        val share = tb[n] ?: tb.getValue(1)
        val tgt = if (b.tgt.dead) null else b.tgt
        ball = null
        if (n > 1 && live.all { it.hp > share }) {
            for (q in live) q.hp -= share
            L("t$t death ball shared by $n: $share each")
            return
        }
        if (tgt == null) return
        val full = tb.getValue(1)
        val c = live.filter { it !== tgt && it.hp > 1 && !meleeable(it, t) }.minByOrNull { it.hp }
        val give = if (c != null) min(floor(c.hp * 0.75).toInt(), 99) else 0
        if (give > 0 && c != null) {
            val old = tgt.hp
            c.hp -= give
            tgt.hp = give
            if (tgt.next!! < t + 3) {
                tgt.next = t + 3
                if (isHerTick(tgt.next!! + 1)) tgt.next = tgt.next!! + 1
            }
            L("t$t death ball on ${tgt.name} alone ($full): tick-healed (stall) - $old -> 0, ${c.name} heals other +$give (${c.name} ${c.hp})")
        } else {
            hurt(t, tgt, full)
            L("t$t death ball on ${tgt.name} alone ($full), no food and nobody to heal them")
        }
    }

    private fun herAttack(t: Int) {
        nextAttack += ATTACK_EVERY
        val tgt = choice(alive())
        if (magicCount >= BALL_EVERY) {
            magicCount = 0
            ball = Ball(t + BALL_LANDS, tgt)
            skipAt = t + ATTACK_EVERY
            L("t$t SOTETSEG death ball -> ${tgt.name}, lands t${t + BALL_LANDS}")
            events.herAttack(t, tgt, "ball")
        } else if (tgt.lastMelee == t - 1 && rng.random() < 0.5) {
            val ch = hitChance((SOTE_ATK + 9) * (0 + 64), playerDef("crush"))
            val d = if (rng.random() < ch) rng.randint(0, floor(MELEE_MAX * 0.6).toInt()) else 0
            tgt.pending += intArrayOf(t + 1, d)
            L("t$t SOTETSEG melees ${tgt.name} (lands t${t + 1}: $d)")
            events.herAttack(t, tgt, "melee")
        } else {
            orbs += Orb(t + ORB_DELAY, tgt, "mage", false)
            magicCount++
            L("t$t SOTETSEG magic orb -> ${tgt.name}, lands t${t + ORB_DELAY}")
            events.herAttack(t, tgt, "orb")
        }
    }

    private fun act(p: P, t: Int) {
        val ph = phase
        val act = chooseAct(p, t, ph)
        if (p.entry != null && ph == 0) {
            val drop = act == "scythe" && isHerTick(t + 1) && (p.entry == "maul" || p.entry == "claw" || p.entryScythed)
            if (drop || act != "scythe") p.entry = null
            if (drop) {
                p.next = t + 1
                L("t$t ${p.name} drops a tick (no maul now - back on her attack ticks)")
                return
            }
        }
        if (p.entry == null && act == "scythe" && isHerTick(t + 1)) {
            p.next = t + 1
            L("t$t ${p.name} drops a tick (back on her attack ticks)")
            return
        }
        p.swings[ph]++
        val horned = { t <= p.hornUntil }
        when (act) {
            "zcb", "zcbCarry" -> {
                spend(p, ZCB_SPEC, t)
                if (act == "zcb") {
                    p.specDone[ph]++
                    p.dmgSpecs[ph]++
                }
                hits += H(t + ZCB_DELAY, p, 0, "ZCB spec", "zcb")
                L("t$t ${p.name} ZCB spec (${p.spec}% left)")
                p.next = t + 5
                p.holding = "zcb"
                events.attack(t, p, "zcb", t + ZCB_DELAY)
            }
            "maul", "bgs" -> {
                spend(p, 50, t)
                p.mauled[ph]++
                p.specDone[ph]++
                val bgs = act == "bgs"
                val (ch, mx) = if (bgs) melee("Bandos godsword", defLvl, 2.0).let { (c, m0) -> c to floor(floor(m0 * 1.1) * 1.1).toInt() } else melee("Elder maul", defLvl, 1.25)
                val hornOn = horned()
                if (hornOn) p.hornUntil = -1
                val hit = hornOn || rng.random() < ch
                val d = if (hit) max(1, rng.randint(0, mx)) else 0
                if (hit) lowerDef(t, if (bgs) "BGS" else "Elder maul", if (bgs) d else floor(defLvl * 0.35).toInt())
                hits += H(t + 1, p, d, "${if (bgs) "BGS" else "Elder maul"} spec ${if (hit) d.toString() else "misses"}", "maul", hit, bgs)
                L("t$t ${p.name} ${if (bgs) "BGS" else "Elder maul"} spec${if (hornOn) " (horned)" else ""} (${p.spec}% left)")
                p.lastMelee = t
                p.next = t + 6
                p.holding = act
                events.attack(t, p, act, t + 1)
            }
            "halb" -> {
                spend(p, 30, t)
                val parts = halbSpec(defLvl).roll()
                hits += H(t + 1, p, parts[0] + parts[1], "halberd spec ${parts.joinToString("/")}", "halb")
                L("t$t ${p.name} Crystal halberd spec - last hit (${p.spec}% left)")
                p.lastMelee = t
                p.next = t + 7
                p.holding = "halb"
                events.attack(t, p, "halb", t + 1)
            }
            "claw", "clawCarry" -> {
                spend(p, 50, t)
                if (act == "claw") {
                    p.specDone[ph]++
                    p.dmgSpecs[ph]++
                }
                val hornOn = horned()
                if (hornOn) p.hornUntil = -1
                val parts = clawSpec(defLvl, hornOn)
                hits += H(t + 1, p, parts.sum(), "claw spec ${parts.joinToString("/")}", "claw")
                p.lastMelee = t
                p.next = t + 4
                p.holding = "claw"
                events.attack(t, p, "claw", t + 1)
            }
            else -> {
                val (ch, mx) = scythe(defLvl)
                val hornOn = horned()
                if (hornOn) p.hornUntil = -1
                val parts = listOf(mx, mx / 2, mx / 4).mapIndexed { j, m -> if ((hornOn && j == 0) || rng.random() < ch) max(1, rng.randint(0, m)) else 0 }
                hits += H(t + 1, p, parts.sum(), "scythe ${parts.joinToString("/")}", null)
                p.lastMelee = t
                p.next = t + 5
                p.holding = "scythe"
                events.attack(t, p, "scythe", t + 1)
            }
        }
        if (p.entry == "backup" && act == "scythe" && !p.entryScythed) {
            p.entryScythed = true
            p.next = t + 5
        }
    }

    class Prefire(val weapon: String, val tick: Int, val delay: Int, val spec: Int = 0)

    companion object {
        const val SOTE_HP = 4000
        const val SOTE_DEF = 200
        const val SOTE_DEF_FLOOR = 100
        const val SOTE_MAG = 250
        const val SOTE_ATK = 250
        const val FIRST_ATTACK = 6
        const val ATTACK_EVERY = 5
        const val ORB_DELAY = 7
        const val PRAYER_OFF = 4
        const val MELEE_MAX = 45
        const val ORB_MAX = 50
        const val VENG_CD = 50
        const val DEATH_ANIM = 5
        const val DEATH_ANIM_OVERKILL = 4
        const val PNECK_AT = 19
        const val PNECK_HEAL = 29
        const val STALL_TICKS = 7
        const val HORN_TICK = 8
        const val ZCB_MARGIN = 100
        /** The death ball's damage by players alive (the table) and players sharing it. */
        val BALL = mapOf(5 to mapOf(5 to 14, 4 to 21, 3 to 38, 2 to 87, 1 to 350), 4 to mapOf(4 to 17, 3 to 31, 2 to 70, 1 to 280), 3 to mapOf(3 to 23, 2 to 52, 1 to 210), 2 to mapOf(2 to 35, 1 to 140), 1 to mapOf(1 to 70))
        const val BALL_EVERY = 10
        const val BALL_LANDS = 15
        const val BACKUP_DEF = 100
        const val CLAW_BELOW = 450
        const val HORN_COST = 25
        const val HORN_TICKS = 10
        const val SURGE = 25
        const val ZCB_SPEC = 75
        const val ZCB_DELAY = 2
        val UNDER_AFTER = listOf(2, 1, 0, 3)
        val PREFIRE =
            mapOf(
                "Twisted bow" to Prefire("Twisted bow", 5, 3),
                "ZCB auto" to Prefire("Zaryte crossbow", 6, 3),
                "ZCB spec" to Prefire("Zaryte crossbow", 6, 3, 75),
            )

        fun mazeDmg(hp: Int) = if (hp >= 106) 22 else if (hp >= 90) 21 else if (hp >= 75) 20 else if (hp >= 60) 19 else if (hp >= 45) 18 else if (hp >= 30) 17 else 16

        fun splashDmg(hp: Int) = if (hp >= 120) 16 else if (hp >= 100) 15 else if (hp >= 80) 14 else if (hp >= 60) 13 else if (hp >= 40) 12 else if (hp >= 20) 11 else 10
    }
}
