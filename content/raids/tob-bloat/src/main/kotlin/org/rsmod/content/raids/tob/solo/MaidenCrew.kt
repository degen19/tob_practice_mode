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
 * - the mage plays the sim's trio procedure: it stands for each proc so its barrage is ready on the spawn, freezes the
 *   wave plan's 1s and 2s, then the 3s once clumped and the 4s as they walk into them, then barrages a frozen pile
 *   worth it;
 * - the scythers each take one of the wave's 1s and 2s the mage doesn't (north first), and a frozen pile close by with
 *   enough hitpoints in it; the melee puts its Dinh's bulwark spec into the crabs once enough are close together;
 * - the sim's specs: the mage's Zaryte crossbow spec after its 70s freezes, the ranger's at the 50s, the melee's at the
 *   30s, each with a surge potion when it's short; the ranger and melee keep Vengeance up and raise a thrall after
 *   their prefires; the mage comes in to scythe at the end.
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
    /** What a bot can throw: its gear, stance (ranged always on rapid) and, for a spec, the energy it costs. */
    enum class Move(val set: GearSet, val stance: Int, val cost: Int = 0) {
        Tbow(NyloLoadout.TBOW, 1),
        Blowpipe(NyloLoadout.BLOWPIPE, 1),
        Ralos(NyloLoadout.RALOS, 1, 500),
        Zcb(NyloLoadout.ZCB, 1, 750),
        Scythe(NyloLoadout.SCYTHE, 1),
        Dinh(NyloLoadout.DINH, 0, 500),
        Barrage(NyloLoadout.BARRAGE, 0),
        ;

        val spec: Boolean
            get() = cost > 0
    }

    private class Plan(val move: Move, val target: Npc)

    /** How many of the opening attacks each bot has thrown, and its last order (the move, on which clock). */
    private val opened = HashMap<Player, Int>()
    private val issued = HashMap<Player, Pair<Move, Int>>()

    /** A bot's order on a crab, kept until it goes off (or the crab goes, or it's stale): a wave plan's pick is used once. */
    private val onCrab = HashMap<Player, Pair<Npc, Int>>()

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
        for ((bot, role) in members) {
            if (!bot.isSlotAssigned || bot.hitpoints <= 0) continue
            noteThrown(bot)
            if (role != NyloRole.Mage) spells(bot)
            if (bot.actionDelay > now + 1) continue
            val (crab, at) = onCrab[bot] ?: (null to 0)
            if (crab != null && crab.isSlotAssigned && crab.hitpoints > 0 && room.t - at <= CRAB_ORDER_TICKS) continue
            onCrab.remove(bot)
            val plan = plan(bot, role, crabs, now) ?: continue
            act(bot, plan, now)
        }
    }

    /** An order went off once the bot's attack delay moves past the clock it could first go off on. */
    private fun noteThrown(bot: Player) {
        val (move, at) = issued[bot] ?: return
        if (bot.actionDelay <= at) return
        issued.remove(bot)
        onCrab.remove(bot)
        val opening = opening(members.first { it.first === bot }.second)
        if (opened.getOrDefault(bot, 0) < opening.size && opening[opened.getOrDefault(bot, 0)] == move) opened[bot] = opened.getOrDefault(bot, 0) + 1
    }

    /** The sim's prefires: everyone bows and throws the Tonalztics spec; the mage then a blowpipe before bowing on. */
    private fun opening(role: NyloRole): List<Move> = if (role == NyloRole.Mage) MAGE_OPENING else OPENING

    /** When each bot last swapped spellbooks, raised its thrall, and drank its surge. */
    private val swappedAt = HashMap<Player, Int>()
    private val thralled = HashSet<Player>()
    private val surged = HashSet<Player>()

    /**
     * The ranger and melee on the Lunars (the sim's DPS spellbook): a thrall once their prefires are out (Spellbook Swap
     * to the Arceuus, then a greater ghost), and Vengeance whenever it's off cooldown.
     */
    private fun spells(bot: Player) {
        if (opened.getOrDefault(bot, 0) >= OPENING.size && bot !in thralled) {
            val swapped = swappedAt[bot]
            if (swapped == null) {
                bots.spell(bot, SPELLBOOK_SWAP, SWAP_TO_ARCEUUS)
                swappedAt[bot] = room.t
            } else if (room.t > swapped) {
                bots.spell(bot, GREATER_GHOST)
                thralled += bot
            }
            return
        }
        if (bot.vars["varbit.vengeance_rebound"] == 0 && bot.vars["varbit.vengeance_timelimit"] == 0 && bot.vars["varbit.spellbook"] == LUNARS) bots.spell(bot, VENGEANCE)
    }

    /**
     * A spec on her when there's the energy for it: short of it by no more than a surge potion's quarter, the bot drinks
     * its surge first (the sim's 'surge' line before a spec).
     */
    private fun spec(bot: Player, move: Move): Plan? {
        val energy = bot.vars["varp.sa_energy"]
        if (energy >= move.cost) return Plan(move, room.boss)
        if (bot in surged || energy + SURGE_ENERGY < move.cost) return null
        if (bots.useItem(bot, NyloLoadout.SURGE.asRSCM(RSCMType.OBJ))) surged += bot
        return null
    }

    private fun plan(bot: Player, role: NyloRole, crabs: List<MaidenSolo.CrabView>, now: Int): Plan? {
        val her = room.boss
        val step = opened.getOrDefault(bot, 0)
        val opening = opening(role)
        if (step < opening.size && room.phase == 0) {
            val move = opening[step]
            if (!move.spec || specReady(bot, move)) return Plan(move, her)
            opened[bot] = opening.size
        }
        return when (role) {
            NyloRole.Mage -> mage(bot, crabs, now)
            else -> scyther(bot, role, crabs, now)
        }
    }

    /**
     * The sim's trio plan for a wave (its `planOf`, from the trio guide): the mage freezes two of the 1s and 2s - all
     * four out: S1 and S2; no S1 or N1: the S2 (it thaws into the clump); else one 1 (S1 before N1) and one 2 (S2 before
     * N2) - and the DPS take the rest, north first, one each.
     */
    private class WavePlan(val freeze: List<String>, val dps: List<String>) {
        val done = HashSet<String>()
    }

    private val plans = HashMap<Int, WavePlan>()

    private fun planOf(wave: Int, crabs: List<MaidenSolo.CrabView>): WavePlan =
        plans.getOrPut(wave) {
            val here = { id: String -> crabs.any { it.born == wave && it.spot == id } }
            val out = listOf("S1", "N1", "S2", "N2").filter(here)
            val freeze =
                when {
                    out.size == 4 -> listOf("S1", "S2")
                    !here("S1") && !here("N1") && here("S2") -> listOf("S2") + listOfNotNull("N2".takeIf(here))
                    else -> {
                        val ones = listOf("S1", "N1").filter(here)
                        val twos = listOf("S2", "N2").filter(here)
                        if (ones.isNotEmpty()) listOf(ones.first()) + twos.take(1) else twos
                    }
                }
            WavePlan(freeze, listOf("N1", "N2", "S1", "S2").filter { it in out && it !in freeze })
        }

    private fun crabOf(crabs: List<MaidenSolo.CrabView>, wave: Int, id: String): MaidenSolo.CrabView? =
        crabs.firstOrNull { it.born == wave && it.spot == id && !it.arrived }

    private fun scyther(bot: Player, role: NyloRole, crabs: List<MaidenSolo.CrabView>, now: Int): Plan {
        val her = room.boss
        val here = room.real(bot.coords)
        if (role == NyloRole.Melee && specReady(bot, Move.Dinh) && room.phase >= params.dinhPhase) {
            val near = crabs.filter { !it.arrived && dist(here, room.real(it.npc.coords), CRAB_SIZE) <= BASH_RADIUS }
            if (near.size >= params.dinhMin) return Plan(Move.Dinh, near.minBy { it.gap }.npc)
        }
        val slot = if (role == NyloRole.Ranger) 0 else 1
        for (wave in crabs.map { it.born }.distinct().sortedDescending()) {
            val plan = planOf(wave, crabs)
            val id = plan.dps.getOrNull(slot)?.takeIf { it !in plan.done } ?: continue
            val c = crabOf(crabs, wave, id)
            if (c == null || c.frozenUntil > now) continue
            if (c.gap > params.crabGap || c.gap - 1 < toHit(here, c)) continue
            plan.done += id
            return Plan(Move.Scythe, c.npc)
        }
        val pile = crabs.filter { !it.arrived && it.frozenUntil > now && (it.npc.hitpoints >= params.pileHp || thawing(it, now)) && dist(here, room.real(it.npc.coords), CRAB_SIZE) <= PILE_REACH }
        pile.maxByOrNull { c -> pile.count { inSquare(it, c) } }?.let { return Plan(Move.Scythe, it.npc) }
        if (room.phase >= (if (role == NyloRole.Ranger) RANGER_ZCB_PHASE else MELEE_ZCB_PHASE)) spec(bot, Move.Zcb)?.let { return it }
        return Plan(Move.Scythe, her)
    }

    /**
     * The sim's trio mage: it stops attacking when she's close to her next proc so its barrage is ready on the spawn,
     * freezes the wave plan's 1s and 2s, then the 3s once they've clumped and the 4s once they walk into the frozen 3s
     * (waiting a tick at a time for a clump), then barrages a frozen pile worth it, else bows her.
     */
    private fun mage(bot: Player, crabs: List<MaidenSolo.CrabView>, now: Int): Plan? {
        val her = room.boss
        for (wave in crabs.map { it.born }.distinct().sortedDescending()) {
            val plan = planOf(wave, crabs)
            for (id in plan.freeze) {
                if (id in plan.done) continue
                val c = crabOf(crabs, wave, id)
                if (c == null || c.frozenUntil > now) {
                    plan.done += id
                    continue
                }
                plan.done += id
                return barrage(c)
            }
            for (group in CLUMPS) {
                if (group.name in plan.done) continue
                val mine = crabs.filter { it.born == wave && !it.arrived && it.spot in group.spots && it.frozenUntil <= now }
                if (mine.isEmpty()) {
                    plan.done += group.name
                    continue
                }
                if (!clumped(mine, crabs, wave, group, now)) return hold(bot)
                plan.done += group.name
                return barrage(mine.maxBy { c -> mine.count { inSquare(it, c) } })
            }
        }
        val standing = crabs.filter { !it.arrived }
        val pile = standing.maxByOrNull { c -> standing.filter { inSquare(it, c) }.sumOf { it.npc.hitpoints } }
        val thawing = standing.any { thawing(it, now) }
        if (pile != null && (thawing || standing.filter { inSquare(it, pile) }.sumOf { it.npc.hitpoints } >= params.mageKillHp)) return barrage(pile)
        if (nearProc(her)) return hold(bot)
        if (her.hitpoints * 100 <= her.baseHitpointsLvl * params.mageScytheBelow) return Plan(Move.Scythe, her)
        if (room.phase >= MAGE_ZCB_PHASE) spec(bot, Move.Zcb)?.let { return it }
        return Plan(Move.Tbow, her)
    }

    private class Clump(val name: String, val spots: Set<String>, val lone: Int)

    /**
     * A clump is ready when one of it reaches her next tick, when all of it stands in one 3x3, or (a lone crab) on the
     * usual tick after the proc; the 4s go into the frozen 3s.
     */
    private fun clumped(mine: List<MaidenSolo.CrabView>, crabs: List<MaidenSolo.CrabView>, wave: Int, group: Clump, now: Int): Boolean {
        if (mine.any { it.gap <= 2 }) return true
        if (group.name == "4s") {
            val threes = crabs.filter { it.born == wave && it.spot in THREES && it.frozenUntil > now }
            if (threes.isNotEmpty()) return mine.any { c -> threes.any { inSquare(c, it) } }
        }
        if (mine.size == 1) return room.t >= wave + group.lone
        return mine.all { c -> mine.all { inSquare(it, c) } }
    }

    private fun barrage(c: MaidenSolo.CrabView): Plan = Plan(Move.Barrage, c.npc)

    private fun hold(bot: Player): Plan? {
        bot.clearInteractionRoute()
        issued.remove(bot)
        return null
    }

    /** Within the sim's trio stand margin (1.5% of her hitpoints) of her next proc. */
    private fun nearProc(her: Npc): Boolean {
        val next = PROCS.getOrNull(room.phase) ?: return false
        return her.hitpoints * PCT_SCALE <= her.baseHitpointsLvl * (next * PCT_SCALE / 100 + params.standPermille)
    }

    /** Ticks until a scythe swung from [here] lands on the crab: the run to it (two tiles a tick), then the swing. */
    private fun toHit(here: CoordGrid, c: MaidenSolo.CrabView): Int = (dist(here, room.real(c.npc.coords), CRAB_SIZE) - 1 + 1) / 2 + 1

    /** A frozen crab that walks on soon: whoever can reach it finishes it first. */
    private fun thawing(c: MaidenSolo.CrabView, now: Int): Boolean = c.frozenUntil > now && c.frozenUntil - now <= params.thawTicks

    private fun inSquare(c: MaidenSolo.CrabView, primary: MaidenSolo.CrabView): Boolean {
        val at = room.real(primary.npc.coords)
        return overlaps(room.real(c.npc.coords), CRAB_SIZE, at.x - 1, at.z - 1, at.x + CRAB_SIZE, at.z + CRAB_SIZE)
    }

    private fun specReady(bot: Player, move: Move): Boolean = bot.vars["varp.sa_energy"] >= move.cost

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
        if (plan.target !== room.boss) onCrab[bot] = plan.target to room.t
    }

    private fun current(bot: Player): Npc? = (bot.interaction as? InteractionNpc)?.target

    private fun overlaps(at: CoordGrid, size: Int, x0: Int, z0: Int, x1: Int, z1: Int): Boolean =
        at.x <= x1 && at.x + size - 1 >= x0 && at.z <= z1 && at.z + size - 1 >= z0

    private fun dist(a: CoordGrid, b: CoordGrid, size: Int): Int =
        max(max(b.x - a.x, a.x - (b.x + size - 1)).coerceAtLeast(0), max(b.z - a.z, a.z - (b.z + size - 1)).coerceAtLeast(0))

    private companion object {
        val OPENING = listOf(Move.Tbow, Move.Ralos)
        val MAGE_OPENING = listOf(Move.Tbow, Move.Ralos, Move.Blowpipe)
        const val SPELLBOOK_SWAP = "component.magic_spellbook:spellbook_swap"
        const val SWAP_TO_ARCEUUS = 4
        const val GREATER_GHOST = "component.magic_spellbook:resurrect_greater_ghost"
        const val VENGEANCE = "component.magic_spellbook:vengeance"
        const val LUNARS = 2
        const val SURGE_ENERGY = 250

        /** The sim's trio lists: the mage's ZCB after its 70s freezes, the ranger's at the 50s, the melee's at the 30s. */
        const val MAGE_ZCB_PHASE = 1
        const val RANGER_ZCB_PHASE = 2
        const val MELEE_ZCB_PHASE = 3
        val START_TILES = listOf(CoordGrid(3184, 4445, 0), CoordGrid(3184, 4447, 0), CoordGrid(3184, 4448, 0))
        const val CRAB_SIZE = 2
        const val BASH_RADIUS = 5
        const val PILE_REACH = 3
        val PROCS = listOf(70, 50, 30)
        const val PCT_SCALE = 1000
        val THREES = setOf("N3", "S3")
        val CLUMPS = listOf(Clump("3s", THREES, 11), Clump("4s", setOf("N4a", "N4b", "S4a", "S4b"), 16))
        const val CRAB_ORDER_TICKS = 6
    }
}

