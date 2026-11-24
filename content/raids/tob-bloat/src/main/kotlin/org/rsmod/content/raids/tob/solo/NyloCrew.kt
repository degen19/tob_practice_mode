package org.rsmod.content.raids.tob.solo

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
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
 * Bot teammates for the Nylocas waves. They fight through the game's own combat (clicks on items, Attack and spells),
 * so their accuracy, damage, attack speed, area hits and switches are the real ones; this class only decides, each
 * tick a bot can act, which nylocas it goes for and with what - the way Blert's fastest trios play:
 *
 * - every bot hits every colour (switches), favouring its own;
 * - newer nylocas first; an older one that will go off on its own before the room could be done anyway is worth a hit
 *   only when nothing better is up, or when the room would stall at the next wave check with it still standing;
 * - area weapons (chinchompas, barrage, Obligator thrust, scythe on bigs) go where they reach the most;
 * - a big one isn't popped into its two splits just before a wave check the splits would stall;
 * - aggressive ones (they come for players) go first;
 * - they know the wave table: between waves each heads for the lane the next wave brings it the most at, and as a wave
 *   is about to arrive they don't chase targets far from that lane's mouth.
 *
 * [NyloCrewParams] holds the weights, so a search over runs can tune them. Bots fill whatever roles the people in the
 * room don't: the room reacts to everyone's kills, so the bots react to a person's play as it happens.
 */
