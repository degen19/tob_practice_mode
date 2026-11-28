package org.rsmod.content.raids.tob.bloat.sote

import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.MoveRestrict
import dev.openrune.types.NpcMode
import kotlin.math.abs
import kotlin.math.max
import org.rsmod.api.config.constants
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.player.hit.modifier.PlayerHitModifier
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.content.raids.tob.bloat.MaidenRules
import org.rsmod.content.raids.tob.bloat.RaidBots
import org.rsmod.content.raids.tob.bloat.RaidLook
import org.rsmod.content.raids.tob.bloat.RoomEntry
import org.rsmod.content.raids.tob.bloat.maiden.SimRng
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.game.obj.Obj
import org.rsmod.game.proj.ProjAnim
import org.rsmod.map.CoordGrid

/**
 * A live Sotetseg: the sim's Sotetseg ([SoteEngine]) runs it tick by tick for a team of 1-5 bots, and this room shows
 * it. They flinch round her on the team's tiles - a tile back between swings, in on her attack tick - and attack as the
 * engine has them; her orbs fly at them and split from them, the death ball comes down on them, and their HP is the
 * engine's, hit by hit. On t12 the west bot drops a shark by her south-west corner. At a maze everyone is caught (the
 * teleport animation), the floor darkens, and 3 ticks later one goes under (out of sight; their tile glows above) and
 * the rest are teleported south of the maze. They run up its first column to the shark through NPC Contact (the stall),
 * the wrong tiles zapping them, and take the stall's hits coming off it; a shark skipper runs up column 11 if others
 * stall; a solo goes under first, then crosses; a run maze: they follow its best route. Once anyone is past the third
 * row the tornado walks the path from its start. Off the maze their first click is her: they swing from the tile beside
 * her nearest them, then go back to their own, swinging off cooldown on the way. One seed, one raid: the same as the
 * index's ([SoteIndex]).
 */
