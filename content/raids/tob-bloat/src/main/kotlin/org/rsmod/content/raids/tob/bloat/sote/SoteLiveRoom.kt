package org.rsmod.content.raids.tob.bloat.sote

import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.NpcMode
import dev.openrune.types.aconverted.SpotanimType
import kotlin.math.max
import org.rsmod.api.config.constants
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.player.hit.modifier.PlayerHitModifier
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.protect.ProtectedAccessLauncher
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
import org.rsmod.game.obj.Obj
import org.rsmod.map.CoordGrid

/**
 * A live Sotetseg: the sim's Sotetseg ([SoteEngine]) runs it tick by tick for a team of 1-5 bots. They stand round her
 * on the team's tiles and attack as the engine has them (prefires, mauls, ZCBs, claws, scythes, the halberd); her orbs
 * fly at them (prayed or taken for veng) and her melee comes off the flinch. On t12 the west bot drops a shark by her
 * south-west corner. At a maze one goes under (out of sight); the others are teleported south of the maze and run up
 * its first column to that shark through their NPC Contact (the stall), then walk back to their tiles; a shark skipper
 * runs up column 11, away from them; a solo goes under first, then crosses. A run maze: they follow its best route. One seed, one raid: the same as the index's ([SoteIndex]).
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
    private val firstSwing = HashMap<Int, CoordGrid>()
    private var shown = 0
    val engine = SoteEngine(SotePlan.of(team, way), SimRng(seed), Shows())
    private var clock = -PRE_TICKS
    private var idle = 0
    private val later = HashMap<Int, MutableList<() -> Unit>>()
    private var shark: Obj? = null
    private val busyUntil = HashMap<Int, Int>()

    val finished: Boolean
        get() = idle > END_HOLD + 2

    fun botOf(p: SoteEngine.P): Player? = bots.getOrNull(p.i)

    fun stackTile(i: Int): CoordGrid = SoteRules.SPOTS.getValue(team)[i].let { (x, y) -> at(x, y) }

    private fun outTile(i: Int): CoordGrid = SoteRules.OUT.getValue(team)[i].let { (x, y) -> at(x, y) }

    /**
     * The flinch: out of her reach between swings - in from the tick before a swing, out again the tick after it. Off a
     * maze the first swing is from the tile beside her nearest them, then they go back to their own (as on Blert).
     */
    private fun flinch(p: SoteEngine.P, bot: Player) {
        if (engine.blocked || p.dead || clock <= ARRIVED || engine.t < busyUntil.getOrDefault(p.i, 0)) return
        val want = if (p.next == engine.t + 1) firstSwing[p.i] ?: stackTile(p.i) else outTile(p.i)
        if (bot.coords != want) clicks.walk(bot, want)
    }

    /** Off the maze: to the tile beside her nearest them, for their first swing. */
    private fun offMaze(p: SoteEngine.P) {
        val bot = botOf(p) ?: return
        val tile = BESIDE.map { (x, y) -> at(x, y) }.minBy { maxOf(kotlin.math.abs(it.x - bot.coords.x), kotlin.math.abs(it.z - bot.coords.z)) }
        firstSwing[p.i] = tile
        clicks.walk(bot, tile)
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

    /** 3 ticks after the proc: the player under to the shadow realm, the rest south of the maze (a solo under first). */
    private fun teleport(t: Int, under: SoteEngine.P?, crossers: List<SoteEngine.P>) {
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
        // her hitsplats land after this, a cycle after they were queued: the engine's HP already has them taken off
        if (sote.isSlotAssigned) sote.hitpoints = max(1, engine.hp) + landing
        for (p in engine.ps) {
            val bot = botOf(p) ?: continue
            if (!look.wearing(bot)) look.equip(bot, kit(p.prefire))
            if (engine.kill == null) flinch(p, bot)
            if (!engine.blocked && (clock > ARRIVED || bot.coords == stackTile(p.i))) bot.faceNpc(sote)
            bot.runEnergy = constants.run_max_energy
        }
        if (TRACE && clock > 0) trace()
    }

    /** `SOTE_TRACE`: one line a tick - her HP, then each bot's room tile, level, HP and what it did (tools/sote-render). */
    private fun trace() {
        val base = at(SoteRules.X, SoteRules.Y)
        val dx = base.x - SoteRules.X
        val dz = base.z - SoteRules.Y
        val bs = engine.ps.joinToString(";") { p ->
            val c = botOf(p)?.coords
            "${p.i},${(c?.x ?: 0) - dx},${(c?.z ?: 0) - dz},${c?.level ?: 0},${p.hp},${acts.remove(engine.t to p.i) ?: ""}"
        }
        println("sotetrace $seed ${engine.t} ${engine.hp} ${if (engine.blocked) 1 else 0} $her|$bs${if (lit.isNotEmpty()) "|$lit" else ""}")
        her = ""
        lit = ""
    }

    fun remove() {
        if (sote.isSlotAssigned) npcRepo.del(sote, Int.MAX_VALUE)
        shark?.let { objRepo.del(it) }
    }

    private fun kit(act: String): MaidenRules.Kit = MaidenRules.KITS.getValue(KIT[act] ?: "S")

    private inner class Shows : SoteEngine.Events {
        override fun attack(t: Int, p: SoteEngine.P, act: String, lands: Int) {
            acts[t to p.i] = act
            if (act != "horn") firstSwing.remove(p.i)
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
            val gfx = if (kind == "ball") SoteRules.BALL else SoteRules.ORB
            worldRepo.projAnim(sote, bot, SpotanimType(gfx.asRSCM(RSCMType.SPOTANIM)), MaidenRules.HER_PROJANIM)
        }

        override fun orbLands(t: Int, p: SoteEngine.P, style: String, d: Int, prayed: Boolean) {
            val bot = botOf(p) ?: return
            if (sote.isSlotAssigned) bot.queueHit(sote, 1, HitType.Magic, d, playerHits)
        }

        override fun veng(t: Int, p: SoteEngine.P, amt: Int) {
            botOf(p)?.say("Taste vengeance!")
        }

        override fun maze(t: Int, n: Int, m: Int, under: SoteEngine.P?, back: Int) {
            val crossers = engine.ps.filter { !it.dead && it !== under }
            val skippers = crossers.filter { (n - 1) in it.sharkMazes }
            val stallers = crossers - skippers.toSet()
            teleport(t, under, crossers)
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
            at(m + 8) { for (p in skippers) offMaze(p) }
            under?.let { u -> at(back) { botOf(u)?.let { go(it, stackTile(u.i)) } } }
        }

        /**
         * A run maze: the runners go south of 3A, then 5A, to the first tile and along the best route a tick behind each
         * other's clicks, wait on row N and step off when they click off; the player under runs it below, out of sight.
         */
        override fun mazeRun(t: Int, n: Int, run: SoteEngine.Run, under: SoteEngine.P?) {
            val runners = engine.ps.filter { !it.dead && it !== under }
            teleport(t, under, runners)
            val route = run.maze.route.map { tile -> at(SoteRules.MAZE_X + tile.x, SoteRules.MAZE_A + SoteMaze.H - 1 - tile.y) }
            fun all(f: (Player) -> Unit) = runners.forEach { p -> botOf(p)?.let(f) }
            at(t + 5) { all { clicks.walk(it, at(SoteRules.MAZE_X + 3, SoteRules.MAZE_A - 1)) } }
            at(t + 6) { all { clicks.walk(it, at(SoteRules.MAZE_X + 5, SoteRules.MAZE_A - 1)) } }
            at(run.onFirst - SoteMaze.runnersToStart(run.maze.start)) { all { clicks.walk(it, route[0]) } }
            for (k in 1..run.maze.toN) at(run.onFirst + k - 1) { all { clicks.walk(it, route[k]) } }
            at(run.light) { lit = run.maze.route.joinToString(";") { "${it.x},${SoteMaze.H - 1 - it.y}" } }
            at(run.clickOff + 1) { for (p in runners) offMaze(p) }
            under?.let { u -> at(run.clickOff + 2) { botOf(u)?.let { go(it, stackTile(u.i)) } } }
        }

        override fun stallOut(t: Int, p: SoteEngine.P) {
            offMaze(p)
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
        private val TRACE = System.getenv("SOTE_TRACE") != null

        /** The tiles beside her (her 5x5's edges, no corners). */
        private val BESIDE =
            (SoteRules.X until SoteRules.X + SoteRules.SIZE).flatMap { x -> listOf(x to SoteRules.Y - 1, x to SoteRules.Y + SoteRules.SIZE) } +
                (SoteRules.Y until SoteRules.Y + SoteRules.SIZE).flatMap { y -> listOf(SoteRules.X - 1 to y, SoteRules.X + SoteRules.SIZE to y) }
        val KIT = mapOf("Twisted bow" to "TB", "ZCB auto" to "Z", "ZCB spec" to "ZCB", "zcb" to "ZCB", "maul" to "EM", "bgs" to "BGS", "claw" to "C", "halb" to "HB", "scythe" to "S")
    }
}
