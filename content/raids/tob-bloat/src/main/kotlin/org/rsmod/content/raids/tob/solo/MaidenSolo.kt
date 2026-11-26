package org.rsmod.content.raids.tob.solo

import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid
import kotlin.math.floor
import kotlin.math.min

/**
 * The Maiden of Sugadinti (trio-and-under numbers: 2625 hitpoints, crabs 75, blood spawns 90), for the owner and any
 * bots raiding with them: each attack goes at one of the party at random.
 *
 * - She attacks every 10 ticks from tick 9, at whoever is closest to her centre (ties to the first in the party): Blackstorm at you (36 + 3.5 per crab that reached her; Protect from Magic
 *   halves it; half the time it drains the stat you're attacking with), or blood splats - one on your tile and two more
 *   in a 5x5 round you - after which she can't throw blood for her next two attacks.
 * - A splat hurts and heals her while you stand on it (10 + 2 per crab) and drains prayer; when it dries there's a
 *   chance (10%, 20% if stood on) a blood spawn rises from it. Blood spawns wander leaving a trail that does the same.
 * - At 70%, 50% and 30% two Nylocas Matomenos a player come in from the north and south sides and walk to her (one tile a
 *   tick, frozen ones stay put); one that reaches her heals her twice its hitpoints and makes her hit harder.
 */
