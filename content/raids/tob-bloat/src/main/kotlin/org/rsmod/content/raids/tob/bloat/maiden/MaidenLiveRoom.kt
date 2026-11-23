package org.rsmod.content.raids.tob.bloat.maiden

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import dev.openrune.types.MoveRestrict
import dev.openrune.types.NpcMode
import dev.openrune.types.aconverted.SpotanimType
import org.rsmod.api.config.refs.params
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.player.PracticeSettings
import org.rsmod.api.player.hit.modifier.PlayerHitModifier
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.config.constants
import org.rsmod.api.player.stat.statRestore
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.content.raids.tob.bloat.MaidenRules
import org.rsmod.content.raids.tob.bloat.RaidBots
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.map.CoordGrid
import kotlin.math.abs
import kotlin.math.max

/**
 * A live Maiden room: the sim's Maiden ([MaidenEngine]) runs it, tick by tick, in the server. Maiden, her crabs and the
 * five bots are real in the instance; the engine decides - her attacks and targeting, crabs spawning on the procs she
 * actually takes, walking, freezing and leaking, every player's list - and the room plays each of it out: bots run to
 * where their next attack goes from and only swing once they're there (the attack waits for them), switch into its gear,
 * turn to their target and attack (the weapon's animation and projectile), and the hits land as the engine rolls them.
 * A real player in the room counts too: their hits on her or a crab go into the engine.
 *
 * One seed, one raid: the bots stand where [MaidenMover] has them, so a seed plays the same raid here as in the
 * headless index ([MaidenIndex]) - unless a real player joins in.
 */
