package org.rsmod.content.raids.tob.bloat.nylo

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.NpcMode
import org.rsmod.api.config.constants
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.player.hit.modifier.PlayerHitModifier
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.statRestore
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.content.raids.tob.bloat.MaidenRules
import org.rsmod.content.raids.tob.bloat.RaidBots
import org.rsmod.content.raids.tob.bloat.RaidLook
import org.rsmod.content.raids.tob.bloat.RoomEntry
import org.rsmod.content.raids.tob.bloat.maiden.SimRng
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.map.CoordGrid
import kotlin.math.max

/**
 * A live Nylo boss fight: the sim's boss ([NyloEngine]) runs it tick by tick. He spawns (his spawn animation) on the
 * room's cycle, then changes form every 10 ticks; the five bots stack on his south side and switch to his form's gear
 * and weapon, go under him when the sim has them under, and the hits land as the engine rolls them - his attacks too
 * (veng on the way back). One seed, one fight: the same as the index's ([NyloIndex]).
 */
class NyloLiveRoom(
    val session: InstanceSession,
    val boss: Npc,
    val seed: String,
    private val at: (Int, Int) -> CoordGrid,
    private val npcRepo: NpcRepository,
    private val npcHits: NpcHitModifier,
    private val playerHits: PlayerHitModifier,
    private val clicks: RaidBots,
    private val look: RaidLook,
) {
    val bots = ArrayList<Player>()
    var log = false
    val engine = NyloEngine(NyloPlan.FIVE, SimRng(seed), Shows())
    /** Room ticks before his spawn (i1). */
    private var clock = -PRE_TICKS
    private var idle = 0
    private val sent = HashMap<Player, CoordGrid>()

    val finished: Boolean
        get() = idle > END_HOLD + 2

    fun botOf(p: NyloEngine.P): Player? = bots.getOrNull(p.i)

    /** Each bot's tile along his south side (x), out of the way of each other a little. */
    fun stackTile(i: Int): CoordGrid = at(NyloRules.X + (i % NyloRules.SIZE), NyloRules.STACK_Y)

    /** Where each bot waits before the room: in line just north of the barrier (you come in going south). */
    fun outsideTile(i: Int): CoordGrid = RoomEntry.outside(at, NyloRules.BARRIER_X, NyloRules.BARRIER_W, NyloRules.BARRIER_Y + 1, i)

    private val entry = RoomEntry(clicks, at(NyloRules.BARRIER_X, NyloRules.BARRIER_Y), pass = PASS, run = RUN_FROM)

    fun tick() {
        clock++
        boss.mode = NpcMode.None
        // his spawn on i1; the engine's tick t is his spawn + clock (his first form starts SHOW_UP later)
        if (clock == 0) boss.anim(NyloRules.SPAWN_SEQ)
        // the team comes in through the barrier and runs round to his south side before he spawns
        entry.tick(clock, bots)
        if (clock > 0) {
            if (engine.kill == null) {
                while (engine.t < engine.i1 + clock && engine.kill == null) engine.tick()
            } else idle++
        }
        if (idle == END_HOLD && boss.isSlotAssigned) npcRepo.del(boss, Int.MAX_VALUE)
        if (boss.isSlotAssigned) boss.hitpoints = max(1, engine.hp)
        val f = engine.formAt(max(engine.t, engine.spawn))
        for (p in engine.ps) {
            val bot = botOf(p) ?: continue
            if (!look.wearing(bot)) look.equip(bot, MaidenRules.KITS.getValue("S"))
            val under = f != null && p.underForm == f.start && f.form != "melee" && engine.kill == null
            val dest = if (under) at(NyloRules.X + 1 + (p.i % 2), NyloRules.UNDER_Y) else stackTile(p.i)
            if (!entry.waiting(clock) && sent[bot] != dest && bot.coords != dest) {
                clicks.walk(bot, dest)
                sent[bot] = dest
            }
            if (!entry.waiting(clock) && (bot.coords == dest || under)) bot.faceNpc(boss)
            if (bot.hitpoints < bot.baseHitpointsLvl) bot.statRestore("stat.hitpoints")
            bot.runEnergy = constants.run_max_energy
        }
    }

    fun remove() {
        if (boss.isSlotAssigned) npcRepo.del(boss, Int.MAX_VALUE)
    }

    private inner class Shows : NyloEngine.Events {
        override fun attack(t: Int, p: NyloEngine.P, code: String, lands: Int) {
            val bot = botOf(p) ?: return
            look.attack(bot, MaidenRules.KITS.getValue(KIT[code] ?: "S"), boss, scythe = code == "S")
        }

        override fun hit(t: Int, p: NyloEngine.P, amt: Int) {
            val bot = botOf(p) ?: return
            boss.queueHit(bot, 1, HitType.Typeless, amt, npcHits)
        }

        override fun form(t: Int, form: String) {
            val id = if (t == engine.spawn) NyloRules.FORM_NPC.getValue("melee") else NyloRules.FORM_NPC.getValue(form)
            ServerCacheManager.getNpc(id.asRSCM(RSCMType.NPC))?.let { boss.transmog(it, Int.MAX_VALUE) }
        }

        override fun attacksPlayer(t: Int, p: NyloEngine.P, form: String, lands: Int) {
            val bot = botOf(p) ?: return
            boss.anim(NyloRules.ATTACK_SEQ.getValue(form))
            boss.facePlayer(bot)
        }

        override fun hitPlayer(t: Int, p: NyloEngine.P, d: Int, veng: Int) {
            val bot = botOf(p) ?: return
            bot.queueHit(boss, 1, HitType.Typeless, d, playerHits)
            if (veng > 0) {
                bot.say("Taste vengeance!")
                boss.queueHit(bot, 1, HitType.Typeless, veng, npcHits)
            }
        }

        override fun dead(t: Int) {
            boss.anim(NyloRules.DEATH_SEQ)
        }
    }

    companion object {
        /** Ticks of the room shown before his spawn. */
        const val PRE_TICKS = 17
        const val PASS = -13
        const val RUN_FROM = -11
        const val END_HOLD = 8
        val KIT = mapOf("S" to "S", "C" to "C", "BGS" to "BGS", "Ch" to "HB", "TB" to "TB", "AX" to "AX", "ZCB" to "ZCB", "E" to "AY", "Sh" to "SH", "Zt" to "AY")
    }
}
