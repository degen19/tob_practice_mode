package org.rsmod.content.raids.tob.solo

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.player.clearInteractionRoute
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.content.raids.tob.bloat.RaidBots
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionNpc
import org.rsmod.map.CoordGrid
import kotlin.math.max

/**
 * Bot teammates for the Maiden, played the way Blert's fastest trios do it (and the sim's trio procedure):
 *
 * - everyone opens with a Twisted bow from range and the Tonalztics of Ralos spec, then the ranger and melee scythe her
 *   for the rest of the room while the mage keeps bowing her from range;
 * - at each proc the mage holds its barrage for the crabs to clump as they close in on her (Blert: they stand frozen a
 *   couple of tiles off her east side), casts where it catches the most, re-freezes any about to thaw, and barrages a
 *   frozen pile with enough hitpoints left in it;
 * - the scythers leave her for a crab nobody froze that is about to reach her, and for a frozen pile close by with
 *   enough hitpoints in it; the melee puts its Dinh's bulwark spec into the crabs once enough are close together;
 * - the ranger spends its second spec on her between procs, the mage its second once the crabs are dealt with, and the
 *   mage comes in to scythe at the end.
 *
 * They fight through the game's own combat; [MaidenCrewParams] holds the choices a search can tune.
 */
class MaidenCrew(
    override val room: MaidenSolo,
    private val members: List<Pair<Player, NyloRole>>,
    private val bots: RaidBots,
    private val clock: MapClock,
    private val barrage: Int,
    private val params: MaidenCrewParams = MaidenCrewParams(),
) : BotCrew {
    enum class Move(val set: GearSet, val stance: Int, val spec: Boolean = false) {
        Tbow(NyloLoadout.TBOW, 1),
        Ralos(NyloLoadout.RALOS, 1, true),
        Scythe(NyloLoadout.SCYTHE, 1),
        Dinh(NyloLoadout.DINH, 0, true),
        Barrage(NyloLoadout.BARRAGE, 0),
    }

    private class Plan(val move: Move, val target: Npc)

    /** How many of the opening attacks each bot has thrown, and its last order (the move, on which clock). */
    private val opened = HashMap<Player, Int>()
    private val issued = HashMap<Player, Pair<Move, Int>>()

    /** A crab a scyther is on its way to (no one else's), until when. */
    private val claimed = HashMap<Npc, Pair<Player, Int>>()

    /** The crab the mage last froze around, until its barrage has landed. */
    private var freezing: Pair<CoordGrid, Int>? = null

    private val procAt = ArrayList<Int>()
    private var phaseSeen = 0

    override fun starts(): List<CoordGrid> = members.indices.map { START_TILES[it % START_TILES.size] }

    override fun doneAt(): Int = room.deadAt

    override fun summary(): String =
        "Maiden dead at room tick ${room.deadAt} (${raidTime(room.deadAt)}), procs at ${procAt.joinToString("/")}, crabs leaked ${room.leaked}, healed ${room.healed}"

    override fun tick() {
        if (room.deadAt >= 0) return
        val now = clock.cycle
        val crabs = room.crabViews()
        val phase = room.phase
        while (phaseSeen < phase) {
            phaseSeen++
            procAt += room.t
        }
        claimed.entries.removeIf { (npc, claim) -> !npc.isSlotAssigned || npc.hitpoints <= 0 || claim.second < room.t }
        freezing = freezing?.takeIf { it.second >= room.t }
        for ((bot, role) in members) {
            if (!bot.isSlotAssigned || bot.hitpoints <= 0) continue
            noteThrown(bot)
            if (bot.actionDelay > now + 1) continue
            val plan = plan(bot, role, crabs, now) ?: continue
            act(bot, plan, now)
        }
    }

    /** An order went off once the bot's attack delay moves past the clock it could first go off on. */
    private fun noteThrown(bot: Player) {
        val (move, at) = issued[bot] ?: return
        if (bot.actionDelay <= at) return
        issued.remove(bot)
        if (opened.getOrDefault(bot, 0) < OPENING.size && OPENING[opened.getOrDefault(bot, 0)] == move) opened[bot] = opened.getOrDefault(bot, 0) + 1
    }

    private fun plan(bot: Player, role: NyloRole, crabs: List<MaidenSolo.CrabView>, now: Int): Plan? {
        val her = room.boss
        val step = opened.getOrDefault(bot, 0)
        if (step < OPENING.size && room.phase == 0) {
            val move = OPENING[step]
            if (!move.spec || specReady(bot)) return Plan(move, her)
            opened[bot] = OPENING.size
        }
        return when (role) {
            NyloRole.Mage -> mage(bot, crabs, now)
            else -> scyther(bot, role, crabs, now)
        }
    }

    private fun scyther(bot: Player, role: NyloRole, crabs: List<MaidenSolo.CrabView>, now: Int): Plan {
        val her = room.boss
        val loose = crabs.filter { !it.arrived && it.frozenUntil <= now && !aboutToFreeze(it) }
        val here = room.real(bot.coords)
        if (role == NyloRole.Melee && specReady(bot) && room.phase >= params.dinhPhase) {
            val near = crabs.filter { !it.arrived && dist(here, room.real(it.npc.coords), CRAB_SIZE) <= BASH_RADIUS }
            if (near.size >= params.dinhMin) return Plan(Move.Dinh, near.minBy { it.gap }.npc)
        }
        val leaking = loose.filter { it.gap <= params.crabGap && it.gap - 1 >= toHit(here, it) && claimed[it.npc]?.first.let { c -> c == null || c == bot } }
        leaking.minByOrNull { it.gap }?.let {
            claimed[it.npc] = bot to room.t + CLAIM_TICKS
            return Plan(Move.Scythe, it.npc)
        }
        val pile = crabs.filter { !it.arrived && it.frozenUntil > now && (it.npc.hitpoints >= params.pileHp || thawing(it, now)) && dist(here, room.real(it.npc.coords), CRAB_SIZE) <= PILE_REACH }
        pile.maxByOrNull { c -> pile.count { inSquare(it, c) } }?.let { return Plan(Move.Scythe, it.npc) }
        if (role == NyloRole.Ranger && room.t >= params.rangerSpecAt && specReady(bot)) return Plan(Move.Ralos, her)
        return Plan(Move.Scythe, her)
    }

    private fun mage(bot: Player, crabs: List<MaidenSolo.CrabView>, now: Int): Plan? {
        val her = room.boss
        val walking = crabs.filter { !it.arrived && it.frozenUntil <= now + params.refreezeTicks }
        val close = walking.filter { it.gap <= params.clumpGap || it.gap - 1 <= params.mustFreeze }
        if (close.isNotEmpty()) {
            val primary = close.maxBy { c -> walking.filter { inSquare(it, c) }.sumOf { urgency(it) } }
            freezing = room.real(primary.npc.coords) to room.t + FREEZE_FLIGHT
            return Plan(Move.Barrage, primary.npc)
        }
        if (walking.any { it.gap <= params.clumpGap + params.holdTicks }) {
            bot.clearInteractionRoute()
            issued.remove(bot)
            return null
        }
        val standing = crabs.filter { !it.arrived }
        val pile = standing.maxByOrNull { c -> standing.filter { inSquare(it, c) }.sumOf { it.npc.hitpoints } }
        val thawing = standing.any { thawing(it, now) }
        if (pile != null && (thawing || standing.filter { inSquare(it, pile) }.sumOf { it.npc.hitpoints } >= params.mageKillHp)) return Plan(Move.Barrage, pile.npc)
        if (her.hitpoints * 100 <= her.baseHitpointsLvl * params.mageScytheBelow) return Plan(Move.Scythe, her)
        if (room.t >= params.mageSpecAt && specReady(bot)) return Plan(Move.Ralos, her)
        return Plan(Move.Tbow, her)
    }

    /** Ticks until a scythe swung from [here] lands on the crab: the run to it (two tiles a tick), then the swing. */
    private fun toHit(here: CoordGrid, c: MaidenSolo.CrabView): Int = (dist(here, room.real(c.npc.coords), CRAB_SIZE) - 1 + 1) / 2 + 1

    /** A frozen crab that walks on soon: whoever can reach it finishes it first. */
    private fun thawing(c: MaidenSolo.CrabView, now: Int): Boolean = c.frozenUntil > now && c.frozenUntil - now <= params.thawTicks

    /** The crab closest to reaching her counts most: a crab one tile out is worth far more than one at its spawn. */
    private fun urgency(c: MaidenSolo.CrabView): Double = 1.0 / (1 + c.gap)

    private fun aboutToFreeze(c: MaidenSolo.CrabView): Boolean {
        val (at, _) = freezing ?: return false
        val tile = room.real(c.npc.coords)
        return overlaps(tile, CRAB_SIZE, at.x - 1, at.z - 1, at.x + CRAB_SIZE, at.z + CRAB_SIZE)
    }

    private fun inSquare(c: MaidenSolo.CrabView, primary: MaidenSolo.CrabView): Boolean {
        val at = room.real(primary.npc.coords)
        return overlaps(room.real(c.npc.coords), CRAB_SIZE, at.x - 1, at.z - 1, at.x + CRAB_SIZE, at.z + CRAB_SIZE)
    }

    private fun specReady(bot: Player): Boolean = bot.vars["varp.sa_energy"] >= SPEC_ENERGY

    private fun act(bot: Player, plan: Plan, now: Int) {
        for ((slot, obj) in plan.move.set.worn) {
            val id = obj.asRSCM(RSCMType.OBJ)
            if (bot.worn[slot.slot]?.id != id) bots.equip(bot, slot.slot, id)
        }
        if (plan.move != Move.Barrage) VarPlayerIntMapSetter.set(bot, "varp.com_mode", plan.move.stance)
        if (plan.move.spec) VarPlayerIntMapSetter.set(bot, "varp.sa_attack", 1)
        val same = current(bot) == plan.target && issued[bot]?.first == plan.move
        if (same && plan.move != Move.Barrage) return
        if (plan.move == Move.Barrage) bots.cast(bot, plan.target, barrage) else bots.attack(bot, plan.target)
        issued[bot] = plan.move to max(now, bot.actionDelay)
    }

    private fun current(bot: Player): Npc? = (bot.interaction as? InteractionNpc)?.target

    private fun overlaps(at: CoordGrid, size: Int, x0: Int, z0: Int, x1: Int, z1: Int): Boolean =
        at.x <= x1 && at.x + size - 1 >= x0 && at.z <= z1 && at.z + size - 1 >= z0

    private fun dist(a: CoordGrid, b: CoordGrid, size: Int): Int =
        max(max(b.x - a.x, a.x - (b.x + size - 1)).coerceAtLeast(0), max(b.z - a.z, a.z - (b.z + size - 1)).coerceAtLeast(0))

    private companion object {
        val OPENING = listOf(Move.Tbow, Move.Ralos)
        val START_TILES = listOf(CoordGrid(3184, 4445, 0), CoordGrid(3184, 4447, 0), CoordGrid(3184, 4448, 0))
        const val CRAB_SIZE = 2
        const val BASH_RADIUS = 5
        const val SPEC_ENERGY = 500
        const val CLAIM_TICKS = 4
        const val PILE_REACH = 3
        const val FREEZE_FLIGHT = 3
    }
}

