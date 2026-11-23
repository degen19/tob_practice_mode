package org.rsmod.content.raids.tob.solo

import dev.openrune.rscm.RSCM.asRSCM
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid

/**
 * Xarpus, solo (3750 hitpoints plus 20 for every exhumed heal).
 *
 * - The exhumeds: he sits in his pool (the feeding form) and can't be hurt. Exhumeds come up one at a time, every 12
 *   ticks from the first instance tick 1 at or after tick 8 (Blert: 8 to 11, by when you came in), and last 11; from
 *   their third tick each one sends him an orb of energy every tick nobody stands on it (+20 hitpoints). Seven of
 *   them, then 92 ticks after the first he rises - the fighting form.
 * - Phase 2: every 4 ticks he spits poison at your tile; it lands 3 ticks later and leaves a pool (hurting you if
 *   you're still on it), then bounces on to two other random tiles. Stand under him on the tick he spits and he
 *   tramples you (two hits of up to 8) instead of spitting. Standing in a pool poisons you harder the longer you stay.
 * - At 33% (on his next 4-tick cycle) he screeches: no more spitting. He stares at one quarter of the room and turns
 *   to another every 8 ticks (never the same one twice); attack him from the quarter he's looking at and he spits back
 *   for 50-80.
 */
class XarpusSolo(owner: Player, session: InstanceSession, d: SoloDeps, origin: CoordGrid, copyBase: CoordGrid) :
    SoloRoom(TobRoom.XARPUS, owner, session, d, origin, copyBase) {
    override val entry = CoordGrid(3170, 4378, 1)
    override val chest = CoordGrid(3171, 4375, 1) to LocAngle.West

    override fun inArena(c: CoordGrid): Boolean = c.level == 1 && c.x in 3160..3180 && c.z in 4380..4394

    private lateinit var xarpus: Npc
    private var phase = 0
    private var hp = BASE_HP
    private var exhumeds = 0
    private var firstExhumed = -1
    private var screechAt = -1
    private var gaze = -1
    private var gazeAt = 0
    private var lastSpitBack = -100
    private var nextSpit = 0
    private var poolTicks = 0
    private var dead = -1

    private class Exhumed(val loc: LocInfo, val tile: CoordGrid, val born: Int)

    private var exhumed: Exhumed? = null
    private val pools = HashMap<CoordGrid, LocInfo>()

    override fun build() {
        phase = 0
        hp = hp(BASE_HP, entryHp(3400))
        exhumeds = 0
        firstExhumed = -1
        screechAt = -1
        gaze = -1
        dead = -1
        poolTicks = 0
        exhumed = null
        pools.clear()
        xarpus = spawn(COMBAT, CoordGrid(X + 1, Y + 1, 1), hp)
        transmog(xarpus, STATIC)
    }

    override fun onStart() {
        logger.info { "xarpus skeletons ${findLocs("loc.tob_skeleton_with_weapon").map { it.first }}" }
        transmog(xarpus, FEEDING)
    }

    override fun floor(npc: Npc): Int? = if (npc === xarpus && dead < 0) 1 else null

    override fun land(npc: Npc, hit: Hit): Int? {
        if (npc !== xarpus) return null
        if (phase == 0 || dead >= 0) return 0
        // every hitsplat from the quarter he's staring at comes back: 50-75, raised by 40% of the boost his exhumed
        // heals gave him (wiki), so a scythe's three splats are three spits back
        if (phase == 3 && hit.isFromPlayer) {
            val p = inside()
            if (p != null && quarter(real(p.coords)) == gaze) {
                lastSpitBack = t
                val boost = (hp - BASE_HP).coerceAtLeast(0).toDouble() / BASE_HP
                p.spotanim("spotanim.tob_xarpus_acidsplash")
                hurt(p, (rand(SPIT_BACK_MIN, SPIT_BACK_MAX) * (1 + 0.4 * boost)).toInt(), source = xarpus)
            }
        }
        return hit.damage
    }

    override fun step() {
        if (dead >= 0) {
            when (t - dead) {
                DEATH_B_AT -> {
                    transmog(xarpus, "npc.xarpus_death")
                    xarpus.anim("seq.tob_xarpus_death_b")
                }
                DEATH_B_AT + DEATH_B_TICKS -> despawn(xarpus)
                DEATH_TICKS -> {
                    cleared = true
                    mes("The way on is open: leave by the north barrier for Verzik Vitur.")
                }
            }
            return
        }
        when (phase) {
            0 -> exhumedPhase()
            else -> fight()
        }
    }

    /* ---- the exhumeds */

    private fun exhumedPhase() {
        val p = inside()
        val e = exhumed
        if (e != null) {
            val age = t - e.born
            if (age == EXHUMED_LIFE - 1) d.worldRepo.locAnim(e.loc, "seq.tob_xarpus_exhumed_end")
            if (age == 1) d.worldRepo.locAnim(e.loc, "seq.tob_xarpus_exhumed_loop")
            if (age >= EXHUMED_LIFE) {
                delLoc(e.loc)
                exhumed = null
            } else if (age >= HEAL_FROM) {
                val stood = p != null && real(p.coords) == e.tile
                if (!stood) {
                    val lands = projTiles("spotanim.tob_xarpus_exhumed_energyorb", e.tile, CoordGrid(X + 2, Y + 2, 1), speed = 4, startHeight = 0, endHeight = 80)
                    xarpus.anim("seq.tob_xarpus_absorb")
                    // his max rises with each heal, and the heal shows as a hitsplat on him when the orb reaches him
                    later(lands) {
                        hp += HEAL
                        xarpus.baseHitpointsLvl = hp
                        healNpc(xarpus, HEAL)
                    }
                }
            }
        }
        if (firstExhumed < 0 && t >= FIRST_EXHUMED && instanceTick == CYCLE_TICK) {
            firstExhumed = t
            logger.info { "xarpus: first exhumed at t$t, clock ${d.mapClock.cycle}" }
        }
        if (firstExhumed >= 0 && exhumeds < EXHUMEDS && (t - firstExhumed) % EXHUMED_EVERY == 0) {
            exhumeds++
            val tile = exhumedTile()
            // a ground decoration (you stand on it), rising, looping, then sinking
            val l = loc("loc.tob_xarpus_exhumed", tile, shape = LocShape.GroundDecor)
            d.worldRepo.locAnim(l, "seq.tob_xarpus_exhumed_start")
            exhumed = Exhumed(l, tile, t)
        }
        if (firstExhumed >= 0 && t - firstExhumed >= RISE_AFTER) rise()
    }

    private fun exhumedTile(): CoordGrid {
        repeat(60) {
            val c = CoordGrid(rand(3162, 3178), rand(4380, 4393), 1)
            if (boxDist(c.x, c.z, X, Y, X + 4, Y + 4) > 0 && !blocked(c)) return c
        }
        return CoordGrid(3164, 4388, 1)
    }

    private fun rise() {
        exhumed?.let { delLoc(it.loc) }
        exhumed = null
        phase = 2
        xarpus.resetTransmog()
        xarpus.telejump(d.collision, at(X, Y, 1))
        xarpus.anim("seq.tob_xarpus_fly_up")
        nextSpit = t + FIRST_SPIT
        mes("Xarpus rises from his pool!")
    }

    /* ---- the fight */

    private fun fight() {
        if (xarpus.hitpoints <= 1) {
            dead = t
            xarpus.hitpoints = 0
            xarpus.anim("seq.tob_xarpus_death_a")
            for ((c, l) in pools) {
                delLoc(l)
                gfx(POOL_END[rand(POOL_END.size)], c)
            }
            pools.clear()
            mes("Xarpus is dead.")
            return
        }
        val p = inside()
        // the screech, on his next cycle at or under 33%
        if (phase == 2 && xarpus.hitpoints <= hp * SCREECH_PCT / 100 && instanceTick == CYCLE_TICK) {
            phase = 3
            screechAt = t
            xarpus.anim("seq.tob_xarpus_fly_up")
            mes("Xarpus begins to stare intently.")
            turn()
        }
        if (p != null) poison(p)
        if (p != null) trample(p)
        if (phase == 2 && p != null && t >= nextSpit) spit(p)
        if (phase == 3 && t - gazeAt >= GAZE_TICKS) turn()
    }

    private fun spit(p: Player) {
        nextSpit = t + SPIT_EVERY
        // a player under him on the tick he spits isn't spat at - he goes to the next player in orb order instead, so
        // in a solo he doesn't spit at all (Blert: every skipped spit in a solo has the player under him that tick)
        val here = real(p.coords)
        if (boxDist(here.x, here.z, X, Y, X + 4, Y + 4) == 0) return
        xarpus.facePlayer(p)
        xarpus.anim("seq.tob_xarpus_attack_ranged")
        recordAttack(xarpus, 10)
        val tile = here
        // Blert: the spit leaves 2 ticks after his attack and flies a tick per tile less one (from his centre)
        val lands = projTiles("spotanim.tob_xarpus_acidspit", CoordGrid(X + 2, Y + 2, 1), tile, delay = 30, speed = 30, startHeight = 90, endHeight = 0, start = 60)
        later(lands) { splat(tile, bounce = true) }
    }

    /**
     * The acid lands on [tile] (hurting whoever stands there); a spit then bounces on to the next two players in orb
     * order, and where there's no next player to a random tile - in a solo, two different random tiles.
     */
    private fun splat(tile: CoordGrid, bounce: Boolean) {
        if (phase != 2) return
        gfx("spotanim.tob_xarpus_acidsplash", tile)
        if (tile !in pools) {
            val pool = loc("loc.tob_xarpus_acidpool", tile)
            pools[tile] = pool
            d.worldRepo.locAnim(pool, "seq.tob_xarpus_acid_splat_start")
            later(1) { if (pools[tile] === pool) d.worldRepo.locAnim(pool, "seq.tob_xarpus_acid_splat_loop") }
        }
        val p = inside()
        if (p != null && real(p.coords) == tile) hurt(p, rand(SPLAT_MIN, SPLAT_MAX), source = xarpus)
        if (!bounce) return
        val targets = ArrayList<CoordGrid>(2)
        var tries = 0
        while (targets.size < 2 && tries++ < 100) {
            val c = CoordGrid(rand(ARENA_MIN_X, ARENA_MAX_X), rand(ARENA_MIN_Y, ARENA_MAX_Y), 1)
            if (c == tile || c in targets || !inArena(c) || blocked(c) || boxDist(c.x, c.z, X, Y, X + 4, Y + 4) == 0) continue
            targets += c
        }
        for (c in targets) {
            // Blert: a bounce leaves as the spit lands and flies a tick per tile plus one
            val lands = projTiles("spotanim.tob_xarpus_acidspit", tile, c, delay = 30, speed = 30, startHeight = 0, endHeight = 0, angle = 40, start = 0)
            later(lands) { splat(c, bounce = false) }
        }
    }

    /** Under him once he's up (spitting or staring): trampled every tick, two hits of up to 8, two ticks later. */
    private fun trample(p: Player) {
        if (phase < 2) return
        val here = real(p.coords)
        if (boxDist(here.x, here.z, X, Y, X + 4, Y + 4) != 0) return
        repeat(2) { hurt(p, rand(0, TRAMPLE_MAX), source = xarpus, delay = TRAMPLE_DELAY) }
    }

    /** Standing in a pool: poison that grows the longer you stay (2 up to 8 a tick). */
    private fun poison(p: Player) {
        if (real(p.coords) in pools) {
            poolTicks++
            hurt(p, minOf(POISON_MAX, POISON_START + poolTicks / 2), source = xarpus)
        } else {
            poolTicks = 0
        }
    }

    /** P3: which quarter of the room he's looking at (0 NE, 1 SE, 2 SW, 3 NW). */
    private fun quarter(c: CoordGrid): Int {
        val east = c.x > X + 2
        val north = c.z > Y + 2
        return when {
            east && north -> 0
            east -> 1
            !north -> 2
            else -> 3
        }
    }

    private fun turn() {
        val next = (0..3).filter { it != gaze }.let { it[rand(it.size)] }
        gaze = next
        gazeAt = t
        recordAttack(xarpus, 25 + next)
        val (dx, dy) = when (next) {
            0 -> 8 to 8
            1 -> 8 to -8
            2 -> -8 to -8
            else -> -8 to 8
        }
        xarpus.resetFaceEntity()
        xarpus.faceSquare(at(X + 2 + dx, Y + 2 + dy, 1))
    }

    /** After Xarpus you walk on yourself: past the skeleton (the Dawnbringer) to the exit door. */
    override val walkOn: Boolean = false

    private var searched = false

    override suspend fun opLoc(name: String, at: CoordGrid, op: Int, access: ProtectedAccess): Boolean {
        when (name) {
            "loc.tob_skeleton_with_weapon" -> {
                if (searched) {
                    access.mes("You find nothing else.")
                    return true
                }
                searched = true
                access.player.invAdd(access.player.inv, DAWNBRINGER, 1, strict = false)
                access.mes("You find the Dawnbringer on the skeleton.")
                findLocs("loc.tob_skeleton_with_weapon").firstOrNull { it.first == at }?.let { (_, l) ->
                    val without = dev.openrune.ServerCacheManager.getObject("loc.tob_skeleton_without_weapon".asRSCM(dev.openrune.rscm.RSCMType.LOC))
                    if (without != null) d.locRepo.change(l, without, Int.MAX_VALUE)
                }
                return true
            }
            "loc.tob_dungeon_xarpus_arena_door_exit" -> {
                if (!cleared) return false
                access.mes("You head on to ${TobRoom.VERZIK.label}.")
                d.raids.travel?.invoke(access.player, TobRoom.VERZIK, access)
                return true
            }
        }
        return false
    }

    override fun autoTarget(): Npc? = if (phase > 0) xarpus else null

    override val autoStart: CoordGrid
        get() = CoordGrid(3167, 4384, 1)

    override fun barBoss(): Npc? = xarpus

    override fun progress(): TobHud.Bar? {
        val bar = super.progress() ?: return null
        return if (phase == 0) bar.copy(type = TobHud.IMMUNE) else bar
    }

    override fun debug(): String = "xarpus ${xarpus.hitpoints}/$hp phase $phase exhumeds $exhumeds pools ${pools.size} gaze $gaze"

    private companion object {
        const val X = 3168
        const val Y = 4385
        const val COMBAT = "npc.tob_xarpus_combat"
        const val STATIC = "npc.tob_xarpus_static"
        const val FEEDING = "npc.tob_xarpus_feeding"
        const val BASE_HP = 3750
        const val HEAL = 20
        const val EXHUMEDS = 7
        const val FIRST_EXHUMED = 8
        const val EXHUMED_EVERY = 12
        const val EXHUMED_LIFE = 11
        const val HEAL_FROM = 3
        const val RISE_AFTER = 92
        const val CYCLE_TICK = 1
        const val CYCLE = 4
        const val FIRST_SPIT = 7
        const val SPIT_EVERY = 4
        const val DAWNBRINGER = "obj.verzik_special_weapon"
        const val TRAMPLE_MAX = 8
        const val TRAMPLE_DELAY = 2
        const val ARENA_MIN_X = 3160
        const val ARENA_MAX_X = 3180
        const val ARENA_MIN_Y = 4380
        const val ARENA_MAX_Y = 4394
        const val SPLAT_MIN = 6
        const val SPLAT_MAX = 12
        const val POISON_START = 2
        const val POISON_MAX = 8
        const val SCREECH_PCT = 33
        const val GAZE_TICKS = 8
        const val SPIT_BACK_MIN = 50
        const val SPIT_BACK_MAX = 75
        const val SPIT_BACK_GAP = 4
        const val DEATH_B_AT = 3
        const val DEATH_TICKS = 6
        const val DEATH_B_TICKS = 2
        val POOL_END =
            listOf(
                "spotanim.tob_xarpus_acidpool_end_0",
                "spotanim.tob_xarpus_acidpool_end_1",
                "spotanim.tob_xarpus_acidpool_end_2",
                "spotanim.tob_xarpus_acidpool_end_3",
            )
    }
}