class SoteLiveRoom(
    val session: InstanceSession,
    val sote: Npc,
    val seed: String,
    val team: Int,
    val way: String,
    private val at: (Int, Int) -> CoordGrid,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val locRepo: LocRepository,
    private val worldRepo: WorldRepository,
    private val npcHits: NpcHitModifier,
    private val playerHits: PlayerHitModifier,
    private val protectedAccess: ProtectedAccessLauncher,
    private val clicks: RaidBots,
    private val look: RaidLook,
) {
    val bots = ArrayList<Player>()
    var log = false
    private val acts = HashMap<Pair<Int, Int>, String>()
    private var her = ""
    private var lit = ""
    private var shown = 0
    private val lastHp = IntArray(team) { 99 }
    private val stallHits = HashMap<Int, List<Int>>()
    private val later = HashMap<Int, MutableList<() -> Unit>>()
    private var maze: Visual? = null
    val engine = SoteEngine(SotePlan.of(team, way), SimRng(seed), Shows())
    private var clock = -PRE_TICKS
    private var idle = 0
    private var shark: Obj? = null
    private val busyUntil = HashMap<Int, Int>()
    private val floor = HashMap<Pair<Int, Int>, LocInfo>()
    private var tornado: Npc? = null
    private var tornadoStep = 0

    /**
     * The maze as shown: its tiles and path (the run's own maze, or one rolled for the look when it's skipped), who
     * crosses it (on it from [from], the teleport), the route the player under runs below from [underFrom], and the glow
     * above on their tile.
     */
    private class Visual(val maze: SoteMaze.Maze, val crossers: List<SoteEngine.P>, val from: Int, val underFrom: Int?) {
        val tiles = SoteMaze.tiles(maze.seed)
        val path = SoteMaze.path(tiles, maze.seed)
        var glow: Pair<Pair<Int, Int>, LocInfo>? = null
    }

    val finished: Boolean
        get() = idle > END_HOLD + 2

    fun botOf(p: SoteEngine.P): Player? = bots.getOrNull(p.i)

    fun stackTile(i: Int): CoordGrid = SoteRules.SPOTS.getValue(team)[i].let { (x, y) -> at(x, y) }

    private fun outTile(i: Int): CoordGrid = SoteRules.OUT.getValue(team)[i].let { (x, y) -> at(x, y) }

    private fun dist(a: CoordGrid, b: CoordGrid) = max(abs(a.x - b.x), abs(a.z - b.z))

    /**
     * The flinch: out of her reach between swings - in from the tick before a swing, out again the tick after it. Away
     * from their own tile (off a maze) a swing goes from the tile beside her nearest them, and they walk on home.
     */
    private fun flinch(p: SoteEngine.P, bot: Player) {
        if (engine.blocked || p.dead || clock <= ARRIVED || engine.t < busyUntil.getOrDefault(p.i, 0)) return
        val home = stackTile(p.i)
        val want =
            if (p.next != engine.t + 1) outTile(p.i)
            else if (dist(bot.coords, home) <= 2) home
            else BESIDE.map { (x, y) -> at(x, y) }.minBy { dist(it, bot.coords) }
        if (bot.coords != want) clicks.walk(bot, want)
    }

    /** Where each bot waits before the raid: just south of the barrier, on the column it comes in by. */
    fun outsideTile(i: Int): CoordGrid = at(SoteRules.ENTRY_COLUMNS.getValue(team)[i], SoteRules.BARRIER_Y - 1)

    private val entry = RoomEntry(clicks, at(SoteRules.BARRIER_X, SoteRules.BARRIER_Y), pass = -1, run = RUN_FROM)

    private fun go(bot: Player, to: CoordGrid) {
        protectedAccess.launch(bot) { telejump(to, TeleportType.Exempt) }
    }

    private fun at(t: Int, f: () -> Unit) {
        later.getOrPut(t) { ArrayList() } += f
    }

    private fun start() = at(SoteRules.CROSS_X, SoteRules.CROSS_START_Y)

    private fun mazeTile(x: Int, y: Int) = at(SoteRules.MAZE_X + x, SoteRules.MAZE_A + SoteMaze.H - 1 - y)

    /**
     * At the proc: everyone caught where they stand (the teleport animation), the floor dark; 3 ticks later the player
     * under to the shadow realm, the rest south of the maze (a solo under first).
     */
    private fun teleport(t: Int, under: SoteEngine.P?, crossers: List<SoteEngine.P>) {
        for (p in engine.ps) if (!p.dead) botOf(p)?.anim(TELEPORT_SEQ)
        paintFloor(DARK)
        at(t + SoteRules.TELEPORT_AFTER) {
            under?.let { u -> botOf(u)?.let { go(it, at(SoteRules.UNDER_X, SoteRules.UNDER_Y)) } }
            for (p in crossers) botOf(p)?.let { go(it, if (team == 1) at(SoteRules.UNDER_X, SoteRules.UNDER_Y) else start()) }
        }
    }

    /** The bot on the west-most tile drops the shark the crossers click, then steps back. */
    private fun dropShark() {
        val i = SoteRules.SPOTS.getValue(team).withIndex().minBy { it.value.first }.index
        val bot = bots.getOrNull(i) ?: return
        val tile = at(SoteRules.CROSS_X, SoteRules.SHARK_Y.getValue(team))
        busyUntil[i] = SoteRules.SHARK_TICK + 2
        clicks.walk(bot, tile)
        at(SoteRules.SHARK_TICK + 1) {
            shark = objRepo.add(SoteRules.SHARK, tile, Int.MAX_VALUE, reveal = 0)
            clicks.walk(bot, stackTile(i))
        }
    }

    fun tick() {
        clock++
        sote.mode = NpcMode.None
        val landing = shown
        shown = 0
        if (clock > 0) {
            if (engine.kill == null) engine.tick() else idle++
        }
        later.remove(engine.t)?.forEach { it() }
        // through the barrier as the room starts, then the run north to the stack (on its tiles by t10, as on Blert)
        entry.tick(clock, bots)
        if (clock == RUN_FROM) for (p in engine.ps) botOf(p)?.let { clicks.walk(it, stackTile(p.i)) }
        if (clock == SoteRules.SHARK_TICK) dropShark()
        if (idle == END_HOLD && sote.isSlotAssigned) npcRepo.del(sote, Int.MAX_VALUE)
        // hitsplats land after this, a cycle after they were queued: the engine's HP already has them taken off
        if (sote.isSlotAssigned) sote.hitpoints = max(1, engine.hp) + landing
        for (p in engine.ps) {
            val bot = botOf(p) ?: continue
            if (!look.wearing(bot)) look.equip(bot, kit(p.prefire))
            if (engine.kill == null) flinch(p, bot)
            if (!engine.blocked && (clock > ARRIVED || bot.coords == stackTile(p.i))) bot.faceNpc(sote)
            bot.runEnergy = constants.run_max_energy
            showHp(p, bot)
        }
        mazeTick()
        if (TRACE && clock > 0) trace()
    }

    /**
     * Their HP is the engine's: what it lost this tick goes out as hitsplats (a stall's hits one by one). They land later
     * this cycle, so the HP is lifted by them first (a splat must never kill a bot) and set to the engine's in [late].
     */
    private fun showHp(p: SoteEngine.P, bot: Player) {
        val now = max(0, p.hp)
        val hits = stallHits.remove(p.i) ?: (lastHp[p.i] - now).takeIf { it > 0 }?.let { listOf(it) }.orEmpty()
        lastHp[p.i] = now
        bot.statMap.setCurrentLevel("stat.hitpoints", (max(1, now) + hits.sum()).coerceAtMost(255).toByte())
        for (d in hits) if (sote.isSlotAssigned) bot.queueHit(sote, 1, HitType.Typeless, d, playerHits) else bot.queueHit(1, HitType.Typeless, d, playerHits)
    }

    /** After the cycle's hits have landed: each bot's HP is the engine's. */
    fun late() {
        for (p in engine.ps) botOf(p)?.statMap?.setCurrentLevel("stat.hitpoints", max(1, p.hp).coerceAtMost(255).toByte())
    }

    /* ---- the maze, as shown */

    private fun paintFloor(type: String) {
        for (x in 0 until SoteMaze.W) for (y in 0 until SoteMaze.H) {
            floor.remove(x to y)?.let { locRepo.del(it, Int.MAX_VALUE) }
            floor[x to y] = locRepo.add(mazeTile(x, y), type, Int.MAX_VALUE, LocAngle.West, LocShape.GroundDecor)
        }
    }

    private fun mazeTick() {
        val v = maze ?: return
        if (!engine.blocked) {
            endMaze(v)
            return
        }
        // the glow above on the tile the player under stands on below, as they run the path from its first tile
        v.underFrom?.let { from -> glowAt(v, v.maze.route.getOrNull(engine.t - from)?.let { it.x to it.y }) }
        var past = false
        if (engine.t >= v.from) for (p in v.crossers) {
            val bot = botOf(p) ?: continue
            val x = bot.coords.x - at(SoteRules.MAZE_X, SoteRules.MAZE_A).x
            val row = bot.coords.z - at(SoteRules.MAZE_X, SoteRules.MAZE_A).z
            if (x !in 0 until SoteMaze.W || row !in 0 until SoteMaze.H) continue
            if (!v.tiles[x][SoteMaze.H - 1 - row]) bot.spotanim(ZAP, height = 0)
            if (row >= SoteRules.TORNADO_ROW) past = true
        }
        tornadoTick(v, past)
    }

    /** Once anyone is past the third row the tornado walks the path from its start, a tile a tick; with nobody past it, it goes. */
    private fun tornadoTick(v: Visual, past: Boolean) {
        if (!past) {
            tornado?.let { if (it.isSlotAssigned) npcRepo.del(it, Int.MAX_VALUE) }
            tornado = null
            return
        }
        val tor = tornado
        if (tor == null || !tor.isSlotAssigned || tornadoStep >= v.path.size - 1) {
            tor?.let { if (it.isSlotAssigned) npcRepo.del(it, Int.MAX_VALUE) }
            tornadoStep = 0
            val npc = Npc(SoteRules.TORNADO, v.path[0].let { mazeTile(it.x, it.y) })
            npcRepo.add(npc, Int.MAX_VALUE)
            npc.respawns = false
            npc.ignoreCombatInteractions = true
            npc.moveRestrict = MoveRestrict.PassThru
            npc.defaultMoveSpeed = MoveSpeed.Walk
            tornado = npc
        } else {
            tornadoStep++
            tor.walk(v.path[tornadoStep].let { mazeTile(it.x, it.y) })
        }
    }

    private fun glowAt(v: Visual, tile: Pair<Int, Int>?) {
        if (v.glow?.first == tile) return
        v.glow?.let { (xy, l) ->
            locRepo.del(l, Int.MAX_VALUE)
            floor[xy] = locRepo.add(mazeTile(xy.first, xy.second), DARK, Int.MAX_VALUE, LocAngle.West, LocShape.GroundDecor)
        }
        v.glow = null
        if (tile == null) return
        floor.remove(tile)?.let { locRepo.del(it, Int.MAX_VALUE) }
        v.glow = tile to locRepo.add(mazeTile(tile.first, tile.second), LIT, Int.MAX_VALUE, LocAngle.West, LocShape.GroundDecor)
    }

    private fun endMaze(v: Visual) {
        v.glow?.let { locRepo.del(it.second, Int.MAX_VALUE) }
        tornado?.let { if (it.isSlotAssigned) npcRepo.del(it, Int.MAX_VALUE) }
        tornado = null
        maze = null
        paintFloor(PLAIN)
    }

    /** A projectile from [from] (her, or a player an orb split from) to [to], timed to land in [ticks] ticks. */
    private fun proj(spot: String, from: Any, to: Player, ticks: Int) {
        val src = if (from is Npc) from.coords.translate(SoteRules.SIZE / 2, SoteRules.SIZE / 2) else (from as Player).coords
        val pa =
            ProjAnim(
                spotanim = spot.asRSCM(RSCMType.SPOTANIM),
                startHeight = if (from is Npc) 110 else 60,
                endHeight = 31,
                startTime = PROJ_START,
                endTime = ticks * CYCLES - 5,
                angle = 10,
                progress = if (from is Npc) SoteRules.SIZE * 64 else 0,
                sourceIndex = if (from is Npc) from.slotId + 1 else -((from as Player).slotId + 1),
                targetIndex = -(to.slotId + 1),
                startCoord = src,
                endCoord = to.coords,
            )
        worldRepo.projAnim(pa)
    }

    /** `SOTE_TRACE`: one line a tick - her HP; each bot's room tile, level, HP, what it did and its HP in game; a run's lit route; the tornado's tile. */
    private fun trace() {
        val base = at(SoteRules.X, SoteRules.Y)
        val dx = base.x - SoteRules.X
        val dz = base.z - SoteRules.Y
        val bs = engine.ps.joinToString(";") { p ->
            val c = botOf(p)?.coords
            "${p.i},${(c?.x ?: 0) - dx},${(c?.z ?: 0) - dz},${c?.level ?: 0},${p.hp},${acts.remove(engine.t to p.i) ?: ""},${botOf(p)?.hitpoints ?: 0}"
        }
        val tor = tornado?.takeIf { it.isSlotAssigned }?.let { "${it.coords.x - dx},${it.coords.z - dz}" } ?: ""
        println("sotetrace $seed ${engine.t} ${engine.hp} ${if (engine.blocked) 1 else 0} $her|$bs|$lit|$tor")
        her = ""
        lit = ""
    }

    fun remove() {
        if (sote.isSlotAssigned) npcRepo.del(sote, Int.MAX_VALUE)
        shark?.let { objRepo.del(it) }
        tornado?.let { if (it.isSlotAssigned) npcRepo.del(it, Int.MAX_VALUE) }
        maze?.glow?.let { locRepo.del(it.second, Int.MAX_VALUE) }
        for (l in floor.values) locRepo.del(l, Int.MAX_VALUE)
        floor.clear()
    }

    private fun kit(act: String): MaidenRules.Kit = MaidenRules.KITS.getValue(KIT[act] ?: "S")

    private inner class Shows : SoteEngine.Events {
        override fun attack(t: Int, p: SoteEngine.P, act: String, lands: Int) {
            acts[t to p.i] = act
            val bot = botOf(p) ?: return
            if (act == "horn") return bot.say("*Soulflame horn*")
            if (clock <= 0) return
            look.attack(bot, kit(act), sote, scythe = act == "scythe")
        }

        override fun hit(t: Int, p: SoteEngine.P, amt: Int) {
            val bot = botOf(p) ?: return
            if (!sote.isSlotAssigned) return
            sote.queueHit(bot, 1, HitType.Typeless, amt, npcHits)
            shown += amt
        }

        override fun herAttack(t: Int, p: SoteEngine.P, kind: String) {
            her = "$kind:${p.i}"
            val bot = botOf(p) ?: return
            sote.facePlayer(bot)
            if (kind == "melee") return sote.anim(SoteRules.MELEE)
            sote.anim(SoteRules.ATTACK)
            if (kind == "ball") proj(SoteRules.BALL, sote, bot, SoteEngine.BALL_LANDS) else proj(SoteRules.ORB, sote, bot, SoteEngine.ORB_DELAY)
        }

        override fun orbSplit(t: Int, from: SoteEngine.P, to: SoteEngine.P, style: String, lands: Int) {
            val a = botOf(from) ?: return
            val b = botOf(to) ?: return
            proj(if (style == "range") SoteRules.ORB_RANGE else SoteRules.ORB, a, b, lands - t)
        }

        override fun orbLands(t: Int, p: SoteEngine.P, style: String, d: Int, prayed: Boolean) {
            // a hit shows as the HP it takes (showHp); a prayed or missed orb shows its 0
            val bot = botOf(p) ?: return
            if (d == 0 && sote.isSlotAssigned) bot.queueHit(sote, 1, HitType.Magic, 0, playerHits)
        }

        override fun ballLands(t: Int, on: List<SoteEngine.P>) {
            for (p in on) botOf(p)?.spotanim(BALL_IMPACT)
        }

        override fun veng(t: Int, p: SoteEngine.P, amt: Int) {
            botOf(p)?.say("Taste vengeance!")
        }

        override fun maze(t: Int, n: Int, m: Int, under: SoteEngine.P?, back: Int) {
            val crossers = engine.ps.filter { !it.dead && it !== under }
            val skippers = crossers.filter { (n - 1) in it.sharkMazes }
            val stallers = crossers - skippers.toSet()
            teleport(t, under, crossers)
            // a skipped maze is rolled for the look only, apart from the engine's numbers
            val rolled = SoteMaze.roll(SimRng("$seed|maze$n"))
            maze = Visual(rolled, crossers, t + SoteRules.TELEPORT_AFTER, under?.let { t + 4 + SoteMaze.underToStart(rolled.start) })
            if (team == 1) at(m - 3) { for (p in crossers) botOf(p)?.let { go(it, start()) } }
            // the stallers run up column 1 to the shark; a shark skipper alone does too, else up column 11, away from them
            val skipTop = if (stallers.isEmpty()) SoteRules.CROSS_X else SoteRules.SKIP_X
            at(m - 1) {
                for (p in stallers) botOf(p)?.let { clicks.walk(it, at(SoteRules.CROSS_X, SoteRules.CROSS_TOP_Y)) }
                for (p in skippers) botOf(p)?.let { clicks.walk(it, at(skipTop, SoteRules.CROSS_TOP_Y)) }
            }
            at(m + 1) {
                for (p in stallers) {
                    val bot = botOf(p) ?: continue
                    bot.anim(RSCM.getReverseMapping(RSCMType.SEQ, SoteRules.CONTACT_SEQ))
                    bot.spotanim(RSCM.getReverseMapping(RSCMType.SPOTANIM, SoteRules.CONTACT_GFX), height = 96)
                }
            }
            under?.let { u -> at(back) { botOf(u)?.let { go(it, stackTile(u.i)) } } }
        }

        /**
         * A run maze: the runners go south of 3A, then 5A, to the first tile and along the best route, wait on row N and
         * step off when they click off; the player under runs it below, out of sight, their tile glowing above.
         */
        override fun mazeRun(t: Int, n: Int, run: SoteEngine.Run, under: SoteEngine.P?) {
            val runners = engine.ps.filter { !it.dead && it !== under }
            teleport(t, under, runners)
            maze = Visual(run.maze, runners, t + SoteRules.TELEPORT_AFTER, under?.let { run.light })
            val route = run.maze.route.map { mazeTile(it.x, it.y) }
            fun all(f: (Player) -> Unit) = runners.forEach { p -> botOf(p)?.let(f) }
            at(t + 5) { all { clicks.walk(it, at(SoteRules.MAZE_X + 3, SoteRules.MAZE_A - 1)) } }
            at(t + 6) { all { clicks.walk(it, at(SoteRules.MAZE_X + 5, SoteRules.MAZE_A - 1)) } }
            at(run.onFirst - SoteMaze.runnersToStart(run.maze.start)) { all { clicks.walk(it, route[0]) } }
            for (k in 1..run.maze.toN) at(run.onFirst + k - 1) { all { clicks.walk(it, route[k]) } }
            at(run.light) { lit = run.maze.route.joinToString(";") { "${it.x},${SoteMaze.H - 1 - it.y}" } }
            under?.let { u -> at(run.clickOff + 2) { botOf(u)?.let { go(it, stackTile(u.i)) } } }
        }

        override fun stallOut(t: Int, p: SoteEngine.P, hits: List<Int>, pneck: Boolean) {
            stallHits[p.i] = hits.take(MAX_SPLATS)
        }

        override fun dead(t: Int) {
            sote.anim(SoteRules.DEATH)
        }
    }

    companion object {
        const val PRE_TICKS = 7

        /** The team runs from the barrier on t2 and is on its tiles by t10 (Blert's 5s raids). */
        const val RUN_FROM = 2
        const val ARRIVED = 10
        const val END_HOLD = 8

        /** A tick in client cycles; a projectile leaves 30 cycles in. */
        const val CYCLES = 30
        const val PROJ_START = 30

        /** Hitsplats a stall shows (the client draws a few a tick; a redemption stall queues many more 1s). */
        const val MAX_SPLATS = 8
        const val TELEPORT_SEQ = "seq.human_teleport_other_impact"
        const val ZAP = "spotanim.tob_sotetseg_zap"
        const val BALL_IMPACT = "spotanim.tob_sotetseg_sharedattack_impact"
        const val LIT = "loc.tob_sotetseg_lighttile"
        const val PLAIN = "loc.tob_sotetseg_plaintile"
        const val DARK = "loc.tob_sotetseg_darktile"
        private val TRACE = System.getenv("SOTE_TRACE") != null
        val KIT = mapOf("Twisted bow" to "TB", "ZCB auto" to "Z", "ZCB spec" to "ZCB", "zcb" to "ZCB", "maul" to "EM", "bgs" to "BGS", "claw" to "C", "halb" to "HB", "scythe" to "S")

        /** The tiles beside her (her 5x5's edges, no corners). */
        private val BESIDE =
            (SoteRules.X until SoteRules.X + SoteRules.SIZE).flatMap { x -> listOf(x to SoteRules.Y - 1, x to SoteRules.Y + SoteRules.SIZE) } +
                (SoteRules.Y until SoteRules.Y + SoteRules.SIZE).flatMap { y -> listOf(SoteRules.X - 1 to y, SoteRules.X + SoteRules.SIZE to y) }
    }
}
