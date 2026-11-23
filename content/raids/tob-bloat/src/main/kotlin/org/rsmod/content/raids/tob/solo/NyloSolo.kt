package org.rsmod.content.raids.tob.solo

import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.player.clearInteractionRoute
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid
import kotlin.math.abs

/**
 * The Nylocas, solo: the 31 waves (Blert's table - lanes, sizes, styles, aggros and flickers, and which pillar each
 * goes for, read off real raids), the room cap (12, 24 from wave 20) and natural stalls on the room's 4-tick cycle, the
 * four pillars, splits, the 52-tick explosions, then Nylocas Vasilias.
 *
 * - Nylocas walk their lane to its end, then to their pillar (attacking it until it falls) - or, if aggressive, turn
 *   into the "fighting" form there and come for you (melee ones to a tile straight beside you, ranged and magic ones to
 *   within 2). Only their own style hurts them; a wrong-style hit nulls one, and after that nothing you do hurts it -
 *   every hit shows its shield and a blue 0.
 * - A flicker changes style halfway down its lane (5 ticks in), and again 2 ticks later to its last style.
 * - A big one killed splits into two small ones of random styles. Every nylocas explodes 52 ticks after it spawns
 *   (a big one 53), hurting anyone next to it; a big one that explodes doesn't split.
 * - A pillar that falls hurts you for up to 50, and the nylocas that were on it come for you. When all four are down
 *   the game wipes the room; for practice the waves carry on and Vasilias still comes.
 * - Vasilias drops in 16 ticks after the last nylocas goes, melee first; every 10 ticks he changes to another style.
 *   The wrong style heals him and comes back at you. Melee: two attacks, 4 ticks apart; ranged/magic: one.
 */
