package org.rsmod.content.raids.tob.solo

import dev.openrune.rscm.RSCM.asRSCM
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.player.output.soundSynth
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.table.TobXarpusScalesRow
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid

/**
 * Xarpus, for the party that came in (Blert's 50 fastest rooms of each scale: [TobXarpusScalesRow]).
 *
 * - The exhumeds: he sits in his pool (the feeding form) and can't be hurt. Exhumeds come up every 12 ticks in a solo,
 *   8 in duos and trios, 4 in 4s and 5s (7 / 9 / 12 / 15 / 18 of them), from the first instance tick 1 at or after
 *   tick 8, and each lasts 11 (so up to three are up at once); from its third tick each one sends him an orb of energy
 *   every tick nobody stands on it (20 / 16 / 12 / 9 / 8 hitpoints). 20 ticks after the last one comes up he rises.
 * - Phase 2: every 4 ticks he spits poison at the next player in turn; it leaves 2 ticks later, lands on the tile they
 *   stood on and leaves a pool, then bounces once or twice: to the tiles the next players in turn stood on when he
 *   spat, or random tiles once there's nobody left. Stand under him on the tick he spits and he tramples you (two hits
 *   of up to 8) instead of throwing it. Standing in a pool poisons you harder the longer you stay.
 * - At 33% (on his next 4-tick cycle) he screeches: no more spitting. He stares at one quarter of the room and turns
 *   to another every 8 ticks (never the same one twice); attack him from the quarter he's looking at and he spits back
 *   for 50-80.
 */