/** The Maiden crew's choices (see [MaidenCrew]); `parse` reads `name=value,...` and keeps the default for the rest. */
data class MaidenCrewParams(
    val crabGap: Int = 4,
    val clumpGap: Int = 3,
    val mustFreeze: Int = 10,
    val thawTicks: Int = 0,
    val holdTicks: Int = 0,
    val mageKillHp: Int = 144,
    val mageScytheBelow: Int = 32,
    val refreezeTicks: Int = 2,
    val pileHp: Int = 0,
    val dinhPhase: Int = 1,
    val dinhMin: Int = 5,
    val rangerSpecAt: Int = 29,
    val mageSpecAt: Int = 120,
) {
    companion object {
        fun parse(text: String): MaidenCrewParams {
            val values = text.split(',').mapNotNull { it.split('=').takeIf { kv -> kv.size == 2 }?.let { (k, v) -> k.trim() to v.trim() } }.toMap()
            val d = MaidenCrewParams()
            fun int(name: String, default: Int) = values[name]?.toDoubleOrNull()?.toInt() ?: default
            return MaidenCrewParams(
                crabGap = int("crabGap", d.crabGap),
                clumpGap = int("clumpGap", d.clumpGap),
                mustFreeze = int("mustFreeze", d.mustFreeze),
                thawTicks = int("thawTicks", d.thawTicks),
                holdTicks = int("holdTicks", d.holdTicks),
                mageKillHp = int("mageKillHp", d.mageKillHp),
                mageScytheBelow = int("mageScytheBelow", d.mageScytheBelow),
                refreezeTicks = int("refreezeTicks", d.refreezeTicks),
                pileHp = int("pileHp", d.pileHp),
                dinhPhase = int("dinhPhase", d.dinhPhase),
                dinhMin = int("dinhMin", d.dinhMin),
                rangerSpecAt = int("rangerSpecAt", d.rangerSpecAt),
                mageSpecAt = int("mageSpecAt", d.mageSpecAt),
            )
        }
    }
}
