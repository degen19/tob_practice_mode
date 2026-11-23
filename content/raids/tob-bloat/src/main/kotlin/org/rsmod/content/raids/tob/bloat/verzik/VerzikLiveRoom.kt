package org.rsmod.content.raids.tob.bloat.verzik

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.MoveRestrict
import dev.openrune.types.NpcMode
import dev.openrune.types.aconverted.SpotanimType
import org.rsmod.api.config.constants
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.statRestore
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.content.raids.tob.bloat.MaidenRules
import org.rsmod.content.raids.tob.bloat.RaidLook
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.map.CoordGrid

/**
 * A Verzik raid played in the room: one of the sim's own 5s raids ([VerzikRaids]) - its attacks, her attacks and HP,
 * the crabs and the phase points - acted out by Verzik and five bots, tick by tick. Unlike the other rooms it isn't an
 * engine running here: the Verzik engine (P1 chart, P2, reds, P3) is the sim's JS, and each raid comes from its log.
 * P1 on the throne; P2 and P3 in the middle of the arena, the bots around her.
 */
class VerzikLiveRoom(
    val session: InstanceSession,
    val raid: VerzikRaids.Raid,
    private val at: (Int, Int) -> CoordGrid,
    private val instances: InstanceManager,
    private val npcRepo: NpcRepository,
    private val worldRepo: WorldRepository,
    private val npcHits: NpcHitModifier,
    private val protectedAccess: ProtectedAccessLauncher,
    private val look: RaidLook,
) {
    val bots = ArrayList<Player>()
    var verzik: Npc? = null
        private set
    private val crabs = HashMap<String, Npc>()
    private var clock = -PRE_TICKS
    private var phase = "P1"
    private val starts = raid.attacks.groupBy { it.t }
    private val lands = raid.attacks.filter { it.lands != null }.groupBy { it.lands!! }
    private val bossAt = raid.boss.groupBy { it.t }
    private val hpAt = raid.hp.associateBy { it.t }
    private val markAt = raid.marks.groupBy { it.first }
    private val lastHitOn = raid.attacks.filter { it.crab.isNotEmpty() && it.lands != null }.groupBy { it.crab }.mapValues { (_, v) -> v.maxOf { it.lands!! } }
    private val endAt = raid.end ?: ((raid.hp.lastOrNull()?.t ?: 0) + 1)

    val finished: Boolean
        get() = clock > endAt + END_HOLD

    fun start() {
        spawn(VerzikRules.PHASE_NPC.getValue("P1"), at(VerzikRules.P1_X, VerzikRules.P1_Y), P1_HP)
        place("P1")
    }

    private fun spawn(name: String, where: CoordGrid, hp: Int) {
        verzik?.let { if (it.isSlotAssigned) npcRepo.del(it, Int.MAX_VALUE) }
        val npc = Npc(name, where)
        npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        npc.moveRestrict = MoveRestrict.PassThru
        npc.ignoreCombatInteractions = true
        instances.attachNpc(session.id, npc)
        npc.baseHitpointsLvl = hp
        npc.hitpoints = hp
        verzik = npc
    }

    /** The bots' tiles for a phase: in front of the throne (P1), around her south side (P2 / P3). */
    fun spot(i: Int, ph: String): CoordGrid =
        if (ph == "P1") at(VerzikRules.P1_X - 1 + i * 2, VerzikRules.P1_Y - 2)
        else at(VerzikRules.MID_X - 1 + i, VerzikRules.MID_Y - 1)

    private fun place(ph: String) {
        bots.forEachIndexed { i, b -> protectedAccess.launch(b) { telejump(spot(i, ph), TeleportType.Exempt) } }
    }

    fun tick() {
        clock++
        val t = clock
        verzik?.mode = NpcMode.None
        if (t < 1) return
        markAt[t]?.forEach { (_, m) -> mark(m) }
        hpAt[t]?.let { h -> verzik?.takeIf { it.isSlotAssigned }?.let { v -> if (h.hp > 0) v.hitpoints = minOf(h.hp, v.baseHitpointsLvl) } }
        bossAt[t]?.forEach { boss(it) }
        starts[t]?.forEach { a ->
            val bot = bots.getOrNull(a.i) ?: return@forEach
            val target = targetOf(a) ?: return@forEach
            look.attack(bot, MaidenRules.KITS.getValue(KIT[a.code] ?: "S"), target, scythe = a.code == "S")
        }
        lands[t]?.forEach { a ->
            val bot = bots.getOrNull(a.i) ?: return@forEach
            val target = targetOf(a) ?: return@forEach
            if (target.isSlotAssigned) target.queueHit(bot, 1, HitType.Typeless, a.dmg, npcHits)
        }
        for ((key, last) in lastHitOn) if (t == last + 1) crabs.remove(key)?.let { c -> if (c.isSlotAssigned) npcRepo.del(c, Int.MAX_VALUE) }
        if (t == endAt && raid.end != null) verzik?.anim(VerzikRules.P3_DEATH)
        if (t == endAt + END_HOLD) verzik?.let { if (it.isSlotAssigned) npcRepo.del(it, Int.MAX_VALUE) }
        for (b in bots) {
            verzik?.takeIf { it.isSlotAssigned }?.let { b.faceNpc(it) }
            if (b.hitpoints < b.baseHitpointsLvl) b.statRestore("stat.hitpoints")
            b.runEnergy = constants.run_max_energy
        }
    }

    private fun targetOf(a: VerzikRaids.Attack): Npc? = when (a.crab) {
        "W" -> crabs["W"]
        "E" -> crabs["E"]
        "P" -> crabs["P"]
        else -> verzik
    }?.takeIf { it.isSlotAssigned } ?: verzik

    private fun mark(m: String) {
        when (m) {
            "P1 dies" -> verzik?.anim(VerzikRules.P1_DEATH)
            "P2" -> {
                phase = "P2"
                spawn(VerzikRules.PHASE_NPC.getValue("P2"), at(VerzikRules.MID_X, VerzikRules.MID_Y), P2_HP)
                verzik?.anim(VerzikRules.P2_SPAWN)
                place("P2")
            }
            "Reds" -> {
                crabs["W"] = crab(VerzikRules.RED, VerzikRules.MID_X - 4, VerzikRules.MID_Y + 1)
                crabs["E"] = crab(VerzikRules.RED, VerzikRules.MID_X + 5, VerzikRules.MID_Y + 1)
            }
            "P2 dies" -> verzik?.anim(VerzikRules.P2_DEATH)
            "P3" -> {
                phase = "P3"
                spawn(VerzikRules.PHASE_NPC.getValue("P3"), at(VerzikRules.MID_X, VerzikRules.MID_Y), P3_HP)
                verzik?.anim(VerzikRules.P3_SPAWN)
                place("P3")
            }
            "Webs" -> verzik?.anim(VerzikRules.P3_WEBS)
        }
    }

    private fun crab(name: String, x: Int, y: Int): Npc {
        val npc = Npc(name, at(x, y))
        npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        npc.moveRestrict = MoveRestrict.PassThru
        npc.ignoreCombatInteractions = true
        instances.attachNpc(session.id, npc)
        return npc
    }

    private fun boss(b: VerzikRaids.Boss) {
        val v = verzik?.takeIf { it.isSlotAssigned } ?: return
        val target = bots.getOrNull(b.target) ?: bots.randomOrNull()
        when (b.kind) {
            "Attack" -> {
                val (seq, proj) = if (phase == "P1") VerzikRules.P1_ATTACK to VerzikRules.P1_PROJ else VerzikRules.P2_ATTACK to VerzikRules.P2_PROJ
                v.anim(seq)
                target?.let { worldRepo.projAnim(v, it, SpotanimType(proj.asRSCM(RSCMType.SPOTANIM)), MaidenRules.HER_PROJANIM) }
            }
            "Lightning" -> {
                v.anim(VerzikRules.P2_LIGHTNING)
                target?.let { worldRepo.projAnim(v, it, SpotanimType(VerzikRules.P2_BOLT.asRSCM(RSCMType.SPOTANIM)), MaidenRules.HER_PROJANIM) }
            }
            "Purple crab" -> if ("P" !in crabs) crabs["P"] = crab(VerzikRules.PURPLE, VerzikRules.MID_X + 1, VerzikRules.MID_Y + 5)
            "Blood heal" -> v.anim(VerzikRules.P2_HEAL)
            "Ranged" -> {
                v.anim(VerzikRules.P3_RANGED)
                target?.let { worldRepo.projAnim(v, it, SpotanimType(VerzikRules.P3_RANGE_PROJ.asRSCM(RSCMType.SPOTANIM)), MaidenRules.HER_PROJANIM) }
            }
            "Magic" -> {
                v.anim(VerzikRules.P3_MAGIC)
                target?.let { worldRepo.projAnim(v, it, SpotanimType(VerzikRules.P3_MAGE_PROJ.asRSCM(RSCMType.SPOTANIM)), MaidenRules.HER_PROJANIM) }
            }
            "Melee" -> v.anim(VerzikRules.P3_MELEE)
        }
    }

    fun remove() {
        verzik?.let { if (it.isSlotAssigned) npcRepo.del(it, Int.MAX_VALUE) }
        for (c in crabs.values) if (c.isSlotAssigned) npcRepo.del(c, Int.MAX_VALUE)
        crabs.clear()
    }

    companion object {
        const val PRE_TICKS = 4
        const val END_HOLD = 8
        const val P1_HP = 2000
        const val P2_HP = 3500
        const val P3_HP = 3500
        val KIT = mapOf("S" to "S", "D" to "D", "E" to "AY", "BP" to "BP", "T" to "T", "C" to "C", "Cs" to "C", "Ch" to "HB", "Sh" to "SH", "Z" to "Z", "EM" to "EM", "BGS" to "BGS", "Zt" to "AY", "3t" to "S", "SuB" to "S", "F" to "S")
    }
}