class NyloCrew(
    override val room: NyloSolo,
    private val members: List<Pair<Player, NyloRole>>,
    private val bots: RaidBots,
    private val clock: MapClock,
    private val barrage: Int,
    private val params: NyloCrewParams = NyloCrewParams(),
) : BotCrew {
    override fun starts(): List<CoordGrid> = members.map { room.autoStart }

    override fun doneAt(): Int = room.bossTick

    override fun summary(): String =
        "Vasilias at room tick ${room.bossTick} (${raidTime(room.bossTick)}), waves ${room.wavesSpawned}, nulls ${room.nulls}, stalls ${room.stalls}, pillars ${room.pillarsStanding}"

    enum class Weapon(
        val set: GearSet,
        val hits: Char,
        val speed: Int,
        val range: Int,
        val area: Area,
        val stance: Int,
    ) {
        Scythe(NyloLoadout.SCYTHE, 'M', 5, 1, Area.Scythe, 1),
        Smash(NyloLoadout.OBLIGATOR, 'M', 3, 1, Area.Single, 2),
        Thrust(NyloLoadout.OBLIGATOR, 'M', 5, 1, Area.Line, 0),
        Blowpipe(NyloLoadout.BLOWPIPE, 'R', 2, 5, Area.Single, 1),
        Tbow(NyloLoadout.TBOW, 'R', 5, 10, Area.Single, 1),
        Chins(NyloLoadout.CHINS, 'R', 3, 9, Area.Square, 1),
        Crossbows(NyloLoadout.CROSSBOWS, 'R', 4, 7, Area.Single, 1),
        Ayak(NyloLoadout.AYAK, 'G', 3, 10, Area.Single, 0),
        Barrage(NyloLoadout.BARRAGE, 'G', 5, 10, Area.Square, 0),
        Dinh(NyloLoadout.DINH, 'M', 5, 1, Area.Bash, 0),
    }

    enum class Area { Single, Square, Line, Scythe, Bash }

    private class Plan(val weapon: Weapon, val target: NyloSolo.Target, val victims: List<NyloSolo.Target>, val score: Double)

    private val weapons: Map<NyloRole, List<Weapon>> =
        mapOf(
            NyloRole.Melee to listOf(Weapon.Scythe, Weapon.Smash, Weapon.Thrust, Weapon.Dinh, Weapon.Blowpipe, Weapon.Ayak, Weapon.Tbow),
            NyloRole.Ranger to listOf(Weapon.Tbow, Weapon.Chins, Weapon.Crossbows, Weapon.Ayak, Weapon.Scythe),
            NyloRole.Mage to listOf(Weapon.Barrage, Weapon.Crossbows, Weapon.Tbow, Weapon.Scythe),
        )

    /** A bot's last Attack click (its target and the tick), and targets it found it couldn't reach, until when. */
    private val lastClick = HashMap<Player, Pair<Npc, Int>>()
    private val unreachable = HashMap<Pair<Player, Npc>, Int>()

    /** Who each bot is busy with: a small nylocas one bot has an attack on its way to is no one else's. */
    private val claimed = HashMap<Npc, Pair<Player, Int>>()

    override fun tick() {
        val now = clock.cycle
        claimed.entries.removeIf { (npc, claim) -> !npc.isSlotAssigned || npc.hitpoints <= 0 || claim.second < room.t }
        unreachable.entries.removeIf { it.value < room.t }
        everyone = room.targets()
        val all = everyone.filter { onFloor(room.real(it.npc.coords)) }
        val wanted = all
        spare = spareOnes(all)
        for ((bot, role) in members) {
            if (!bot.isSlotAssigned || bot.hitpoints <= 0) continue
            if (bot.actionDelay > now + 1) continue
            noteStuck(bot, now)
            val plan = best(bot, role, wanted.filter { bot !in it.nulledBy && !it.doomed })
            if (plan == null) prefire(bot, role) else act(bot, plan)
        }
    }

    /* ---- what's worth hitting */

    /** This tick's nylocas not worth a hit unless nothing better is up (see [spareOnes]). */
    private var spare: Set<Npc> = emptySet()

    /** Every nylocas someone can still hurt, lanes included: an area hit nulls any of the wrong colour it catches. */
    private var everyone: List<NyloSolo.Target> = emptyList()

    /**
     * Players' logic: a nylocas that will go off on its own before the room could be done anyway (wave 31 out on
     * schedule, then cleanup) isn't worth the ticks - unless the room is heading for a stall at the next wave check
     * and it would still be standing then. The aggressive ones are always worth it.
     */
    private fun spareOnes(all: List<NyloSolo.Target>): Set<Npc> {
        val wavesLeft = room.wavesSpawned < room.wavesTotal
        val check = room.nextWaveCheck
        val standingAtCheck = all.filter { room.t + room.lifeLeft(it) > check }
        val stallAhead = wavesLeft && standingAtCheck.size >= room.cap
        val end = room.lastWaveEta + params.cleanupTicks
        return all.filter { t ->
            !t.aggro && room.t + room.lifeLeft(t) <= end && !(stallAhead && t in standingAtCheck)
        }.mapTo(HashSet()) { it.npc }
    }

    private fun best(bot: Player, role: NyloRole, wanted: List<NyloSolo.Target>): Plan? {
        val here = room.real(bot.coords)
        var best: Plan? = null
        for (weapon in weapons.getValue(role)) {
            for (target in wanted) {
                if (target.style != weapon.hits) continue
                if (weapon == Weapon.Dinh && !dinhReady(bot)) continue
                if (weapon.range <= 1 && !meleeReach(target)) continue
                if (unreachable.containsKey(bot to target.npc)) continue
                val holder = claimed[target.npc]?.first
                if (holder != null && holder != bot && !target.big && weapon.area == Area.Single) continue
                val victims = victims(weapon, target, wanted, here)
                if (weapon == Weapon.Dinh && victims.size < params.dinhMin) continue
                val score = score(bot, role, weapon, target, victims, nulled(bot, weapon, target, here), here)
                if (best == null || score > best.score) best = Plan(weapon, target, victims, score)
            }
        }
        return best
    }

    private fun victims(weapon: Weapon, target: NyloSolo.Target, wanted: List<NyloSolo.Target>, from: CoordGrid): List<NyloSolo.Target> {
        val at = room.real(target.npc.coords)
        return when (weapon.area) {
            Area.Single, Area.Scythe -> listOf(target)
            Area.Bash -> listOf(target) + wanted.filter { it !== target && it.style == 'M' && dist(from, room.real(it.npc.coords), size(it)) <= BASH_RADIUS }.take(BASH_MAX - 1)
            Area.Square -> wanted.filter { it.style == weapon.hits && overlaps(room.real(it.npc.coords), size(it), at.x - 1, at.z - 1, at.x + size(target), at.z + size(target)) }.take(AREA_MAX)
            Area.Line -> {
                val dx = Integer.signum(at.x - from.x)
                val dz = if (dx != 0) 0 else Integer.signum(at.z - from.z)
                val tiles = (0 until LINE_TILES).map { CoordGrid(at.x + dx * it, at.z + dz * it, 0) }
                wanted.filter { o -> o.style == 'M' && tiles.any { overlaps(room.real(o.npc.coords), size(o), it.x, it.z, it.x, it.z) } }.take(LINE_TILES)
            }
        }
    }

    /** The wrong-coloured nylocas an attack would also catch: each one this bot nulls is one it can't hurt again. */
    private fun nulled(bot: Player, weapon: Weapon, target: NyloSolo.Target, from: CoordGrid): List<NyloSolo.Target> {
        val at = room.real(target.npc.coords)
        val others = everyone.filter { it !== target && it.style != weapon.hits && bot !in it.nulledBy && !it.doomed }
        return when (weapon.area) {
            Area.Single -> emptyList()
            Area.Bash -> others.filter { dist(from, room.real(it.npc.coords), size(it)) <= BASH_RADIUS }
            Area.Square -> others.filter { overlaps(room.real(it.npc.coords), size(it), at.x - 1, at.z - 1, at.x + size(target), at.z + size(target)) }
            Area.Line, Area.Scythe -> {
                if (weapon.area == Area.Scythe && target.big) return emptyList()
                val dx = Integer.signum(at.x - from.x)
                val dz = Integer.signum(at.z - from.z)
                val tiles =
                    if (weapon.area == Area.Line) (1 until LINE_TILES).map { CoordGrid(at.x + dx * it, at.z + if (dx != 0) 0 else dz * it, 0) }
                    else listOf(CoordGrid(at.x + dz, at.z + dx, 0), CoordGrid(at.x - dz, at.z - dx, 0))
                others.filter { o -> tiles.any { overlaps(room.real(o.npc.coords), size(o), it.x, it.z, it.x, it.z) } }
            }
        }
    }

    private fun score(bot: Player, role: NyloRole, weapon: Weapon, target: NyloSolo.Target, victims: List<NyloSolo.Target>, nulls: List<NyloSolo.Target>, here: CoordGrid): Double {
        var value = victims.sumOf { worth(weapon, it) }
        if (target.style == role.style) value *= params.ownColour
        if (weapon.area == Area.Square) value *= params.areaWorth
        value -= params.nullCost * nulls.sumOf { worth(Weapon.Ayak, it) }
        if (value <= 0.0) return value
        val reach = dist(here, room.real(target.npc.coords), size(target))
        val walk = (reach - weapon.range).coerceAtLeast(0) * params.walkCost
        val swap = if (wearing(bot, weapon.set)) 0.0 else params.swapCost
        val stay = if (current(bot) == target.npc) 1.0 + params.stickiness else 1.0
        val ahead = 1.0 / (1.0 + params.lookahead * drift(role, target) / LOOKAHEAD_SCALE)
        return value / (weapon.speed + walk + swap) * stay * ahead
    }

    private fun worth(weapon: Weapon, v: NyloSolo.Target): Double {
        var worth = if (v.big) params.bigWorth else 1.0
        if (v.big && splitsWouldStall()) worth *= params.bigBeforeCheck
        if (v.aggro) worth *= params.aggroWorth
        if (v.onPillar) worth *= params.pillarWorth
        if (weapon.area == Area.Scythe && v.big) worth *= params.scytheBig
        worth *= 1.0 + params.youth * room.lifeLeft(v).coerceIn(0, LIFE) / LIFE
        if (v.npc in spare) worth *= params.spareWorth
        return worth
    }

    /** Shield Bash (half the bar, in the late waves Blert's trios save it for), only into a crowd of melee nylocas. */
    private fun dinhReady(bot: Player): Boolean =
        room.wavesSpawned >= params.dinhWave && bot.vars["varp.sa_energy"] >= DINH_ENERGY

    /** Popping a big one now puts two splits on the room just before a wave that the count would stall. */
    private fun splitsWouldStall(): Boolean {
        if (room.wavesSpawned >= room.wavesTotal) return false
        val untilCheck = room.nextWaveCheck - room.t
        return untilCheck <= params.bigCheckTicks && room.roomAlive + 1 >= room.cap
    }

    /**
     * Nothing to hit: get to the lane the next wave brings this bot the most to do at (its own colour above all, then
     * what its switches hit), on the tile Blert's fastest trios stand on for that wave when it's at that lane's mouth.
     */
    private fun prefire(bot: Player, role: NyloRole) {
        val lane = nextLane(role) ?: return
        val blert = PREFIRES.getOrNull(room.wavesSpawned)?.get(role.ordinal)
        val mouth = MOUTHS.getValue(lane)
        val tile = if (blert != null && chebyshev(blert, mouth) <= PREFIRE_SLACK) blert else mouth
        if (room.real(bot.coords) != tile && bot.routeDestination.isEmpty()) bots.walk(bot, room.at(tile))
    }

    /** The lane whose part of the next wave is worth most to [role] (null: no waves left). */
    private fun nextLane(role: NyloRole): String? {
        val next = room.incoming(1)
        if (next.isEmpty()) return null
        return next.groupBy { it.lane }.maxByOrNull { (_, spawns) -> spawns.sumOf { worth(role, it) } }?.key
    }

    private fun worth(role: NyloRole, n: NyloSolo.Incoming): Double {
        var w = if (n.style == role.style) params.ownColour else 1.0
        if (n.big) w *= params.bigWorth
        if (n.aggro) w *= params.aggroWorth
        return w
    }

    /** How far a target pulls the bot from the mouth its next wave arrives at, if that wave is about to arrive. */
    private fun drift(role: NyloRole, target: NyloSolo.Target): Double {
        val next = room.incoming(1).firstOrNull() ?: return 0.0
        if (next.at + LANE_TICKS - room.t > params.lookaheadTicks) return 0.0
        val mouth = MOUTHS.getValue(nextLane(role) ?: return 0.0)
        return (chebyshev(room.real(target.npc.coords), mouth) - PREFIRE_SLACK).coerceAtLeast(0).toDouble()
    }

    private fun chebyshev(a: CoordGrid, b: CoordGrid): Int = max(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.z - b.z))

    private fun onFloor(c: CoordGrid): Boolean = c.x in FLOOR_X && c.z in FLOOR_Z

    /** Players stand only on the room's middle (the lanes are theirs), so melee reaches what touches its edge. */
    private fun meleeReach(t: NyloSolo.Target): Boolean {
        val at = room.real(t.npc.coords)
        return overlaps(at, size(t), STAND_X.first - 1, STAND_Z.first - 1, STAND_X.last + 1, STAND_Z.last + 1)
    }

    /** A click that got nowhere (no fight under way, no swing since): the bot couldn't reach it, so it looks elsewhere. */
    private fun noteStuck(bot: Player, now: Int) {
        val (npc, at) = lastClick[bot] ?: return
        if (room.t - at !in STUCK_AFTER || current(bot) != null || bot.actionDelay > now - STUCK_AFTER.first) return
        unreachable[bot to npc] = room.t + UNREACHABLE_TICKS
        lastClick.remove(bot)
    }

    /* ---- doing it */

    private fun act(bot: Player, plan: Plan) {
        for ((slot, obj) in plan.weapon.set.worn) {
            val id = obj.asRSCM(RSCMType.OBJ)
            if (bot.worn[slot.slot]?.id != id) bots.equip(bot, slot.slot, id)
        }
        if (plan.weapon != Weapon.Barrage) VarPlayerIntMapSetter.set(bot, "varp.com_mode", plan.weapon.stance)
        if (plan.weapon == Weapon.Dinh) VarPlayerIntMapSetter.set(bot, "varp.sa_attack", 1)
        val npc = plan.target.npc
        if (!plan.target.big && plan.weapon.area == Area.Single) claimed[npc] = bot to room.t + plan.weapon.speed
        if (current(bot) == npc && plan.weapon != Weapon.Barrage) return
        if (plan.weapon == Weapon.Barrage) bots.cast(bot, npc, barrage) else bots.attack(bot, npc)
        lastClick[bot] = npc to room.t
    }

    private fun current(bot: Player): Npc? = (bot.interaction as? InteractionNpc)?.target

    private fun wearing(bot: Player, set: GearSet): Boolean = set.worn.all { (slot, obj) -> bot.worn[slot.slot]?.id == obj.asRSCM(RSCMType.OBJ) }

    private fun size(t: NyloSolo.Target): Int = if (t.big) 2 else 1

    private fun overlaps(at: CoordGrid, size: Int, x0: Int, z0: Int, x1: Int, z1: Int): Boolean =
        at.x <= x1 && at.x + size - 1 >= x0 && at.z <= z1 && at.z + size - 1 >= z0

    private fun dist(a: CoordGrid, b: CoordGrid, size: Int): Int =
        max(max(b.x - a.x, a.x - (b.x + size - 1)).coerceAtLeast(0), max(b.z - a.z, a.z - (b.z + size - 1)).coerceAtLeast(0)) +
            if (a.x in b.x until b.x + size && a.z in b.z until b.z + size) 1 else 0

    private companion object {
        const val AREA_MAX = 9
        const val LINE_TILES = 3
        const val LIFE = 52
        const val PREFIRE_SLACK = 3
        const val LANE_TICKS = 9
        const val LOOKAHEAD_SCALE = 10.0
        val MOUTHS = mapOf("W" to CoordGrid(3290, 4248, 0), "E" to CoordGrid(3301, 4248, 0), "S" to CoordGrid(3295, 4243, 0))
        const val BASH_RADIUS = 5
        const val BASH_MAX = 10
        const val DINH_ENERGY = 500
        val STAND_X = 3290..3301
        val STAND_Z = 4243..4254
        val STUCK_AFTER = 2..3
        const val UNREACHABLE_TICKS = 6
        val FLOOR_X = 3282..3309
        val FLOOR_Z = 4234..4254

        /** Per wave (1-31), per role (mage, ranger, melee): the tile Blert's 50 fastest regular trios stood on most. */
        val PREFIRES: List<List<CoordGrid>> =
            listOf(
                listOf(CoordGrid(3296, 4248, 0), CoordGrid(3291, 4249, 0), CoordGrid(3296, 4248, 0)),
                listOf(CoordGrid(3297, 4244, 0), CoordGrid(3297, 4249, 0), CoordGrid(3298, 4245, 0)),
                listOf(CoordGrid(3301, 4248, 0), CoordGrid(3297, 4245, 0), CoordGrid(3301, 4249, 0)),
                listOf(CoordGrid(3296, 4243, 0), CoordGrid(3294, 4245, 0), CoordGrid(3296, 4243, 0)),
                listOf(CoordGrid(3301, 4248, 0), CoordGrid(3290, 4248, 0), CoordGrid(3290, 4248, 0)),
                listOf(CoordGrid(3290, 4249, 0), CoordGrid(3295, 4243, 0), CoordGrid(3295, 4247, 0)),
                listOf(CoordGrid(3295, 4243, 0), CoordGrid(3295, 4243, 0), CoordGrid(3301, 4248, 0)),
                listOf(CoordGrid(3290, 4248, 0), CoordGrid(3290, 4248, 0), CoordGrid(3300, 4248, 0)),
                listOf(CoordGrid(3290, 4248, 0), CoordGrid(3291, 4248, 0), CoordGrid(3296, 4244, 0)),
                listOf(CoordGrid(3301, 4248, 0), CoordGrid(3291, 4248, 0), CoordGrid(3293, 4250, 0)),
                listOf(CoordGrid(3301, 4248, 0), CoordGrid(3290, 4249, 0), CoordGrid(3290, 4249, 0)),
                listOf(CoordGrid(3296, 4245, 0), CoordGrid(3296, 4249, 0), CoordGrid(3297, 4249, 0)),
                listOf(CoordGrid(3296, 4245, 0), CoordGrid(3290, 4249, 0), CoordGrid(3301, 4249, 0)),
                listOf(CoordGrid(3290, 4248, 0), CoordGrid(3294, 4245, 0), CoordGrid(3295, 4243, 0)),
                listOf(CoordGrid(3296, 4243, 0), CoordGrid(3296, 4243, 0), CoordGrid(3290, 4248, 0)),
                listOf(CoordGrid(3301, 4248, 0), CoordGrid(3297, 4248, 0), CoordGrid(3290, 4249, 0)),
                listOf(CoordGrid(3301, 4248, 0), CoordGrid(3293, 4248, 0), CoordGrid(3290, 4251, 0)),
                listOf(CoordGrid(3297, 4248, 0), CoordGrid(3290, 4248, 0), CoordGrid(3295, 4249, 0)),
                listOf(CoordGrid(3300, 4248, 0), CoordGrid(3295, 4248, 0), CoordGrid(3297, 4248, 0)),
                listOf(CoordGrid(3296, 4243, 0), CoordGrid(3295, 4248, 0), CoordGrid(3297, 4248, 0)),
                listOf(CoordGrid(3292, 4249, 0), CoordGrid(3301, 4248, 0), CoordGrid(3295, 4246, 0)),
                listOf(CoordGrid(3301, 4248, 0), CoordGrid(3297, 4248, 0), CoordGrid(3296, 4243, 0)),
                listOf(CoordGrid(3301, 4248, 0), CoordGrid(3295, 4243, 0), CoordGrid(3301, 4248, 0)),
                listOf(CoordGrid(3296, 4243, 0), CoordGrid(3290, 4248, 0), CoordGrid(3296, 4250, 0)),
                listOf(CoordGrid(3301, 4248, 0), CoordGrid(3295, 4243, 0), CoordGrid(3299, 4248, 0)),
                listOf(CoordGrid(3291, 4248, 0), CoordGrid(3296, 4247, 0), CoordGrid(3291, 4248, 0)),
                listOf(CoordGrid(3291, 4248, 0), CoordGrid(3297, 4248, 0), CoordGrid(3291, 4251, 0)),
                listOf(CoordGrid(3296, 4248, 0), CoordGrid(3295, 4247, 0), CoordGrid(3294, 4245, 0)),
                listOf(CoordGrid(3300, 4248, 0), CoordGrid(3295, 4247, 0), CoordGrid(3290, 4249, 0)),
                listOf(CoordGrid(3301, 4248, 0), CoordGrid(3293, 4248, 0), CoordGrid(3296, 4245, 0)),
                listOf(CoordGrid(3301, 4248, 0), CoordGrid(3291, 4248, 0), CoordGrid(3296, 4245, 0))
            )
    }
}

