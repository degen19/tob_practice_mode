package org.rsmod.content.raids.tob.bloat.verzik

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.MoveRestrict
import dev.openrune.types.NpcMode
import dev.openrune.types.aconverted.SpotanimType
import kotlin.math.abs
import kotlin.math.max
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.done.hitmark_groups
import org.rsmod.api.config.refs.params
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.statRestore
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.content.raids.tob.bloat.MaidenRules
import org.rsmod.content.raids.tob.bloat.RaidLook
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.util.PathingEntityCommon
import org.rsmod.game.headbar.Headbar
import org.rsmod.game.hit.Hitmark
import org.rsmod.game.inv.InvObj
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * One of Blert's fastest Verzik rooms ([VerzikBlert]) played back 1:1: each bot stands on its player's recorded tile
 * every tick (walking one tile, running two, jumping on knock-backs), wears the recorded gear and overhead, and
 * animates each recorded attack and spell; every NPC (Verzik through her forms, pillars, crabs, nylos, tornadoes)
 * spawns, moves, changes form and dies where and when it did, its hitpoints as recorded; her attacks play their
 * animation and projectile at their target. [drift] counts the ticks anything stood off its recorded tile.
 */
class VerzikReplayRoom(
    val session: InstanceSession,
    val raid: VerzikBlert.Raid,
    private val at: (Int, Int) -> CoordGrid,
    private val instances: InstanceManager,
    private val npcRepo: NpcRepository,
    private val locRepo: LocRepository,
    private val worldRepo: WorldRepository,
    private val collision: CollisionFlagMap,
    private val look: RaidLook,
) {
    val bots = ArrayList<Player>()
    private val npcs = HashMap<Int, Npc>()
    private val shown = HashMap<Int, VerzikBlert.Step>()
    private val pillarLocs = HashMap<Int, LocInfo>()
    private val locs = ArrayList<LocInfo>()
    private val later = ArrayList<Pair<Int, () -> Unit>>()
    private var clock = -PRE_TICKS
    private val stepsAt = raid.npcs.mapIndexed { i, n -> i to n.steps.associateBy { it.t } }.toMap()
    private val spawnsAt = raid.npcs.indices.groupBy { raid.npcs[it].spawn }
    private val deathsAt = raid.npcs.indices.filter { raid.npcs[it].death != null }.groupBy { raid.npcs[it].death!! }
    private val attacksAt = raid.attacks.groupBy { it.t }
    private val spellsAt = raid.spells.groupBy { it.t }
    private val herAt = raid.her.groupBy { it.t }
    private val gearAt = raid.gear.map { g -> g.groupBy { it.first } }
    private val overheadAt = raid.overheads.map { o -> o.associate { it.first to it.second } }

    var botDrift = 0
        private set
    var npcDrift = 0
        private set
    var checked = 0
        private set

    val finished: Boolean
        get() = clock > raid.end + END_HOLD

    fun spot(i: Int): CoordGrid = at(raid.x(i, 0), raid.y(i, 0))

    fun start() {
        bots.forEachIndexed { i, b ->
            for (slot in 0 until b.worn.size) b.worn[slot] = null
            wear(i, b, 0)
            overhead(i, b, 0)
        }
        spawnsAt[0]?.forEach { spawn(it) }
    }

    fun tick() {
        clock++
        val t = clock
        if (t < 0) return
        if (t >= 1 && t - 1 <= raid.end) check(t - 1)
        later.filter { it.first == t }.forEach { it.second() }
        later.removeAll { it.first <= t }
        if (t > raid.end) {
            if (t == raid.end + END_HOLD) remove()
            return
        }
        if (t > 0) spawnsAt[t]?.forEach { spawn(it) }
        for ((i, npc) in npcs) {
            npc.mode = NpcMode.None
            stepsAt.getValue(i)[t]?.let { step(i, npc, it) }
        }
        deathsAt[t]?.forEach { die(it) }
        bots.forEachIndexed { i, b ->
            if (t > 0) {
                wear(i, b, t)
                overhead(i, b, t)
                move(b, i, t)
            }
            if (b.hitpoints < b.baseHitpointsLvl) b.statRestore("stat.hitpoints")
            b.runEnergy = constants.run_max_energy
        }
        attacksAt[t]?.forEach { attack(it) }
        spellsAt[t]?.forEach { spell(it) }
        herAt[t]?.forEach { her(it) }
    }

    private fun check(t: Int) {
        checked++
        bots.forEachIndexed { i, b -> if (b.coords != at(raid.x(i, t), raid.y(i, t))) botDrift++ }
        for ((i, npc) in npcs) {
            val s = shown[i] ?: continue
            if (npc.isSlotAssigned && npc.coords != at(s.x, s.y)) npcDrift++
        }
    }

    private fun move(b: Player, i: Int, t: Int) {
        val dest = at(raid.x(i, t), raid.y(i, t))
        val dist = max(abs(dest.x - b.coords.x), abs(dest.z - b.coords.z))
        when {
            dist == 0 -> b.routeDestination.clear()
            dist <= MoveSpeed.Run.steps -> {
                b.walk(dest)
                if (dist == MoveSpeed.Run.steps) b.moveSpeed = MoveSpeed.Run
            }
            else -> PathingEntityCommon.telejump(b, collision, dest)
        }
    }

    private fun wear(i: Int, b: Player, t: Int) {
        val changes = gearAt[i][t] ?: return
        for ((_, slot, obj) in changes) {
            if (obj < 0) {
                WEARPOS.getOrNull(slot)?.takeIf { it >= 0 }?.let { b.worn[it] = null }
                continue
            }
            val type = ServerCacheManager.getItem(obj) ?: continue
            if (type.wearpos1 >= 0) b.worn[type.wearpos1] = InvObj(type)
        }
        b.rebuildAppearance()
    }

    private fun overhead(i: Int, b: Player, t: Int) {
        val head = overheadAt[i][t] ?: return
        b.overheadIcon = OVERHEAD_ICONS[head]
        b.rebuildAppearance()
    }

    private fun spawn(i: Int) {
        val track = raid.npcs[i]
        val first = track.steps.first()
        val type = ServerCacheManager.getNpc(first.type) ?: return
        val npc = Npc(type, at(first.x, first.y))
        npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        npc.moveRestrict = MoveRestrict.PassThru
        npc.ignoreCombatInteractions = true
        instances.attachNpc(session.id, npc)
        npcs[i] = npc
        hitpoints(npc, first)
        shown[i] = first
        if (first.type == PILLAR.asRSCM(RSCMType.NPC)) {
            val loc = locRepo.add(at(first.x, first.y), PILLAR_LOC, Int.MAX_VALUE, LocAngle.West, LocShape.CentrepieceStraight)
            pillarLocs[i] = loc
            locs += loc
        }
    }

    private fun step(i: Int, npc: Npc, s: VerzikBlert.Step) {
        val was = shown[i] ?: s
        shown[i] = s
        if (s.type != was.type) form(npc, was.type, s.type)
        if (s.type == was.type && s.type == P3.asRSCM(RSCMType.NPC) && s.hp == 0 && was.hp > 0) npc.anim(VerzikRules.P3_DEATH)
        val dest = at(s.x, s.y)
        val dist = max(abs(dest.x - npc.coords.x), abs(dest.z - npc.coords.z))
        if (dist == 1) npc.walk(dest) else if (dist > 1) npc.telejump(collision, dest)
        hitpoints(npc, s)
        if (s.base > 0 && s.hp < was.hp && was.type == s.type) splat(npc, was.hp - s.hp, hitmark_groups.regular_damage.lit)
        if (s.base > 0 && s.hp > was.hp && was.type == s.type && was.base == s.base) splat(npc, s.hp - was.hp, hitmark_groups.heal.lit)
    }

    private fun hitpoints(npc: Npc, s: VerzikBlert.Step) {
        if (s.base <= 0) return
        npc.baseHitpointsLvl = s.base
        npc.hitpoints = s.hp.coerceIn(0, s.base)
    }

    private fun splat(npc: Npc, amount: Int, hitmark: String) {
        val id = hitmark.asRSCM(RSCMType.HITMARK)
        val mark = Hitmark.fromNoSource(self = id, source = id, public = id, damage = amount, delay = 0)
        npc.showHitmark(mark)
        val bar = npc.visHeadbar(params.headbar)
        val fill = (bar.segments * npc.hitpoints.coerceAtLeast(0) / npc.baseHitpointsLvl.coerceAtLeast(1)).coerceIn(0, bar.segments)
        npc.showHeadbar(Headbar.fromNoSource(bar.id, bar.id, fill, fill, 0, 0))
    }

    /** Her own animation as she changes form; each form's model takes over from there. */
    private fun form(npc: Npc, from: Int, to: Int) {
        ServerCacheManager.getNpc(to)?.let { npc.transmog(it, Int.MAX_VALUE) }
        val seq =
            when (to) {
                P1_TO_P2.asRSCM(RSCMType.NPC) -> VerzikRules.P1_DEATH
                P2.asRSCM(RSCMType.NPC) -> VerzikRules.P2_SPAWN
                P2_TO_P3.asRSCM(RSCMType.NPC) -> VerzikRules.P2_DEATH
                P3.asRSCM(RSCMType.NPC) -> VerzikRules.P3_SPAWN
                else -> null
            }
        if (from != to) seq?.let { npc.anim(it) }
    }

    private fun die(i: Int) {
        val npc = npcs[i] ?: return
        pillarLocs.remove(i)?.let { collapse(it) }
        if (raid.npcs[i].type0 in HER_FORMS.map { it.asRSCM(RSCMType.NPC) }) return
        npc.visType.paramOrNull(params.death_anim)?.let { npc.anim(RSCM.getReverseMapping(RSCMType.SEQ, it.id)) }
        later += (clock + NPC_DEATH_TICKS) to { if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE) }
    }

    private fun collapse(pillar: LocInfo) {
        locRepo.del(pillar, Int.MAX_VALUE)
        locs.remove(pillar)
        val wide = CoordGrid(pillar.coords.x - 1, pillar.coords.z - 1, pillar.coords.level)
        val falling = locRepo.add(wide, PILLAR_COLLAPSE, Int.MAX_VALUE, LocAngle.West, LocShape.CentrepieceStraight)
        locs += falling
        later += (clock + PILLAR_COLLAPSE_TICKS) to {
            locRepo.del(falling, Int.MAX_VALUE)
            locs.remove(falling)
            val rubble = locRepo.add(wide, PILLAR_RUBBLE, Int.MAX_VALUE, LocAngle.West, LocShape.CentrepieceStraight)
            locs += rubble
            later += (clock + PILLAR_FADE - PILLAR_COLLAPSE_TICKS) to { worldRepo.locAnim(rubble, PILLAR_FADE_SEQ) }
            later += (clock + PILLAR_FADE + PILLAR_FADE_TICKS - PILLAR_COLLAPSE_TICKS) to {
                locRepo.del(rubble, Int.MAX_VALUE)
                locs.remove(rubble)
            }
        }
    }

    private fun attack(a: VerzikBlert.Attack) {
        val bot = bots.getOrNull(a.player) ?: return
        val target = npcs[a.target]?.takeIf { it.isSlotAssigned }
        if (target != null) {
            bot.faceSquare(target.coords, target.size, target.size)
            bot.faceNpc(target)
        }
        if (a.anim >= 0) RSCM.getReverseMapping(RSCMType.SEQ, a.anim)?.let { bot.anim(it) }
        target ?: return
        val weapon = ServerCacheManager.getItem(a.weapon)
        if (weapon != null) look.projectile(bot, weapon, bot.worn[AMMO_WEARPOS]?.let { ServerCacheManager.getItem(it.id) }, target)
        if (a.name == "SCYTHE") look.sweep(bot, target)
    }

    private fun spell(s: VerzikBlert.Spell) {
        val bot = bots.getOrNull(s.player) ?: return
        bots.getOrNull(s.target)?.let { bot.facePlayer(it) }
        if (s.anim >= 0) RSCM.getReverseMapping(RSCMType.SEQ, s.anim)?.let { bot.anim(it) }
        if (s.gfx >= 0) RSCM.getReverseMapping(RSCMType.SPOTANIM, s.gfx)?.let { (bots.getOrNull(s.target) ?: bot).spotanim(it) }
    }

    private fun her(h: VerzikBlert.Her) {
        val v = npcs[h.npc]?.takeIf { it.isSlotAssigned } ?: return
        val target = bots.getOrNull(h.target)
        target?.let { v.facePlayer(it) }
        fun at(proj: String, p: Player) = worldRepo.projAnim(v, p, SpotanimType(proj.asRSCM(RSCMType.SPOTANIM)), MaidenRules.HER_PROJANIM)
        fun everyone(proj: String) = bots.forEach { at(proj, it) }
        when (h.name) {
            "P1_AUTO" -> {
                v.anim(VerzikRules.P1_ATTACK)
                everyone(VerzikRules.P1_PROJ)
            }
            "P2_CABBAGE" -> {
                v.anim(VerzikRules.P2_ATTACK)
                everyone(VerzikRules.P2_PROJ)
            }
            "P2_ZAP" -> {
                v.anim(VerzikRules.P2_ATTACK)
                target?.let { at(VerzikRules.P2_BOLT, it) }
            }
            "P2_PURPLE" -> v.anim(VerzikRules.P2_ATTACK)
            "P2_MAGE" -> {
                v.anim(VerzikRules.P2_ATTACK)
                target?.let { at(VerzikRules.P2_BLOOD_PROJ, it) }
            }
            "P2_BOUNCE" -> {
                v.anim(VerzikRules.P2_MELEE)
                target?.let {
                    it.anim(VerzikRules.STUNNED)
                    it.spotanim(VerzikRules.STUNNED_GFX)
                }
            }
            "P3_MELEE" -> v.anim(VerzikRules.P3_MELEE)
            "P3_RANGE", "P3_AUTO" -> {
                v.anim(VerzikRules.P3_RANGED)
                target?.let { at(VerzikRules.P3_RANGE_PROJ, it) }
            }
            "P3_MAGE" -> {
                v.anim(VerzikRules.P3_MAGIC)
                target?.let { at(VerzikRules.P3_MAGE_PROJ, it) }
            }
            "P3_WEBS" -> v.anim(VerzikRules.P3_WEBS)
            "P3_YELLOWS" -> v.anim(VerzikRules.P3_YELLOWS)
            "P3_BALL" -> {
                v.anim(VerzikRules.P3_MAGIC)
                target?.let { at(VerzikRules.P3_BALL_PROJ, it) }
            }
        }
    }

    fun remove() {
        for (n in npcs.values) if (n.isSlotAssigned) npcRepo.del(n, Int.MAX_VALUE)
        npcs.clear()
        for (l in locs) locRepo.del(l, Int.MAX_VALUE)
        locs.clear()
        pillarLocs.clear()
        later.clear()
    }

    companion object {
        const val PRE_TICKS = 4
        const val END_HOLD = 8
        const val NPC_DEATH_TICKS = 3
        const val PILLAR_COLLAPSE_TICKS = 4
        const val PILLAR_FADE = 10
        const val PILLAR_FADE_TICKS = 2
        const val AMMO_WEARPOS = 13
        const val PILLAR = "npc.verzik_pillar_npc"
        const val PILLAR_LOC = "loc.tob_dungeon_verzik_pillar"
        const val PILLAR_COLLAPSE = "loc.tob_dungeon_verzik_pillar_collapse"
        const val PILLAR_RUBBLE = "loc.tob_dungeon_verzik_pillar_rubble"
        const val PILLAR_FADE_SEQ = "seq.verzik_pillar_fade"
        const val P1_TO_P2 = "npc.verzik_phase1_to2_transition"
        const val P2 = "npc.verzik_phase2"
        const val P2_TO_P3 = "npc.verzik_phase2_to3_transition"
        const val P3 = "npc.verzik_phase3"
        const val P3_DEAD = "npc.verzik_death_bat"
        val HER_FORMS = listOf("npc.verzik_initial", "npc.verzik_phase1", P1_TO_P2, P2, P2_TO_P3, P3, P3_DEAD)

        /** Blert's equipment slots (head, cape, amulet, ammo, weapon, torso, shield, legs, gloves, boots, ring, quiver) as wearpos. */
        val WEARPOS = listOf(0, 1, 2, 13, 3, 4, 5, 7, 9, 10, 12, -1)
        val OVERHEAD_ICONS =
            mapOf(
                "magic" to constants.overhead_protect_from_magic,
                "missiles" to constants.overhead_protect_from_missiles,
                "melee" to constants.overhead_protect_from_melee,
                "retribution" to constants.overhead_retribution,
                "redemption" to constants.overhead_redemption,
                "smite" to constants.overhead_smite,
            )
    }
}