/** The Maiden crew's choices (see [MaidenCrew]); `parse` reads `name=value,...` and keeps the default for the rest. */
data class MaidenCrewParams(
    val crabGap: Int = 8,
    val standPermille: Int = 15,
    val thawTicks: Int = 6,
    val mageKillHp: Int = 45,
    val mageScytheBelow: Int = 30,
    val pileHp: Int = 40,
    val dinhPhase: Int = 2,
    val dinhMin: Int = 3,
) {
    companion object {
        fun parse(text: String): MaidenCrewParams {
            val values = text.split(',').mapNotNull { it.split('=').takeIf { kv -> kv.size == 2 }?.let { (k, v) -> k.trim() to v.trim() } }.toMap()
            val d = MaidenCrewParams()
            fun int(name: String, default: Int) = values[name]?.toDoubleOrNull()?.toInt() ?: default
            return MaidenCrewParams(
                crabGap = int("crabGap", d.crabGap),
                standPermille = int("standPermille", d.standPermille),
                thawTicks = int("thawTicks", d.thawTicks),
                mageKillHp = int("mageKillHp", d.mageKillHp),
                mageScytheBelow = int("mageScytheBelow", d.mageScytheBelow),
                pileHp = int("pileHp", d.pileHp),
                dinhPhase = int("dinhPhase", d.dinhPhase),
                dinhMin = int("dinhMin", d.dinhMin),
            )
        }
    }
}
