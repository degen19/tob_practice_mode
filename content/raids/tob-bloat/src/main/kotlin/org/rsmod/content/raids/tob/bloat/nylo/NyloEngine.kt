package org.rsmod.content.raids.tob.bloat.nylo

import org.rsmod.content.raids.tob.bloat.maiden.MaidenNumbers
import org.rsmod.content.raids.tob.bloat.maiden.SimRng
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The sim's Nylocas Vasilias (verzikSim engine/nylo.js runNylo) for the 5s, line for line, so the same seed gives the
 * same fight: his forms (melee 9 ticks, then ranged / magic / melee 10 each, at random), players' attacks by form (scythe
 * and the melee specs, the green form's blowpipe / Twisted bow plans, Ayak, tome with its tick fix, ZCB), his attacks
 * with veng, prayer and takeoffs, going under him and Heal Other, cross praying (a wrong guess pushes N2 to N3), thralls
 * and spec regen. Runs a tick at a time ([tick]); [Events] says what happens.
 *
 * Everyone wears the same gear (the Maiden port's): its numbers come from scratchpad nylo/nnums.mjs. The plan per player
 * is [NyloPlan].
 */
class NyloEngine(
    plans: List<NyloPlan>,
    private val rng: SimRng,
    private val events: Events = object : Events {},
    val spawn: Int = SPAWN,
) {
    interface Events {
        /** A player attacks: code S / C / BGS / Ch / TB / AX / ZCB / E / Sh / Zt; their hits land on [lands]. */
        fun attack(t: Int, p: P, code: String, lands: Int) {}

        fun hit(t: Int, p: P, amt: Int) {}

        /** His form changes ([form] melee / ranged / magic). */
        fun form(t: Int, form: String) {}

        /** He attacks [p] (lands on [lands] for [d]). */
        fun attacksPlayer(t: Int, p: P, form: String, lands: Int) {}

        fun hitPlayer(t: Int, p: P, d: Int, veng: Int) {}

        fun under(t: Int, p: P) {}

        fun dead(t: Int) {}
    }

    class Form(val form: String, val start: Int, val end: Int) {
        val first = start + FIRST_ATTACK_OFFSET
    }

    inner class P(val i: Int, plan: NyloPlan) {
        val name = "Player ${i + 1}"
        val open = plan.open
        val specs = plan.specs.toMutableList()
        val clawMode = plan.clawMode
        val surgePlan = plan.surge
        val tomeTickFix = plan.tomeTickFix
        var spec = plan.startSpec
        var hold = plan.startSpec < 100
        var regen = 0
        var next = 0
        var n = 0
        var plan: MutableList<String> = ArrayList()
        var planForm: Int? = null
        var buff = 0
        var buffUntil = -1
        var hp = HP_MAX
        var veng = true
        var vengReady = spawn - SHOW_UP
        var dead = false
        var underForm: Int? = null
        var healForm: Int? = null
        var thrallNext: Int? = null
        var surged = false
        var surgePending = false
        var pred: String? = null
        var mis: Int? = null
        var misUsed = false
        var opened = false
        var openDone = false
        var clawNow = false
        var tomeToZcb = false
    }

    private class Q(val t: Int, val i: Int, val amt: Int?)

    private class BQ(val t: Int, val p: P, val d: Int)

    val sched: List<Form>
    var hp = NYLO_HP
        private set
    var kill: Int? = null
        private set
    var overkill = false
        private set
    var def = NYLO_DEF
        private set
    val i1 = spawn - SHOW_UP
    var t = spawn - 1
        private set
    val ps: List<P>
    private val q = ArrayList<Q>()
    private val bq = ArrayList<BQ>()
    var stWrong = 0
    var stN2late = 0
    var stVeng = 0
    var stUnder = 0

    val end: Int?
        get() = kill?.let { roomEnd(it, overkill, i1) }

    val over: Boolean
        get() = kill != null

    init {
        val s = ArrayList<Form>()
        s += Form("melee", spawn, spawn + FIRST_FORM_TICKS - 1)
        while (s.last().end < spawn + 600) {
            val prev = s.last()
            val st = prev.end + 1
            val o = FORMS.filter { it != prev.form }
            s += Form(o[rng.randint(0, 1)], st, st + FORM_TICKS - 1)
        }
        sched = s
        ps = plans.mapIndexed { i, pl -> P(i, pl) }
    }

    fun formAt(t: Int): Form? = sched.firstOrNull { t >= it.start && t <= it.end }

    private fun alive() = ps.filter { !it.dead }

    private fun after(f: Form) = sched[sched.indexOf(f) + 1]

    private fun isUnder(p: P, f: Form, t: Int) = p.underForm == f.start && (t - f.start) in UNDER_TICKS

    /* ---- the numbers */

    private fun hitChance(a: Int, d: Int): Double = MaidenNumbers.hitChance(a, d)

    private fun dRoll(d: Int) = (d + 9) * 64

    private fun melee(weapon: String, d: Int, accMult: Double = 1.0): Pair<Double, Int> {
        val r = NyloNumbers.MELEE.getValue(weapon)
        return hitChance(floor(r.ea.toDouble() * r.ab * accMult).toInt(), dRoll(d)) to r.mx
    }

    private fun ranged(weapon: String, d: Int): Pair<Double, Int> {
        val r = NyloNumbers.RANGED.getValue(weapon)
        var roll = r.ea * r.ab
        var mx = r.mx
        if (weapon == "Twisted bow") {
            roll = floor(roll * NyloNumbers.TBOW_ACC).toInt()
            mx = floor(mx * NyloNumbers.TBOW_DMG).toInt()
        }
        return hitChance(roll, dRoll(d)) to mx
    }

    private fun zcbChance(d: Int): Double {
        val r = NyloNumbers.RANGED.getValue("Zaryte crossbow")
        return hitChance(r.ea * r.ab * 2, dRoll(d))
    }

    private fun roll(ch: Double, mx: Int): Int = if (rng.random() < ch) max(1, rng.randint(0, mx)) else 0

    private fun mean(ch: Double, m: Int): Double = MaidenNumbers.hitMV(ch, m).first

    private fun fastMV(d: Int): Double {
        val (ch, mx) = ranged(NyloNumbers.FAST, d)
        if (NyloNumbers.FAST != "Ascension crossbows") return mean(ch, mx)
        val m1 = mean(1.0, floor(mx * 1.15).toInt())
        val m0 = mean(ch, mx)
        return DIAMOND_PROC * m1 + (1 - DIAMOND_PROC) * m0
    }

    private fun fastHit(d: Int): Int {
        val (ch, mx) = ranged(NyloNumbers.FAST, d)
        if (NyloNumbers.FAST == "Ascension crossbows" && rng.random() < DIAMOND_PROC) return max(1, rng.randint(0, floor(mx * 1.15).toInt()))
        return if (rng.random() < ch) max(1, rng.randint(0, mx)) else 0
    }

    private fun clawSpec(d: Int): List<Int> {
        val (ch, mx) = melee("Dragon claws", d)
        var hits: List<Int>? = null
        var k = 0
        while (k < 4 && hits == null) {
            if (rng.random() >= ch) {
                k++
                continue
            }
            hits =
                when (k) {
                    0 -> rng.randint(mx / 2, mx - 1).let { x -> listOf(x, x / 2, x / 4, x / 4 + 1) }
                    1 -> rng.randint(3 * mx / 8, 7 * mx / 8).let { x -> listOf(0, x, x / 2, x / 2 + 1) }
                    2 -> rng.randint(mx / 4, 3 * mx / 4).let { x -> listOf(0, 0, x, x + 1) }
                    else -> listOf(0, 0, 0, rng.randint(mx / 4, 5 * mx / 4))
                }
            k++
        }
        return hits ?: if (rng.random() < 2.0 / 3) listOf(1, 1, 0, 0) else listOf(0, 0, 0, 0)
    }

    private fun halbSpec(d: Int): List<Int> {
        val r = NyloNumbers.HALB
        return listOf(1.0, 0.75).map { m -> hitChance(floor(r.ea.toDouble() * r.ab * m).toInt(), dRoll(d)) }.map { c -> if (rng.random() < c) max(1, rng.randint(0, r.mx)) + r.mx / 10 else 0 }
    }

    private fun predictForm(p: P, form: String): String {
        if (p.buff > 0 || (p.specs.firstOrNull() == "tome" && p.spec >= 50)) return "magic"
        return if (form == "ranged") "melee" else "ranged"
    }

    /** The tome tick fix's pick (alignAct): the most damage that still leaves them free to fit the tome in by [target]. */
    private fun alignAct(t: Int, f: Form, tb: Double, bp: Double?, target: Int): String? {
        val memo = HashMap<Int, Pair<Double, String?>>()
        val opts = listOf(Triple("tb", 5, tb), Triple("bp", 2, bp))
        fun v(n: Int): Pair<Double, String?> {
            if (n > f.end) return if (n <= target) 0.0 to "wait" else -1e9 to null
            memo[n]?.let { return it }
            var best: Pair<Double, String?> = (v(n + 1).first - 1e-6) to "wait"
            for ((a, d, x0) in opts) if (x0 != null) {
                val x = x0 + v(n + d).first
                if (x > best.first + 1e-9) best = x to a
            }
            memo[n] = best
            return best
        }
        return v(t).second
    }

    private fun drink(p: P) {
        if (p.spec > 100 - SURGE) return
        p.spec = min(100, p.spec + SURGE)
        p.surged = true
        p.surgePending = false
    }

    /* ---- the tick */

    fun tick() {
        if (kill != null) return
        t++
        val t = t
        for (h in q.filter { it.t == t }) {
            val amt = h.amt ?: min(110, floor(hp * 0.22).toInt())
            hp -= amt
            events.hit(t, ps[h.i], amt)
            if (hp <= 0) {
                overkill = hp < 0
                hp = 0
                kill = t
                break
            }
        }
        for (h in bq.filter { it.t == t }) {
            val p = h.p
            if (p.dead) continue
            p.hp -= h.d
            var r = 0
            if (h.d > 0 && p.veng && kill == null) {
                r = floor(h.d * 0.75).toInt()
                p.veng = false
                stVeng++
                hp -= r
                if (hp <= 0) {
                    overkill = hp < 0
                    hp = 0
                    kill = t
                }
            }
            events.hitPlayer(t, p, h.d, r)
            if (p.hp <= 0) p.dead = true
        }
        if (kill != null) {
            events.dead(t)
            return
        }
        val f = formAt(t)!!
        val ft = t - f.start
        if (ft == 0) events.form(t, f.form)
        if (ft == 0 && f.start != spawn) {
            val pf = sched[sched.indexOf(f) - 1]
            for (p in alive()) {
                p.pred = predictForm(p, pf.form)
                p.mis = if (p.pred == f.form) null else f.start
                p.misUsed = false
                if (p.mis != null) stWrong++
            }
        }
        for (p in alive()) if (!p.veng && t >= p.vengReady) {
            p.veng = true
            p.vengReady = t + VENG_CD
        }
        if (f.form != "melee" && ft == 0) {
            fun heal(c: P, g: P): Boolean {
                val amt = min(floor(c.hp * 0.75).toInt(), HP_MAX - g.hp)
                if (amt <= 0) return false
                c.hp -= amt
                g.hp += amt
                if (c.hp <= 9 && c.underForm == f.start) c.hp += REDEMPTION
                return true
            }
            for (g in alive().filter { it.hp <= PRAYED_MAX }) {
                val c = alive().filter { it !== g && it.hp > PRAYED_MAX }.sortedByDescending { it.hp }.firstOrNull()
                if (c != null && c.hp - min(floor(c.hp * 0.75).toInt(), HP_MAX - g.hp) > PRAYED_MAX) heal(c, g)
            }
            for (p in alive()) {
                val free = (if (f.form == "ranged") p.next == f.start + 4 else false) || p.next >= f.start + 9
                val noVeng = !p.veng && p.vengReady > f.start + 2
                if (p.hp <= PRAYED_MAX || (noVeng && free)) {
                    p.underForm = f.start
                    stUnder++
                    if (p.next < f.start + 4) p.next = f.start + 4
                    events.under(t, p)
                }
            }
        }
        if (f.form != "melee" && ft == 1) {
            for (c in alive().filter { it.underForm == f.start && it.healForm != f.start && it.hp >= 12 }) {
                val out = alive().filter { it.underForm != f.start && it.hp < HP_MAX }
                fun give(g: P) = min(floor(c.hp * 0.75).toInt(), HP_MAX - g.hp)
                val g =
                    out.firstOrNull { it.veng && it.hp <= BOSS_MAX && it.hp + give(it) > BOSS_MAX }
                        ?: out.firstOrNull { !it.veng && it.vengReady <= f.start + 2 && it.hp <= BOSS_MAX && it.hp + give(it) > BOSS_MAX }
                        ?: out.filter { it.hp <= 30 }.sortedBy { it.hp }.firstOrNull()
                        ?: continue
                c.healForm = f.start
                val amt = give(g)
                c.hp -= amt
                g.hp += amt
                if (c.hp <= 9) c.hp += REDEMPTION
            }
        }
        val hits = if (f.start == spawn) SPAWN_HITS else BOSS_HITS
        if (ft in hits) {
            val pool = alive().filter { !isUnder(it, f, t) }
            if (pool.isNotEmpty()) {
                val p = pool[rng.randint(0, pool.size - 1)]
                val off = p.veng && p.hp > BOSS_MAX
                val tk = off && p.next >= t + 2
                val mx = if (off) BOSS_MAX else if (f.form == "melee") 0 else PRAYED_MAX
                val ch = hitChance(BOSS_ATK.getValue(f.form), if (tk) NyloNumbers.PDEF_OFF.getValue(f.form) else NyloNumbers.PDEF.getValue(f.form))
                val d = if (rng.random() < ch) rng.randint(0, mx) else 0
                val lands = t + BOSS_DELAY.getValue(f.form)
                bq += BQ(lands, p, d)
                events.attacksPlayer(t, p, f.form, lands)
            }
        }
        for (p in alive()) {
            if (p.thrallNext != t) continue
            p.thrallNext = t + 4
            if (f.form != "melee") continue
            q += Q(t + 1, p.i, rng.randint(0, 3))
        }
        for (p in ps) {
            if (p.hold) continue
            if (p.spec < 100) {
                if (++p.regen >= SPEC_REGEN) {
                    p.regen = 0
                    p.spec = min(100, p.spec + 10)
                }
            } else p.regen = 0
        }
        for (p in alive()) if (p.surgePending) drink(p)
        for (p in alive()) attack(p, f, t)
    }

    private fun attack(p: P, f: Form, t: Int) {
        if (t < f.first || p.next > t) return
        if (isUnder(p, f, t)) return
        if (p.thrallNext == null) p.thrallNext = t + rng.randint(1, 2)
        val under = p.underForm == f.start && f.form != "melee"
        if (p.mis == f.start && !p.misUsed && f.start != spawn && t - f.start == FIRST_ATTACK_OFFSET) {
            p.next = t + 1
            stN2late++
            return
        }
        // a wrong prediction: their first attack on Max switches (8 a tick - a whole set by N3)
        var ovr = p.mis == f.start && !p.misUsed
        fun pay(c: Int) {
            p.spec -= c
            if (p.hold) {
                p.hold = false
                p.regen = SPEC_REGEN - 1
            }
        }
        fun go(code: String, hits: List<Int?>, delays: List<Int>, speed: Int) {
            hits.forEachIndexed { k, d -> q += Q(t + delays[k], p.i, d) }
            p.next = t + speed
            p.n++
            if (ovr) {
                ovr = false
                p.misUsed = true
            }
            events.attack(t, p, code, t + delays.max())
        }
        var nextSpec = p.specs.firstOrNull()
        fun fbk(x: String) = if (x == "tome" || x == "zcb") FALLBACK_AT else -1.0
        fun fb(x: String) = hp <= NYLO_HP * fbk(x)
        if (f.form == "melee" && p.specs.any { fb(it) } && "Dragon claws" in NyloNumbers.WEAPONS) {
            for (k in p.specs.indices) if (fb(p.specs[k])) p.specs[k] = "claw"
            p.clawNow = true
            nextSpec = p.specs.firstOrNull()
        }
        if (f.form == "ranged" && !p.tomeToZcb && p.spec >= ZCB_SPEC && sched.indexOf(f) == 3 && sched.take(4).none { it.form == "magic" } && "tome" in p.specs && "Zaryte crossbow" in NyloNumbers.WEAPONS) {
            p.tomeToZcb = true
            p.specs.removeAt(p.specs.indexOf("tome"))
            p.specs.add(0, "zcb")
            nextSpec = "zcb"
        }
        fun surge() {
            if (p.surgePlan && !p.surged) {
                p.surgePending = true
                drink(p)
            }
        }
        var fix: String? = null
        val tomeDue = (p.buff > 0 && t <= p.buffUntil) || (nextSpec == "tome" && p.spec >= 50)
        if (p.tomeTickFix && tomeDue && f.form != "magic" && !under) {
            if (hp <= NyloNumbers.DPT * (f.end - t + 1 + FORM_TICKS)) {
                if (nextSpec == "tome") {
                    p.specs[0] = "claw"
                    p.clawNow = true
                }
                p.buff = 0
            } else if (f.form == "ranged") {
                val (c3, m3) = ranged("Twisted bow", def)
                val nf = after(f)
                val k = if (nextSpec == "tome" && p.spec >= 50) 2 else FORM_TICKS - 1 - 2 * (p.buff - 1)
                fix = alignAct(t, f, mean(c3, m3), fastMV(def), nf.start + k)
                if (fix == "wait") {
                    p.next = t + 1
                    return
                }
            }
        }
        fun fast() = go(NyloNumbers.FAST_CODE, listOf(fastHit(def)), listOf(BP_DELAY), 2)
        fun tbow() {
            val (ch, mx) = ranged("Twisted bow", def)
            go("TB", listOf(roll(ch, mx)), listOf(TBOW_DELAY), 5)
        }
        if (fix == "bp" || fix == "tb") {
            if (fix == "bp") fast() else tbow()
            return
        }
        when (f.form) {
            "melee" -> {
                val first = p.n == 0
                val opening = !p.opened && (if (p.open == "halb") p.n >= 1 else first)
                var act = "scythe"
                var isOpen = false
                val cost = COST[p.open]
                val halbFirst = p.open == "halb" && !p.opened && p.spec >= 30
                if (opening && cost != null && p.spec >= cost) {
                    act = p.open
                    isOpen = true
                } else if (!first && !halbFirst && nextSpec == "halb" && p.spec >= 30) act = "halb"
                else if (!first && !halbFirst && nextSpec == "claw" && p.spec >= 50) {
                    if (p.clawMode != "gain" || p.clawNow) act = "claw"
                    else {
                        val (aC, eC) = ride(t + 4, f)
                        val (aS, eS) = ride(t + 5, f)
                        var c = t
                        while (inst(c, i1) != 4) c++
                        val dying = hp <= NyloNumbers.DPT * (c - t + 1 + CYCLE)
                        val clawMax = 2 * melee("Dragon claws", def).second
                        if (aC > aS || eC < eS) act = "claw" else if (dying && hp > clawMax) act = "claw"
                    }
                }
                when (act) {
                    "bgs" -> {
                        pay(50)
                        val (ch, mx0) = melee("Bandos godsword", def, 2.0)
                        val mx = floor(floor(mx0 * 1.1) * 1.1).toInt()
                        val d = roll(ch, mx)
                        go("BGS", listOf(d), listOf(SCYTHE_DELAY), 6)
                        if (first) p.openDone = true
                        if (d > 0) def = max(0, def - d)
                        surge()
                    }
                    "claw" -> {
                        pay(50)
                        go("C", clawSpec(def), listOf(1, 1, 2, 2), 4)
                        if (!isOpen) p.specs.removeAt(0)
                        p.clawNow = false
                        surge()
                    }
                    "halb" -> {
                        pay(30)
                        go("Ch", halbSpec(def), listOf(1, 1), 7)
                        if (!isOpen) p.specs.removeAt(0)
                        surge()
                    }
                    else -> {
                        val (ch, mx) = melee("Scythe of vitur", def)
                        go("S", listOf(mx, mx / 2, mx / 4).map { roll(ch, it) }, listOf(1, 1, 1), 5)
                    }
                }
                if (first) p.openDone = true
                if (isOpen || (first && p.open != "halb")) p.opened = true
            }
            "ranged" -> {
                if (p.planForm != f.start) {
                    p.planForm = f.start
                    p.plan = if (under) ArrayList() else rangedPlan(t - f.start).toMutableList()
                }
                var w = if (p.plan.isEmpty()) "tb" else p.plan.removeAt(0)
                if (w == "tb" && nextSpec == "zcb" && p.spec >= ZCB_SPEC) {
                    w = "zcb"
                    p.specs.removeAt(0)
                }
                when (w) {
                    "bp" -> fast()
                    "zcb" -> {
                        pay(ZCB_SPEC)
                        val ok = rng.random() < zcbChance(def)
                        go("ZCB", listOf(if (ok) null else 0), listOf(ZCB_DELAY), 5)
                        surge()
                    }
                    else -> tbow()
                }
            }
            else -> {
                if (p.buff > 0 && t > p.buffUntil) p.buff = 0
                val (ayCh, ayMx) = NyloNumbers.AYAK
                when {
                    under -> {
                        val (ch, mx) = NyloNumbers.SHADOW
                        go("Sh", listOf(roll(ch, mx)), listOf(SHADOW_DELAY), 5)
                    }
                    p.buff > 0 -> {
                        val (ch, mx) = NyloNumbers.TOME[1]
                        val d = roll(ch, mx)
                        p.buff--
                        go("Zt", listOf(d), listOf(TOME_DELAY), 2)
                    }
                    nextSpec == "tome" && p.spec >= 50 -> {
                        p.specs.removeAt(0)
                        pay(50)
                        val (ch, mx) = NyloNumbers.TOME[1]
                        val d = roll(ch, mx)
                        p.buff = if (d > 0) 3 else 0
                        p.buffUntil = t + 50
                        go("Zt", listOf(d), listOf(TOME_DELAY), 3)
                        surge()
                    }
                    else -> go("E", listOf(roll(ayCh, ayMx)), listOf(AYAK_DELAY), 3)
                }
            }
        }
    }

    /** Scythes from tick n to the end of melee form f: (attacks, the tick they're free in the next form). */
    private fun ride(n0: Int, f: Form): Pair<Int, Int> {
        var n = n0
        var a = 0
        while (n <= f.end) {
            a++
            n += 5
        }
        return a to max(n, after(f).first)
    }

    companion object {
        const val NYLO_HP = 2500
        const val NYLO_DEF = 50
        val FORMS = listOf("melee", "ranged", "magic")
        const val FIRST_FORM_TICKS = 9
        const val FORM_TICKS = 10
        const val FIRST_ATTACK_OFFSET = 1
        const val CYCLE = 4
        const val SHOW_UP = 2
        const val DEATH_TICKS = 5
        const val DEATH_OVERKILL = 4
        /** His first form (the default spawn: the waves done in their usual time). */
        const val SPAWN = 286
        const val SCYTHE_DELAY = 1
        const val TBOW_DELAY = 2
        const val AYAK_DELAY = 2
        const val TOME_DELAY = 2
        const val SHADOW_DELAY = 3
        const val BP_DELAY = 2
        const val ZCB_DELAY = 2
        val BOSS_HITS = listOf(2, 6)
        val SPAWN_HITS = listOf(1, 5)
        const val SPEC_REGEN = 50
        const val SURGE = 25
        const val ZCB_SPEC = 75
        const val FALLBACK_AT = 0.20
        const val DIAMOND_PROC = 0.055
        const val BOSS_MAX = 70
        const val PRAYED_MAX = 17
        const val VENG_CD = 50
        const val HP_MAX = 99
        const val REDEMPTION = 24
        val BOSS_ATK = mapOf("melee" to (400 + 9) * 64, "ranged" to (350 + 9) * 64, "magic" to (50 + 9) * (600 + 64))
        val BOSS_DELAY = mapOf("melee" to 1, "ranged" to 2, "magic" to 2)
        val UNDER_TICKS = setOf(1, 2, 3, 5, 6, 7)
        val COST = mapOf("bgs" to 50, "claw" to 50, "halb" to 30)

        fun inst(t: Int, i1: Int): Int = Math.floorMod(t - i1, CYCLE) + 1

        fun roomEnd(kill: Int, overkill: Boolean, i1: Int): Int {
            var t = kill + if (overkill) DEATH_OVERKILL else DEATH_TICKS
            while (inst(t, i1) != 4) t++
            return t + 1
        }

        /** A green form's plan by how far into it their first attack goes (k: the form's 2nd tick is 1). */
        fun rangedPlan(k: Int): List<String> =
            when (k) {
                1 -> listOf("bp", "bp", "bp", "bp", "tb")
                2 -> listOf("tb", "bp", "tb")
                3 -> listOf("bp", "bp", "bp", "tb")
                else -> listOf("tb", "tb")
            }
    }
}
