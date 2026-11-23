package org.rsmod.content.raids.tob.solo

import dev.openrune.rscm.RSCM.asRSCM
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.player.lockOverheads
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid

/**
 * Sotetseg, solo (3000 hitpoints).
 *
 * - She attacks every 5 ticks from tick 6: a magic orb at you (solo it's always magic) that lands 7 ticks later for up to 50,
 *   nothing on the right prayer - an orb taken off prayer knocks your overheads off for 5 ticks; or, if you're beside
 *   her, half the time a horn lunge (up to 45, 40% off on Protect from Melee). Every 11th orb is the death ball: her
 *   next attack is skipped and it lands where her third would (70 in a solo).
 * - At 66.6% and 33.3% the maze: she goes still, and 3 ticks later you're taken to the Shadow Realm - you're the chosen
 *   one in a solo. The path is lit under you; walk it (or not - nothing hurts you there but the realm itself, 1-3
 *   every 7 ticks) and leave by the portal at the top. Every path tile you step on below is lit above.
 * - Back in the room you come in below the maze and must cross it to her: any tile off the path hurts you as you stand
 *   on it. She wakes (Defence back to full, attacking at once) on the first tick of the 4-tick instance cycle at
 *   least two ticks after you reach the top - from Blert recordings of real solos, which fit this every time.
 * - The maze procs on the landing of the hit that brings her to the threshold; you're held still until the
 *   teleport 3 ticks later. Every 11th orb (melees don't count, mazes don't reset it) is the death ball.
 */