class NyloSolo(owner: Player, session: InstanceSession, d: SoloDeps, origin: CoordGrid, copyBase: CoordGrid) :
    SoloRoom(TobRoom.NYLOCAS, owner, session, d, origin, copyBase) {
    override val entry = CoordGrid(3296, 4257, 0)
    override val chest = CoordGrid(3297, 4259, 0) to LocAngle.West

    override fun inArena(c: CoordGrid): Boolean = c.z in 4228..4254 && c.x in 3276..3315

    private class Spawn(val lane: String, val x: Int, val y: Int, val big: Boolean, val rot: String, val target: String)

    private class Wave(val stall: Int, vararg val spawns: Spawn)

    private inner class Pillar(val name: String, val x: Int, val y: Int) {
        var hp = PILLAR_HP
        var shaking = false
        var npc: Npc? = null
        var loc: LocInfo? = null
        val up: Boolean
            get() = hp > 0
        val cx: Int
            get() = x + 1
        val cy: Int
            get() = y + 1
    }

    private inner class Nylo(val npc: Npc, val born: Int, val big: Boolean, val lane: String?, rot: String, var aggro: Boolean, var pillar: Pillar?) {
        val rot = rot
        var style = rot[0]
        var fighting = false
        var nextAttack = 0
        var diedAt = -1
        var exploding = -1
        var nulled = false
        val size: Int
            get() = if (big) 2 else 1
    }

    private val pillars = listOf(Pillar("SW", OX + 25, OY + 18), Pillar("NW", OX + 25, OY + 29), Pillar("SE", OX + 36, OY + 18), Pillar("NE", OX + 36, OY + 29))
    private val nylos = ArrayList<Nylo>()
    private var wave = 0
    private var nextWaveAt = FIRST_WAVE
    private var lastWaveAt = 0
    private var bossAt = -1
    private var boss: Npc? = null
    private var bossStyle = 'M'
    private var formAt = 0
    private var bossNext = 0
    private var bossLast = -100
    private var bossDead = -1
    private var allDown = false

    override fun build() {
        nylos.clear()
        dying.clear()
        wave = 0
        nextWaveAt = FIRST_WAVE
        lastWaveAt = 0
        bossAt = -1
        boss = null
        bossDead = -1
        allDown = false
        val standing = findLocs(PILLAR_PRISTINE)
        for (p in pillars) {
            p.hp = PILLAR_HP
            p.shaking = false
            p.npc = spawn(PILLAR_NPC, CoordGrid(p.x, p.y, 0), PILLAR_HP)
            p.loc = standing.firstOrNull { (c, _) -> c.x == p.x && c.z == p.y }?.second
            if (p.loc == null) logger.warn { "nylocas: no support loc at ${p.x}, ${p.y}" }
        }
    }

    override fun floor(npc: Npc): Int? =
        when {
            npc === boss && bossDead < 0 -> 1
            pillars.any { it.npc === npc } -> 1
            else -> null
        }

    override fun land(npc: Npc, hit: Hit): Int? {
        if (pillars.any { it.npc === npc }) return 0
        if (npc === boss) {
            if (bossDead >= 0 || bossStyle == '-') return 0
            return hit.damage
        }
        val n = nylos.firstOrNull { it.npc === npc } ?: return null
        if (hit.type == HitType.Typeless) return hit.damage
        // a wrong style nulls it: from then on nothing you do hurts it (Blert), and every hit shows its shield
        if (hit.type != styleType(n.style)) n.nulled = true
        if (!n.nulled) return hit.damage
        npc.spotanim(SHIELDED)
        return 0
    }

    /** The wrong style heals Vasilias (the purple heal splat) and comes back at you. */
    override fun healedByHits(npc: Npc, hit: Hit): Boolean {
        if (npc !== boss || bossDead >= 0 || bossStyle == '-') return false
        if (hit.type == HitType.Typeless || hit.type == styleType(bossStyle)) return false
        inside()?.let { hurt(it, hit.damage, source = npc) }
        return true
    }

    private fun styleType(s: Char): HitType = when (s) {
        'M' -> HitType.Melee
        'R' -> HitType.Ranged
        else -> HitType.Magic
    }

    private fun styleName(s: Char): String = when (s) {
        'M' -> "melee"
        'R' -> "ranged"
        else -> "magic"
    }

    private fun prayerFor(s: Char): String = when (s) {
        'M' -> "melee"
        'R' -> "missiles"
        else -> "magic"
    }

    private fun typeOf(big: Boolean, fighting: Boolean, s: Char): String =
        "npc.tob_nylocas_" + (if (big) "big_" else "") + (if (fighting) "fighting_" else "incoming_") + styleName(s)

    override fun step() {
        val p = inside()
        waves()
        for (n in nylos.toList()) nylo(n, p)
        nylos.removeAll { !it.npc.isSlotAssigned }
        pillarsTick()
        bossTick(p)
    }

    /* ---- waves */

    private fun waves() {
        if (wave >= WAVES.size) {
            if (bossAt < 0 && nylos.isEmpty()) bossAt = t + BOSS_DELAY
            return
        }
        if (t < nextWaveAt || instanceTick != WAVE_TICK) return
        val cap = if (wave + 1 >= CAP_WAVE) CAP_LATE else CAP_EARLY
        if (roomCount() >= cap) return
        val w = WAVES[wave]
        wave++
        for (s in w.spawns) spawnNylo(s)
        logger.info { "nylocas: wave $wave at t$t, clock ${d.mapClock.cycle}, instance tick $instanceTick" }
        lastWaveAt = t
        nextWaveAt = t + w.stall.coerceAtLeast(CYCLE)
    }

    /**
     * Nylocas the room counts towards its cap: every one alive, plus each dead one until its death animation is
     * over - a small one for 2 ticks, a big one until its two splits come out (so the count goes 1 -> 2, never
     * through 0). Blert: a wave stalls when this is at the cap on the wave's tick.
     */
    private fun roomCount(): Int = nylos.count { it.npc.isSlotAssigned && it.npc.hitpoints > 0 } + dying.count { it > t }

    /** The tick each dead nylocas stops counting (Int.MAX_VALUE: a big one waiting to split). */
    private val dying = ArrayList<Int>()

    private fun spawnNylo(s: Spawn) {
        val first = s.rot[0]
        val npc = spawn(typeOf(s.big, false, first), CoordGrid(OX + s.x, OY + s.y, 0), if (s.big) hp(BIG_HP, ENTRY_BIG_HP) else hp(SMALL_HP, ENTRY_SMALL_HP))
        npc.moveRestrict = dev.openrune.types.MoveRestrict.BlockedNormal
        val pillar = if (s.target == "A") null else pillars.first { it.name == s.target }
        nylos += Nylo(npc, t, s.big, s.lane, s.rot, s.target == "A", pillar)
    }

    private fun split(at: CoordGrid) {
        for (i in 0 until 2) {
            val style = "MRG"[rand(3)]
            val c = CoordGrid(at.x + i, at.z + i, 0)
            val npc = spawn(typeOf(false, false, style), c, hp(SMALL_HP, ENTRY_SMALL_HP))
            npc.moveRestrict = dev.openrune.types.MoveRestrict.BlockedNormal
            val n = Nylo(npc, t, false, null, style.toString(), false, nearestPillar(c))
            n.style = style
            nylos += n
        }
    }

    private fun nearestPillar(c: CoordGrid): Pillar? = pillars.filter { it.up }.minByOrNull { abs(it.cx - c.x) + abs(it.cy - c.z) }

    /* ---- one nylocas's tick */

    private fun nylo(n: Nylo, p: Player?) {
        val npc = n.npc
        if (!npc.isSlotAssigned) return
        val age = t - n.born
        val here = real(npc.coords)
        // killed: a big one splits as it goes
        if (npc.hitpoints <= 0) {
            if (n.diedAt < 0) {
                n.diedAt = t
                npc.routeDestination.clear()
                gfx("spotanim.tob_nylocas_death_${styleName(n.style)}_standard", here)
                if (n.big) {
                    dying += Int.MAX_VALUE
                    later(BIG_DEATH) {
                        dying.remove(Int.MAX_VALUE)
                        split(here)
                    }
                } else {
                    dying += t + SMALL_DEATH
                }
                dying.removeAll { it <= t }
            }
            return
        }
        // its time is up: it explodes
        val life = if (n.big) BIG_LIFE else SMALL_LIFE
        if (age >= life) {
            if (n.exploding < 0) {
                n.exploding = t
                npc.routeDestination.clear()
                npc.anim("seq.top_spider_${styleName(n.style)}_death_detonate")
                gfx("spotanim.tob_nylocas_death_${styleName(n.style)}_detonate", here)
                val max = if (n.big) BIG_BOOM else SMALL_BOOM
                p?.let { q -> if (boxDist(real(q.coords).x, real(q.coords).z, here.x, here.z, here.x + n.size - 1, here.z + n.size - 1) <= 1) hurt(q, rand(0, max), source = npc) }
            }
            // only a kill splits a big one; one that goes off on its timer just goes
            if (t - n.exploding >= (if (n.big) BIG_BOOM_TICKS else DETONATE_TICKS)) despawn(npc)
            return
        }
        // flickers: halfway down the lane, then 2 ticks later
        if (n.rot.length == 3 && n.lane != null) {
            val s = when (age) {
                FLICK_1 -> n.rot[1]
                FLICK_2 -> n.rot[2]
                else -> null
            }
            if (s != null && s != n.style) {
                n.style = s
                transmog(npc, typeOf(n.big, n.fighting, s))
            }
        }
        if (frozen(npc)) return
        // down the lane first
        val laneEnd = n.lane?.let { laneEnd(it, here) }
        if (laneEnd != null && here != laneEnd && age < LANE_TICKS) {
            npc.walk(at(stepToward(here, laneEnd, n.size)))
            return
        }
        if (n.aggro && !n.fighting) {
            n.fighting = true
            transmog(npc, typeOf(n.big, true, n.style))
        }
        if (n.aggro) {
            p ?: return
            val pc = real(p.coords)
            val dist = boxDist(pc.x, pc.z, here.x, here.z, here.x + n.size - 1, here.z + n.size - 1)
            val inPlace = if (n.style == 'M') here in meleeSpots(pc, n.size) else dist in 1..AGGRO_RANGE
            if (!inPlace) {
                val goal = if (n.style == 'M' || dist == 0) meleeSpots(pc, n.size).filter { free(it, n.size) }.minByOrNull { chebyshev(here, it) } else pc
                if (goal != null) npc.walk(at(stepToward(here, goal, n.size)))
                return
            }
            if (t >= n.nextAttack) attackPlayer(n, p)
            return
        }
        // its pillar fell: it comes for you instead (Blert)
        if (n.pillar?.up == false) {
            n.aggro = true
            return
        }
        val pillar = n.pillar ?: nearestPillar(here)?.also { n.pillar = it }
        if (pillar == null) {
            n.aggro = true
            return
        }
        val pd = boxDist(here.x, here.z, pillar.x - n.size + 1, pillar.y - n.size + 1, pillar.x + 2, pillar.y + 2)
        if (pd > 1) {
            npc.walk(at(stepToward(here, CoordGrid(pillar.cx, pillar.cy, 0), n.size)))
            return
        }
        if (t >= n.nextAttack) {
            n.nextAttack = t + NYLO_SPEED
            npc.faceSquare(at(pillar.x, pillar.y), 3, 3)
            npc.anim(attackSeq(n.style, true))
            pillar.hp -= rand(PILLAR_HIT_MIN, PILLAR_HIT_MAX)
        }
    }

    private fun attackSeq(s: Char, close: Boolean): String = if (s == 'M' || !close) "seq.top_spider_${styleName(s)}_attack" else "seq.top_spider_${styleName(s)}_meleeattack"

    private fun attackPlayer(n: Nylo, p: Player) {
        n.nextAttack = t + NYLO_SPEED
        n.npc.facePlayer(p)
        n.npc.anim("seq.top_spider_${styleName(n.style)}_attack")
        val max = if (n.big) BIG_MAX else SMALL_MAX
        val roll = if (chance(NYLO_ACCURACY)) rand(0, max) else 0
        val delay =
            when (n.style) {
                'M' -> 1
                'R' -> proj(if (n.big) "spotanim.tob_nylocas_rangedprojectile_size2" else "spotanim.tob_nylocas_rangedprojectile_size1", n.npc, p, delay = 20, speed = 5, startHeight = 40)
                else -> 2
            }
        val prayer = prayerFor(n.style)
        later(delay) { inside()?.let { hurt(it, roll, prayer = prayer, source = n.npc) } }
    }

    private fun laneEnd(lane: String, here: CoordGrid): CoordGrid =
        when (lane) {
            "W" -> CoordGrid(OX + 26, here.z, 0)
            "S" -> CoordGrid(here.x, OY + 19, 0)
            else -> CoordGrid(OX + 37, here.z, 0)
        }

    /** The game's NPC step: diagonally toward the target, else along whichever axis is free. */
    private fun stepToward(from: CoordGrid, to: CoordGrid, size: Int): CoordGrid {
        val dx = sign(to.x - from.x)
        val dy = sign(to.z - from.z)
        val opts = listOf(CoordGrid(from.x + dx, from.z + dy, 0), CoordGrid(from.x + dx, from.z, 0), CoordGrid(from.x, from.z + dy, 0))
        return opts.firstOrNull { it != from && free(it, size) } ?: from
    }

    /** Where a nylocas of [size] stands to bite [pc]: its south-west tile, with the player straight beside it (no diagonals). */
    private fun meleeSpots(pc: CoordGrid, size: Int): List<CoordGrid> =
        (0 until size).flatMap { i ->
            listOf(
                CoordGrid(pc.x - size, pc.z - i, 0),
                CoordGrid(pc.x + 1, pc.z - i, 0),
                CoordGrid(pc.x - i, pc.z - size, 0),
                CoordGrid(pc.x - i, pc.z + 1, 0),
            )
        }

    private fun chebyshev(a: CoordGrid, b: CoordGrid): Int = maxOf(abs(a.x - b.x), abs(a.z - b.z))

    /** Nylocas walk the lanes (no tiles for players) and only the pillars stand in their way. */
    private fun free(c: CoordGrid, size: Int): Boolean =
        pillars.none { p -> p.up && c.x + size - 1 >= p.x && c.x <= p.x + 2 && c.z + size - 1 >= p.y && c.z <= p.y + 2 }

    private fun frozen(n: Npc): Boolean = n.vars["varn.freeze_end_clock"] > d.mapClock.cycle

    /* ---- pillars */

    private fun swapPillar(pl: Pillar, type: String) {
        val l = pl.loc ?: return
        pl.loc = d.locRepo.add(l.coords, type, Int.MAX_VALUE, l.angle, l.shape)
    }

    private fun pillarsTick() {
        for (pl in pillars) {
            val npc = pl.npc ?: continue
            if (pl.hp > 0) {
                npc.hitpoints = pl.hp.coerceIn(1, PILLAR_HP)
                if (!pl.shaking && pl.hp * 5 <= PILLAR_HP) {
                    pl.shaking = true
                    swapPillar(pl, PILLAR_COLLAPSING)
                }
                continue
            }
            despawn(npc)
            pl.npc = null
            if (!pl.shaking) swapPillar(pl, PILLAR_COLLAPSING)
            pl.loc?.let { d.worldRepo.locAnim(it, "seq.nylocas_pillar_collapse") }
            later(PILLAR_COLLAPSE_TICKS) { swapPillar(pl, PILLAR_DEAD) }
            inside()?.let { hurt(it, rand(PILLAR_FALL_MIN, PILLAR_FALL_MAX)) }
            mes("A pillar collapses!")
            // the game wipes the room here; for practice the wave carries on and Vasilias still comes
            if (pillars.none { it.up } && !allDown) {
                allDown = true
                mes("The last pillar falls. (In the game this wipes the room; here the waves carry on.)")
            }
        }
    }

    /* ---- Nylocas Vasilias */

    private fun bossTick(p: Player?) {
        if (bossAt < 0 || t < bossAt) return
        if (t == bossAt) {
            val b = spawn(BOSS_SPAWNING, CoordGrid(BOSS_X, BOSS_Y, 0), hp(BOSS_HP, entryHp(1800)))
            b.anim("seq.tob_spider_tank_spawn")
            boss = b
            bossStyle = '-'
            return
        }
        val b = boss ?: return
        if (bossDead >= 0) {
            if (t - bossDead == BOSS_DEATH_SEQ_TICKS) despawn(b)
            // the room is done at the start of the first cycle after his death animation
            if (!cleared && t - bossDead >= BOSS_DEATH_SEQ_TICKS && instanceTick == WAVE_TICK) {
                cleared = true
                mes("The way on is open: go back out through the barrier for Sotetseg.")
            }
            return
        }
        if (b.hitpoints <= 1) {
            bossDead = t
            b.hitpoints = 0
            b.routeDestination.clear()
            b.anim("seq.tob_spider_tank_death")
            mes("Nylocas Vasilias is dead.")
            return
        }
        if (t == bossAt + BOSS_DROP) {
            form('M')
            bossNext = t + MELEE_FIRST
        } else if (bossStyle != '-' && t - formAt == FORM_TICKS) {
            form("MRG".filter { it != bossStyle }[rand(2)])
            bossNext = if (bossStyle == 'M') t + MELEE_FIRST else maxOf(t + RANGED_FIRST, bossLast + RANGED_GAP)
        }
        if (bossStyle == '-' || p == null) return
        val here = real(b.coords)
        val pc = real(p.coords)
        val dist = boxDist(pc.x, pc.z, here.x, here.z, here.x + BOSS_SIZE - 1, here.z + BOSS_SIZE - 1)
        if (bossStyle == 'M' && (dist > 1 || dist == 0) && !frozen(b)) b.walk(at(stepToward(here, pc, BOSS_SIZE)))
        if (t != bossNext) return
        if (bossStyle == 'M' && dist != 1) {
            bossNext = t + 1
            return
        }
        bossLast = t
        if (bossStyle == 'M' && t - formAt < FORM_TICKS - MELEE_GAP) bossNext = t + MELEE_GAP
        b.facePlayer(p)
        b.anim("seq.top_spider_${styleName(bossStyle)}_attack")
        recordAttack(b, when (bossStyle) { 'M' -> 4; 'R' -> 5; else -> 6 })
        val delay = if (bossStyle == 'M') 1 else proj(if (bossStyle == 'R') "spotanim.tob_nylocas_rangedprojectile_size2" else "spotanim.tob_nylocas_rangedprojectile_sizemid", b, p, delay = 30, speed = 5, startHeight = 60)
        val roll = rand(0, BOSS_MAX)
        val prayer = prayerFor(bossStyle)
        later(delay) {
            val q = inside() ?: return@later
            hurt(q, if (prays(q, prayer)) roll / 4 else roll, source = b)
        }
    }

    private fun form(s: Char) {
        val b = boss ?: return
        bossStyle = s
        formAt = t
        // a new form is a new npc to the client: anyone attacking him stops
        for (p in d.players) {
            val i = p.interaction as? org.rsmod.game.interact.InteractionNpc ?: continue
            if (i.target === b) p.clearInteractionRoute()
        }
        transmog(b, when (s) {
            'M' -> "npc.nylocas_boss_melee"
            'R' -> "npc.nylocas_boss_ranged"
            else -> "npc.nylocas_boss_magic"
        })
    }

    override fun autoTarget(): Npc? {
        boss?.let { if (bossDead < 0) return it }
        val p = owner
        return nylos.filter { it.npc.isSlotAssigned && it.npc.hitpoints > 0 && it.style == 'M' }.minByOrNull { abs(real(it.npc.coords).x - real(p.coords).x) + abs(real(it.npc.coords).z - real(p.coords).z) }?.npc
    }

    override val autoStart: CoordGrid
        get() = CoordGrid(3295, 4248, 0)

    override fun barBoss(): Npc? = boss

    /** The bar shows the pillars until Vasilias drops in, then him. */
    override fun progress(): TobHud.Bar? {
        if (t < 0 || cleared) return null
        if (boss != null) return super.progress()
        return TobHud.bar(TobHud.FIGHT, pillars.sumOf { it.hp.coerceAtLeast(0) }, pillars.size * PILLAR_HP)
    }

    override fun debug(): String = nylos.take(4).joinToString(" ") { "${real(it.npc.coords).x - OX},${real(it.npc.coords).z - OY}${it.style}" } + " wave $wave nylos ${nylos.size} (${nylos.count { it.aggro }} aggro) pillars ${pillars.map { it.hp }} boss ${boss?.hitpoints} $bossStyle"

    private companion object {
        const val OX = 3264
        const val OY = 4224
        const val FIRST_WAVE = 3
        const val CYCLE = 4
        const val WAVE_TICK = 1
        const val CAP_WAVE = 20
        const val CAP_EARLY = 12
        const val CAP_LATE = 24
        const val SMALL_HP = 8
        const val ENTRY_SMALL_HP = 2
        const val ENTRY_BIG_HP = 3
        const val BIG_HP = 16
        const val SMALL_LIFE = 52
        const val BIG_LIFE = 53
        const val BIG_BOOM_TICKS = 3
        const val DETONATE_TICKS = 2
        const val BIG_DEATH = 6
        const val SMALL_DEATH = 2
        const val SMALL_BOOM = 18
        const val BIG_BOOM = 21
        const val SMALL_MAX = 17
        const val BIG_MAX = 24
        const val NYLO_SPEED = 4
        const val NYLO_ACCURACY = 0.6
        const val AGGRO_RANGE = 2
        const val SHIELDED = "spotanim.tob_nylocas_shielded"
        const val FLICK_1 = 5
        const val FLICK_2 = 7
        const val LANE_TICKS = 14
        const val PILLAR_NPC = "npc.tob_nylocas_support"
        const val PILLAR_PRISTINE = "loc.tob_nylocas_support_pristine"
        const val PILLAR_COLLAPSING = "loc.tob_nylocas_support_collapsing"
        const val PILLAR_DEAD = "loc.tob_nylocas_support_dead"
        const val PILLAR_COLLAPSE_TICKS = 4
        const val PILLAR_HP = 100
        const val PILLAR_HIT_MIN = 0
        const val PILLAR_HIT_MAX = 2
        const val PILLAR_FALL_MIN = 25
        const val PILLAR_FALL_MAX = 50
        const val BOSS_SPAWNING = "npc.nylocas_boss_spawning"
        const val BOSS_X = 3293
        const val BOSS_Y = 4246
        const val BOSS_SIZE = 4
        const val BOSS_HP = 1875
        const val BOSS_DELAY = 16
        const val BOSS_DROP = 2
        const val FORM_TICKS = 10
        const val MELEE_FIRST = 4
        const val MELEE_GAP = 4
        const val RANGED_FIRST = 5
        const val RANGED_GAP = 8
        const val BOSS_MAX = 70
        const val BOSS_DEATH_SEQ_TICKS = 5

        val WAVES =
            listOf(
            W(4, S("E", 46, 25, false, "M", "NW"), S("S", 32, 9, false, "G", "NE"), S("W", 17, 24, false, "R", "A")),
            W(4, S("E", 46, 24, false, "R", "SE"), S("S", 31, 9, false, "M", "A"), S("W", 17, 25, false, "G", "NE")),
            W(4, S("E", 46, 25, false, "G", "A"), S("S", 32, 9, false, "R", "SE"), S("W", 17, 24, false, "M", "NE")),
            W(4, S("E", 46, 24, false, "M", "NE"), S("S", 31, 9, true, "G", "SW"), S("W", 17, 25, false, "R", "NW")),
            W(16, S("E", 46, 25, false, "G", "NE"), S("S", 32, 9, false, "M", "SE"), S("W", 17, 24, true, "R", "SW")),
            W(4, S("E", 45, 24, true, "M", "SE"), S("S", 31, 9, false, "R", "NW"), S("W", 17, 25, false, "G", "NE")),
            W(12, S("E", 46, 25, false, "M", "NW"), S("S", 32, 9, false, "G", "SE"), S("S", 31, 9, true, "R", "A")),
            W(4, S("E", 46, 24, false, "R", "SE"), S("S", 31, 9, false, "M", "NW"), S("W", 17, 24, true, "G", "A")),
            W(12, S("E", 46, 25, false, "G", "NW"), S("W", 17, 24, true, "R", "A"), S("W", 17, 24, false, "M", "NE")),
            W(8, S("E", 46, 25, false, "R", "A"), S("E", 45, 24, true, "R", "NE"), S("S", 32, 9, false, "R", "SE"), S("S", 31, 9, false, "R", "SW"), S("W", 17, 25, false, "R", "NW"), S("W", 17, 24, false, "R", "A")),
            W(8, S("E", 46, 25, false, "G", "A"), S("E", 46, 24, false, "G", "NE"), S("S", 32, 9, false, "G", "SE"), S("S", 31, 9, false, "G", "SW"), S("W", 17, 24, true, "G", "A")),
            W(8, S("E", 46, 25, false, "M", "A"), S("E", 46, 24, false, "M", "NE"), S("S", 31, 9, true, "M", "SW"), S("W", 17, 25, false, "M", "NW"), S("W", 17, 24, false, "M", "A")),
            W(8, S("E", 45, 24, true, "M", "A"), S("S", 32, 9, false, "R", "SE"), S("S", 31, 9, false, "M", "SW"), S("W", 17, 25, false, "R", "NW"), S("W", 17, 24, false, "G", "A")),
            W(8, S("E", 45, 24, true, "R", "A"), S("S", 32, 9, false, "G", "SE"), S("S", 31, 9, false, "R", "SW"), S("W", 17, 25, false, "G", "NW"), S("W", 17, 24, false, "M", "A")),
            W(8, S("E", 46, 25, false, "G", "A"), S("E", 46, 24, false, "R", "NE"), S("S", 31, 9, true, "G", "SW"), S("W", 17, 25, false, "M", "NW"), S("W", 17, 24, false, "R", "A")),
            W(4, S("E", 46, 24, false, "GMR", "NE"), S("S", 31, 9, false, "MGR", "SW"), S("W", 17, 25, false, "RGR", "NW")),
            W(12, S("E", 45, 24, true, "GMG", "NE"), S("S", 31, 9, true, "GMG", "SE"), S("W", 17, 24, true, "GMG", "SW")),
            W(8, S("E", 45, 24, true, "RGR", "NW"), S("S", 31, 9, true, "RGR", "SW"), S("W", 17, 24, true, "RGR", "A")),
            W(12, S("E", 45, 24, true, "GMG", "A"), S("S", 31, 9, true, "GMG", "SE"), S("W", 17, 24, true, "GMG", "NE")),
            W(16, S("E", 45, 24, true, "MRM", "NE"), S("S", 31, 9, true, "GRM", "A"), S("W", 17, 24, true, "MRM", "NW")),
            W(8, S("E", 46, 25, false, "RMR", "NE"), S("E", 46, 24, false, "RMR", "A"), S("S", 32, 9, false, "MGM", "SE"), S("S", 31, 9, false, "MRM", "A"), S("W", 17, 25, false, "GRG", "NW"), S("W", 17, 24, false, "GMR", "SW")),
            W(12, S("E", 45, 24, true, "GRM", "A"), S("S", 32, 9, false, "RGM", "SE"), S("S", 31, 9, false, "GRM", "SW"), S("W", 17, 24, true, "MRM", "SW")),
            W(8, S("E", 45, 24, true, "GRM", "NE"), S("S", 31, 9, true, "RGM", "A"), S("W", 17, 25, false, "GRM", "NW"), S("W", 17, 24, false, "RGR", "SW")),
            W(8, S("E", 45, 24, true, "M", "A"), S("S", 31, 9, true, "G", "A"), S("W", 17, 24, true, "RGM", "A")),
            W(8, S("E", 45, 24, true, "GMG", "SE"), S("S", 31, 9, true, "R", "SW"), S("W", 17, 24, true, "M", "A")),
            W(4, S("E", 45, 24, true, "G", "NE"), S("S", 31, 9, true, "MGM", "SW"), S("W", 17, 24, true, "G", "A")),
            W(8, S("E", 45, 24, true, "GMG", "SE"), S("S", 31, 9, true, "MGR", "A"), S("W", 17, 24, true, "G", "NW")),
            W(4, S("E", 46, 25, false, "GMG", "NE"), S("E", 46, 24, false, "RGM", "SE"), S("S", 32, 9, false, "MRM", "SE"), S("S", 31, 9, false, "GMR", "SW"), S("W", 17, 25, false, "MGM", "NW"), S("W", 17, 24, false, "RMG", "A")),
            W(4, S("E", 46, 25, false, "GRM", "NE"), S("E", 46, 24, false, "RMG", "A"), S("S", 31, 9, true, "M", "SE"), S("W", 17, 25, false, "MRG", "A"), S("W", 17, 24, false, "RMR", "SW")),
            W(4, S("E", 45, 24, true, "G", "A"), S("S", 32, 9, false, "MRM", "SE"), S("S", 31, 9, false, "MRM", "SW"), S("W", 17, 24, true, "RMR", "SW")),
            W(0, S("E", 46, 25, false, "GRG", "NE"), S("E", 46, 24, false, "RMR", "SE"), S("S", 32, 9, false, "MGR", "SE"), S("S", 31, 9, false, "GMR", "SW"), S("W", 17, 25, false, "MRG", "NW"), S("W", 17, 24, false, "RGR", "SW")),
            )

        @Suppress("FunctionName")
        private fun W(stall: Int, vararg s: Spawn) = Wave(stall, *s)

        @Suppress("FunctionName")
        private fun S(lane: String, x: Int, y: Int, big: Boolean, rot: String, target: String) = Spawn(lane, x, y, big, rot, target)
    }
}
