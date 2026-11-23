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
 * The Maiden of Sugadinti, solo (trio-and-under numbers: 2625 hitpoints, crabs 75, blood spawns 90).
 *
 * - She attacks every 10 ticks from tick 9: Blackstorm at you (36 + 3.5 per crab that reached her; Protect from Magic
 *   halves it; half the time it drains the stat you're attacking with), or blood splats - one on your tile and two more
 *   in a 5x5 round you - after which she can't throw blood for her next two attacks.
 * - A splat hurts and heals her while you stand on it (10 + 2 per crab) and drains prayer; when it dries there's a
 *   chance (10%, 20% if stood on) a blood spawn rises from it. Blood spawns wander leaving a trail that does the same.
 * - At 70%, 50% and 30% two Nylocas Matomenos come in from the north and south sides and walk to her (one tile a
 *   tick, frozen ones stay put); one that reaches her heals her twice its hitpoints and makes her hit harder.
 */
class MaidenSolo(owner: Player, session: InstanceSession, d: SoloDeps, origin: CoordGrid, copyBase: CoordGrid) :
    SoloRoom(TobRoom.MAIDEN, owner, session, d, origin, copyBase) {
    override val entry = CoordGrid(3186, 4446, 0)
    override val chest = CoordGrid(3188, 4448, 0) to LocAngle.West

    override fun inArena(c: CoordGrid): Boolean = c.x in 3160..3184 && c.z in 4435..4458

    private lateinit var maiden: Npc
    private var phase = 0
    private var leaks = 0
    private var throwCooldown = 0
    private var dead = -1

    private class Crab(val npc: Npc) {
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
    private val spawns = ArrayList<Spawn>()
    private val trails = LinkedHashMap<CoordGrid, Pair<LocInfo, Int>>()

    override fun build() {
        phase = 0
        leaks = 0
        throwCooldown = 0
        dead = -1
        crabs.clear()
        splats.clear()
        spawns.clear()
        trails.clear()
        maiden = spawn(PHASES[0], CoordGrid(X, Y, 0), hp(HP, entryHp(2500)))
    }

    override fun floor(npc: Npc): Int? = if (npc === maiden && dead < 0) 1 else null

    override fun land(npc: Npc, hit: Hit): Int? {
        if (npc !== maiden) return null
        if (dead >= 0) return 0
        // the crabs come on the tick the hit that takes her past 70 / 50 / 30% lands (Blert: same tick, and she
        // takes the whole hit)
        phases(maiden.hitpoints - hit.damage)
        return null
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
        val p = inside()
        phases()
        if (p != null && t >= FIRST_ATTACK && (t - FIRST_ATTACK) % ATTACK_EVERY == 0) attack(p)
        crabs(p)
        splats(p)
        bloodSpawns(p)
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
        val spots = CRAB_SPOTS.shuffled(java.util.Random(d.random.of(Int.MAX_VALUE).toLong())).take(CRABS)
        for ((x, y) in spots) {
            val n = spawn(CRAB, CoordGrid(x, y, 0), hp(CRAB_HP, ENTRY_CRAB_HP))
            crabs += Crab(n)
        }
    }

    private fun crabs(p: Player?) {
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
            healNpc(maiden, n.hitpoints * 2)
            deathAnim(n)
            n.hitpoints = 0
        }
    }

    /** A crab beside her is melting into her from the tick after it arrives: hits already on the way still land. */
    override fun attackable(npc: Npc): Boolean = crabs.none { it.npc === npc && it.arrived }

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

    private fun attack(p: Player) {
        maiden.facePlayer(p)
        if (throwCooldown == 0 && chance(THROW_CHANCE)) {
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
        val lands = hitDelay - 1
        val max = floor(BASE_MAX + PER_LEAK * leaks).toInt()
        val roll = rand(0, max)
        later(lands) {
            val q = inside() ?: return@later
            val dmg = if (prays(q, "magic")) roll / 2 else roll
            hurt(q, dmg, source = maiden)
            if (dmg > 0 && chance(0.5)) drain(q, (dmg + 1) / 5)
        }
    }

    /** Her drain goes on the stat you're attacking with (your weapon's style). */
    private fun drain(p: Player, amount: Int) {
        if (amount <= 0) return
        val weapon = p.worn[3]?.let { dev.openrune.ServerCacheManager.getItem(it.id)?.name?.lowercase() } ?: ""
        val stats =
            when {
                RANGED_WORDS.any { weapon.contains(it) } -> listOf("stat.ranged")
                MAGIC_WORDS.any { weapon.contains(it) } -> listOf("stat.magic")
                else -> listOf("stat.attack", "stat.strength")
            }
        for (s in stats) p.statSub(s, constant = amount, percent = 0)
    }

    private fun throwBlood(p: Player) {
        maiden.anim(THROW_SEQ)
        recordAttack(maiden, 2)
        val target = real(p.coords)
        val tiles = LinkedHashSet<CoordGrid>()
        tiles += target
        var tries = 0
        while (tiles.size < 3 && tries++ < 40) {
            val c = CoordGrid(target.x + rand(-2, 2), target.z + rand(-2, 2), 0)
            if (inArena(c) && boxDist(c.x, c.z, X, Y, X + SIZE - 1, Y + SIZE - 1) > 0 && !blocked(c)) tiles += c
        }
        for (tile in tiles) {
            val lands = projTiles(BLOOD_PROJ, CoordGrid(X + SIZE / 2, Y + SIZE / 2, 0), tile, delay = PROJ_START, speed = BLOOD_SPEED, startHeight = 110, endHeight = 0, angle = 40)
            later(lands) {
                gfx(SPLAT_GFX[rand(SPLAT_GFX.size)], tile)
                splats += Splat(tile, t + SPLAT_TICKS)
            }
        }
    }

    private fun splats(p: Player?) {
        val here = p?.let { real(it.coords) }
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
            if (p != null && here == s.tile) {
                s.stoodOn = true
                bleed(p)
            }
        }
        // the trails left by blood spawns
        if (p != null && here != null) trails[here]?.let { bleed(p) }
        val dry = trails.entries.filter { t >= it.value.second }
        for (e in dry) {
            delLoc(e.value.first)
            trails.remove(e.key)
        }
    }

    /** Standing in her blood: it hurts, heals her 1:1 with the damage you take, and drains prayer. */
    private fun bleed(p: Player) {
        val dmg = rand(1, BLOOD_BASE + BLOOD_PER_LEAK * leaks)
        // she heals exactly what the blood takes from you, as it lands
        val dealt = hurt(p, dmg, source = maiden)
        val hpBefore = p.hitpoints
        later(1) { healNpc(maiden, minOf(dealt, hpBefore)) }
        p.statSub("stat.prayer", constant = dmg / 2 + 1, percent = 0)
    }

    private fun bloodSpawns(p: Player?) {
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
        const val HP = 2625
        val PHASES = listOf("npc.tob_maiden_100", "npc.tob_maiden_70", "npc.tob_maiden_50", "npc.tob_maiden_30")
        const val DYING_B = "npc.tob_maiden_dying_b"
        const val FIRST_ATTACK = 9
        const val ATTACK_EVERY = 10
        /** 1 in 3 once off cooldown, the community figure (Blert's real solos: 47 of 123, which fits). */
        const val THROW_CHANCE = 1.0 / 3
        const val THROW_COOLDOWN = 2
        const val BASE_MAX = 36.5
        const val PER_LEAK = 3.5
        const val BLOOD_BASE = 10
        const val BLOOD_PER_LEAK = 2
        const val SPLAT_TICKS = 10
        const val TRAIL_TICKS = 42
        const val CRAB = "npc.maiden_elemental"
        const val CRAB_HP = 75
        const val ENTRY_CRAB_HP = 16
        const val CRABS = 2
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
        val RANGED_WORDS = listOf("bow", "chinchompa", "blowpipe", "dart", "knife", "javelin", "thrownaxe", "ballista", "eclipse atlatl")
        val MAGIC_WORDS = listOf("staff", "wand", "sceptre", "trident", "shadow", "sanguinesti", "harmonised", "nightmare", "ayak", "kodai")

        /** Blert's ten crab spawns (N1-N4 outer, S1-S4 outer). */
        val CRAB_SPOTS =
            listOf(
                3173 to 4456, 3177 to 4456, 3181 to 4456, 3185 to 4454, 3185 to 4456,
                3173 to 4436, 3177 to 4436, 3181 to 4436, 3185 to 4438, 3185 to 4436,
            )
    }
}