class MaidenSolo(owner: Player, session: InstanceSession, d: SoloDeps, origin: CoordGrid, copyBase: CoordGrid) :
    SoloRoom(TobRoom.MAIDEN, owner, session, d, origin, copyBase) {
    override val entry = CoordGrid(3186, 4446, 0)
    override val chest = CoordGrid(3188, 4448, 0) to LocAngle.West

    override fun inArena(c: CoordGrid): Boolean = c.x in 3160..3184 && c.z in 4435..4458

    private lateinit var maiden: Npc
    /** Procs so far: 1 after the 70s, 2 the 50s, 3 the 30s. */
    var phase = 0
        private set
    private var leaks = 0
    private var throwCooldown = 0
    private var dead = -1

    /** What crabs that reached her healed her, this run, and what her blood did. */
    var healed = 0
        private set

    var bled = 0
        private set

    private class Crab(val npc: Npc, val spot: Int, val born: Int, val wave: Int) {
        var arrived = false
        var leakedAt = -1
    }

    private class Splat(val tile: CoordGrid, val until: Int) {
        var stoodOn = false
    }

    private class Spawn(val npc: Npc) {
        var last: CoordGrid? = null
    }

    private val crabs = ArrayList<Crab>()
    private val splats = ArrayList<Splat>()
    private val incoming = HashMap<CoordGrid, Int>()
    private val spawns = ArrayList<Spawn>()
    private val trails = LinkedHashMap<CoordGrid, Pair<LocInfo, Int>>()

    override fun build() {
        phase = 0
        leaks = 0
        throwCooldown = 0
        dead = -1
        healed = 0
        bled = 0
        crabs.clear()
        splats.clear()
        incoming.clear()
        autos.clear()
        spawns.clear()
        trails.clear()
        maiden = spawn(PHASES[0], CoordGrid(X, Y, 0), hp(HP, entryHp(2500)))
    }

    override fun floor(npc: Npc): Int? = if (npc === maiden && dead < 0) 1 else null

    override fun land(npc: Npc, hit: Hit): Int? {
        val replayed = replay?.let { replayed(npc, hit, it) }
        if (npc !== maiden) return replayed
        if (dead >= 0) return 0
        val damage = replayed ?: hit.damage
        if (TALLY) {
            val who = attacker(hit)?.username?.filter { it.isLetter() } ?: "other"
            tally.merge("$phase:$who:${hit.type}", damage, Int::plus)
            tally.merge("$phase:$who:n", 1, Int::plus)
        }
        // the crabs come on the tick the hit that takes her past 70 / 50 / 30% lands (Blert: same tick, and she
        // takes the whole hit)
        phases(maiden.hitpoints - damage)
        return replayed
    }

    /** A sim hit to play back (`TOB_MAIDEN_REPLAY`, a run with no blood throws, as the sim has none): its landing tick, whose, on what, and its hitsplats in order. */
    private class SimHit(val t: Int, val who: String, val on: String, val kind: String, val splats: ArrayDeque<Int>)

    private val replay: MutableList<SimHit>? =
        System.getenv("TOB_MAIDEN_REPLAY")?.let { java.io.File(it).readLines() }?.filter { it.isNotBlank() }?.mapTo(ArrayList()) { l ->
            val f = l.split(' ')
            SimHit(f[0].toInt(), f[1], f[2], f[3], ArrayDeque(f[4].split('/').map { it.toInt() }))
        }

    /**
     * Tests: the damage the sim's run rolled for this hit - the same player's (any thrall's) of the same kind on the
     * same target landing within a tick of now - so a run with the sim's spawns and rolls can be lined up tick by tick.
     */
    private fun replayed(npc: Npc, hit: Hit, hits: MutableList<SimHit>): Int? {
        val on = if (npc === maiden) "her" else crabs.firstOrNull { it.npc === npc }?.let { "${SPOT_NAMES[it.spot]}@${it.wave}" } ?: return null
        val p = attacker(hit)
        val who = p?.username?.filter { it.isLetter() }?.let { if (it == "ranger") "range" else it }
        val kind = if (p == null) "thrall" else if (hit.type == org.rsmod.game.hit.HitType.Typeless) "veng" else "atk"
        val near = hits.filter { it.kind == kind && (kind == "thrall" || it.who == who) && kotlin.math.abs(it.t - t) <= if (kind == "thrall") THRALL_SLACK else REPLAY_SLACK }
        val e = (near.filter { it.on == on }.ifEmpty { near.filter { it.on == on.substringBefore('@') + "@?" } }).minByOrNull { kotlin.math.abs(it.t - t) }
        if (e == null) {
            println("replay t$t miss $who $kind on $on ours ${hit.damage}")
            return null
        }
        val dmg = e.splats.removeFirst()
        if (e.splats.isEmpty()) hits.remove(e)
        println("replay t$t $who $kind on $on ours ${hit.damage} sim $dmg (sim t${e.t})")
        return dmg
    }

    private val box: IntArray
        get() = intArrayOf(X, Y, X + SIZE - 1, Y + SIZE - 1)

    override fun step() {
        if (dead >= 0) {
            dying()
            return
        }
        if (maiden.hitpoints <= 1) {
            die()
            return
        }
        val party = party()
        phases()
        if (party.isNotEmpty() && t >= FIRST_ATTACK && (t - FIRST_ATTACK) % ATTACK_EVERY == 0) attack(target(party))
        crabs()
        splats(party)
        bloodSpawns()
    }

    /* ---- her phases and the crabs they bring */

    private fun phases(hp: Int = maiden.hitpoints) {
        val pct = hp * 100.0 / maiden.baseHitpointsLvl
        val want = when {
            pct > 70.0 -> 0
            pct > 50.0 -> 1
            pct > 30.0 -> 2
            else -> 3
        }
        while (phase < want) {
            phase++
            transmog(maiden, PHASES[phase])
            spawnCrabs()
        }
    }

    private fun spawnCrabs() {
        val count = (CRABS_EACH * party().size).coerceIn(CRABS_EACH, CRAB_SPOTS.size)
        val left = CRAB_SPOTS.indices.toMutableList()
        val forced = FORCED_SPAWNS?.getOrNull(phase - 1)
        repeat(forced?.size ?: count) { k ->
            val weights = if (UNIFORM_SPAWNS) List(CRAB_WEIGHTS.size) { 1 } else CRAB_WEIGHTS
            var roll = rand(left.sumOf { weights[it] })
            val spot = forced?.get(k) ?: left.first { i -> (roll < weights[i]).also { roll -= weights[i] } }
            left -= spot
            val (x, y) = CRAB_SPOTS[spot]
            val crab = spawn(CRAB, CoordGrid(x, y, 0), hp(CRAB_HP, ENTRY_CRAB_HP))
            // the Nylocas Matomenos wiki: a freeze can't miss them from an accurate enough caster
            crab.vars["varn.freeze_guaranteed"] = FREEZE_SURE_ON_ROLL
            crabs += Crab(crab, spot, t, PROC_PCT[phase - 1])
        }
    }

    private fun crabs() {
        val it = crabs.iterator()
        while (it.hasNext()) {
            val c = it.next()
            val n = c.npc
            if (!n.isSlotAssigned) {
                it.remove()
                continue
            }
            if (c.leakedAt >= 0) {
                if (t - c.leakedAt >= LEAK_DESPAWN) {
                    despawn(n)
                    it.remove()
                }
                continue
            }
            if (n.hitpoints <= 0 || c.arrived) continue
            if (gap(n) <= 1) {
                c.arrived = true
                continue
            }
            if (frozen(n)) continue
            // the game's plain NPC step at her south-west tile: diagonal, else along one axis
            stepToward(n, X, Y)
        }
    }

    /**
     * A crab that stood beside her through the tick's hits melts into her (Blert: she heals the tick after it arrives,
     * and a hit landing on it that tick kills it first, so she gets nothing).
     */
    override fun late() {
        for (c in crabs) {
            val n = c.npc
            if (!c.arrived || c.leakedAt >= 0 || !n.isSlotAssigned || n.hitpoints <= 0) continue
            c.leakedAt = t - 1
            leaks++
            healed += n.hitpoints * 2
            recordEvent(LEAK_EVENT, "\"npc\":{\"id\":${n.visType.id},\"roomId\":${n.slotId},\"hitpoints\":${(n.hitpoints shl 16) or n.baseHitpointsLvl}}")
            healNpc(maiden, n.hitpoints * 2)
            deathAnim(n)
            n.hitpoints = 0
        }
    }

    /** A crab beside her is melting into her from the tick after it arrives: hits already on the way still land. */
    override fun attackable(npc: Npc): Boolean = !(npc === maiden && dead >= 0) && crabs.none { it.npc === npc && it.arrived }

    private fun gap(n: Npc): Int {
        val r = real(n.coords)
        val gapX = maxOf(X - (r.x + n.size - 1), r.x - (X + SIZE - 1), 0)
        val gapZ = maxOf(Y - (r.z + n.size - 1), r.z - (Y + SIZE - 1), 0)
        return maxOf(gapX, gapZ)
    }

    /** A crab's death (and its melt into her when it reaches her): the Nylocas Matomenos death animation. */
    fun deathAnim(n: Npc) {
        n.anim(CRAB_DEATH)
    }

    // the room's clock, not the npc's: a crab spawned mid-tick has no clock of its own until the next npc pass
    private fun frozen(n: Npc): Boolean = n.vars["varn.freeze_end_clock"] > d.mapClock.cycle

    /* ---- her attacks */

    /** Whether her next step is an attack (as a bot sees it between ticks). */
    val attacksNext: Boolean
        get() = attacksAt(t)

    /** Whether she attacks on room tick [tick]. */
    fun attacksAt(tick: Int): Boolean = tick >= FIRST_ATTACK && (tick - FIRST_ATTACK) % ATTACK_EVERY == 0

    /** Her centre tile (real), and tiles from it: 3 is the red ring round her, 4 orange and so on. */
    val centre: CoordGrid
        get() = CoordGrid(X + CENTRE, Y + CENTRE, 0)

    fun centreDist(at: CoordGrid): Int = maxOf(kotlin.math.abs(at.x - centre.x), kotlin.math.abs(at.z - centre.z))

    private fun target(party: List<Player>): Player = party.withIndex().minWith(compareBy({ centreDist(real(it.value.coords)) }, { it.index })).value

    private fun attack(p: Player) {
        maiden.facePlayer(p)
        if (replay == null && !NO_THROWS && throwCooldown == 0 && chance(THROW_CHANCE)) {
            throwCooldown = THROW_COOLDOWN
            throwBlood(p)
            return
        }
        if (throwCooldown > 0) throwCooldown--
        maiden.anim(AUTO_SEQ)
        recordAttack(maiden, 1)
        val dist = boxDist(real(p.coords).x, real(p.coords).z, X, Y, X + SIZE - 1, Y + SIZE - 1)
        // Blert, real solos: the hit shows 4 ticks after her attack next to her, a tick later every ~3 tiles further
        val hitDelay = AUTO_DELAY + (dist - 1).coerceAtLeast(0) / 3
        // her shadow slides across the floor upright: no climb or drop, no arc
        proj(SHADOW_PROJ, maiden, p, delay = PROJ_START, speed = 0, startHeight = 0, endHeight = 0, angle = 0, offset = hitDelay * 30 - PROJ_START)
        val lands = hitDelay
        val max = floor(BASE_MAX + PER_LEAK * leaks).toInt()
        val roll = rand(0, max)
        // the drain is set on her attack tick, by the gear worn then (players flick a crossbow and quiver on for it)
        val drained = if (chance(0.5)) drainStats(p) else emptyList()
        autos[p] = d.mapClock.cycle + lands - 1 to (if (entryMode) max / 2 else max)
        later(lands) {
            val q = p.takeIf { it in party() } ?: return@later
            val dmg = if (prays(q, "magic")) roll / 2 else roll
            hurt(q, dmg, source = maiden)
            if (dmg > 0) for (stat in drained) q.statSub(stat, constant = (dmg + 1) / 5, percent = 0)
        }
    }

    /** The most her auto can hit now. */
    val autoMax: Int
        get() = floor(BASE_MAX + PER_LEAK * leaks).toInt().let { if (entryMode) it / 2 else it }

    /** Her autos on their way: the cycle each lands on its target (the queue it's held in takes it a tick early) and the most it can hit. */
    val autos = HashMap<Player, Pair<Int, Int>>()

    /** Her drain goes on the combat stats of the target's highest accuracy bonus. */
    private fun drainStats(p: Player): List<String> {
        val b = d.bonuses
        val melee = maxOf(b.offensiveStabBonus(p), b.offensiveSlashBonus(p), b.offensiveCrushBonus(p))
        val ranged = b.offensiveRangedBonus(p)
        val magic = b.offensiveMagicBonus(p)
        return when {
            ranged >= melee && ranged >= magic -> listOf("stat.ranged")
            magic >= melee -> listOf("stat.magic")
            else -> listOf("stat.attack", "stat.strength")
        }
    }

    /**
     * Her blood throw (the wiki): a spot on every player's tile, and three on the one standing furthest from her - theirs
     * and two beside it. Players step off as she throws, so the spots land where they stood.
     */
    private fun throwBlood(p: Player) {
        maiden.anim(THROW_SEQ)
        recordAttack(maiden, 2)
        val party = party()
        val tiles = LinkedHashSet<CoordGrid>()
        for (q in party) tiles += real(q.coords)
        val far = party.maxByOrNull { boxDist(real(it.coords).x, real(it.coords).z, X, Y, X + SIZE - 1, Y + SIZE - 1) } ?: p
        val at = real(far.coords)
        var extra = 0
        var tries = 0
        while (extra < FAR_EXTRA && tries++ < 40) {
            val c = CoordGrid(at.x + rand(-1, 1), at.z + rand(-1, 1), 0)
            if (inArena(c) && boxDist(c.x, c.z, X, Y, X + SIZE - 1, Y + SIZE - 1) > 0 && !blocked(c) && tiles.add(c)) extra++
        }
        for (tile in tiles) {
            val lands = projTiles(BLOOD_PROJ, CoordGrid(X + SIZE / 2, Y + SIZE / 2, 0), tile, delay = PROJ_START, speed = BLOOD_SPEED, startHeight = 110, endHeight = 0, angle = 40)
            incoming.merge(tile, 1, Int::plus)
            later(lands) {
                incoming.computeIfPresent(tile) { _, n -> (n - 1).takeIf { it > 0 } }
                gfx(SPLAT_GFX[rand(SPLAT_GFX.size)], tile)
                splats += Splat(tile, t + SPLAT_TICKS)
            }
        }
    }

    private fun splats(party: List<Player>) {
        val here = party.associateWith { real(it.coords) }
        val it = splats.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (t >= s.until) {
                it.remove()
                if (chance(if (s.stoodOn) 0.2 else 0.1)) {
                    val n = spawn(BLOOD_SPAWN, s.tile, hp(SPAWN_HP))
                    spawns += Spawn(n)
                }
                continue
            }
            for ((p, tile) in here) {
                if (tile != s.tile) continue
                s.stoodOn = true
                bleed(p)
            }
        }
        // the trails left by blood spawns
        for ((p, tile) in here) if (trails.containsKey(tile)) bleed(p)
        val dry = trails.entries.filter { t >= it.value.second }
        for (e in dry) {
            delLoc(e.value.first)
            trails.remove(e.key)
        }
    }

    /** Standing in her blood: it hurts, heals her 1:1 with the damage you take, and drains prayer. */
    private fun bleed(p: Player) {
        val dmg = rand(1, BLOOD_BASE + BLOOD_PER_LEAK * leaks)
        // she heals exactly what the blood takes from you, as it lands; sourceless, so Vengeance doesn't fire off it
        val dealt = hurt(p, dmg)
        val hpBefore = p.hitpoints
        later(1) {
            bled += minOf(dealt, hpBefore)
            healNpc(maiden, minOf(dealt, hpBefore))
        }
        p.statSub("stat.prayer", constant = dmg / 2 + 1, percent = 0)
    }

    private fun bloodSpawns() {
        val it = spawns.iterator()
        while (it.hasNext()) {
            val s = it.next()
            val n = s.npc
            if (!n.isSlotAssigned || n.hitpoints <= 0) {
                if (!n.isSlotAssigned) it.remove()
                continue
            }
            val r = real(n.coords)
            if (s.last != null && s.last != r && !trails.containsKey(s.last!!)) {
                trails[s.last!!] = loc(TRAIL, s.last!!, shape = LocShape.CentrepieceStraight) to t + TRAIL_TICKS
            }
            s.last = r
            if (n.routeDestination.isEmpty() && !frozen(n)) {
                var tries = 0
                while (tries++ < 20) {
                    val c = CoordGrid(r.x + rand(-4, 4), r.z + rand(-4, 4), 0)
                    if (inArena(c) && boxDist(c.x, c.z, X, Y, X + SIZE - 1, Y + SIZE - 1) > 1 && !blocked(c)) {
                        n.walk(at(c))
                        break
                    }
                }
            }
        }
    }

    /* ---- the end */

    private fun die() {
        dead = t
        maiden.hitpoints = 0
        maiden.anim(DEATH_A)
        for (c in crabs) if (c.npc.isSlotAssigned) despawn(c.npc)
        for (s in spawns) if (s.npc.isSlotAssigned) despawn(s.npc)
        crabs.clear()
        spawns.clear()
        splats.clear()
        mes("The Maiden of Sugadinti is dead.")
    }

    private fun dying() {
        when (t - dead) {
            DEATH_B_AT -> {
                transmog(maiden, DYING_B)
                maiden.anim(DEATH_B)
            }
            DEATH_B_AT + DEATH_B_TICKS -> despawn(maiden)
            DEATH_END -> {
                cleared = true
                mes("The way on is open: pass the barrier to go on to the Pestilent Bloat.")
            }
        }
    }

    /** A crab as a bot sees it: its spawn and proc tick, tiles from her, the clock its freeze ends on, whether it's melting in. */
    class CrabView(val npc: Npc, val spot: String, val born: Int, val gap: Int, val frozenUntil: Int, val arrived: Boolean)

    fun crabViews(): List<CrabView> =
        crabs.filter { it.npc.isSlotAssigned && it.npc.hitpoints > 0 && it.leakedAt < 0 }
            .map { CrabView(it.npc, SPOT_NAMES[it.spot], it.born, gap(it.npc), it.npc.vars["varn.freeze_end_clock"], it.arrived) }

    /** `TOB_TRIO_TRACE`: her damage taken by phase, attacker and hit type, for comparing with the sim. */
    val tally = java.util.TreeMap<String, Int>()

    /** Her blood on [tile] (a real tile), or a spot of hers still in the air for it. */
    fun bloody(tile: CoordGrid): Boolean = splats.any { it.tile == tile } || trails.containsKey(tile) || incoming.containsKey(tile)

    /** A live blood spawn on [tile] (a real tile). */
    fun spawnOn(tile: CoordGrid): Boolean = spawns.any { s -> s.npc.isSlotAssigned && s.npc.hitpoints > 0 && real(s.npc.coords) == tile }

    /** Whether [tile] (a real tile) is under her. */
    fun under(tile: CoordGrid): Boolean = tile.x in X until X + SIZE && tile.z in Y until Y + SIZE

    /** Whether a player on [tile] (a real tile) can hit her in melee: beside her, not on a corner. */
    /** Tiles from [tile] to her nearest edge (1: beside her). */
    fun gapToHer(tile: CoordGrid): Int = maxOf(X - tile.x, tile.x - (X + SIZE - 1), Y - tile.z, tile.z - (Y + SIZE - 1), 0)

    fun besideHer(tile: CoordGrid): Boolean {
        val gx = maxOf(X - tile.x, tile.x - (X + SIZE - 1), 0)
        val gz = maxOf(Y - tile.z, tile.z - (Y + SIZE - 1), 0)
        return maxOf(gx, gz) == 1 && !(gx == 1 && gz == 1)
    }

    val boss: Npc
        get() = maiden

    /** The room tick she died on (-1 while she's up), and the crabs that reached her. */
    val deadAt: Int
        get() = dead

    val leaked: Int
        get() = leaks

    override fun autoTarget(): Npc? =
        if (System.getenv("TOB_LEAK") != null) maiden
        else crabs.firstOrNull { it.npc.isSlotAssigned && it.npc.hitpoints > 0 && it.leakedAt < 0 }?.npc ?: maiden

    override val autoStart: CoordGrid
        get() = CoordGrid(3174, 4446, 0)

    override fun barBoss(): Npc? = maiden

    override fun debug(): String = "maiden ${maiden.hitpoints} phase $phase crabs ${crabs.joinToString(",") { c -> real(c.npc.coords).let { "${it.x}/${it.z}:${c.npc.hitpoints}" } }} leaks $leaks splats ${splats.size} spawns ${spawns.size} trails ${trails.size} dead $dead"

    private companion object {
        const val X = 3162
        const val Y = 4444
        const val SIZE = 6
        const val CENTRE = 3
        val PROC_PCT = listOf(70, 50, 30)
        const val THRALL_SLACK = 3
        const val REPLAY_SLACK = 2
        const val HP = 2625
        val PHASES = listOf("npc.tob_maiden_100", "npc.tob_maiden_70", "npc.tob_maiden_50", "npc.tob_maiden_30")
        const val DYING_B = "npc.tob_maiden_dying_b"
        const val FIRST_ATTACK = 9
        const val ATTACK_EVERY = 10
        /** 1 in 3 once off cooldown, the community figure (Blert's real solos: 47 of 123, which fits). */
        const val THROW_CHANCE = 1.0 / 3

        /** Tests: no blood throws (`TOB_MAIDEN_NO_THROWS`), as the sim has none. */
        val NO_THROWS = System.getenv("TOB_MAIDEN_NO_THROWS") != null
        const val THROW_COOLDOWN = 2
        const val BASE_MAX = 36.5
        const val PER_LEAK = 3.5
        const val BLOOD_BASE = 10
        const val BLOOD_PER_LEAK = 2
        const val SPLAT_TICKS = 10
        const val FAR_EXTRA = 2
        const val TRAIL_TICKS = 42
        const val CRAB = "npc.maiden_elemental"
        const val CRAB_HP = 75
        const val ENTRY_CRAB_HP = 16
        const val CRABS_EACH = 2
        const val FREEZE_SURE_ON_ROLL = 2
        /** Blert's TOB_MAIDEN_CRAB_LEAK event. */
        const val LEAK_EVENT = 100
        const val LEAK_DESPAWN = 2
        const val BLOOD_SPAWN = "npc.maiden_blood_slug"
        const val SPAWN_HP = 90
        const val AUTO_SEQ = "seq.maiden_attack_special"
        const val THROW_SEQ = "seq.maiden_attack_blood"
        const val DEATH_A = "seq.maiden_death_a"
        const val DEATH_B = "seq.maiden_death_b"
        const val DEATH_B_AT = 5
        const val DEATH_END = 8
        const val DEATH_B_TICKS = 4
        const val SHADOW_PROJ = "spotanim.maiden_shadow_proj"
        const val BLOOD_PROJ = "spotanim.maiden_blood_proj"
        const val PROJ_START = 41
        const val AUTO_DELAY = 4
        const val BLOOD_SPEED = 15
        const val CRAB_DEATH = "seq.elemental_death"
        val SPLAT_GFX = listOf("spotanim.maiden_lingering_blood", "spotanim.maiden_lingering_blood_sw", "spotanim.maiden_lingering_blood_nw", "spotanim.maiden_lingering_blood_ne")
        const val TRAIL = "loc.tob_maiden_blood"

        /** Blert's ten crab spawns (N1-N4 outer, S1-S4 outer), and how often each came in Blert's 50 fastest trios. */
        val CRAB_SPOTS =
            listOf(
                3173 to 4456, 3177 to 4456, 3181 to 4456, 3185 to 4454, 3185 to 4456,
                3173 to 4436, 3177 to 4436, 3181 to 4436, 3185 to 4438, 3185 to 4436,
            )
        val CRAB_WEIGHTS = listOf(44, 66, 110, 109, 107, 59, 79, 107, 109, 104)

        /** `TOB_CRAB_UNIFORM`: every spawn as likely (the sim's draw), for comparing runs with it. */
        val UNIFORM_SPAWNS = System.getenv("TOB_CRAB_UNIFORM") != null
        val TALLY = System.getenv("TOB_TRIO_TRACE") != null

        /** `TOB_CRAB_SPAWNS` (tests, e.g. from a sim run): each wave's spots, `N2,N3,N4a,S1,S4a,S4b;...` - 70s;50s;30s. */
        val FORCED_SPAWNS: List<List<Int>>? by lazy { System.getenv("TOB_CRAB_SPAWNS")?.split(';')?.map { w -> w.split(',').map { SPOT_NAMES.indexOf(it.trim()) } } }
        val SPOT_NAMES = listOf("N1", "N2", "N3", "N4a", "N4b", "S1", "S2", "S3", "S4a", "S4b")
    }
}
