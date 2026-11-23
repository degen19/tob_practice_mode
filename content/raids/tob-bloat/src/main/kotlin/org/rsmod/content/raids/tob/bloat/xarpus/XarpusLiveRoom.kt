package org.rsmod.content.raids.tob.bloat.xarpus

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.NpcMode
import org.rsmod.api.config.constants
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
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
import org.rsmod.routefinder.collision.CollisionFlagMap
import kotlin.math.max

/**
 * A live Xarpus room: the sim's Xarpus ([XarpusEngine]) runs it tick by tick. The five bots stack on his south side
 * (the scythers and the Ralos players alike - everything reaches from there), switch into each attack's gear and swing;
 * the hits land as the engine rolls them. The tail of the exhumeds plays first ([PRE_TICKS]), then he wakes up. One
 * seed, one raid: the same as the index's ([XarpusIndex]).
 */
class XarpusLiveRoom(
    val session: InstanceSession,
    val xarpus: Npc,
    val seed: String,
    private val at: (Int, Int) -> CoordGrid,
    private val npcRepo: NpcRepository,
    private val npcHits: NpcHitModifier,
    private val clicks: RaidBots,
    private val look: RaidLook,
    private val collision: CollisionFlagMap,
) {
    private val logger = InlineLogger()
    val bots = ArrayList<Player>()
    var log = false
    val engine = XarpusEngine(5, XarpusPlan.FIVE, SimRng(seed), Shows())
    private var clock = -PRE_TICKS

    /** Ticks since his death. */
    private var idle = 0

    val finished: Boolean
        get() = idle > END_HOLD + 2

    fun botOf(p: XarpusEngine.P): Player? = bots.getOrNull(p.i)

    /** Where the team waits before the room: stacked just south of the barrier's middle (as on Blert). */
    fun outsideTile(i: Int): CoordGrid = at(XarpusRules.ENTRY_X, XarpusRules.BARRIER_Y - 1)

    private val entry = RoomEntry(clicks, at(XarpusRules.BARRIER_X, XarpusRules.BARRIER_Y), pass = PASS, run = RUN_FROM)

    fun tick() {
        clock++
        xarpus.mode = NpcMode.None
        if (clock == 0) wake()
        // through the barrier, then the run in to the stack; the exhumeds' part of the room is cut short
        entry.tick(clock, bots)
        // the room starts as they come in: he's feeding on the exhumeds' heals from then till he wakes
        if (clock == PASS + 2) ServerCacheManager.getNpc(XarpusRules.FEEDING.asRSCM(RSCMType.NPC))?.let { xarpus.transmog(it, Int.MAX_VALUE) }
        if (clock == RUN_FROM) bots.forEachIndexed { i, b -> XarpusRules.SPOTS[i % XarpusRules.SPOTS.size].let { clicks.walk(b, at(it[0], it[1])) } }
        if (clock > 0) {
            if (engine.kill == null) engine.tick() else idle++
        }
        if (idle == END_HOLD && xarpus.isSlotAssigned) npcRepo.del(xarpus, Int.MAX_VALUE)
        if (xarpus.isSlotAssigned && engine.kill == null) xarpus.hitpoints = max(1, engine.hp)
        // his attacks (cosmetic: the sim doesn't model the acid) - one at the start of each cycle up to the screech
        val t = engine.t
        val s = engine.screech
        if (clock > 0 && engine.kill == null && t >= engine.exh + 7 && (t - engine.exh - 7) % 4 == 0 && (s == null || t < s)) xarpus.anim(ATTACK)
        for (p in engine.ps) {
            val bot = botOf(p) ?: continue
            if (!look.wearing(bot)) look.equip(bot, kitOf(p.holding))
            if (!entry.waiting(clock)) bot.faceNpc(xarpus)
            bot.runEnergy = constants.run_max_energy
        }
        if (log && clock > 0) logger.info { "xarpus t${engine.t} hp ${engine.hp} def ${engine.defLvl}" }
    }

    private fun wake() {
        // the 3x3 sat on the middle tile; the 5x5 goes one tile south-west so his middle stays put
        xarpus.resetTransmog()
        xarpus.telejump(collision, at(XarpusRules.X, XarpusRules.Y))
        xarpus.hitpoints = engine.startHp
    }

    private fun kitOf(kind: String): MaidenRules.Kit = MaidenRules.KITS.getValue(KIT.getValue(kind))

    private inner class Shows : XarpusEngine.Events {
        override fun attack(t: Int, p: XarpusEngine.P, kind: String, lands: Int) {
            val bot = botOf(p) ?: return
            look.attack(bot, kitOf(kind), xarpus, scythe = kind == "scythe")
        }

        override fun hit(t: Int, p: XarpusEngine.P, kind: String, parts: List<Int>) {
            val bot = botOf(p) ?: return
            if (!xarpus.isSlotAssigned) return
            val type = if (kind == "ralos" || kind == "zcb") HitType.Ranged else if (kind == "thrall") HitType.Typeless else HitType.Melee
            for (d in parts) xarpus.queueHit(bot, 1, type, d, npcHits)
        }

        override fun screech(t: Int) {
            xarpus.say("SCREEEEECH!")
        }

        override fun dead(t: Int) {
            ServerCacheManager.getNpc(XarpusRules.DEATH.asRSCM(RSCMType.NPC))?.let { xarpus.transmog(it, Int.MAX_VALUE) }
            xarpus.anim(DEATH_SEQ)
        }
    }

    fun remove() {
        if (xarpus.isSlotAssigned) npcRepo.del(xarpus, Int.MAX_VALUE)
    }

    companion object {
        /** The exhumeds' last ticks, shown before he wakes up: the team comes through the barrier at the start. */
        const val PRE_TICKS = 17
        const val PASS = -12
        const val RUN_FROM = -10
        const val END_HOLD = 6
        const val ATTACK = "seq.tob_xarpus_attack_ranged"
        const val DEATH_SEQ = "seq.tob_xarpus_death_a"
        val KIT = mapOf("scythe" to "S", "maul" to "EM", "bgs" to "BGS", "ralos" to "Ra", "zcb" to "ZCB", "claw" to "C", "scratch" to "C", "halb" to "HB")
    }
}