/** The crew's weights: what a search over runs tunes ([parse]: `name=value,...`, unnamed ones keep their defaults). */
data class NyloCrewParams(
    val ownColour: Double = 2.866,
    val bigWorth: Double = 1.153,
    val aggroWorth: Double = 1.0,
    val scytheBig: Double = 2.307,
    val bigBeforeCheck: Double = 0.086,
    val bigCheckTicks: Int = 11,
    val spareWorth: Double = 1.0,
    val cleanupTicks: Int = 29,
    val swapCost: Double = 0.313,
    val stickiness: Double = 1.611,
    val pillarWorth: Double = 1.157,
    val walkCost: Double = 1.499,
    val lookahead: Double = 3.607,
    val lookaheadTicks: Int = 7,
    val dinhWave: Int = 23,
    val dinhMin: Int = 2,
    val youth: Double = 3.502,
    val nullCost: Double = 0.869,
    val areaWorth: Double = 0.655,
) {
    companion object {
        fun parse(text: String): NyloCrewParams {
            val values = text.split(',').mapNotNull { it.split('=').takeIf { kv -> kv.size == 2 }?.let { (k, v) -> k.trim() to v.trim() } }.toMap()
            val d = NyloCrewParams()
            fun num(name: String, default: Double) = values[name]?.toDoubleOrNull() ?: default
            fun int(name: String, default: Int) = values[name]?.toDoubleOrNull()?.toInt() ?: default
            return NyloCrewParams(
                ownColour = num("ownColour", d.ownColour),
                bigWorth = num("bigWorth", d.bigWorth),
                aggroWorth = num("aggroWorth", d.aggroWorth),
                scytheBig = num("scytheBig", d.scytheBig),
                bigBeforeCheck = num("bigBeforeCheck", d.bigBeforeCheck),
                bigCheckTicks = int("bigCheckTicks", d.bigCheckTicks),
                spareWorth = num("spareWorth", d.spareWorth),
                cleanupTicks = int("cleanupTicks", d.cleanupTicks),
                swapCost = num("swapCost", d.swapCost),
                stickiness = num("stickiness", d.stickiness),
                youth = num("youth", d.youth),
                pillarWorth = num("pillarWorth", d.pillarWorth),
                walkCost = num("walkCost", d.walkCost),
                lookahead = num("lookahead", d.lookahead),
                lookaheadTicks = int("lookaheadTicks", d.lookaheadTicks),
                dinhWave = int("dinhWave", d.dinhWave),
                dinhMin = int("dinhMin", d.dinhMin),
                nullCost = num("nullCost", d.nullCost),
                areaWorth = num("areaWorth", d.areaWorth),
            )
        }
    }
}