class XarpusSolo(owner: Player, session: InstanceSession, d: SoloDeps, origin: CoordGrid, copyBase: CoordGrid) :
    SoloRoom(TobRoom.XARPUS, owner, session, d, origin, copyBase) {
    override val entry = CoordGrid(3170, 4378, 1)
    override val chest = CoordGrid(3171, 4375, 1) to LocAngle.West

    override fun inArena(c: CoordGrid): Boolean = c.level == 1 && c.x in 3160..3180 && c.z in 4380..4394

    lateinit var xarpus: Npc
        private set
    private lateinit var scale: TobXarpusScalesRow
    var phase = 0
        private set
    private var hp = SOLO_HP
    private var exhumeds = 0
    private var firstExhumed = -1
    var screechAt = -1
        private set
    var heals = 0
        private set
    var gaze = -1
        private set
    private var gazeAt = 0
    private var lastSpitBack = -100
    var nextSpit = 0
        private set
    private var spitTurn = 0
    private val poolTicks = HashMap<Player, Int>()

    /** What each player has taken from him (acid, poison, trampling, spit-backs). */
    val taken = HashMap<Player, Int>()
    var dead = -1
        private set

    /** His last spit: the tick and who it went at. */
    var lastSpitAt = -1
        private set
    var lastSpitTarget: Player? = null
        private set

    class Exhumed(val loc: LocInfo, val tile: CoordGrid, val born: Int) {
        val heals = ArrayList<Int>()

        /** The tick it sinks: it heals him on every tick before this nobody stands on it. */
        val gone: Int
            get() = born + EXHUMED_LIFE
    }

    private val exhumedsUp = ArrayList<Exhumed>()
    private val pools = HashMap<CoordGrid, LocInfo>()

    val exhumedsNow: List<Exhumed>
        get() = exhumedsUp

    val pooled: Set<CoordGrid>
        get() = pools.keys

    /** The room tick he rises on, once the first exhumed is up (-1 before). */
    val riseAt: Int
        get() = if (firstExhumed < 0) -1 else firstExhumed + (scale.exhumeds - 1) * scale.exhumedGap + RISE_AFTER_LAST

    /** The tick the next exhumed comes up (-1 before the first and after the last). */
    val nextExhumedAt: Int
        get() = if (firstExhumed < 0 || exhumeds >= scale.exhumeds) -1 else firstExhumed + exhumeds * scale.exhumedGap

    /** Who his next spit goes at. */
    fun nextSpitAt(): Player? = order().let { if (it.isEmpty()) null else it[Math.floorMod(spitTurn, it.size)] }

    override fun build() {
        phase = 0
        hp = hp(SOLO_HP, entryHp(ENTRY_FIVE_HP))
        exhumeds = 0
        firstExhumed = -1
        screechAt = -1
        heals = 0
        gaze = -1
        dead = -1
        spitTurn = 0
        lastSpitAt = -1
        lastSpitTarget = null
        poolTicks.clear()
        taken.clear()
        exhumedsUp.clear()
        pools.clear()
        xarpus = spawn(COMBAT, CoordGrid(X + 1, Y + 1, 1), hp)
        transmog(xarpus, STATIC)
    }

    override fun onStart() {
        val size = (1 + helpers.size).coerceIn(1, MAX_PARTY)
        scale = TobXarpusScalesRow.all().first { it.party == size }
        hp = hp(scale.hitpoints, entryHp(ENTRY_FIVE_HP, size))
        xarpus.baseHitpointsLvl = hp
        xarpus.hitpoints = hp
        transmog(xarpus, FEEDING)
    }

    /** The order he spits in (and the bounces follow): the owner, then the others as they came in. */
    private fun order(): List<Player> = party().let { here -> (listOf(owner) + helpers).filter { it in here } }

    override fun floor(npc: Npc): Int? = if (npc === xarpus && dead < 0) 1 else null

    override fun land(npc: Npc, hit: Hit): Int? {
        if (npc !== xarpus) return null
        if (phase == 0 || dead >= 0) return 0
        // every hitsplat from the quarter he's staring at comes back: 50-75, raised by 40% of the boost his exhumed
        // heals gave him (wiki), so a scythe's three splats are three spits back
        if (phase == 3 && hit.isFromPlayer) {
            val p = attacker(hit) ?: inside()
            val seen = if (hit.queueTag == 0) p != null && quarter(real(p.coords)) == gaze else hit.queueTag == SEEN
            if (p != null && seen) {
                lastSpitBack = t
                val boost = (hp - scale.hitpoints).coerceAtLeast(0).toDouble() / scale.hitpoints
                p.spotanim("spotanim.tob_xarpus_acidsplash")
                harm(p, (rand(SPIT_BACK_MIN, SPIT_BACK_MAX) * (1 + 0.4 * boost)).toInt())
            }
        }
        return hit.damage
    }

    /** An attack thrown while he isn't looking the thrower's way doesn't come back, even if he turns before it lands. */
    override fun queued(npc: Npc, hit: org.rsmod.game.hit.HitBuilder) {
        if (npc !== xarpus || phase != 3) return
        val p = attacker(hit) ?: return
        hit.queueTag = if (quarter(real(p.coords)) == gaze) SEEN else UNSEEN
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
        val stood = party().map { real(it.coords) }.toSet()
        val up = exhumedsUp.iterator()
        while (up.hasNext()) {
            val e = up.next()
            val age = t - e.born
            if (age == EXHUMED_LIFE - 1) {
                d.worldRepo.locAnim(e.loc, "seq.tob_xarpus_exhumed_end")
                sound(EXHUMED_CLOSE_SOUND, e.tile, SOUND_RADIUS)
            }
            if (age == 1) d.worldRepo.locAnim(e.loc, "seq.tob_xarpus_exhumed_loop")
            if (age >= EXHUMED_LIFE) {
                delLoc(e.loc)
                recordExhumed(e)
                up.remove()
            } else if (age >= HEAL_FROM && e.tile !in stood) {
                e.heals += t
                heals++
                val lands = projTiles("spotanim.tob_xarpus_exhumed_energyorb", e.tile, CoordGrid(X + 2, Y + 2, 1), speed = 4, startHeight = 0, endHeight = 80)
                sound(HEAL_SHOT_SOUND, e.tile, SOUND_RADIUS)
                xarpus.anim("seq.tob_xarpus_absorb")
                // his max rises with each heal, and the heal shows as a hitsplat on him when the orb reaches him
                later(lands) {
                    sound(SWALLOW_SOUND, real(xarpus.coords), SOUND_RADIUS)
                    hp += scale.heal
                    xarpus.baseHitpointsLvl = hp
                    healNpc(xarpus, scale.heal)
                }
            }
        }
        if (firstExhumed < 0 && t >= FIRST_EXHUMED && instanceTick == CYCLE_TICK) {
            firstExhumed = t
            logger.info { "xarpus: first exhumed at t$t, clock ${d.mapClock.cycle}" }
        }
        if (firstExhumed >= 0 && exhumeds < scale.exhumeds && (t - firstExhumed) % scale.exhumedGap == 0) {
            exhumeds++
            val tile = exhumedTile()
            // a ground decoration (you stand on it), rising, looping, then sinking
            val l = loc("loc.tob_xarpus_exhumed", tile, shape = LocShape.GroundDecor)
            d.worldRepo.locAnim(l, "seq.tob_xarpus_exhumed_start")
            sound(EXHUMED_OPEN_SOUND, tile, SOUND_RADIUS)
            exhumedsUp += Exhumed(l, tile, t)
        }
        if (firstExhumed >= 0 && t >= riseAt) rise()
    }

    private fun exhumedTile(): CoordGrid {
        repeat(60) {
            val c = CoordGrid(rand(ARENA_MIN_X, ARENA_MAX_X), rand(ARENA_MIN_Y, ARENA_MAX_Y), 1)
            if (boxDist(c.x, c.z, X, Y, X + 4, Y + 4) > 0 && !blocked(c) && exhumedsUp.none { it.tile == c }) return c
        }
        return CoordGrid(3164, 4388, 1)
    }

    private fun recordExhumed(e: Exhumed) =
        recordEvent(141, "\"xarpusExhumed\":{\"spawnTick\":${e.born},\"healAmount\":${scale.heal},\"healTicks\":${e.heals}}", e.tile)

    private fun rise() {
        for (e in exhumedsUp) {
            delLoc(e.loc)
            recordExhumed(e)
        }
        exhumedsUp.clear()
        recordEvent(140, "\"xarpusPhase\":1")
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
        // the screech, on his next cycle at or under 33%
        if (phase == 2 && xarpus.hitpoints <= hp * SCREECH_PCT / 100 && instanceTick == CYCLE_TICK) {
            phase = 3
            screechAt = t
            recordEvent(140, "\"xarpusPhase\":2")
            xarpus.anim("seq.tob_xarpus_fly_up")
            mes("Xarpus begins to stare intently.")
            turn()
        }
        val party = party()
        for (p in party) poison(p)
        poolTicks.keys.retainAll(party.toSet())
        for (p in party) trample(p)
        if (phase == 2 && party.isNotEmpty() && t >= nextSpit) spit()
        if (phase == 3 && t - gazeAt >= GAZE_TICKS) turn()
    }

    fun under(c: CoordGrid): Boolean = boxDist(c.x, c.z, X, Y, X + 4, Y + 4) == 0

    /**
     * His spit at the next player in turn. Under him on the tick, they're trampled instead: he still rears to spit
     * (Blert logs the attack) but throws nothing. Where the others in turn stand now is where it bounces to.
     */
    private fun spit() {
        nextSpit = t + SPIT_EVERY
        val order = order()
        val k = Math.floorMod(spitTurn, order.size)
        spitTurn = k + 1
        val p = order[k]
        val tile = real(p.coords)
        lastSpitAt = t
        lastSpitTarget = p
        xarpus.facePlayer(p)
        xarpus.anim("seq.tob_xarpus_attack_ranged")
        recordAttack(xarpus, 10)
        if (under(tile)) return
        val next = (1 until order.size).map { real(order[(k + it) % order.size].coords) }
        val bounces = rand(1, MAX_BOUNCES)
        // Blert: the spit leaves 2 ticks after his attack and flies a tick per tile less one (from his centre)
        val lands = projTiles("spotanim.tob_xarpus_acidspit", CoordGrid(X + 2, Y + 2, 1), tile, delay = 30, speed = 30, startHeight = 90, endHeight = 0, start = 60)
        val from = t + SPIT_LEAVES
        later(lands) { splat(tile, bounceTo(tile, next, bounces), from) }
    }

    /** Blert: the next players' tiles in turn (none for one stood on the spit's own tile), then random tiles. */
    private fun bounceTo(from: CoordGrid, next: List<CoordGrid>, n: Int): List<CoordGrid?> =
        (0 until n).map { i -> if (i < next.size) next[i].takeIf { it != from } else null }

    /** The acid lands on [tile], hurting whoever stands there, and bounces on ([to]: a tile, or null for a random one). */
    private fun splat(tile: CoordGrid, to: List<CoordGrid?> = emptyList(), from: Int = t, bounced: CoordGrid? = null) {
        if (phase != 2) return
        val origin = bounced?.let { "{\"x\":${it.x},\"y\":${it.z}}" } ?: "null"
        recordEvent(142, "\"xarpusSplat\":{\"source\":${if (bounced == null) 1 else 2},\"startTick\":$from,\"bounceFrom\":$origin}", tile)
        gfx("spotanim.tob_xarpus_acidsplash", tile)
        sound(ACID_FLOOR_SOUND, tile, SOUND_RADIUS)
        if (tile !in pools) {
            val pool = loc("loc.tob_xarpus_acidpool", tile)
            pools[tile] = pool
            d.worldRepo.locAnim(pool, "seq.tob_xarpus_acid_splat_start")
            later(1) { if (pools[tile] === pool) d.worldRepo.locAnim(pool, "seq.tob_xarpus_acid_splat_loop") }
        }
        for (p in party()) if (real(p.coords) == tile) harm(p, rand(SPLAT_MIN, SPLAT_MAX))
        val targets = ArrayList<CoordGrid>(to.size)
        for (c in to) {
            if (c != null) {
                targets += c
                continue
            }
            var tries = 0
            while (tries++ < 100) {
                val r = CoordGrid(rand(ARENA_MIN_X, ARENA_MAX_X), rand(ARENA_MIN_Y, ARENA_MAX_Y), 1)
                if (r == tile || r in targets || blocked(r) || under(r)) continue
                targets += r
                break
            }
        }
        for (c in targets) {
            // Blert: a bounce leaves as the spit lands and flies a tick per tile plus one
            val lands = projTiles("spotanim.tob_xarpus_acidspit", tile, c, delay = 30, speed = 30, startHeight = 0, endHeight = 0, angle = 40, start = 0)
            val leaves = t
            later(lands) { splat(c, from = leaves, bounced = tile) }
        }
    }

    /** Under him once he's up (spitting or staring): trampled every tick, two hits of up to 8, two ticks later. */
    private fun trample(p: Player) {
        if (phase < 2 || !under(real(p.coords))) return
        repeat(2) { harm(p, rand(0, TRAMPLE_MAX), TRAMPLE_DELAY) }
    }

    private fun harm(p: Player, damage: Int, delay: Int = 1) {
        taken.merge(p, hurt(p, damage, source = xarpus, delay = delay), Int::plus)
    }

    /** Standing in a pool: poison that grows the longer you stay (2 up to 8 a tick). */
    private fun poison(p: Player) {
        if (real(p.coords) in pools) {
            val n = (poolTicks[p] ?: 0) + 1
            poolTicks[p] = n
            p.soundSynth(ACID_BURN_SOUND)
            harm(p, minOf(POISON_MAX, POISON_START + n / 2))
        } else {
            poolTicks.remove(p)
        }
    }

    /** P3: which quarter of the room he's looking at (0 NE, 1 SE, 2 SW, 3 NW). */
    fun quarter(c: CoordGrid): Int {
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
        const val SEEN = 1
        const val UNSEEN = 2
        const val COMBAT = "npc.tob_xarpus_combat"
        const val STATIC = "npc.tob_xarpus_static"
        const val FEEDING = "npc.tob_xarpus_feeding"
        const val SOLO_HP = 3750
        const val ENTRY_FIVE_HP = 3400
        const val MAX_PARTY = 5
        const val EXHUMED_OPEN_SOUND = "synth.tob_xarpus_exhumes_open"
        const val EXHUMED_CLOSE_SOUND = "synth.tob_xarpus_exhumes_close"
        const val HEAL_SHOT_SOUND = "synth.tob_xarpus_exhume_health_shots"
        const val SWALLOW_SOUND = "synth.tob_xarpus_swollowing_health"
        const val ACID_FLOOR_SOUND = "synth.tob_xarpus_acid_floor_hit"
        const val ACID_BURN_SOUND = "synth.tob_xarpus_player_acid_burn"

        /** How far his sounds carry: his attack animation's sound reach in the cache. */
        const val SOUND_RADIUS = 7
        const val FIRST_EXHUMED = 8
        const val EXHUMED_LIFE = 11
        const val HEAL_FROM = 3
        const val RISE_AFTER_LAST = 20
        const val MAX_BOUNCES = 2
        const val CYCLE_TICK = 1
        const val CYCLE = 4
        const val FIRST_SPIT = 7
        const val SPIT_EVERY = 4
        const val SPIT_LEAVES = 2
        const val DAWNBRINGER = "obj.verzik_special_weapon"
        const val TRAMPLE_MAX = 8
        const val TRAMPLE_DELAY = 2
        const val ARENA_MIN_X = 3163
        const val ARENA_MAX_X = 3177
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