class SoteSolo(owner: Player, session: InstanceSession, d: SoloDeps, origin: CoordGrid, copyBase: CoordGrid) :
    SoloRoom(TobRoom.SOTETSEG, owner, session, d, origin, copyBase) {
    override val entry = CoordGrid(3279, 4305, 0)
    override val chest = CoordGrid(3283, 4302, 0) to LocAngle.North

    override fun inArena(c: CoordGrid): Boolean = (c.level == 0 && c.x in 3268..3292 && c.z in 4308..4340) || (c.level == 3 && c.x in 3340..3380 && c.z in 4300..4340)

    private lateinit var sote: Npc
    private var nextAttack = FIRST_ATTACK
    private var orbs = 0
    private var skip = false
    private var epoch = 0
    private var mazes = 0
    private var dead = -1

    /** The current maze: the path's tiles (columns by row, row 0 the first one you step on) and its state. */
    private var maze: Array<BooleanArray>? = null
    private var mazeAt = -1
    private var under = false
    private var crossed = -1
    private val lit = ArrayList<LocInfo>()
    private val litAbove = HashSet<Pair<Int, Int>>()

    override fun build() {
        nextAttack = FIRST_ATTACK
        orbs = 0
        skip = false
        mazes = 0
        dead = -1
        endMaze(quiet = true)
        sote = spawn(IDLE, CoordGrid(X, Y, 0), hp(HP, entryHp(2800)))
        // nobody walks under Sotetseg
        blockPlayers(CoordGrid(X, Y, 0), SIZE, true)
    }

    override fun onStart() {
        transmog(sote, COMBAT)
    }

    override fun floor(npc: Npc): Int? {
        if (npc !== sote) return null
        if (dead >= 0) return 0
        // the hit that procs a maze stops at its threshold
        if (maze != null) return if (mazes == 1) maze1 else maze2
        return when (mazes) {
            0 -> maze1
            1 -> maze2
            else -> 1
        }
    }

    /** Her mazes come at two thirds and one third of her hitpoints. */
    private val maze1: Int
        get() = sote.baseHitpointsLvl * MAZE_1 / HP

    private val maze2: Int
        get() = sote.baseHitpointsLvl * MAZE_2 / HP

    override fun land(npc: Npc, hit: Hit): Int? {
        if (npc !== sote) return null
        if (maze != null || dead >= 0) return 0
        // the maze procs on the landing of the hit that takes her to the threshold (the floor holds her there)
        val threshold = when (mazes) {
            0 -> maze1
            1 -> maze2
            else -> return null
        }
        if (sote.hitpoints - hit.damage <= threshold) startMaze()
        return null
    }

    /** The world clock: the maze's timings run on it so a hit landing before or after the room's tick agrees. */
    private val now: Int
        get() = d.mapClock.cycle

    override fun step() {
        showCycle()
        if (dead >= 0) {
            if (t - dead == DEATH_TICKS) {
                cleared = true
                despawn(sote)
                blockPlayers(CoordGrid(X, Y, 0), SIZE, false)
                mes("The way on is open: go back out through the barrier for Xarpus.")
            }
            return
        }
        if (maze != null) {
            mazeTick()
            if (maze != null) return
        }
        val hp = sote.hitpoints
        if ((mazes == 0 && hp <= maze1) || (mazes == 1 && hp <= maze2)) {
            startMaze()
            return
        }
        if (hp <= 1) {
            dead = t
            sote.hitpoints = 0
            sote.anim(DEATH_SEQ)
            mes("Sotetseg is dead.")
            return
        }
        val p = inside() ?: return
        if (t >= nextAttack) attack(p)
    }

    /* ---- her attacks */

    private fun attack(p: Player) {
        nextAttack = t + ATTACK_EVERY
        // she can't reach a player back on the entrance platform, south of the maze
        val here = real(p.coords)
        if (here.level != 0 || here.z < OVER_Y) return
        if (skip) {
            skip = false
            return
        }
        sote.facePlayer(p)
        val adjacent = boxDist(here.x, here.z, X, Y, X + SIZE - 1, Y + SIZE - 1) == 1
        if (adjacent && chance(0.5)) {
            sote.anim(MELEE_SEQ)
            recordAttack(sote, 7)
            val roll = rand(0, MELEE_MAX)
            val e = epoch
            later(1) {
                val q = inside() ?: return@later
                if (e != epoch) return@later
                val r = real(q.coords)
                if (boxDist(r.x, r.z, X, Y, X + SIZE - 1, Y + SIZE - 1) != 1) return@later
                hurt(q, if (prays(q, "melee")) roll * 6 / 10 else roll, source = sote)
            }
            return
        }
        sote.anim(ORB_SEQ)
        orbs++
        if (orbs % DEATH_BALL_EVERY == 0) {
            recordAttack(sote, 9)
            deathBall(p)
            return
        }
        recordAttack(sote, 8)
        // her main orb at the player she's attacking is always magic (the ranged orbs go to the rest of a team)
        val magic = true
        val lands = projLanding(if (magic) "spotanim.tob_sotetseg_maging" else "spotanim.tob_sotetseg_ranging", p, ORB_TICKS)
        val roll = rand(0, ORB_MAX)
        val prayer = if (magic) "magic" else "missiles"
        val e = epoch
        later(lands) {
            val q = inside() ?: return@later
            if (e != epoch) return@later
            if (prays(q, prayer)) return@later
            hurt(q, roll, source = sote)
            q.lockOverheads(PRAYER_OFF)
        }
    }

    /** A projectile at [p] timed to land in [ticks] ticks. */
    private fun projLanding(spot: String, p: Player, ticks: Int): Int {
        val src = sote.coords.translate(SIZE / 2, SIZE / 2)
        val dist = maxOf(kotlin.math.abs(src.x - p.coords.x), kotlin.math.abs(src.z - p.coords.z)).coerceAtLeast(1)
        val start = 30
        val end = ticks * 30 - 5
        val pa = org.rsmod.game.proj.ProjAnim(
            spotanim = spot.asRSCM(dev.openrune.rscm.RSCMType.SPOTANIM),
            startHeight = 110,
            endHeight = 31,
            startTime = start,
            endTime = end,
            angle = 10,
            progress = SIZE * 64,
            sourceIndex = sote.slotId + 1,
            targetIndex = -(p.slotId + 1),
            startCoord = src,
            endCoord = p.coords,
        )
        d.worldRepo.projAnim(pa)
        return ticks
    }

    private fun deathBall(p: Player) {
        skip = true
        val lands = projLanding("spotanim.tob_sotetseg_sharedattack", p, DEATH_BALL_TICKS)
        val e = epoch
        later(lands) {
            val q = inside() ?: return@later
            if (e != epoch) return@later
            q.spotanim("spotanim.tob_sotetseg_sharedattack_impact")
            hurt(q, if (entryMode) DEATH_BALL_ENTRY * 2 else DEATH_BALL_SOLO, source = sote)
        }
    }

    /* ---- the maze */

    private fun startMaze() {
        mazes++
        epoch++
        skip = false
        mazeAt = now
        recordEvent(130, "\"soteMaze\":{\"maze\":${mazes - 1}}")
        under = false
        crossed = -1
        transmog(sote, IDLE)
        sote.facePlayer(owner)
        maze = roll()
        // everyone is held where they stand until the teleport
        inside()?.let {
            it.anim("seq.human_teleport_other_impact")
            it.frozen = true
            it.routeDestination.clear()
        }
        mes("Sotetseg chooses you as her target!")
    }

    /** A maze as the game rolls them: 8 columns, each within 5 of the last; odd rows run sideways between them. */
    private fun roll(): Array<BooleanArray> {
        val seed = IntArray(TURNS)
        seed[0] = rand(1, W - 1)
        for (i in 1 until TURNS) seed[i] = rand(maxOf(seed[i - 1] - MAX_STEP, 0), minOf(seed[i - 1] + MAX_STEP, W - 1))
        // rows numbered from the bottom (row 0 is the first you step on)
        val m = Array(W) { BooleanArray(H) }
        var s = 0
        var x = seed[0]
        for (top in H - 1 downTo 0) {
            val row = H - 1 - top
            if (top % 2 == 1) {
                val nx = seed[++s]
                for (i in minOf(x, nx)..maxOf(x, nx)) m[i][row] = true
                x = nx
            } else {
                m[x][row] = true
            }
        }
        return m
    }

    /** A lit path tile, glowing as it comes up. */
    private fun glow(c: CoordGrid): LocInfo {
        val l = loc(LIT, c, shape = LocShape.GroundDecor)
        d.worldRepo.locAnim(l, "seq.tob_sotetseg_tile_glow")
        return l
    }

    private fun mazeTick() {
        val m = maze ?: return
        val since = now - mazeAt
        val p = player()
        if (since == TELEPORT && p != null) {
            under = true
            p.clearQueue("queue.hit")
            p.frozen = false
            jump(p, at(UNDER_START))
            // the path, lit for the chosen one
            for (x in 0 until W) for (y in 0 until H) if (m[x][y]) lit += glow(CoordGrid(UNDER_X + x, UNDER_Y + y, 3))
            return
        }
        if (p == null) return
        val here = real(p.coords)
        if (under && here.level == 3) {
            if (since > TELEPORT && (since - TELEPORT) % REALM_EVERY == 0) {
                p.spotanim("spotanim.tob_sotetseg_drain")
                hurt(p, rand(1, 3))
            }
            val x = here.x - UNDER_X
            val y = here.z - UNDER_Y
            if (x in 0 until W && y in 0 until H && m[x][y] && litAbove.add(x to y)) lit += glow(CoordGrid(OVER_X + x, OVER_Y + y, 0))
            return
        }
        if (here.level != 0) return
        under = false
        if (crossed >= 0) {
            // she wakes on the first tick of the instance's 4-tick cycle at least two ticks after you reach the top
            if (now - crossed >= WAKE && cycle == 0) endMaze(quiet = false)
            return
        }
        val x = here.x - OVER_X
        val y = here.z - OVER_Y
        if (x in 0 until W && y in 0 until H && !m[x][y]) {
            gfx("spotanim.tob_sotetseg_zap", here)
            hurt(p, p.hitpoints * 667 / 10000 + MAZE_HIT)
        }
        if (here.z >= OVER_Y + H) {
            crossed = now
            if (WAKE == 0 && cycle == 0) endMaze(quiet = false)
        }
    }

    override suspend fun opLoc(name: String, at: CoordGrid, op: Int, access: ProtectedAccess): Boolean {
        if (name != "loc.tob_sotetseg_darkrealm_exit") return false
        if (maze == null) return true
        jump(access.player, this.at(RETURN))
        under = false
        return true
    }

    private fun endMaze(quiet: Boolean) {
        for (l in lit) delLoc(l)
        lit.clear()
        litAbove.clear()
        maze = null
        if (quiet) return
        epoch++
        transmog(sote, COMBAT)
        sote.defenceLvl = sote.baseDefenceLvl
        nextAttack = t
    }

    override fun autoTarget(): Npc? = if (maze == null) sote else null

    override val autoStart: CoordGrid
        get() = CoordGrid(3279, 4325, 0)

    override fun autoAct(p: Player) {
        val m = maze ?: return
        val here = real(p.coords)
        if (here.level == 3 && now - mazeAt > TELEPORT + 6) {
            jump(p, at(RETURN))
            under = false
        } else if (here.level == 0 && now - mazeAt > TELEPORT + 8 && crossed < 0) {
            // run straight up the path's first column
            val col = (0 until W).first { m[it][0] }
            jump(p, at(OVER_X + col, OVER_Y + H, 0))
        }
    }

    override fun onCleanup() {
        blockPlayers(CoordGrid(X, Y, 0), SIZE, false)
    }

    override fun barBoss(): Npc? = sote

    override fun debug(): String = "sote ${sote.hitpoints} mazes $mazes inMaze ${maze != null} under $under orbs $orbs next $nextAttack"

    private companion object {
        const val X = 3277
        const val Y = 4326
        const val SIZE = 5
        const val HP = 3000
        const val MAZE_1 = 2000
        const val DEATH_BALL_ENTRY = 15
        const val MAZE_2 = 1000
        const val IDLE = "npc.tob_sotetseg_noncombat"
        const val COMBAT = "npc.tob_sotetseg_combat"
        const val FIRST_ATTACK = 6
        const val ATTACK_EVERY = 5
        const val ORB_TICKS = 7
        const val ORB_MAX = 50
        const val MELEE_MAX = 45
        const val PRAYER_OFF = 5
        const val DEATH_BALL_EVERY = 11
        const val DEATH_BALL_TICKS = 15
        const val DEATH_BALL_SOLO = 70
        const val ORB_SEQ = "seq.tob_sotetseg_attack_ranged"
        const val MELEE_SEQ = "seq.tob_sotetseg_attack_melee"
        const val DEATH_SEQ = "seq.tob_sotetseg_death"
        const val DEATH_TICKS = 5
        const val W = 14
        const val H = 15
        const val TURNS = 8
        const val MAX_STEP = 5
        const val OVER_X = 3273
        const val OVER_Y = 4310
        const val UNDER_X = 3354
        const val UNDER_Y = 4311
        val UNDER_START = CoordGrid(3360, 4309, 3)
        val RETURN = CoordGrid(3274, 4307, 0)
        const val TELEPORT = 3
        const val REALM_EVERY = 7
        const val MAZE_HIT = 15
        const val WAKE = 2
        const val WAKE_CYCLE = 4
        const val LIT = "loc.tob_sotetseg_lighttile"
    }
}
