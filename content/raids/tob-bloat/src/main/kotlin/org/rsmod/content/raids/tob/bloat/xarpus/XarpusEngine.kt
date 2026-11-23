package org.rsmod.content.raids.tob.bloat.xarpus

import org.rsmod.content.raids.tob.bloat.maiden.SimRng
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The sim's Xarpus (verzikSim engine/xarpus.js runXarpus), line for line, so the same seed gives the same raid: the
 * exhumeds' heals, the opening Elder mauls, re-mauls and Ralos backups down to the Defence target, scythes, ZCBs held
 * for the screech and the tick fix on the i3 before it, thralls, spec regen and Lightbearer. Runs a tick at a time
 * ([tick]) so a live room can play it; [Events] says what happens as it happens.
 *
 * The 5s gear is everyone's (the same as the Maiden port's): its numbers come from scratchpad xarpus/xnums.mjs.
 */
class XarpusEngine(
    val team: Int,
    plan: XarpusPlan,
    private val rng: SimRng,
    private val events: Events = object : Events {},
    private val log: MutableList<String>? = null,
) {
    interface Events {
        /** A player attacks: [kind] scythe / maul / bgs / ralos / zcb / claw / scratch / halb; the hit lands on [lands]. */
        fun attack(t: Int, p: P, kind: String, lands: Int) {}

        /** A hit lands on him (0 = a miss); [parts] the hitsplats. */
        fun hit(t: Int, p: P, kind: String, parts: List<Int>) {}

        fun screech(t: Int) {}

        fun dead(t: Int) {}
    }

    class P(val i: Int, val src: XarpusPlan.Player) {
        val name = "Player ${i + 1}"
        var spec = src.startSpec
        var regen = 0
        var regenHold = src.startSpec < 100
        var freeRegen = false
        var lb = false
        var lbLeft = src.lbCycles
        var firstSpec = false
        val open = src.open
        var openDone = src.open == "none"
        var used = 0
        var attacks = 0
        var remaulDue = false
        var next = 0
        var thrallNext = 0
        var scytheSure = false
        val rangePot = src.rangePot

        /** What they're holding now, for the look: the last attack's kind. */
        var holding = if (src.open == "ralos") "ralos" else "scythe"
    }

    private class Hit(val t: Int, val p: P, val amt: Int?, val label: String, val kind: String, val hit: Boolean = false, val role: String? = null, val parts: List<Int> = emptyList())

    private class Backup(val p: P, val spec: String, val now: Int) {
        var state = "wait"
    }

    private class Def(val kind: String, val role: String, val b: Backup? = null, val asMaul: Boolean = false)

    val exh = EXHUMED_TICKS.getValue(team)
    private val base = XARPUS_HP.getValue(team)
    private val cap = if (team == 1) 3 else 2
    val heals: Int
    val startHp: Int
    private val screechHp: Int
    var hp: Int
        private set
    var defLvl = XARPUS_DEF
        private set
    var kill: Int? = null
        private set
    var anim = DEATH_ANIM
        private set
    var dropAt: Int? = null
        private set
    var screech: Int? = null
        private set
    var t = exh
        private set
    val ps: List<P>
    private val pid: List<P>
    private var hits = ArrayList<Hit>()
    private val defTarget = plan.defTarget
    private val holdZcb = plan.holdZcb
    private val remaul = plan.remaul
    private val maulers: List<P>
    private var maulsLanded = 0
    private var openSeen = 0
    private var openLanded = 0
    private var remaulers: List<P>? = null
    private val remaulAny: Set<Int>
    private val backs: List<Backup>

    val end: Int?
        get() = kill?.let { it + anim }

    val over: Boolean
        get() = kill != null

    init {
        heals = max(0, plan.heals + rng.randint(-HEAL_SPREAD, HEAL_SPREAD))
        startHp = base + heals * HEAL.getValue(team)
        screechHp = floor(startHp * SCREECH_AT).toInt()
        hp = startHp
        L("exhumeds: $heals heal${if (heals == 1) "" else "s"} -> Xarpus $startHp HP; interactable t$exh, first attacks t${exh + 1}")
        ps = plan.players.mapIndexed { i, c -> P(i, c).also { it.next = exh + 1; it.thrallNext = exh + 1 + rng.randint(1, 2) } }
        pid = ps.toMutableList().also { a ->
            for (i in a.size - 1 downTo 1) {
                val j = floor(rng.random() * (i + 1)).toInt()
                val x = a[i]
                a[i] = a[j]
                a[j] = x
            }
        }
        maulers = ps.filter { it.open == "maul" }
        if (maulers.isEmpty()) remaulers = emptyList()
        remaulAny = remaul.values.flatten().toSet()
        backs = plan.backups.mapNotNull { (k, spec) -> ps.getOrNull(k)?.let { Backup(it, spec, -1) } }
    }

    private fun L(s: String) {
        log?.add(s)
    }

    /* ---- the numbers */

    private fun defRoll(lvl: Int, style: String): Int = (lvl + 9) * ((if (style == "range") XARPUS_RANGED_DEF else 0) + 64)

    private fun hitChance(a: Int, d: Int): Double = if (a > d) 1.0 - (d + 2).toDouble() / (2.0 * (a + 1)) else a.toDouble() / (2.0 * (d + 1))

    private fun melee(p: P, weapon: String, d: Int, accMult: Double = 1.0): Pair<Double, Int> {
        val r = XarpusNumbers.MELEE.getValue("$weapon|${if (p.lb) 1 else 0}")
        return hitChance(floor(r.ea.toDouble() * r.ab * accMult).toInt(), defRoll(d, "melee")) to r.mx
    }

    private fun rangedLevel(pot: String, t: Int): Int =
        when (pot) {
            "divine" -> 112
            "regular" -> max(99, 112 - max(0, t) / RANGE_POT_DRAIN)
            else -> 99
        }

    private fun ranged(weapon: String, d: Int, accMult: Double, lvl: Int): Pair<Double, Int> {
        val r = XarpusNumbers.ranged(weapon, lvl)
        return hitChance(floor(r.ea.toDouble() * r.ab * accMult).toInt(), defRoll(d, "range")) to r.mx
    }

    private fun clawRolls(p: P, d: Int): List<Int> {
        val (ch, mx) = melee(p, "Dragon claws", d)
        for (k in 0 until 4) {
            if (rng.random() >= ch) continue
            if (k == 0) {
                val x = rng.randint(mx / 2, mx - 1)
                return listOf(x, x / 2, x / 4, x / 4 + 1)
            }
            if (k == 1) {
                val x = rng.randint(3 * mx / 8, 7 * mx / 8)
                return listOf(0, x, x / 2, x / 2 + 1)
            }
            if (k == 2) {
                val x = rng.randint(mx / 4, 3 * mx / 4)
                return listOf(0, 0, x, x + 1)
            }
            return listOf(0, 0, 0, rng.randint(mx / 4, 5 * mx / 4))
        }
        return if (rng.random() < 2.0 / 3) listOf(1, 1, 0, 0) else listOf(0, 0, 0, 0)
    }

    private fun halbSpec(p: P, d: Int): List<Int> {
        val r = XarpusNumbers.HALB.getValue(if (p.lb) 1 else 0)
        val ch = { m: Double -> hitChance(floor(r.ea.toDouble() * r.ab * m).toInt(), defRoll(d, "melee")) }
        return listOf(ch(1.0), ch(0.75)).map { c -> if (rng.random() < c) max(1, rng.randint(0, r.mx)) + r.mx / 10 else 0 }
    }

    /* ---- the plan's rules */

    private fun spend(p: P, n: Int) {
        p.spec -= n
        p.firstSpec = true
        if (p.regenHold) {
            p.regenHold = false
            p.regen = 49
            p.freeRegen = true
        }
    }

    private fun lowerDef(t: Int, what: String, by: Int) {
        if (by <= 0 || defLvl <= 0) return
        defLvl = max(0, defLvl - by)
        L("t$t $what lowers his Defence -> $defLvl")
    }

    private fun damage(t: Int, amt: Int, label: String) {
        if (kill != null || amt <= 0) {
            if (kill == null) L("t$t $label 0")
            return
        }
        hp -= amt
        L("t$t $label $amt -> Xarpus ${max(0, hp)}")
        if (dropAt == null && hp <= screechHp && hp > 0) {
            dropAt = t
            var s = t + 1
            while (s % 4 != 0) s++
            screech = s
            L("t$t Xarpus at or below 33% of his start ($screechHp): screech on t$s")
        }
        if (hp <= 0) {
            kill = t
            L("t$t *** XARPUS DIES ***")
        }
    }

    private fun inFlight(vararg k: String) = hits.count { it.kind in k }

    private fun defFlying() = inFlight("maul", "bgs", "ralos") > 0

    private fun remaulOpen() = remaulers == null || ps.any { it.remaulDue } || hits.any { it.role == "remaul" }

    private fun settled() = maulers.all { it.openDone } && !remaulOpen()

    private fun startDefDone() = ps.all { it.openDone } && !remaulOpen() && !defFlying()

    private fun canSpec(p: P) = p.used < cap

    private fun maxDrain(b: Backup, d: Int): Int {
        if (b.spec == "maul") return floor(d * 0.35).toInt()
        if (b.spec == "ralos") return 2 * RALOS_DRAIN
        val mx0 = melee(b.p, "Bandos godsword", d, 2.0).second
        return floor(floor(mx0 * 1.1) * 1.1).toInt()
    }

    private fun planDefence(t: Int) {
        if (remaulers == null && maulers.all { it.openDone } && openSeen >= maulers.size) {
            val r = (remaul[openLanded] ?: emptyList()).mapNotNull { ps.getOrNull(it) }.filter { canSpec(it) }
            remaulers = r
            for (p in r) p.remaulDue = true
            L("t$t $openLanded of ${maulers.size} opening mauls landed${if (r.isNotEmpty()) ": ${r.joinToString { it.name }} maul again" else ""}")
        }
        if (remaulers == null) return
        val volleyGone = ps.none { it.remaulDue }
        for (b in backs) {
            if (b.state == "wait" && b.now >= 0 && openLanded <= b.now && volleyGone && b.p.openDone && !b.p.remaulDue && canSpec(b.p) && b.p.spec >= 50 && defLvl > defTarget) b.state = "armed"
        }
        if (backs.any { it.state == "armed" } || defFlying() || !settled() || ps.any { !it.openDone }) return
        val rest = backs.filter { it.state == "wait" }
        if (rest.isEmpty()) return
        if (defLvl <= defTarget) {
            for (b in rest) b.state = "skip"
            return
        }
        var d = defLvl
        val group = ArrayList<Backup>()
        for (b in rest) {
            if (group.any { it.p === b.p }) continue
            if (!canSpec(b.p) || b.p.spec < 50 || (b.spec == "maul" && maulsLanded >= MAUL_BACKUP_UNTIL)) {
                b.state = "skip"
                continue
            }
            group += b
            d -= maxDrain(b, d)
            if (d <= defTarget) break
        }
        for (b in group) b.state = "armed"
        if (group.isNotEmpty()) L("t$t backup${if (group.size > 1) "s together" else ""}: ${group.joinToString { "${it.p.name} ${it.spec}" }} (Defence $defLvl, target $defTarget)")
    }

    private fun defAct(p: P): Def? {
        if (!p.openDone) {
            if (p.open == "maul") return if (p.spec >= 50) Def("maul", "open") else null
            if (p.attacks < 1 || !settled()) return null
            if (defLvl <= defTarget) {
                p.openDone = true
                return null
            }
            return if (p.spec >= 50) Def(p.open, "open") else null
        }
        if (p.remaulDue) return if (p.spec >= 50) Def("maul", "remaul") else null
        val b = backs.firstOrNull { it.p === p && it.state == "armed" } ?: return null
        if (defLvl <= defTarget || !canSpec(p) || (b.spec == "maul" && maulsLanded >= MAUL_BACKUP_UNTIL)) {
            b.state = "skip"
            return null
        }
        if (p.spec < 50) return null
        return Def(b.spec, "backup", b)
    }

    private fun defOpen() = ps.any { !it.openDone } || remaulOpen() || defFlying() || (defLvl > defTarget && backs.any { it.state == "wait" || it.state == "armed" })

    private fun ownDefDone(p: P) = p.openDone && !p.remaulDue && !(remaulers == null && p.i in remaulAny) && !(defLvl > defTarget && backs.any { it.p === p && (it.state == "wait" || it.state == "armed") })

    private fun dpsAct(p: P, t: Int): String? {
        val dps = p.src.dps
        if (dps == "none" || !ownDefDone(p)) return null
        if (dps == "zcb") {
            if (hp < ZCB_BELOW) return if (!defOpen() && p.spec >= 50) "claw" else null
            if (!startDefDone()) return null
            if (holdZcb) return if (screech != null && t >= screech!! && p.spec >= ZCB_SPEC) "zcb" else null
            return if (settled() && p.spec >= ZCB_SPEC) "zcb" else null
        }
        if (defOpen()) return null
        return if (p.spec >= 50) "claw" else null
    }

    /* ---- the tick */

    fun tick() {
        if (kill != null) return
        t++
        val t = t
        val landing = hits.filter { it.t == t }
        hits = ArrayList(hits.filter { it.t != t })
        for (h in landing) {
            val p = h.p
            if (kill != null) {
                if (kill == t) {
                    anim = DEATH_ANIM_OVERKILL
                    L("t$t ${p.name} ${h.label} lands after the kill (overkill)")
                }
                break
            }
            if (h.kind == "maul" && h.hit) maulsLanded++
            if (h.role == "open" && h.kind == "maul") {
                openSeen++
                if (h.hit) openLanded++
            }
            when (h.kind) {
                "ralos" -> {
                    var total = 0
                    val parts = ArrayList<Int>()
                    for (k in 0 until 2) {
                        val (ch, mx) = ranged("Tonalztics of Ralos", defLvl, 1.5, rangedLevel(p.rangePot, t))
                        val d = if (rng.random() < ch) max(1, rng.randint(0, floor(mx * 0.75).toInt())) else 0
                        parts += d
                        total += d
                        if (d >= 1) lowerDef(t, "${p.name} Ralos Spec hit ${k + 1}", RALOS_DRAIN)
                    }
                    events.hit(t, p, h.kind, parts)
                    damage(t, total, "${p.name} Ralos Spec ${parts.joinToString("/")}")
                }
                "zcb" -> {
                    val (ch) = ranged("Zaryte crossbow", defLvl, 2.0, rangedLevel(p.rangePot, t))
                    val ok = rng.random() < ch
                    val ruby = min(110, floor(hp * 0.22).toInt())
                    events.hit(t, p, h.kind, listOf(if (ok) ruby else 0))
                    if (ok) damage(t, ruby, "${p.name} ZCB spec ruby") else L("t$t ${p.name} ZCB spec misses")
                }
                else -> {
                    events.hit(t, p, h.kind, h.parts.ifEmpty { listOf(h.amt ?: 0) })
                    damage(t, h.amt ?: 0, "${p.name} ${h.label}")
                }
            }
        }
        if (kill != null) {
            events.dead(t)
            return
        }
        if (screech == t) events.screech(t)
        for (p in ps) {
            val wantLb = p.lbLeft > 0 && p.spec < 100 && !p.regenHold
            if (wantLb != p.lb) p.lb = wantLb
            if (!p.regenHold && p.spec < 100) {
                if (++p.regen >= (if (p.lb) 25 else 50)) {
                    p.regen = 0
                    p.spec = min(100, p.spec + 10)
                    if (p.freeRegen) p.freeRegen = false
                    else if (p.lb && --p.lbLeft <= 0) {
                        p.lb = false
                        L("t$t ${p.name} Lightbearer cycles done (${p.spec}%) - Ultor")
                    }
                }
            } else if (!p.regenHold) p.regen = 0
            if (p.thrallNext == t) {
                hits += Hit(t + 1, p, rng.randint(0, 3), "thrall", "thrall")
                p.thrallNext = t + 4
            }
        }
        planDefence(t)
        for (p in pid) {
            if (t != p.next) continue
            val da = defAct(p)
            var act = da?.kind ?: dpsAct(p, t) ?: "scythe"
            val s = screech
            if (s != null && t == s - 1 && dropAt!! < t && (act == "scythe" || act == "zcb")) {
                val f = if (p.src.fix == "claw" && p.spec < 50) "scratch" else p.src.fix
                act = "fix:$f"
            }
            if (da != null) {
                p.used++
                when (da.role) {
                    "open" -> p.openDone = true
                    "remaul" -> p.remaulDue = false
                    else -> da.b!!.state = "done"
                }
            }
            p.attacks++
            val tag = if (da != null && da.role != "open") " (${if (da.role == "remaul") (if (p.open == "maul") "maul again" else "mauls short") else "backup"})" else ""
            when (act) {
                "maul", "bgs" -> {
                    spend(p, 50)
                    if (act == "maul") {
                        val (ch, mx) = melee(p, "Elder maul", defLvl, 1.25)
                        val hit = rng.random() < ch
                        val d = if (hit) max(1, rng.randint(0, mx)) else 0
                        if (hit) lowerDef(t, "${p.name} Elder maul", floor(defLvl * 0.35).toInt())
                        hits += Hit(t + MELEE_DELAY, p, d, "Elder maul spec${if (hit) "" else " misses"}", "maul", hit, da?.role)
                    } else {
                        val (ch, mx0) = melee(p, "Bandos godsword", defLvl, 2.0)
                        val mx = floor(floor(mx0 * 1.1) * 1.1).toInt()
                        val hit = rng.random() < ch
                        val d = if (hit) max(1, rng.randint(0, mx)) else 0
                        if (hit) lowerDef(t, "${p.name} BGS", d)
                        hits += Hit(t + MELEE_DELAY, p, d, "BGS spec${if (hit) "" else " misses"}", if (da?.asMaul == true) "maul" else "bgs", hit, da?.role)
                    }
                    L("t$t ${p.name} ${if (act == "maul") "Elder maul" else "BGS"} spec$tag (${p.spec}% left)")
                    p.next = t + 6
                    event(p, act, t + MELEE_DELAY)
                }
                "ralos" -> {
                    spend(p, 50)
                    hits += Hit(t + RALOS_DELAY, p, null, "Ralos Spec", "ralos", role = da?.role)
                    L("t$t ${p.name} Ralos Spec$tag (${p.spec}% left), lands t${t + RALOS_DELAY}")
                    p.next = t + 6
                    event(p, act, t + RALOS_DELAY)
                }
                "zcb" -> {
                    spend(p, ZCB_SPEC)
                    hits += Hit(t + ZCB_DELAY, p, null, "ZCB spec", "zcb")
                    L("t$t ${p.name} ZCB spec (${p.spec}% left)")
                    p.next = t + 5
                    event(p, act, t + ZCB_DELAY)
                }
                "halb" -> {
                    spend(p, 30)
                    val parts = halbSpec(p, defLvl)
                    hits += Hit(t + MELEE_DELAY, p, parts.sum(), "halberd spec ${parts.joinToString("/")} (last hit)", "halb", parts = parts)
                    p.next = t + 7
                    event(p, act, t + MELEE_DELAY)
                }
                "claw", "fix:claw" -> {
                    spend(p, 50)
                    val parts = clawRolls(p, defLvl)
                    hits += Hit(t + MELEE_DELAY, p, parts.sum(), "claw spec ${parts.joinToString("/")}${if (act == "fix:claw") " (tick fix)" else ""}", "claw", parts = parts)
                    L("t$t ${p.name} claw spec${if (act == "fix:claw") " (tick fix)" else ""} (${p.spec}% left)")
                    p.next = t + 4
                    event(p, "claw", t + MELEE_DELAY)
                }
                "fix:scratch" -> {
                    val (ch, mx) = melee(p, "Dragon claws", defLvl)
                    val d = if (rng.random() < ch) max(1, rng.randint(0, mx)) else 0
                    hits += Hit(t + MELEE_DELAY, p, d, "claw scratch (tick fix)", "scratch")
                    p.next = t + 4
                    event(p, "scratch", t + MELEE_DELAY)
                }
                else -> {
                    val (ch, mx) = melee(p, "Scythe of vitur", defLvl)
                    val sure = p.scytheSure
                    p.scytheSure = false
                    val parts = listOf(mx, mx / 2, mx / 4).map { m -> if (sure || rng.random() < ch) max(1, rng.randint(0, m)) else 0 }
                    val lose = if (act.startsWith("fix:")) (if (rng.random() < 0.75) 1 else 2) else 0
                    hits += Hit(t + MELEE_DELAY, p, parts.sum(), "scythe ${parts.joinToString("/")}${if (lose > 0) " (tick fix: next swing $lose tick${if (lose > 1) "s" else ""} late)" else ""}", "scythe", parts = parts)
                    p.next = t + 5 + lose
                    event(p, "scythe", t + MELEE_DELAY)
                }
            }
        }
    }

    private fun event(p: P, kind: String, lands: Int) {
        p.holding = kind
        events.attack(t, p, kind, lands)
    }

    companion object {
        val XARPUS_HP = mapOf(5 to 5000, 4 to 4375, 3 to 3750, 2 to 3750, 1 to 3750)
        const val XARPUS_DEF = 250
        const val XARPUS_MAGIC = 220
        const val XARPUS_RANGED_DEF = 160
        val EXHUMED_TICKS = mapOf(1 to 100, 2 to 92, 3 to 116, 4 to 84, 5 to 96)
        val HEAL = mapOf(1 to 20, 2 to 16, 3 to 12, 4 to 9, 5 to 8)
        const val HEAL_SPREAD = 2
        const val MELEE_DELAY = 1
        const val ZCB_DELAY = 2
        const val RALOS_DELAY = ZCB_DELAY + 1
        const val RALOS_DRAIN = XARPUS_MAGIC / 8
        const val SCREECH_AT = 0.33
        const val DEATH_ANIM = 4
        const val DEATH_ANIM_OVERKILL = 3
        const val ZCB_SPEC = 75
        const val ZCB_BELOW = 450
        const val MAUL_BACKUP_UNTIL = 2
        /** A regular ranging potion: 1 level lost every this many ticks (90s). */
        const val RANGE_POT_DRAIN = 150
    }
}
