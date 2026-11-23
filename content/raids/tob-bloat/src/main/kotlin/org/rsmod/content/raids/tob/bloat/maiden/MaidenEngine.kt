package org.rsmod.content.raids.tob.bloat.maiden

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import org.rsmod.content.raids.tob.bloat.maiden.MaidenNumbers.dRoll
import org.rsmod.content.raids.tob.bloat.maiden.MaidenNumbers.hitChance
import org.rsmod.content.raids.tob.bloat.maiden.MaidenNumbers.melee
import org.rsmod.content.raids.tob.bloat.maiden.MaidenNumbers.ranged

/**
 * The sim's Maiden (verzikSim engine/maiden.js runMaiden), ported line for line for the 5s plan (the stacked procedure,
 * its testing rules on): the room's crabs and freezes, her attacks and targeting, Vengeance and thralls, and every
 * player's list - what each attack does, when and on what. Same seed, same raid as the sim (draw for draw).
 *
 * It runs a tick at a time ([tick]). Live, [gate] can hold a player's next attack until their character is in place
 * (the attack goes when they're there); [events] gets everything that happens, for the room to show.
 */
class MaidenEngine(
    private val team: Int,
    private val plan: List<MaidenPlans.PlayerPlan>,
    val rng: SimRng,
    private val target: Int,
    private val events: Events = Events.NONE,
    val log: MutableList<String>? = null,
) {
    /** What the room shows. Everything has its tick. */
    interface Events {
        fun spawn(t: Int, c: Crab) {}
        fun freeze(t: Int, c: Crab, spell: String) {}
        fun leak(t: Int, c: Crab) {}
        fun crabDies(t: Int, c: Crab) {}
        fun heal(t: Int, amt: Int, hp: Int) {}
        fun hitHer(t: Int, p: P?, amt: Int, kind: String, label: String) {}
        fun hitCrab(t: Int, p: P, c: Crab, amt: Int) {}
        /** A player's attack goes: the list code (e.g. "ibs:S1@70"), what it's on (her or a crab), and its gear code. */
        fun attack(t: Int, p: P, a: String, on: Crab?, code: String, lands: Int) {}
        fun herAttack(t: Int, on: P, lands: Int) {}
        fun herHit(t: Int, on: P, amt: Int) {}
        fun veng(t: Int, p: P, amt: Int) {}
        fun thrall(t: Int, p: P, on: Crab?, amt: Int) {}
        fun summon(t: Int, p: P) {}
        fun proc(t: Int, pct: Int) {}
        fun dead(t: Int) {}

        companion object {
            val NONE = object : Events {}
        }
    }

    /** Live: may this player act now (e.g. their character has reached where the attack goes from)? */
    var gate: (P, Int, String) -> Boolean = { _, _, _ -> true }

    /** Live: hits on her show but take nothing off (the rotten potato's infinite NPC HP) - she holds her phase. */
    var holdHp: () -> Boolean = { false }

    class Crab(
        val id: String,
        val wave: Int,
        val key: String,
        var x: Int,
        var y: Int,
        var hp: Int,
        val born: Int,
        var entered: Int,
        val idx: Int,
    ) {
        var frozenTo = -1
        var immuneTo = -1
        var alive = true
        var leaked: Int? = null
        var died: Int? = null
        var rushed = false
        var scyBy: Int? = null
        var claim: Int? = null
    }

    inner class P(val i: Int, src: MaidenPlans.PlayerPlan) {
        val name = "Player ${i + 1}"
        var seq: MutableList<String> = src.seq.toMutableList()
        var seq70: MutableList<String> = src.s70.toMutableList()
        var seq50: MutableList<String> = src.s50.toMutableList()
        var seq30: MutableList<String> = src.s30.toMutableList()
        val side = src.side
        /** P3: bows on her from the north edge, 9 out (as the third bow does in real 5s raids on Blert). */
        val northBow = i == 2
        var spec = src.startSpec
        var tbowFrom = 4
        var step = 0
        var next = 0
        var style = "range"
        var veng = false
        var vengReady = 0
        var regenAt = 0
        var surged = false
        var spot = intArrayOf(0, 0)
        var pos = intArrayOf(24, 10 + i % 4)
        /** "maiden", a crab, or null. */
        var lastTgt: Any? = null
        var lastScy: Int? = null
        var started = false
        var prefireT: Int? = null
        var thrallAt: Int? = null
        var finDone = false
        var standing = false
        var lostLast = false
        var stoppedOnce = false
        var pending: String? = null
        val thralls = true
        val vengOn = true
    }

    class Hit(var t: Int, val p: P, val a: String) {
        var amt = 0
        var exp: Double? = null
        var v: Double? = null
        var label = ""
        var kind = ""
        var ruby = false
        var acc: Double? = null
        var parts: List<Part>? = null
        var targets: List<Crab>? = null
        var k: String? = null
        var crab: String? = null
        var stalled: Int? = null
    }

    class Part(val c: Crab?, val d: Int) {
        val maiden get() = c == null
    }

    // ---- constants (engine/maiden.js)
    private val MAX = HP.getValue(team)
    var hp = MAX
        private set
    var def = 200
        private set
    var proc: Int? = null
        private set
    var proc50: Int? = null
        private set
    var proc30: Int? = null
        private set
    var dead: Int? = null
        private set
    var end: Int? = null
        private set
    var phase = 70
        private set
    var leaks = 0
        private set
    var healed = 0
        private set
    var t = -1
        private set

    val ps: List<P> = plan.mapIndexed { i, c -> P(i, c) }
    val crabs = ArrayList<Crab>()
    private val heals = ArrayList<Triple<Int, Int, String>>()
    private val hits = ArrayList<Hit>()
    private val frozenCount = HashMap<String, Int>()
    private var early50 = false
    private var scyAt: Int? = null
    private var spawn70: Int? = null
    private var spawn50: Int? = null
    private var spawn30: Int? = null
    private val tgt: P
    private val sec: P?
    private val vengers: List<P>
    private val casters: List<P>

    private fun L(s: String) {
        log?.add("t$t $s")
    }

    private fun L(at: Int, s: String) {
        log?.add("t$at $s")
    }

    init {
        // a list ending in scythes then a Halberd spec (on her): scythe until the halberd's time ('fin')
        fun lastHit(l: MutableList<String>): MutableList<String> {
            if (l.lastOrNull() != "chally") return l
            var i = l.size - 1
            while (i > 0 && l[i - 1] == "scythe") i--
            return if (i < l.size - 1) (l.subList(0, i) + "fin").toMutableList() else l
        }
        for (p in ps) {
            if (p.seq70.isEmpty()) p.seq70 = mutableListOf(p.seq.last())
            if (p.seq50.isEmpty()) p.seq50 = p.seq70.toMutableList()
            if (p.seq30.isEmpty()) p.seq30 = mutableListOf(p.seq50.last())
            p.next = prefireTick(p.seq[0])
        }
        for (p in ps) {
            p.seq70 = lastHit(p.seq70)
            p.seq50 = lastHit(p.seq50)
            p.seq30 = lastHit(p.seq30)
        }
        for (p in ps) p.regenAt = rng.randint(1, REGEN_EVERY)
        // where each player stands on her (backend, from their lists)
        val n = arrayOf(intArrayOf(7, 8), intArrayOf(6, 8), intArrayOf(5, 8), intArrayOf(4, 8))
        val s = arrayOf(intArrayOf(5, 15), intArrayOf(4, 15), intArrayOf(6, 15), intArrayOf(3, 15))
        val e = arrayOf(intArrayOf(8, 11), intArrayOf(8, 12), intArrayOf(8, 10), intArrayOf(8, 13))
        var ni = 0
        var si = 0
        var ei = 0
        fun has(p: P, k: String) = listOf(p.seq, p.seq70, p.seq50, p.seq30).any { l -> l.any { it.startsWith(k) } }
        for (p in ps) {
            p.spot =
                when {
                    p.side == "N" -> n[ni++ % 4]
                    p.side == "S" -> s[si++ % 4]
                    p.side == "E" -> e[ei++ % 4]
                    has(p, "ib:") -> e[ei++ % 4]
                    has(p, "dinhs") -> n[ni++ % 4]
                    else -> s[si++ % 4]
                }
            p.pos = intArrayOf(24, 10 + p.i % 4)
        }
        vengers = ps.filter { it.vengOn }
        for (p in ps) p.veng = vengers.isNotEmpty()
        tgt = ps[min(ps.size - 1, max(0, target))]
        sec = ps.filter { it !== tgt && it.seq.any { a -> ATTACKS[a]?.style == "melee" } }.lastOrNull()
        casters = ps.filter { p -> p.seq70.any { atkOf(it).crab } }
    }

    // ---- helpers

    private fun prefireTick(a: String): Int =
        when {
            a == "ralos" || a == "ralosl" -> PREFIRE_TICK.getValue(a)
            ATTACKS.getValue(a).weapon == "Twisted bow" -> PREFIRE_TICK.getValue("tbow")
            else -> PREFIRE_TICK.getValue("zcb")
        }

    private fun roll(ch: Double, mx: Int): Int = if (rng.random() < ch) max(1, rng.randint(0, mx)) else 0

    private fun lineOf(p: P): String = p.seq[min(p.step, p.seq.size - 1)]

    private fun isFreeze(a: String): Boolean = FREEZE_RE.containsMatchIn(a) || a == "tfz" || a == "qfz"

    private fun autoChance(p: P, style0: String): Double {
        val style = if (style0 == "magic") "mage" else style0
        return MaidenNumbers.AUTO.getValue(style)
    }

    private val her = MAIDEN_TILE
    private val mx1 = her.x + her.size - 1
    private val my1 = her.y + her.size - 1
    private val swRow = her.y + her.size - 1
    private val hc = intArrayOf(her.x + 3, her.y + her.size - 1 - 3)

    private fun zone(xy: IntArray): Int = max(abs(xy[0] - hc[0]), abs(xy[1] - hc[1]))

    fun adjacent(x: Int, y: Int): Boolean {
        val gx = max(max(her.x - (x + 1), x - mx1), 0)
        val gy = max(max(her.y - (y + 1), y - my1), 0)
        return max(gx, gy) <= 1 && !(gx == 1 && gy == 1)
    }

    /** Where a player is for her targeting: on their spot when a melee hit on her is coming within 2 ticks. */
    private fun posAt(p: P, t: Int): IntArray {
        val nx = ATTACKS[p.seq[min(p.step, p.seq.size - 1)].substringBefore(':')]
        return if (nx != null && nx.style == "melee" && !nx.crab && p.next - t <= 2) p.spot else p.pos
    }

    private class Cand(val p: P, val z: Int, val own: Boolean)

    /** Who she attacks: the closest to her centre (zones), ties by orb order; flinches for Vengeance (see the sim). */
    private fun pick(t: Int): P {
        val land = t + AUTO_DELAY
        fun ahead(az: Int, ap: P, bz: Int, bp: P) = az < bz || (az == bz && ap.i < bp.i)
        val c = ps.map { p -> Cand(p, zone(posAt(p, t)), p.veng || (p.vengOn && p.vengReady <= land - 1)) }.sortedWith(compareBy({ it.z }, { it.p.i }))
        for ((k, q) in c.withIndex()) {
            val vo = if (q.own) null else vengers.firstOrNull { it !== q.p && t >= it.vengReady }
            if (!q.own && vo == null) continue
            val before = c.subList(0, k)
            if (!before.all { b -> ahead(q.z, q.p, b.z + 2, b.p) }) break
            if (vo != null) {
                q.p.veng = true
                vo.vengReady = t + VENG_CD
                L("${vo.name} casts Vengeance Other on ${q.p.name}")
            }
            for (b in before) flinch(b.p, t, q.p)
            return q.p
        }
        return c[0].p
    }

    private fun flinch(q: P, t: Int, to: P) {
        val nx = atkOf(q.seq[min(q.step, q.seq.size - 1)])
        if (nx.style == "melee" && q.next <= t) q.next = t + 1
        L("${q.name} flinches two tiles (no Vengeance in time; praying Magic) - her attack goes to ${to.name}")
    }

    private fun recast(p: P, t: Int) {
        val others = if (p === tgt && sec != null && sec.vengOn) listOf(sec) + vengers.filter { it !== sec } else vengers
        val by = if (p.vengOn && t >= p.vengReady) p else others.firstOrNull { it !== p && t >= it.vengReady }
        if (by == null) return
        p.veng = true
        by.vengReady = t + VENG_CD
        L(if (by === p) "${p.name} recasts Vengeance" else "${by.name} casts Vengeance Other on ${p.name}")
    }

    private fun procsOn(pct: Int, before: Int, after: Int): Boolean = !(before * 100 < MAX * pct) && after * 100 < MAX * pct

    private fun damage(t: Int, amt: Int, label: String, kind: String, stalled: Boolean = false, by: P? = null) {
        if (amt <= 0) {
            L(t, "$label 0")
            return
        }
        if (dead != null) return
        events.hitHer(t, by, amt, kind, label)
        if (holdHp()) return
        val before = hp
        hp -= amt
        if (hp <= 0 && dead == null) {
            dead = t
            end = t + DEATH_TICKS
            L(t, "$label $amt -> Maiden 0 *** dead ***")
            events.dead(t)
            return
        }
        L(t, "$label $amt -> Maiden $hp")
        if (phase == 70 && procsOn(70, before, hp)) {
            proc = t
            phase = 50
            L(t, "*** 70s proc ***")
            events.proc(t, 70)
        } else if (phase == 50 && proc != null && procsOn(50, before, hp)) {
            proc50 = t
            phase = 30
            L(t, "*** 50s proc ***")
            events.proc(t, 50)
        } else if (phase == 30 && proc50 != null && procsOn(30, before, hp)) {
            proc30 = t
            phase = 0
            L(t, "*** 30s proc ***")
            events.proc(t, 30)
        }
    }

    /** Damage on her from outside the sim's players (a real player in the room): counts like any hit. */
    fun external(amt: Int) {
        if (amt <= 0 || dead != null) return
        if (holdHp()) return
        val before = hp
        hp -= amt
        L("Player hit $amt -> Maiden $hp")
        if (hp <= 0) {
            dead = t
            end = t + DEATH_TICKS
            events.dead(t)
            return
        }
        if (phase == 70 && procsOn(70, before, hp)) { proc = t; phase = 50; events.proc(t, 70) }
        else if (phase == 50 && proc != null && procsOn(50, before, hp)) { proc50 = t; phase = 30; events.proc(t, 50) }
        else if (phase == 30 && proc50 != null && procsOn(30, before, hp)) { proc30 = t; phase = 0; events.proc(t, 30) }
    }

    /** Damage on a crab from a real player in the room. */
    fun externalCrab(c: Crab, amt: Int) {
        if (!c.alive || amt <= 0) return
        c.hp -= amt
        if (c.hp <= 0) {
            c.alive = false
            c.died = t
            events.crabDies(t, c)
        }
    }

    /** The line a player's on now (their list's current entry), for where to stand. */
    fun lineAt(p: P): String = p.pending ?: p.seq[min(p.step, p.seq.size - 1)]

    /** The walking crab this player goes after once it thaws (the testing rules' chase), while one is coming. */
    fun chaseOf(p: P): Crab? = crabs.firstOrNull { CHASE[it.key] == p.i && it.alive && it.leaked == null && it.hp > CHASE_HP }

    /**
     * What line [a] most likely turns into when it comes up, for heading there early: an "if" on her HP by the HP she
     * should have by then (the hits on their way, and the team's damage a tick), an alternative by whether its crab
     * is still worth it, and an attack on a crab that's gone by the attack on her it falls back to.
     */
    fun ahead(p: P, a: String?): String? {
        var x = a ?: return null
        if (x.startsWith("s1if:")) {
            val pct = x.split(':')[1].toInt()
            val by = max(t, p.next)
            val est = hp - hits.filter { it.t <= by && it.exp != null }.sumOf { it.exp!! } - AHEAD_DPS * (by - t)
            val s1 = crabRef("S1@70")
            x = if (est * 100 < MAX * pct && s1 != null && s1.alive) "scyk:S1@70" else "scythe"
        }
        if (x.contains('|')) {
            val (m, alt) = x.split('|')
            x = if (invalid(m, t) != null) alt else m
        }
        val ref = crabOf(x)
        if (ref != null && ATTACKS[x.substringBefore(':')]?.style == "melee" && crabRef(ref)?.alive != true) x = "scythe"
        return x
    }

    /** The line after [lineAt] (where to head once this attack is done), if any. */
    fun lineAfter(p: P): String? = if (p.pending != null) p.seq.getOrNull(p.step) else p.seq.getOrNull(p.step + 1)

    private fun lowerDef(t: Int, what: String, by: Int) {
        if (by <= 0 || def <= 0) return
        def = max(0, def - by)
        L(t, "$what lowers her Defence -> $def")
    }

    private fun stallTo(p: P, t: Int): Int? {
        val mine = hits.filter { it.p === p && it.a == "scythe" && it.t >= t }.map { it.t }
        if (mine.isNotEmpty()) return mine.min()
        if (p.seq[min(p.step, p.seq.size - 1)] == "scythe") return max(p.next, t) + 1
        val any = hits.filter { it.a == "scythe" && it.t >= t }.map { it.t }
        return if (any.isNotEmpty()) any.min() else null
    }

    private fun thrall(p: P, at: Int) {
        val d = rng.randint(0, 3)
        hits += Hit(at, p, "thrall").also { it.amt = d }
    }

    private fun reachOf(c: Crab, t: Int): Int {
        var x = c.x
        var y = c.y
        var n = 0
        while (!adjacent(x, y) && n < 60) {
            val sw = y + 1
            x -= 1
            if (sw < swRow) y += 1 else if (sw > swRow) y -= 1
            n++
        }
        return max(max(t + 1, c.frozenTo + 1), c.born + 1) + n - 1
    }

    private fun deadline(t: Int): Int? {
        var d: Int? = null
        for (c in crabs) {
            if (!c.alive || c.leaked != null) continue
            val reach = reachOf(c, t)
            if (d == null || reach + 1 < d) d = reach + 1
        }
        return d
    }

    private fun killP(p: P, t: Int, by: Int, mine: Pair<Double, Double>): Double {
        val due = hits.filter { it.t <= by && it.exp != null }
        var mean = due.sumOf { it.exp!! } + mine.first
        var v = due.sumOf { it.v ?: 0.0 } + mine.second
        for (q in ps) {
            if (q === p || !SCYTHE_LINE.containsMatchIn(q.seq[min(q.step, q.seq.size - 1)])) continue
            val (ch, mx) = melee("Scythe of vitur", 1, def)
            val (sm, sv) = MaidenNumbers.scytheMV(ch, mx)
            var f = max(q.next, t)
            while (f + 1 <= by) {
                mean += sm
                v += sv
                f += 5
            }
        }
        val z = (mean - hp + 0.5) / sqrt(max(1.0, v))
        return 1.0 / (1.0 + exp(-1.702 * z))
    }

    private fun halbOf(): Pair<Double, Double> {
        val (c1, mx) = melee("Crystal halberd", 1, def)
        val (c2) = melee("Crystal halberd", 1, def, 0.75)
        return MaidenNumbers.halbMV(c1, c2, mx)
    }

    private fun scyOf(): Pair<Double, Double> {
        val (ch, mx) = melee("Scythe of vitur", 1, def)
        return MaidenNumbers.scytheMV(ch, mx)
    }

    private fun canHalb(p: P) = !p.finDone && p.spec >= 30

    private fun finPick(p: P, t: Int, x: Int?): String {
        if (p.finDone) return "scythe"
        if (canHalb(p)) {
            val pH = killP(p, t, t + 1, halbOf())
            val pS = killP(p, t, t + 1, scyOf())
            if (pH >= KILL_HALBERD && pS < KILL_HALBERD) {
                p.finDone = true
                L("${p.name}: halberd early - likely kills her (${Math.round(pH * 100)}%, scythe ${Math.round(pS * 100)}%)")
                return "challyf"
            }
        }
        val d = deadline(t)
        if (d != null && t + 1 <= d && t + 6 > d) {
            p.finDone = true
            return "challyf"
        }
        if (x != null && hp * 100 <= MAX * x) {
            p.finDone = true
            return "challyf"
        }
        return "scythe"
    }

    // ---- crabs

    private fun spawn(t: Int, wave: Int) {
        for ((id, xy) in SPAWNS) {
            val c = Crab(id, wave, "$id@$wave", xy.first, xy.second, CRAB_HP.getValue(team), t, t, rng.randint(0, 32767))
            crabs += c
            events.spawn(t, c)
        }
        L(t, "${SPAWNS.size} crabs spawn: ${SPAWNS.keys.joinToString(" ")}")
    }

    private fun chunkXY(x: Int, y: Int) = Math.floorDiv(x - CHUNK_X0, CHUNK) to Math.floorDiv(y - CHUNK_Y0, CHUNK)

    private fun chunkOf(x: Int, y: Int) = chunkXY(x, y).let { "${it.first},${it.second}" }

    private fun chunkOrder(cx: Int, cy: Int, x: Int, y: Int): Int {
        val (a, b) = chunkXY(cx, cy)
        val (c, d) = chunkXY(x, y)
        val dc = c - a
        val dr = d - b
        return if (abs(dc) > 1 || abs(dr) > 1) 99 else (1 - dc) * 3 + (dr + 1) + 1
    }

    private fun moveCrabs(t: Int) {
        for (c in crabs) {
            if (!c.alive || t <= c.born || t <= c.frozenTo) continue
            val sw = c.y + 1
            val ck = chunkOf(c.x, c.y + 1)
            c.x -= 1
            if (sw < swRow) c.y += 1 else if (sw > swRow) c.y -= 1
            if (chunkOf(c.x, c.y + 1) != ck) c.entered = t
            if (adjacent(c.x, c.y)) {
                c.alive = false
                c.leaked = t
                leaks++
                heals += Triple(t + 1, 2 * c.hp, c.key)
                L(t, "${c.id} leaks (${c.hp} HP left)")
                events.leak(t, c)
            }
        }
    }

    fun crabRef(ref: String): Crab? = if (ref.contains('@')) crabs.firstOrNull { it.key == ref } else crabs.lastOrNull { it.id == ref }

    private fun latestWave(): Int? = crabs.lastOrNull()?.wave

    private fun inArea(a: Crab, b: Crab): Boolean = abs(a.x - b.x) <= 1 && abs(a.y - b.y) <= 1

    private fun clumpPrim(pool: List<String>, withS2: Boolean): Crab? {
        val w = latestWave()
        val s2 = crabRef("S2")
        var best: Crab? = null
        var bs = -1
        for (c in pool.mapNotNull { crabRef(it) }.filter { it.alive && it.wave == w }) {
            val n = crabs.count { q -> q.alive && q.wave == w && inArea(c, q) } + if (withS2 && s2 != null && s2.alive && s2.wave == w && inArea(c, s2)) 100 else 0
            if (n > bs) {
                bs = n
                best = c
            }
        }
        return if (withS2 && bs < 100) null else best
    }

    private fun aoe3(prim: Crab, cap: Int): List<Crab> =
        (listOf(prim) +
                crabs.filter { it !== prim && it.alive && inArea(prim, it) }
                    .map { it to chunkOrder(prim.x, prim.y + 1, it.x, it.y + 1) }
                    .sortedWith(compareBy<Pair<Crab, Int>>({ it.second }, { -it.first.entered }, { it.first.idx }))
                    .map { it.first })
            .take(cap)

    private fun barragePrim(id: String, t: Int = 0): Crab? {
        if (id == "3s" || id == "4s") {
            val w = latestWave()
            var best: Crab? = null
            var bs = -1
            val pool = if (id == "3s") listOf("N3", "S3") else FOURS
            for (c in pool.mapNotNull { crabRef(it) }.filter { it.alive && it.wave == w }) {
                val n = aoe3(c, BARRAGE_CAP)
                val sc = n.count { q -> t > q.immuneTo && t > q.frozenTo } * 100 + n.size
                if (sc > bs) {
                    bs = sc
                    best = c
                }
            }
            return best
        }
        if (id == "S2" && latestWave() != 70) return clumpPrim(CLUMP, true) ?: crabRef("S2")
        if (id == "N4") return clumpPrim(FOURS, false) ?: crabRef("N4a")
        return crabRef(id)
    }

    private fun boom(c: Crab?, t: Int): Boolean = c != null && !c.alive && c.leaked == t && c.died == null

    /** Why a list hit can't go now (skipped, no tick lost), or null. */
    private fun invalid(a: String, t: Int): String? {
        val k = a.substringBefore(':')
        val ref = crabOf(a)
        if (k == "scyc" || k == "scyk" || k == "scys" || (k == "chally" && ref != null)) {
            val c = crabRef(ref!!)
            if (c == null || (!c.alive && !boom(c, t))) return "$ref gone"
            val t3 = a.split(':').getOrNull(2)
            val thr = if (t3.isNullOrEmpty()) 20 else t3.toInt()
            if (k == "scyc" && c.hp <= thr - (if (thr == 20) 1 else 0)) return "$ref under"
        }
        return null
    }

    private fun spotOf(p: P): IntArray = CASTER_SPOTS[max(0, casters.indexOf(p)) % CASTER_SPOTS.size]

    private fun distTo(p: P, c: Crab): Int {
        val (px, py) = spotOf(p).let { it[0] to it[1] }
        return max(max(max(c.x - px, px - (c.x + 1)), 0), max(max(c.y - py, py - (c.y + 1)), 0))
    }

    private fun fz(k: String) = MaidenNumbers.MAGIC.getValue(k)

    private fun barrageCast(t: Int, p: P, id: String, k: String): List<Crab>? {
        val prim = barragePrim(id, t)
        if (prim == null || !prim.alive) return null
        val f = FSPELL.getValue(k)
        val list = if (f.aoe) aoe3(prim, BARRAGE_CAP) else listOf(prim)
        val froze = ArrayList<String>()
        val sure = fz(k).second >= FREEZE_SURE
        for (c in list) {
            if (t > c.immuneTo && t > c.frozenTo && (sure || rng.random() < hitChance(fz(k).second, (CRAB_MAGIC + 9) * 64))) {
                c.frozenTo = t + f.ticks
                c.immuneTo = c.frozenTo + FREEZE_IMMUNE
                frozenCount[c.key] = (frozenCount[c.key] ?: 0) + 1
                froze += if (c.wave == 70 || crabs.none { it.wave == 50 }) c.id else c.key
                events.freeze(t, c, f.spell)
            }
        }
        L(t, "${p.name} ${f.name} on $id${if (prim.id != id) " (through ${prim.id})" else ""}: freezes ${if (froze.isNotEmpty()) froze.joinToString(", ") else "none (frozen / immune)"}")
        return list
    }

    private fun barrageLands(t: Int, h: Hit) {
        val parts = ArrayList<String>()
        for (c in h.targets!!) {
            if (!c.alive) {
                parts += "${c.id} gone"
                continue
            }
            val b = fz(h.k ?: "ib")
            val ok = rng.random() < hitChance(b.second, (CRAB_MAGIC + 9) * 64)
            val d = if (ok) rng.randint(0, b.first) else 0
            c.hp -= d
            events.hitCrab(t, h.p, c, d)
            if (c.hp <= 0) {
                c.alive = false
                c.died = t
                events.crabDies(t, c)
            }
            parts += "${c.id} $d${if (c.alive) "" else " (dies)"}"
        }
        L(t, "${h.p.name} ${FSPELL.getValue(h.k ?: "ib").name} on ${h.crab} lands: ${parts.joinToString(", ")}")
    }

    /** The scythe's arc (the community's theory): 1x3 across the line from the player to the primary's centre. */
    private fun scytheArc(p: P, prim: Crab, px: Int, py: Int): List<Part> {
        val cx = prim.x
        val cy = prim.y + 1
        val dx = (cx - px).sign
        val dy = (cy - py).sign
        val c = intArrayOf(px + dx, py + dy)
        val diag = dx != 0 && dy != 0
        val lt = if (diag) intArrayOf(px + dx, py) else intArrayOf(c[0] + dy, c[1] - dx)
        val rt = if (diag) intArrayOf(px, py + dy) else intArrayOf(c[0] - dy, c[1] + dx)
        fun on(q: Crab, tile: IntArray) = tile[0] >= q.x && tile[0] <= q.x + 1 && tile[1] >= q.y && tile[1] <= q.y + 1
        fun onHer(tile: IntArray) = tile[0] >= her.x && tile[0] <= mx1 && tile[1] >= her.y && tile[1] <= my1
        // null: nothing there; a Part with c = null: her
        fun at(tile: IntArray, notPrim: Boolean): Any? {
            val found = crabs.firstOrNull { q -> (q.alive || q === prim) && q !== (if (notPrim) prim else null) && on(q, tile) && (!notPrim || q !== prim) }
            return found ?: if (onHer(tile)) HER else null
        }
        val (ch, mx) = melee("Scythe of vitur", 1, CRAB_DEF)
        val (chM, mxM) = melee("Scythe of vitur", 1, def)
        val tg = arrayListOf<Any>(prim)
        for ((tile, notPrim) in listOf(c to true, lt to false, rt to false)) {
            if (tg.size >= 3) break
            val x = at(tile, notPrim)
            if (x != null) tg += x
        }
        return tg.mapIndexed { k, x ->
            val m = intArrayOf(1, 2, 4)[k]
            if (x === HER) Part(null, roll(chM, mxM / m)) else Part(x as Crab, roll(ch, mx / m))
        }
    }

    private fun crabHit(t: Int, p: P, a: String, k: String) {
        val atk = atkOf(a)
        if (atk.spec > 0) {
            p.spec -= atk.spec
            L("${p.name} ${atk.label} (${p.spec}% left)")
        }
        var parts: List<Part> = emptyList()
        var label = atk.label
        var pos: IntArray? = null
        var aim: String? = null
        val prim = crabRef(crabOf(a)!!)!!
        if (k == "scys" || k == "scyc" || k == "scyk") {
            val side = if (k == "scys") a.split(':').getOrNull(2) else null
            aim = prim.key
            pos = if (side == "N") intArrayOf(prim.x, prim.y - 1) else if (side == "S") intArrayOf(prim.x, prim.y + 2) else intArrayOf(prim.x + 2, prim.y + 1)
            parts = scytheArc(p, prim, pos[0], pos[1])
            label = "scythe on ${prim.key}"
        } else if (k == "chally") {
            pos = intArrayOf(prim.x + 2, prim.y + 1)
            aim = prim.key
            val (c1, mx) = melee("Crystal halberd", 1, CRAB_DEF)
            val ch = doubleArrayOf(c1, melee("Crystal halberd", 1, CRAB_DEF, 0.75).first)
            parts = ch.map { c -> Part(prim, if (rng.random() < c) max(1, rng.randint(0, mx)) + mx / 10 else 0) }
            label = "Halberd spec on ${prim.key}"
        }
        p.lastTgt = aim?.let { crabRef(it) } ?: p.lastTgt
        p.pos = pos ?: p.pos
        hits += Hit(t + atk.delay, p, "aoe").also {
            it.parts = parts
            it.label = label
            it.kind = k
        }
        events.attack(t, p, a, prim, atk.code, t + atk.delay)
        p.style = atk.style ?: p.style
        p.next = t + atk.speed
    }

    private fun procRisk(t: Int, by: Int, pct: Int): Boolean {
        val due = hits.filter { it.t <= by && it.exp != null }
        var mean = due.sumOf { it.exp!! }
        var v = due.sumOf { it.v ?: 0.0 }
        for (q in ps) {
            if (q.seq[min(q.step, q.seq.size - 1)] != "scythe") continue
            val (ch, mx) = melee("Scythe of vitur", 1, def)
            val (sm, sv) = MaidenNumbers.scytheMV(ch, mx)
            var f = max(q.next, t)
            while (f + 1 <= by) {
                mean += sm
                v += sv
                f += 5
            }
        }
        return (hp - mean - EARLY50_Z * sqrt(v)) * 100 < MAX * pct
    }

    private fun applyHeals(t: Int) {
        for (h in heals.filter { it.first == t }) {
            val b = hp
            hp = min(MAX, hp + h.second)
            healed += hp - b
            L(t, "she heals ${hp - b} (${h.third.substringBefore('@')}) -> $hp")
            events.heal(t, hp - b, hp)
        }
    }

    /** The player's next attack: their list in turn (the last repeats), skipping what they can't do. */
    private fun nextAct(p: P, t: Int): String {
        while (true) {
            val last = p.step >= p.seq.size - 1
            var a = p.seq[min(p.step, p.seq.size - 1)]
            if (a == "surge") {
                if (!p.surged && p.spec < 100) {
                    p.spec = min(100, p.spec + SURGE)
                    p.surged = true
                    L("${p.name} drinks a surge potion (${p.spec}%)")
                }
                if (!last) {
                    p.step++
                    continue
                }
                return "tbr"
            }
            if (proc30 != null && latestWave() == 30 && isFreeze(a) && Regex("^[34]s").containsMatchIn(crabOf(a) ?: "")) {
                val id = crabOf(a)!!.take(2)
                val cl = crabs.filter { c -> c.wave == 30 && c.alive && c.leaked == null && t > c.frozenTo && c.id.replace(Regex("[ab]$"), "").endsWith(id[0]) }
                val heal = if (cl.isNotEmpty()) cl.minOf { reachOf(it, t) } + 1 else Int.MAX_VALUE
                val by = if (heal != Int.MAX_VALUE) heal else t + 1
                fun mineOf(first: String): Pair<Double, Double> {
                    var mv = if (first == "challyf") halbOf() else scyOf()
                    var f = t + if (first == "challyf") 7 else 5
                    while (f + 1 <= by) {
                        mv = MaidenNumbers.sumMV(mv, scyOf())
                        f += 5
                    }
                    return mv
                }
                val opt = listOfNotNull(if (canHalb(p)) "challyf" else null, "scythe").map { it to killP(p, t, by, mineOf(it)) }.sortedByDescending { it.second }.firstOrNull()
                if (opt != null && t + 1 < heal && opt.second >= KILL_SKIP) {
                    L("${p.name}: skips the $id barrage - ${if (opt.first == "challyf") "halberd" else "scythe"} on her likely kills her first")
                    if (opt.first == "challyf") p.finDone = true
                    if (!last) p.step++
                    return opt.first
                }
            }
            if (a == "fin" || a.startsWith("fin:")) {
                val t3 = a.split(':').getOrNull(1)
                a = finPick(p, t, if (t3.isNullOrEmpty()) null else t3.toInt())
            }
            if (a.startsWith("s1if:")) {
                val x = a.split(':')[1].toInt()
                val s1 = crabRef("S1@70")
                a = if (hp * 100 < MAX * x && s1 != null && s1.alive) "scyk:S1@70" else "scythe"
                if (a != "scythe") L("${p.name}: Maiden under $x% - the 70s S1 once")
            }
            if (a.contains('|')) {
                val (m, alt) = a.split('|')
                a = if (invalid(m, 0) != null) alt else m
                if (a == alt) L("${p.name}: ${hitLabel(m)} not needed - ${hitLabel(alt)} instead")
            }
            if (a == "lose" && last) {
                if (p.lostLast) a = p.seq.dropLast(1).filter { it != "lose" }.lastOrNull() ?: "tbr" else p.lostLast = true
            }
            val atk = atkOf(a)
            val why = invalid(a, t)
            val energy = atk.spec == 0 || p.spec >= atk.spec || (!p.surged && p.spec + SURGE >= atk.spec)
            if (energy && why == null) {
                if (atk.spec > 0 && p.spec < atk.spec) {
                    p.spec = min(100, p.spec + SURGE)
                    p.surged = true
                    L("${p.name} drinks a surge potion (${p.spec}%)")
                }
                if (!last) p.step++
                return a
            }
            if (!last || why != null) {
                L("${p.name} skips ${hitLabel(a)} (${why ?: "not enough spec"})")
                if (!last) {
                    p.step++
                    continue
                }
                return if (atk.style == "melee") "scythe" else "tbr"
            }
            return if (atk.style == "melee") "scythe" else "tbr"
        }
    }

    private fun delayOf(a: String, first: Boolean, from: Int): Int =
        if ((a == "tbr" || a == "tba" || a.startsWith("zcb")) && !first) from else atkOf(a).delay

    /** Is the room over (her death)? */
    val over: Boolean
        get() = dead != null

    // ---- the tick

    fun tick() {
        t++
        val t = t
        for (h in hits.filter { it.t == t }) {
            hits.remove(h)
            if (h.a == "auto") {
                val vmax = (0.75 * MAX_HIT_OFF[min(leaks, MAX_HIT_OFF.size - 1)]).toInt()
                if (phase > 0 && (hp - vmax) * 100 < MAX * phase && h.stalled == null && h.p.veng && h.p.lastScy != t) {
                    val to = stallTo(h.p, t)
                    if (to != null && to > t) {
                        h.stalled = t
                        h.t = to
                        hits += h
                        L(t, "${h.p.name} stalls her attack to t$to (Vengeance up)")
                        continue
                    }
                }
                val p = h.p
                val pray = !p.veng
                val ch = autoChance(p, p.style)
                val maxes = if (pray) MAX_HIT_ON else MAX_HIT_OFF
                val d = if (rng.random() < ch) rng.randint(0, maxes[min(leaks, MAX_HIT_ON.size - 1)]) else 0
                L(t, "her attack on ${p.name}${if (pray) " (praying)" else ""}: $d")
                events.herHit(t, p, d)
                if (d >= 1 && p.veng) {
                    val v = (d * 0.75).toInt()
                    p.veng = false
                    events.veng(t, p, v)
                    damage(t, v, "${p.name} Vengeance", "veng", h.stalled != null, p)
                    recast(p, t)
                }
                continue
            }
            if (h.a == "thrall") {
                val tg = h.p.lastTgt
                if (tg is Crab) {
                    if (tg.alive) {
                        tg.hp -= h.amt
                        events.thrall(t, h.p, tg, h.amt)
                        if (tg.hp <= 0) {
                            tg.alive = false
                            tg.died = t
                            events.crabDies(t, tg)
                        }
                        L(t, "${h.p.name} thrall on ${tg.key} ${h.amt}${if (tg.alive) "" else " (dies)"}")
                    }
                } else {
                    events.thrall(t, h.p, null, h.amt)
                    damage(t, h.amt, "${h.p.name} thrall", "thrall", by = h.p)
                }
                thrall(h.p, t + 4)
                continue
            }
            if (h.a == "barrage") {
                barrageLands(t, h)
                continue
            }
            if (h.a == "aoe") {
                var herD = 0
                val parts = ArrayList<String>()
                for (x in h.parts!!) {
                    if (x.maiden) {
                        herD += x.d
                        parts += "Maiden ${x.d}"
                        continue
                    }
                    val c = x.c!!
                    if (!c.alive && c.leaked == t - 1 && c.died == null) {
                        c.hp -= x.d
                        val hh = heals.indexOfFirst { it.third == c.key && it.first == t }
                        if (hh >= 0) heals[hh] = Triple(t, 2 * max(0, c.hp), c.key)
                        events.hitCrab(t, h.p, c, x.d)
                        parts += "${c.key} ${x.d} (as it explodes)"
                        continue
                    }
                    if (!c.alive) {
                        parts += "${c.key} gone"
                        continue
                    }
                    c.hp -= x.d
                    events.hitCrab(t, h.p, c, x.d)
                    if (c.hp <= 0) {
                        c.alive = false
                        c.died = t
                        events.crabDies(t, c)
                    }
                    parts += "${c.key} ${x.d}${if (c.alive) "" else " (dies)"}"
                }
                L(t, "${h.p.name} ${h.label} lands: ${parts.joinToString(", ")}")
                if (herD != 0) damage(t, herD, "${h.p.name} ${h.label}", h.kind, by = h.p)
                continue
            }
            if (h.a == "scythe") {
                h.p.lastScy = t
                scyAt = t
            }
            if (h.a == "ralos") {
                val partsD = IntArray(2)
                for (k in 0..1) {
                    val (ch, mx) = ranged("Tonalztics of Ralos", def, 1.5)
                    val m = (mx * 0.75).toInt()
                    val d = roll(ch, m)
                    partsD[k] = d
                    if (d >= 1) lowerDef(t, "${h.p.name} Ralos Spec hit ${k + 1}", MAIDEN_MAGIC / 8)
                }
                damage(t, partsD[0] + partsD[1], "${h.p.name} Ralos Spec ${partsD[0]}/${partsD[1]}", "ralos", by = h.p)
                continue
            }
            if (h.ruby) {
                val d = min(110, max(0, hp) * 22 / 100)
                damage(t, d, "${h.p.name} ${h.label} ruby", h.a, by = h.p)
                continue
            }
            damage(t, h.amt, "${h.p.name} ${h.label}", h.a, by = h.p)
        }
        if (dead != null) return
        applyHeals(t)
        if (proc == t && crabs.isEmpty()) {
            spawn(t, 70)
            spawn70 = t
            for (p in ps) {
                p.seq = p.seq70
                p.step = 0
                p.lostLast = false
                p.pending = null
            }
        }
        if (proc50 == t && spawn50 == null) {
            spawn(t, 50)
            spawn50 = t
            val early = t - proc!! < EARLY50_TICKS
            if (early) {
                early50 = true
                L(t, "the 50s came early")
            }
            for (p in ps) {
                val left = if (early) p.seq.drop(p.step).filter { it.startsWith("ib:") }.map { "$it@70" } else emptyList()
                p.seq = if (early && p.i in EARLY50_S1) (listOf("scyk:S1@50") + p.seq50).toMutableList() else (left + p.seq50.filter { !(left.isNotEmpty() && it == "ib:S1") }).toMutableList()
                p.step = 0
                p.lostLast = false
                p.pending = null
            }
        }
        if (proc30 == t && spawn30 == null) {
            spawn(t, 30)
            spawn30 = t
            for (p in ps) {
                p.seq = p.seq30
                p.step = 0
                p.lostLast = false
                p.pending = null
            }
        }
        moveCrabs(t)
        // ---- her attack
        if (t >= FIRST_ATTACK && (t - FIRST_ATTACK) % ATTACK_EVERY == 0) {
            val on = pick(t)
            hits += Hit(t + AUTO_DELAY, on, "auto")
            events.herAttack(t, on, t + AUTO_DELAY)
        }
        // ---- attacks
        for (p in ps) {
            if (t >= p.regenAt) {
                p.spec = min(100, p.spec + 10)
                p.regenAt += REGEN_EVERY
            }
            if (dead != null) break
            if (!p.veng && p.vengOn && t >= p.vengReady) {
                p.veng = true
                p.vengReady = t + VENG_CD
                L("${p.name} recasts Vengeance")
            }
            if (p.thralls && p.prefireT != null && p.thrallAt == null) p.thrallAt = rng.randint(p.prefireT!! + 1, max(p.prefireT!! + 1, p.next - 1))
            if (t == p.thrallAt) {
                L("${p.name} summons a thrall")
                events.summon(t, p)
                thrall(p, t + rng.randint(1, 3))
            }
            if (t < p.next) continue
            if (p.pending == null) {
                // the testing rules: a walking 50s S1 / N1
                val chase = crabs.firstOrNull { x -> CHASE[x.key] == p.i && x.alive && t > x.frozenTo && x.hp > CHASE_HP }
                if (chase != null) {
                    if (!gate(p, t, "scyc:${chase.key}")) continue
                    L("${p.name} goes after ${chase.key} (${chase.hp} HP, walking)")
                    crabHit(t, p, "scyc:${chase.key}", "scyc")
                    continue
                }
                if (phase == 50 && proc != null && p.i == EARLY50_STOPPER && t + 1 < proc!! + EARLY50_TICKS && lineOf(p) == "scythe") {
                    val s1 = crabRef("S1@70")
                    val (ch, mx) = melee("Scythe of vitur", 1, def)
                    val (sm, sv) = MaidenNumbers.scytheMV(ch, mx)
                    val n = 1 + ps.count { q -> q !== p && q.next <= t && lineOf(q) == "scythe" }
                    val due0 = hits.filter { it.t <= t + 1 && it.exp != null }
                    val vengs = hits.filter { it.a == "auto" && it.t <= t + 1 && it.p.veng }
                    val mean = due0.sumOf { it.exp!! } + n * sm + vengs.size * 0.75 * MAX_HIT_OFF[min(leaks, MAX_HIT_OFF.size - 1)] / 2 * 0.8
                    val sd = sqrt(due0.sumOf { it.v ?: 0.0 } + n * sv)
                    if (s1 != null && s1.alive && (hp - mean - EARLY50_Z * sd) * 100 < MAX * 50) {
                        if (!gate(p, t, "scyc:S1@70")) continue
                        L("${p.name} scythes the standalone S1@70 (the 50s at risk of coming early)")
                        crabHit(t, p, "scyc:S1@70", "scyc")
                        continue
                    }
                }
                // freezers stand for the 50s / 30s proc
                run {
                    val nextList = if (phase == 50) p.seq50 else if (phase == 30) p.seq30 else null
                    val cur = p.seq[min(p.step, p.seq.size - 1)]
                    if (nextList != null && isFreeze(nextList[0]) && !isFreeze(cur) && cur != "lose") {
                        val due = HOLD[nextList[0]] ?: 1
                        val s = atkOf(cur).speed
                        val by = t + s - due - 1
                        if (s > 1 && procRisk(t, by, phase)) {
                            if (!p.standing) L("${p.name} stands for the ${phase}s proc")
                            p.standing = true
                            return@run true
                        }
                        p.standing = false
                    }
                    false
                }.let { if (it) continue }
                val nx = p.seq[min(p.step, p.seq.size - 1)]
                val lastProc = proc30 ?: proc50 ?: proc
                if (nx == "zcbg" && !(scyAt == t && phase == 30)) continue
                if (lastProc == t && atkOf(nx).crab) continue
                val hk = nx.split('@')[0]
                if (lastProc != null && HOLD[hk] != null && t < lastProc + HOLD.getValue(hk)) continue
                PLUS_HOLD.find(nx)?.let { m -> if (lastProc != null && t < lastProc + m.groupValues[1].toInt()) return@let true else null }?.let { continue }
            }
            val first = p.step == 0 && !p.started
            val a = p.pending ?: nextAct(p, t)
            if (!gate(p, t, a)) {
                p.pending = a
                continue
            }
            p.pending = null
            p.started = true
            if (first) p.prefireT = t
            act(p, t, a, first)
        }
    }

    private fun act(p: P, t: Int, a: String, first: Boolean) {
        val atk = atkOf(a)
        val k = a.substringBefore(':')
        if (a == "lose") {
            L("${p.name} loses a tick")
            p.next = t + 1
            return
        }
        if (k == "scyc" || k == "scyk" || k == "scys" || (k == "chally" && crabOf(a) != null)) {
            crabHit(t, p, a, k)
            return
        }
        if (atk.crab) {
            val id = crabOf(a)!!
            val c = barragePrim(id, t)
            val d = if (c == null) 7 else if (phase == 0 && id in listOf("S2", "S3", "N3")) 1 else distTo(p, c)
            val targets = barrageCast(t, p, id, k)
            if (targets != null) {
                p.lastTgt = targets[0]
                if (d == 1) p.pos = intArrayOf(targets[0].x + 2, targets[0].y + 1)
                hits += Hit(t + magicDelay(d), p, "barrage").also {
                    it.k = k
                    it.crab = id
                    it.targets = targets
                }
                events.attack(t, p, a, targets[0], atk.code, t + magicDelay(d))
            } else {
                L("${p.name} ${FSPELL.getValue(k).name} on $id: gone")
            }
            p.style = "magic"
            p.next = t + atk.speed
            return
        }
        val aa = if (k == "scyf") "scythe" else if (a == "ralosl") "ralos" else a
        fun go(h: Hit.() -> Unit) {
            val lands = t + delayOf(if (k == "scyf") "scythe" else if (k == "zcbg" || k == "zcbx") "zcbs" else a, first, p.tbowFrom)
            hits += Hit(lands, p, aa).also {
                it.label = atk.label
                it.h()
            }
            events.attack(t, p, a, null, atk.code, lands)
        }
        val tag = if (first) " (prefire)" else ""
        p.lastTgt = "maiden"
        p.pos = if (atk.style == "melee") p.spot else intArrayOf(7 + if (first) 10 else if (p.tbowFrom == 4) 10 else if (p.tbowFrom == 3) 7 else 2, 10 + p.i % 4)
        if (atk.spec > 0) {
            p.spec -= atk.spec
            L("${p.name} ${atk.label} (${p.spec}% left)")
        }
        when (a) {
            "tbr", "tba", "bp" -> {
                val (ch, mx) = ranged(atk.weapon!!, def, accurate = a == "tba")
                val d = roll(ch, mx)
                val mv = MaidenNumbers.hitMV(ch, mx)
                go {
                    amt = d
                    exp = mv.first
                    v = mv.second
                    label = "${atk.label}$tag"
                }
            }
            "zcbs", "zcbg", "zcbx", "zcbf" -> {
                val (ch) = ranged(atk.weapon!!, def, 2.0)
                val ok = rng.random() < ch
                val rb = min(110, max(0, hp) * 22 / 100)
                go {
                    if (ok) {
                        ruby = true
                        acc = ch
                        label = "ZCB spec$tag"
                    } else {
                        amt = 0
                        label = "ZCB spec misses"
                    }
                    exp = ch * rb
                    v = ch * (1 - ch) * rb * rb
                }
            }
            "ralos", "ralosl" -> go {}
            "maul", "bgs" -> {
                val (ch, mx0) = if (a == "maul") melee(atk.weapon!!, 2, def, 1.25) else melee(atk.weapon!!, 1, def, 2.0)
                val mx = if (a == "maul") mx0 else floor(floor(mx0 * 1.1) * 1.1).toInt()
                val ok = rng.random() < ch
                val d = if (ok) max(1, rng.randint(0, mx)) else 0
                if (ok) lowerDef(t, "${p.name} ${atk.label}", if (a == "maul") (def * 0.35).toInt() else d)
                go {
                    amt = d
                    label = "${atk.label}${if (ok) "" else " misses"}"
                }
            }
            "chally", "challyf" -> {
                val (c1, mx) = melee(atk.weapon!!, 1, def)
                val ch = doubleArrayOf(c1, melee(atk.weapon, 1, def, 0.75).first)
                val parts = ch.map { c -> if (rng.random() < c) max(1, rng.randint(0, mx)) + mx / 10 else 0 }
                go {
                    amt = parts[0] + parts[1]
                    label = "Halberd spec ${parts.joinToString("/")}"
                }
            }
            else -> {
                val (ch, mx) = melee(atk.weapon ?: "Scythe of vitur", 1, def)
                val parts = intArrayOf(mx, mx / 2, mx / 4).map { m -> roll(ch, m) }
                val mv = MaidenNumbers.scytheMV(ch, mx)
                go {
                    amt = parts.sum()
                    exp = mv.first
                    v = mv.second
                    label = "scythe ${parts.joinToString("/")}"
                }
            }
        }
        p.style = atk.style ?: p.style
        p.next = t + atk.speed
    }

    /**
     * What attack `a` is on, as the room's tiles [x0, y0, x1, y1] (her 6x6 or a crab's 2x2), and how far it reaches:
     * the weapon's own range ([RANGE]: melee next to it, a halberd 2, the bow and spells 10, the blowpipe 5, ...).
     * Null: not an attack (nothing to be in range of).
     */
    /** Where crab [c] stands [n] ticks on (it walks one tile a tick unless frozen, and stops by her). */
    fun crabAhead(c: Crab, n: Int): IntArray {
        var x = c.x
        var y = c.y
        for (k in 1..n) {
            val at = t + k
            if (!c.alive || at <= c.born || at <= c.frozenTo || adjacent(x, y)) continue
            val sw = y + 1
            x -= 1
            if (sw < swRow) y += 1 else if (sw > swRow) y -= 1
        }
        return intArrayOf(x, y)
    }

    fun reach(p: P, a0: String, lead: Int = 0): Pair<IntArray, Int>? {
        val a = a0.substringBefore('|').let { if (it == "fin" || it.startsWith("fin:") || it.startsWith("s1if")) "scythe" else it }
        val k = a.substringBefore(':')
        val atk = ATTACKS[k] ?: return null
        if (atk.style == null) return null
        val ref = crabOf(a)
        val crab =
            when {
                ref == null -> null
                atk.crab && atk.style == "magic" -> barragePrim(ref, t)
                else -> crabRef(ref)
            }
        val box = crab?.let { val (x, y) = if (lead > 0) crabAhead(it, lead).let { a -> a[0] to a[1] } else it.x to it.y; intArrayOf(x, y, x + 1, y + 1) } ?: intArrayOf(her.x, her.y, mx1, my1)
        return box to (RANGE[k] ?: if (atk.style == "melee") 1 else 10)
    }

    /**
     * The nearest a player stands to attack `a` from: the bow is fired from range, not from next to her (players walk out
     * from the scythe to 5-9 tiles in its 5 ticks, blert's 5-man 1:01.2s) - except the opening shot, fired on the run in.
     */
    fun minReach(p: P, a0: String): Int {
        val k = a0.substringBefore('|').substringBefore(':')
        return if ((k == "tbr" || k == "tba") && p.started && crabOf(a0) == null) TBOW_MIN else 1
    }

    /**
     * How far out a player with time to spare stands for attack `a`: the bow from 9 (most of blert's bow shots on her go
     * from 9-10 tiles), the blowpipe from 4 (blert's go from 3-5, none from next to her), else anywhere in range ([minReach]). Closer than this, they walk back out to their spot.
     */
    /** The north-edge bow tile for a [P.northBow] player: 9 north of her, 5-10 east of her west edge (Blert's most used). */
    fun northBowTile(p: P, a0: String): IntArray? {
        val k = a0.substringBefore('|').substringBefore(':')
        if (!p.northBow || !p.started || (k != "tbr" && k != "tba") || crabOf(a0) != null) return null
        return intArrayOf(her.x + NORTH_BOW_DX[p.i - 2], her.y - TBOW_STAND)
    }

    /** Whether her next proc (70/50/30) likely comes within [ticks], at the team's usual damage. */
    fun procSoon(ticks: Int): Boolean = phase in intArrayOf(70, 50, 30) && (hp - AHEAD_DPS * ticks) * 100 < MAX * phase

    fun standsFrom(p: P, a0: String): Int {
        val k = a0.substringBefore('|').substringBefore(':')
        return if ((k == "tbr" || k == "tba") && crabOf(a0) == null) TBOW_STAND else if (k == "bp") BP_STAND else minReach(p, a0)
    }

    /** Where this player's attack `a` goes from (the sim's own spots): their tile on her, by the crab, or at range. */
    fun spotFor(p: P, a0: String): IntArray? {
        val a = a0.substringBefore('|').let { if (it == "fin" || it.startsWith("fin:") || it.startsWith("s1if")) "scythe" else it }
        val k = a.substringBefore(':')
        val atk = ATTACKS[k] ?: return null
        val ref = crabOf(a)
        if ((k == "scyc" || k == "scyk" || k == "scys") && ref != null) {
            val prim = crabRef(ref) ?: return null
            val side = if (k == "scys") a.split(':').getOrNull(2) else null
            return if (side == "N") intArrayOf(prim.x, prim.y - 1) else if (side == "S") intArrayOf(prim.x, prim.y + 2) else intArrayOf(prim.x + 2, prim.y + 1)
        }
        if (k == "chally" && ref != null) {
            val prim = crabRef(ref) ?: return null
            return intArrayOf(prim.x + 2, prim.y + 1)
        }
        if (atk.crab) return spotOf(p)
        if (atk.style == "melee") return p.spot
        return intArrayOf(7 + if (!p.started) 10 else if (p.tbowFrom == 4) 10 else if (p.tbowFrom == 3) 7 else 2, 10 + p.i % 4)
    }

    // ---- the sim's tables (engine/maiden.js)

    class Atk(val label: String, val weapon: String?, val style: String?, val speed: Int, val delay: Int, val spec: Int = 0, val code: String, val crab: Boolean = false)

    class Spell(val spell: String, val name: String, val ticks: Int, val aoe: Boolean)

    class Tile(val x: Int, val y: Int, val size: Int)

    private object HER

    companion object {
        val HP = mapOf(5 to 3500, 4 to 3062, 3 to 2625, 2 to 2625, 1 to 2625)
        val CRAB_HP = mapOf(5 to 100, 4 to 87, 3 to 75, 2 to 75, 1 to 75)
        const val CRAB_DEF = 100
        const val CRAB_MAGIC = 100
        const val MAIDEN_MAGIC = 350
        val MAIDEN_TILE = Tile(2, 9, 6)
        val SPAWNS = linkedMapOf("N1" to (13 to 1), "N2" to (17 to 1), "N3" to (21 to 1), "N4a" to (25 to 1), "N4b" to (25 to 3), "S1" to (13 to 21), "S2" to (17 to 21), "S3" to (21 to 21), "S4a" to (25 to 21), "S4b" to (25 to 19))
        const val CHUNK_X0 = 0
        const val CHUNK_Y0 = 3
        const val CHUNK = 8
        const val FREEZE_IMMUNE = 5
        const val FREEZE_SURE = (99 + 9) * 204
        const val DEATH_TICKS = 8
        const val FIRST_ATTACK = 9
        const val ATTACK_EVERY = 10
        const val AUTO_DELAY = 5
        const val VENG_CD = 50
        const val REGEN_EVERY = 50
        const val SURGE = 25
        const val BARRAGE_CAP = 9
        const val KILL_HALBERD = 0.5
        const val KILL_SKIP = 0.75
        const val EARLY50_TICKS = 20
        const val EARLY50_STOPPER = 4
        val EARLY50_S1 = setOf(3, 4)
        const val EARLY50_Z = 0.674
        const val CHASE_HP = 25
        /** The team's damage on her a tick, roughly (for [ahead]). */
        const val AHEAD_DPS = 40.0
        val CHASE = mapOf("S1@50" to 4, "N1@50" to 3)
        val HOLD = mapOf("ib:S2" to 7, "ib:N1" to 2, "ir:S2" to 2)
        /**
         * How far each weapon reaches, in tiles: the furthest any player attacked from with it in blert's 5-man 1:01.2
         * Maidens (53 raids) - the bow 10, the blowpipe 5, Ralos 7, the Zaryte crossbow 8; melee 1, the halberd 2.
         */
        val RANGE = mapOf("tbr" to 10, "tba" to 10, "bp" to 5, "zcbs" to 8, "ralos" to 7, "ralosl" to 7, "chally" to 2, "challyf" to 2)
        const val TBOW_MIN = 5
        const val TBOW_STAND = 9
        val NORTH_BOW_DX = intArrayOf(8, 6, 10)
        const val BP_STAND = 4
        val PREFIRE_TICK = mapOf("tbow" to 5, "zcb" to 6, "ralos" to 6, "ralosl" to 5)
        val CASTER_SPOTS = arrayOf(intArrayOf(14, 10), intArrayOf(14, 13), intArrayOf(14, 7), intArrayOf(14, 16), intArrayOf(14, 4))
        val MAX_HIT_ON = intArrayOf(18, 20, 21, 23, 25, 27, 29, 30, 32, 34, 36, 38, 39, 41, 43)
        val MAX_HIT_OFF = intArrayOf(36, 40, 43, 47, 50, 54, 58, 61, 65, 68, 72, 76, 79, 83, 86)
        val CLUMP = listOf("S3", "S4a", "S4b", "N4a", "N4b")
        val FOURS = listOf("N4a", "N4b", "S4a", "S4b")
        private val FREEZE_RE = Regex("^(ib|ibs|ibl|ibu|ir):")
        private val SCYTHE_LINE = Regex("^(scythe|fin|qfin|tdps|qscy|finz)")
        private val PLUS_HOLD = Regex("""\+(\d+)$""")

        fun magicDelay(d: Int) = 1 + (1 + d) / 3

        val FSPELL =
            mapOf(
                "ib" to Spell("barrage", "Ice barrage", 32, true),
                "ibs" to Spell("barrage", "Ice barrage", 35, true),
                "ibl" to Spell("blitz", "Ice blitz", 24, false),
                "ibu" to Spell("burst", "Ice burst", 16, true),
            )

        val ATTACKS =
            mapOf(
                "tbr" to Atk("Tbow (rapid)", "Twisted bow", "range", 5, 4, code = "TB"),
                "tba" to Atk("Tbow (accurate)", "Twisted bow", "range", 6, 4, code = "TB"),
                "bp" to Atk("Blowpipe", "Toxic blowpipe", "range", 2, 2, code = "BP"),
                "surge" to Atk("Surge potion (+25% spec)", null, null, 0, 0, code = ""),
                "zcbs" to Atk("ZCB spec", "Zaryte crossbow", "range", 5, 3, 75, "ZCB"),
                "ralos" to Atk("Ralos spec (rapid)", "Tonalztics of Ralos", "range", 6, 3, 50, "Ra"),
                "ralosl" to Atk("Ralos spec (long range)", "Tonalztics of Ralos", "range", 7, 3, 50, "Ra"),
                "maul" to Atk("Elder maul spec", "Elder maul", "melee", 6, 1, 50, "EM"),
                "bgs" to Atk("BGS spec", "Bandos godsword", "melee", 6, 1, 50, "BGS"),
                "scythe" to Atk("Scythe", "Scythe of vitur", "melee", 5, 1, code = "S"),
                "lose" to Atk("Lose 1 tick", null, null, 1, 0, code = ""),
                "ib" to Atk("Ice barrage", null, "magic", 5, 0, code = "IB", crab = true),
                "ibs" to Atk("Ice barrage (sceptre)", null, "magic", 5, 0, code = "IBS", crab = true),
                "ibl" to Atk("Ice blitz", null, "magic", 5, 0, code = "IBL", crab = true),
                "ibu" to Atk("Ice burst", null, "magic", 5, 0, code = "IBU", crab = true),
                "s1if" to Atk("Scythe (the 70s S1 if she is under X%)", "Scythe of vitur", "melee", 5, 1, code = "S"),
                "fin" to Atk("Scythe, then the halberd as the last hit", "Scythe of vitur", "melee", 5, 1, code = "S"),
                "challyf" to Atk("Halberd spec (forced)", "Crystal halberd", "melee", 7, 1, code = "HB"),
                "chally" to Atk("Halberd spec", "Crystal halberd", "melee", 7, 1, 30, "HB"),
                "scyk" to Atk("Scythe (crab, to kill)", "Scythe of vitur", "melee", 5, 1, code = "S", crab = true),
                "scyc" to Atk("Scythe (crab)", "Scythe of vitur", "melee", 5, 1, code = "S", crab = true),
                "scys" to Atk("Scythe (crab + her)", "Scythe of vitur", "melee", 5, 1, code = "S", crab = true),
            )

        fun atkOf(a: String): Atk = ATTACKS.getValue(a.substringBefore(':'))

        fun crabOf(a: String): String? = if (a.contains(':')) a.split(':')[1].replace(Regex("""\+\d+$"""), "") else null

        fun hitLabel(a: String): String = crabOf(a)?.let { "${atkOf(a).label} -> $it" } ?: ATTACKS[a]?.label ?: a
    }
}
