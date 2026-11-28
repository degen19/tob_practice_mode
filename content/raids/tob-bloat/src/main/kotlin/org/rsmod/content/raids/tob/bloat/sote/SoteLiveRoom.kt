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
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.content.raids.tob.bloat.MaidenRules
import org.rsmod.content.raids.tob.bloat.RaidBots
import org.rsmod.content.raids.tob.bloat.RaidLook
import org.rsmod.content.raids.tob.bloat.RoomEntry
import org.rsmod.content.raids.tob.bloat.maiden.SimRng
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.map.CoordGrid

/**
 * A live Sotetseg: the sim's Sotetseg ([SoteEngine]) runs it tick by tick for a team of 1-5 bots. They stand round her
 * on the team's tiles and attack as the engine has them (prefires, mauls, ZCBs, claws, scythes, the halberd); her orbs
 * fly at them (prayed or taken for veng) and her melee comes off the flinch. At a maze one goes under (out of sight) and
 * the others are taken to the maze's first row, cast NPC Contact (the stall) and come back out when the stall ends; a
 * solo goes under first, then stalls. One seed, one raid: the same as the index's ([SoteIndex]).
 */
class SoteLiveRoom(
    val session: InstanceSession,
    val sote: Npc,
    val seed: String,
    val team: Int,
    private val at: (Int, Int) -> CoordGrid,
    private val npcRepo: NpcRepository,
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
    private var shown = 0
    val engine = SoteEngine(SotePlan.of(team), SimRng(seed), Shows())
    private var clock = -PRE_TICKS
    private var idle = 0
    private val later = HashMap<Int, MutableList<() -> Unit>>()

    val finished: Boolean
        get() = idle > END_HOLD + 2

    fun botOf(p: SoteEngine.P): Player? = bots.getOrNull(p.i)

    fun stackTile(i: Int): CoordGrid = SoteRules.SPOTS.getValue(team)[i].let { (x, y) -> at(x, y) }

    /** Where each bot waits before the raid: just south of the barrier, on the column it comes in by. */
    fun outsideTile(i: Int): CoordGrid = at(SoteRules.ENTRY_COLUMNS.getValue(team)[i], SoteRules.BARRIER_Y - 1)

    private val entry = RoomEntry(clicks, at(SoteRules.BARRIER_X, SoteRules.BARRIER_Y), pass = -1, run = RUN_FROM)

    private fun go(bot: Player, to: CoordGrid) {
        protectedAccess.launch(bot) { telejump(to, TeleportType.Exempt) }
    }

    private fun at(t: Int, f: () -> Unit) {
        later.getOrPut(t) { ArrayList() } += f
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
        if (idle == END_HOLD && sote.isSlotAssigned) npcRepo.del(sote, Int.MAX_VALUE)
        // her hitsplats land after this, a cycle after they were queued: the engine's HP already has them taken off
        if (sote.isSlotAssigned) sote.hitpoints = max(1, engine.hp) + landing
        for (p in engine.ps) {
            val bot = botOf(p) ?: continue
            if (!look.wearing(bot)) look.equip(bot, kit(p.prefire))
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
        println("sotetrace $seed ${engine.t} ${engine.hp} ${if (engine.blocked) 1 else 0} $her|$bs")
        her = ""
    }

    fun remove() {
        if (sote.isSlotAssigned) npcRepo.del(sote, Int.MAX_VALUE)
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
            val stallers = engine.ps.filter { !it.dead && it !== under }
            under?.let { u -> botOf(u)?.let { go(it, at(SoteRules.UNDER_X, SoteRules.UNDER_Y)) } }
            // teleported south of the maze (a solo goes under first), then onto its first row on m + 1: NPC Contact
            val south = { stallers.forEachIndexed { k, p -> botOf(p)?.let { go(it, at(SoteRules.MAZE_X + 4 + k, SoteRules.MAZE_A - 1)) } } }
            if (team == 1) {
                stallers.forEach { p -> botOf(p)?.let { go(it, at(SoteRules.UNDER_X, SoteRules.UNDER_Y)) } }
                at(m) { south() }
            } else south()
            at(m + 1) {
                stallers.forEachIndexed { k, p ->
                    val bot = botOf(p) ?: return@forEachIndexed
                    go(bot, at(SoteRules.MAZE_X + 4 + k, SoteRules.MAZE_A))
                    bot.anim(RSCM.getReverseMapping(RSCMType.SEQ, SoteRules.CONTACT_SEQ))
                    bot.spotanim(RSCM.getReverseMapping(RSCMType.SPOTANIM, SoteRules.CONTACT_GFX), height = 96)
                }
            }
            under?.let { u -> at(back) { botOf(u)?.let { go(it, stackTile(u.i)) } } }
        }

        override fun stallOut(t: Int, p: SoteEngine.P) {
            botOf(p)?.let { go(it, stackTile(p.i)) }
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
        val KIT = mapOf("Twisted bow" to "TB", "ZCB auto" to "Z", "ZCB spec" to "ZCB", "zcb" to "ZCB", "maul" to "EM", "bgs" to "BGS", "claw" to "C", "halb" to "HB", "scythe" to "S")
    }
}