class MaidenLiveRoom(
    val session: InstanceSession,
    private val base: CoordGrid,
    val maiden: Npc,
    val seed: String,
    private val npcRepo: NpcRepository,
    private val worldRepo: WorldRepository,
    private val npcHits: NpcHitModifier,
    private val playerHits: PlayerHitModifier,
    private val clicks: RaidBots,
) {
    private val logger = InlineLogger()

    val bots = ArrayList<Player>()
    var clock = -START_DELAY
        private set

    var log = false

    /** Bot-ticks a bot wasn't on the mover's tile (should stay 0: then the room plays the index's raid). */
    var offSpot = 0
        private set
    val engine = MaidenEngine(5, MaidenPlans.FIVE_STACK, SimRng(seed), MaidenPlans.FIVE_TARGET, Shows())
    private val crabs = HashMap<String, Npc>()
    private val removeAt = HashMap<Npc, Int>()
    private val worn = HashMap<Player, String>()
    private val lastClick = HashMap<Player, CoordGrid>()
    private val faceOf = HashMap<Player, Npc>()
    private val lastSeen = HashMap<Player, CoordGrid>()
    private val later = HashMap<Int, MutableList<() -> Unit>>()
    private val pendingFreezes = ArrayList<Pair<MaidenEngine.Crab, String>>()
    private var pendingHer = 0
    private val pendingCrab = HashMap<String, Int>()

    /** Where the bots stand: it also holds an attack until its bot is in reach (the engine's gate). */
    val mover = MaidenMover(engine)

    init {
        mover.onHold = { p, a, xy, g -> if (log) logger.info { "maiden t${engine.t} hold ${p.name} $a at ${xy.joinToString(",")} going ${g.joinToString(",")}" } }
        engine.holdHp = { PracticeSettings.infiniteHp }
    }

    fun tile(x: Int, y: Int): CoordGrid = base.translate(x, MaidenRules.ROOM_H - 1 - y)

    /** A crab's south-west tile from the engine's (its north-west). */
    fun crabTile(x: Int, y: Int): CoordGrid = tile(x, y + 1)

    private fun simOf(c: CoordGrid): IntArray = intArrayOf(c.x - base.x, MaidenRules.ROOM_H - 1 - (c.z - base.z))

    fun contains(c: CoordGrid): Boolean = c.level == base.level && c.x in base.x - 4..base.x + 40 && c.z in base.z - 4..base.z + 30

    val finished: Boolean
        get() = engine.end?.let { clock > it + END_HOLD } ?: false

    fun botOf(p: MaidenEngine.P): Player? = bots.getOrNull(p.i)

    fun tick() {
        clock++
        maiden.mode = NpcMode.None
        pendingHer = 0
        pendingCrab.clear()
        // the bots should be where the mover put them last tick
        if (clock > 0 && engine.dead == null) for (p in engine.ps) botOf(p)?.let { b -> if (b.coords != tile(mover.pos[p.i][0], mover.pos[p.i][1])) offSpot++ }
        if (clock >= 0 && engine.dead == null && engine.t < MaidenMover.MAX_T) {
            mover.move()
            engine.tick()
            mover.afterTick()
        }
        // her HP is the engine's: this tick's splats (queued above) take it there as they land
        if (maiden.isSlotAssigned && !PracticeSettings.infiniteHp) maiden.hitpoints = max(0, engine.hp) + pendingHer
        for (c in engine.crabs) {
            val npc = npcOf(c) ?: continue
            npc.hitpoints = max(0, c.hp) + (pendingCrab[c.key] ?: 0)
        }
        // the team comes in: everyone clicks Pass on the barrier in front of them, and lands inside as the raid starts
        if (clock == -PASS_TICKS) for (b in bots) clicks.opLoc(b, MaidenRules.BARRIER_ID, base.translate(MaidenRules.BARRIER_X, b.coords.z - base.z))
        later.remove(clock)?.forEach { it() }
        moveCrabs()
        moveBots()
        // bots stay up and keep running (the sim's healing and run energy aren't modelled)
        for (b in bots) {
            if (b.hitpoints < b.baseHitpointsLvl) b.statRestore("stat.hitpoints")
            b.runEnergy = constants.run_max_energy
        }
        for (b in bots) lastSeen[b] = b.coords
        for ((npc, at) in removeAt.entries.toList()) if (clock >= at) {
            removeAt.remove(npc)
            if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE)
        }
        if (log) logger.info { "maiden t${engine.t} hp ${engine.hp} " + bots.joinToString { b -> "${b.username}@${simOf(b.coords).joinToString(",")}" } }
    }

    private fun at(t: Int, f: () -> Unit) {
        later.getOrPut(t) { ArrayList() } += f
    }

    /* ---- bots: where the mover has them (2 tiles a tick, to their next attack's spot - the attack waits for them) */

    /** Moved last tick (the client is still drawing that step), or still on its way to where it was sent. */
    private fun Player.isMoving(): Boolean = coords != lastSeen[this] || lastClick[this]?.let { it != coords } == true

    private fun walk(bot: Player, dest: CoordGrid) {
        if (dest == lastClick[bot]) return
        if (dest == bot.coords && lastClick[bot] == null) return
        clicks.walk(bot, dest)
        lastClick[bot] = dest
    }

    private fun moveBots() {
        for (p in engine.ps) {
            val bot = botOf(p) ?: continue
            if (bot !in worn) MaidenRules.KITS[MaidenEngine.atkOf(p.seq[0]).code]?.let { equip(bot, it) }
            if (clock < 0) continue
            val xy = mover.pos[p.i]
            walk(bot, tile(xy[0], xy[1]))
            faceOf[bot]?.takeIf { it.isSlotAssigned }?.let { if (!bot.isMoving()) bot.faceNpc(it) }
        }
    }

    /* ---- crabs */

    private fun moveCrabs() {
        for (c in engine.crabs) {
            val npc = crabs[c.key] ?: continue
            if (!npc.isSlotAssigned || !c.alive) continue
            npc.mode = NpcMode.None
            val dest = crabTile(c.x, c.y)
            if (dest != npc.coords) {
                npc.routeDestination.clear()
                npc.routeDestination.add(dest)
                npc.moveSpeed = MoveSpeed.Walk
                npc.forcedRoute = true
            }
        }
    }

    private fun npcOf(c: MaidenEngine.Crab?): Npc? = c?.let { crabs[it.key] }?.takeIf { it.isSlotAssigned }

    /* ---- what the engine does, shown */

    private inner class Shows : MaidenEngine.Events {
        override fun spawn(t: Int, c: MaidenEngine.Crab) {
            val npc = Npc(MaidenRules.CRAB_NPC, crabTile(c.x, c.y))
            npcRepo.add(npc, Int.MAX_VALUE)
            npc.respawns = false
            npc.moveRestrict = MoveRestrict.PassThru
            npc.ignoreCombatInteractions = true
            npc.baseHitpointsLvl = c.hp
            npc.hitpoints = c.hp
            crabs[c.key] = npc
        }

        override fun freeze(t: Int, c: MaidenEngine.Crab, spell: String) {
            pendingFreezes += c to spell
        }

        override fun leak(t: Int, c: MaidenEngine.Crab) {
            // into her: gone at once (she heals twice its HP next tick)
            npcOf(c)?.let { npcRepo.del(it, Int.MAX_VALUE) }
        }

        override fun crabDies(t: Int, c: MaidenEngine.Crab) {
            val npc = npcOf(c) ?: return
            npc.visType.paramOrNull(params.death_anim)?.let { npc.anim(RSCM.getReverseMapping(RSCMType.SEQ, it.id)) }
            removeAt[npc] = clock + CRAB_DEATH_TICKS
        }

        override fun hitHer(t: Int, p: MaidenEngine.P?, amt: Int, kind: String, label: String) {
            val bot = p?.let { botOf(it) } ?: bots.firstOrNull() ?: return
            if (!maiden.isSlotAssigned) return
            val parts = SPLIT.find(label)?.value?.split('/')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.sum() == amt } ?: listOf(amt)
            val type = typeOf(kind)
            for (d in parts) {
                pendingHer += d
                maiden.queueHit(bot, 1, type, d, npcHits)
            }
        }

        override fun hitCrab(t: Int, p: MaidenEngine.P, c: MaidenEngine.Crab, amt: Int) {
            val npc = npcOf(c) ?: return
            val bot = botOf(p) ?: return
            pendingCrab[c.key] = (pendingCrab[c.key] ?: 0) + amt
            npc.queueHit(bot, 1, HitType.Typeless, amt, npcHits)
        }

        override fun attack(t: Int, p: MaidenEngine.P, a: String, on: MaidenEngine.Crab?, code: String, lands: Int) {
            val bot = botOf(p) ?: return
            val target = if (on != null) npcOf(on) ?: maiden else maiden
            val kit = MaidenRules.KITS[code]
            if (kit != null) equip(bot, kit)
            bot.faceSquare(target.coords, target.size, target.size)
            bot.faceNpc(target)
            faceOf[bot] = target
            if (kit != null) bot.anim(RSCM.getReverseMapping(RSCMType.SEQ, kit.seq))
            if (kit?.style == "range") {
                val weapon = ServerCacheManager.getItem(kit.weapon)
                if (weapon != null) projectile(bot, weapon, kit.ammo?.let { ServerCacheManager.getItem(it) }, target)
            }
            if (code == "S") sweep(bot, target)
            if (kit?.style == "mage") {
                // the spell's ice on what it froze, as it lands
                val frozen = pendingFreezes.toList()
                pendingFreezes.clear()
                val at = clock + max(0, lands - t)
                at(at) { for ((c, spell) in frozen) npcOf(c)?.let { n -> worldRepo.spotanimMap(SpotanimType(IMPACT.getValue(spell).asRSCM(RSCMType.SPOTANIM)), n.coords.translate(1, 1), height = 0) } }
            }
        }

        override fun herAttack(t: Int, on: MaidenEngine.P, lands: Int) {
            val bot = botOf(on) ?: return
            maiden.anim(MaidenRules.HER_ATTACK)
            worldRepo.projAnim(maiden, bot, SpotanimType(MaidenRules.HER_PROJ.asRSCM(RSCMType.SPOTANIM)), MaidenRules.HER_PROJANIM)
        }

        override fun herHit(t: Int, on: MaidenEngine.P, amt: Int) {
            val bot = botOf(on) ?: return
            bot.queueHit(maiden, 1, HitType.Magic, amt, playerHits)
        }

        override fun veng(t: Int, p: MaidenEngine.P, amt: Int) {
            botOf(p)?.say("Taste vengeance!")
        }

        override fun proc(t: Int, pct: Int) {
            val phase = when (pct) { 70 -> 1; 50 -> 2; else -> 3 }
            ServerCacheManager.getNpc(MaidenRules.PHASES[phase].asRSCM(RSCMType.NPC))?.let { maiden.transmog(it, Int.MAX_VALUE) }
        }

        override fun dead(t: Int) {
            // the crabs left die with her
            for (c in engine.crabs) if (c.alive) crabDies(t, c)
        }
    }

    private fun typeOf(kind: String): HitType =
        when {
            kind in RANGED_KINDS -> HitType.Ranged
            kind == "veng" || kind == "thrall" || kind == "external" -> HitType.Typeless
            else -> HitType.Melee
        }

    /* ---- a real player's hits (the hit hook): into the engine */

    /** A hit landing on her or a crab: a bot's is the engine's own (already counted); anyone else's goes in. */
    fun land(npc: Npc, hit: Hit, source: Player?): Int {
        if (source == null || source in bots) return hit.damage
        if (npc === maiden) {
            engine.external(hit.damage)
            return hit.damage
        }
        val c = crabs.entries.firstOrNull { it.value === npc }?.key?.let { engine.crabRef(it) } ?: return hit.damage
        engine.externalCrab(c, hit.damage)
        return hit.damage
    }

    /* ---- the bots' look: gear, animation, projectile */

    private fun projectile(bot: Player, weapon: ItemServerType, ammo: ItemServerType?, target: Npc) {
        val projanim = weapon.paramOrNull(params.proj_type)?.let { RSCM.getReverseMapping(RSCMType.PROJANIM, it.id) } ?: return
        val travel = ammo?.paramOrNull(params.proj_travel) ?: weapon.paramOrNull(params.proj_travel) ?: return
        worldRepo.projAnim(bot, target, travel, projanim)
    }

    /** The scythe's swing graphic, on the side of the bot facing the target. */
    private fun sweep(bot: Player, target: Npc) {
        val dx = target.coords.x + target.size / 2 - bot.coords.x
        val dz = target.coords.z + target.size / 2 - bot.coords.z
        val (dir, step) =
            if (abs(dx) >= abs(dz)) (if (dx > 0) "east" to (1 to 0) else "west" to (-1 to 0))
            else (if (dz > 0) "north" to (0 to 1) else "south" to (0 to -1))
        worldRepo.spotanimMap(SpotanimType("spotanim.dragon_halberd_special_${dir}_red".asRSCM(RSCMType.SPOTANIM)), bot.coords.translate(step.first, step.second), height = 96, delay = 20)
    }

    /** Puts the code's armour set and weapon on (only when it changes). */
    private fun equip(bot: Player, kit: MaidenRules.Kit) {
        val key = "${kit.style}/${kit.weapon}/${kit.ammo}"
        if (worn[bot] == key) return
        worn[bot] = key
        for (slot in 0 until bot.worn.size) bot.worn[slot] = null
        for (id in MaidenRules.STYLE_SETS.getValue(kit.style) + kit.weapon + listOfNotNull(kit.ammo)) {
            val item = ServerCacheManager.getItem(id) ?: continue
            if (item.wearpos1 < 0) continue
            bot.worn[item.wearpos1] = InvObj(item)
        }
        bot.rebuildAppearance()
    }

    fun has(npc: Npc): Boolean = npc === maiden || crabs.values.any { it === npc }

    fun remove() {
        for (c in crabs.values) if (c.isSlotAssigned) npcRepo.del(c, Int.MAX_VALUE)
        crabs.clear()
        if (maiden.isSlotAssigned) npcRepo.del(maiden, Int.MAX_VALUE)
    }

    companion object {
        const val START_DELAY = 5

        /** Ticks before the raid's first that the team clicks Pass (the walk through the barrier is drawn over two). */
        const val PASS_TICKS = 1
        const val END_HOLD = 6
        const val CRAB_DEATH_TICKS = 3
        private val SPLIT = Regex("""\d+(?:/\d+)+""")
        private val RANGED_KINDS = setOf("tbr", "tba", "bp", "zcbs", "zcbg", "zcbx", "zcbf", "ralos", "ralosl", "ax", "zcb")
        private val IMPACT = mapOf("barrage" to "spotanim.ice_barrage_impact", "burst" to "spotanim.ice_burst_impact", "blitz" to "spotanim.ice_blitz_impact", "rush" to "spotanim.ice_rush_impact")
    }
}
