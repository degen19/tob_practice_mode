package org.rsmod.content.raids.tob.solo

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.util.Wearpos
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
 * tick a bot can act, which nylocas it goes for and with what, or which lane mouth of the next wave it covers. The
 * choice scores every option on [NyloPolicy]'s weights, learned in the stand-alone Nylocas sim (`tools/nylo-sim`).
 * Bots fill whatever roles the people in the room don't: the room reacts to everyone's kills, so the bots react to a
 * person's play as it happens.
 */
class NyloCrew(
    override val room: NyloSolo,
    private val members: List<Pair<Player, NyloRole>>,
    private val bots: RaidBots,
    private val clock: MapClock,
    private val barrage: Int,
) : BotCrew {
    override fun starts(): List<CoordGrid> = members.map { room.entry }

    override val walksIn: Boolean
        get() = true

    override fun doneAt(): Int = room.bossTick

    /** The ranger and melee walk in with an upgraded Death Charge up; the mage with her Lightbearer on. */
    override fun prepare(bot: Player) {
        val role = members.firstOrNull { it.first === bot }?.second ?: return
        if (role.freezes) {
            bots.equip(bot, Wearpos.Ring.slot, NyloLoadout.LIGHTBEARER.asRSCM(RSCMType.OBJ))
            return
        }
        VarPlayerIntMapSetter.set(bot, "varbit.death_charge_scroll_used", 1)
        VarPlayerIntMapSetter.set(bot, "varbit.death_charge_charges", DEATH_CHARGES)
        VarPlayerIntMapSetter.set(bot, DC_ACTIVE, 1)
    }

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
        Tbow(NyloLoadout.TBOW, 'R', 5, 10, Area.Single, 1),
        Chins(NyloLoadout.CHINS, 'R', 3, 9, Area.Square, 1),
        Crossbows(NyloLoadout.CROSSBOWS, 'R', 2, 7, Area.Single, 1),
        Ayak(NyloLoadout.AYAK, 'G', 3, 10, Area.Single, 0),
        Tome(NyloLoadout.TOME, 'G', 3, 10, Area.Single, 0),
        Barrage(NyloLoadout.BARRAGE, 'G', 5, 10, Area.Square, 0),
        Dinh(NyloLoadout.DINH, 'M', 5, 1, Area.Bash, 0),
        Claws(NyloLoadout.CLAWS, 'M', 4, 1, Area.Single, 1),
        TomeSpec(NyloLoadout.TOME, 'G', 3, 10, Area.Single, 0),
    }

    enum class Area { Single, Square, Line, Scythe, Bash }

    private class Plan(val weapon: Weapon, val target: NyloSolo.Target, val victims: List<NyloSolo.Target>, val score: Double)

    override fun tick() {
        val now = clock.cycle
        everyone = room.targets()
        if (TRACING && room.wavesSpawned >= TRACE_FROM && room.bossTick < 0 && room.t % 2 == 0) {
            println("nylo ${members[0].first.username} t${room.t} alive " + everyone.joinToString(" ") { "w${it.wave}${if (it.big) "B" else ""}${it.style}:${it.npc.hitpoints}/${room.lifeLeft(it)}${if (it.nulledBy.isNotEmpty()) "n" + it.nulledBy.joinToString("") { p -> p.username.take(2) } else ""}${if (it.doomed) "d" else ""}${if (it.onPillar) "p" + room.real(it.npc.coords).let { c -> "@${c.x},${c.z}" } + if (meleeReach(it)) "+" else "-" else ""}" })
        }
        for ((bot, role) in members) {
            if (!bot.isSlotAssigned || bot.hitpoints <= 0 || bot !in bots.all) continue
            if (bot.actionDelay > now + 1) {
                if (!role.freezes && bot.vars[DC_COOLDOWN] == 0) bots.spell(bot, DEATH_CHARGE)
                continue
            }
            guard(bot)
            val plan = choose(bot, role)
            if (TRACING && room.wavesSpawned >= TRACE_FROM && room.bossTick < 0) {
                println("nylo ${members[0].first.username} t${room.t} $role " + (plan?.let { "${it.weapon} w${it.target.wave}${if (it.target.big) "B" else ""}${it.target.style}:${it.target.npc.hitpoints} x${it.victims.size}" } ?: "prefire"))
            }
            if (plan != null) act(bot, role, plan) else if (bot !in covering) prefire(bot, role)
        }
    }

    /* ---- the choice */

    private val policy: NyloPolicy = NyloPolicy.LOADED

    /** Every nylocas someone can still hurt, lanes included: an area hit nulls any of the wrong colour it catches. */
    private var everyone: List<NyloSolo.Target> = emptyList()

    /** The lane each bot went to cover for the next wave (set by its choice, until it attacks). */
    private val posts = HashMap<Player, String>()

    /** The specs each bot gets once a room (the mage's Zorya's flow, the melee's Shield Bash) it has used. */
    private val specced = HashMap<Player, HashSet<Weapon>>()

    /** Bots whose choice this tick was a lane to cover: walking there, no prefire. */
    private val covering = HashSet<Player>()

    /**
     * Every weapon and target the bot could take, and each lane mouth of the next wave to cover, scored on
     * [NyloPolicy]'s weights; the best one over 0 goes, else a prefire.
     */
    private fun choose(bot: Player, role: NyloRole): Plan? {
        covering -= bot
        val here = room.real(bot.coords)
        val weights = policy.weights(role.style, room.wavesSpawned)
        var post: Pair<String, CoordGrid>? = null
        var postScore = 0.0
        if (room.wavesSpawned < room.wavesTotal) {
            val next = room.incoming(1)
            val untilWave = room.nextWaveCheck - room.t
            val p = DoubleArray(NyloPolicy.POST_FEATURES.size)
            for (lane in LANES) {
                val spawns = next.filter { it.lane == lane }
                if (spawns.isEmpty()) continue
                val tile = postTile(role, lane)
                p[0] = 1.0
                p[1] = untilWave / 4.0
                p[2] = spawns.count { it.style == role.style }.toDouble()
                p[3] = spawns.size.toDouble()
                p[4] = spawns.count { it.big }.toDouble()
                p[5] = chebyshev(here, tile) / 5.0
                p[6] = posts.count { (other, l) -> other != bot && l == lane }.toDouble()
                p[7] = if (posts[bot] == lane) 1.0 else 0.0
                p[8] = room.wavesSpawned / room.wavesTotal.toDouble()
                p[9] = room.roomAlive / room.cap.toDouble()
                var score = 0.0
                for (i in p.indices) score += weights[NyloPolicy.POST_AT + i] * p[i]
                if (score > postScore) {
                    postScore = score
                    post = lane to tile
                }
            }
        }
        val attack = attack(bot, role, here, weights)
        if (attack != null && attack.score > postScore) {
            posts -= bot
            return attack
        }
        val (lane, tile) = post ?: return null
        posts[bot] = lane
        covering += bot
        if (here != tile && bot.routeDestination.isEmpty()) bots.walk(bot, room.at(tile))
        return null
    }

    /** The tile a role covers a lane's mouth from: Blert's for the next wave if it's by that mouth, else the mouth. */
    private fun postTile(role: NyloRole, lane: String): CoordGrid {
        val mouth = MOUTHS.getValue(lane)
        val blert = PREFIRES.getOrNull(room.wavesSpawned)?.get(role.ordinal)
        return if (blert != null && chebyshev(blert, mouth) <= PREFIRE_SLACK) blert else mouth
    }

    private fun attack(bot: Player, role: NyloRole, here: CoordGrid, weights: DoubleArray): Plan? {
        val wanted = everyone.filter { bot !in it.nulledBy && !it.doomed }
        if (wanted.isEmpty()) return null
        val cleanup = room.wavesSpawned >= room.wavesTotal
        val untilCheck = room.nextWaveCheck - room.t
        val cap = room.cap
        val count = room.roomAlive
        val standing = everyone.count { room.lifeLeft(it) > untilCheck }
        val pressure = if (cleanup) 0.0 else (standing - cap + 1).coerceAtLeast(0) / 4.0
        val ends = everyone.map { room.lifeLeft(it) + if (it.big) LIFE else 0 }.sorted()
        val latest = ends.last()
        val medEnd = ends[ends.size / 2]
        val late = room.wavesSpawned >= LATE_FROM
        val f = DoubleArray(NyloPolicy.FEATURES.size)
        var best: Plan? = null
        for (weapon in KITS.getValue(role)) {
            if (weapon == Weapon.Dinh && bot.vars["varp.sa_energy"] < DINH_ENERGY) continue
            if (weapon == Weapon.Claws && bot.vars["varp.sa_energy"] < CLAW_ENERGY) continue
            if (weapon == Weapon.TomeSpec && bot.vars["varp.sa_energy"] < TOME_ENERGY) continue
            if (weapon in ONCE && weapon in specced[bot].orEmpty()) continue
            for (target in wanted) {
                if (target.style != weapon.hits) continue
                if (weapon.range <= 1 && !meleeReach(target)) continue
                val victims = victims(weapon, target, wanted, here)
                val nulls = nulled(bot, weapon, target, here)
                val life = room.lifeLeft(target)
                val gone = life + if (target.big) LIFE else 0
                f.fill(0.0)
                f[0] = 1.0
                f[1] = if (target.style == role.style) 1.0 else 0.0
                f[2] = victims.size.toDouble()
                f[3] = victims.count { it.big }.toDouble()
                f[4] = nulls.size.toDouble()
                f[5] = if (target.big) 1.0 else 0.0
                f[6] = if (target.aggro) 1.0 else 0.0
                f[7] = if (target.onPillar) 1.0 else 0.0
                f[8] = (life / LIFE.toDouble()).coerceIn(0.0, 1.0)
                f[9] = (dist(here, room.real(target.npc.coords), size(target)) - weapon.range).coerceAtLeast(0) / 2.0
                f[10] = if (wearing(bot, weapon.set)) 0.0 else 1.0
                f[11] = if (current(bot) == target.npc) 1.0 else 0.0
                f[12] = pressure
                f[13] = if (pressure > 0 && life > untilCheck) 1.0 else 0.0
                f[14] = if (cleanup) 1.0 else 0.0
                f[15] = if (cleanup) gone / LIFE.toDouble() else 0.0
                f[16] = weapon.speed / 5.0
                f[17] = victims.sumOf { policy.killP(weapon, it.big, it.npc.hitpoints - it.pending) }
                f[18] = if (overlaps(room.real(target.npc.coords), size(target), STAND_X.first, STAND_Z.first, STAND_X.last, STAND_Z.last)) 0.0 else 1.0
                f[19] = if (target.pending > 0) 1.0 else 0.0
                f[20] = if (target.big && untilCheck <= SPLIT_CHECK_TICKS && count + 1 >= cap && !cleanup) 1.0 else 0.0
                f[21] = target.wave / room.wavesTotal.toDouble()
                f[22] = if (room.t - target.born < YOUNG_TICKS) 1.0 else 0.0
                f[23] = if (target.spawnTile == null) 1.0 else 0.0
                f[24] = count / cap.toDouble()
                f[25] = if (cleanup && gone >= latest - LAST_SLACK) 1.0 else 0.0
                f[26] = if (target.big && target.wave >= LATE_FROM) 1.0 else 0.0
                f[27] = ends.count { it > gone } / ends.size.toDouble()
                f[28] = (gone - medEnd) / LIFE.toDouble()
                f[29] = f[1] * f[17]
                f[30] = if (late) 1.0 else 0.0
                f[31] = if (cleanup && target.big) 1.0 else 0.0
                f[32] = if (target.aggro && late) 1.0 else 0.0
                f[33] = target.nulledBy.count { it != bot } / 2.0
                f[34] = ((room.wavesSpawned - target.wave) / OLD_WAVES).coerceIn(0.0, 2.0)
                f[35] = if (room.wavesSpawned >= END_FROM) f[34] else 0.0
                f[36] = if (weapon == Weapon.Tome && bot.vars[FLOW_STACKS] > 0) 1.0 else 0.0
                var score = weights[NyloPolicy.FEATURES.size + weapon.ordinal]
                for (i in f.indices) score += weights[i] * f[i]
                if (score > (best?.score ?: 0.0)) best = Plan(weapon, target, victims, score)
            }
        }
        return best
    }

    private fun victims(weapon: Weapon, target: NyloSolo.Target, wanted: List<NyloSolo.Target>, from: CoordGrid): List<NyloSolo.Target> {
        val at = room.real(target.npc.coords)
        return when (weapon.area) {
            Area.Single -> listOf(target)
            Area.Scythe -> listOf(target) + arc(at, from).let { tiles -> wanted.filter { o -> o !== target && o.style == 'M' && tiles.any { overlaps(room.real(o.npc.coords), size(o), it.x, it.z, it.x, it.z) } } }
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
                    else arc(at, from)
                others.filter { o -> tiles.any { overlaps(room.real(o.npc.coords), size(o), it.x, it.z, it.x, it.z) } }
            }
        }
    }

    /** A scythe's two side tiles across the swing at [at], swung from [from]. */
    private fun arc(at: CoordGrid, from: CoordGrid): List<CoordGrid> {
        val dx = Integer.signum(at.x - from.x)
        val dz = Integer.signum(at.z - from.z)
        return listOf(CoordGrid(at.x + dz, at.z + dx, 0), CoordGrid(at.x - dz, at.z - dx, 0))
    }

    /**
     * Nothing to hit: get to the lane the next wave brings this bot the most to do at (its own colour above all), on the
     * tile Blert's fastest trios stand on for that wave when it's at that lane's mouth.
     */
    private fun prefire(bot: Player, role: NyloRole) {
        val lane = nextLane(role) ?: return
        val tile = postTile(role, lane)
        if (room.real(bot.coords) != tile && bot.routeDestination.isEmpty()) bots.walk(bot, room.at(tile))
    }

    /** The lane whose part of the next wave is worth most to [role] (null: no waves left). */
    private fun nextLane(role: NyloRole): String? {
        val next = room.incoming(1)
        if (next.isEmpty()) return null
        return next.groupBy { it.lane }.maxByOrNull { (_, spawns) -> spawns.sumOf { (if (it.style == role.style) OWN_LANE_WORTH else 1.0) * if (it.big) BIG_LANE_WORTH else 1.0 } }?.key
    }

    private fun chebyshev(a: CoordGrid, b: CoordGrid): Int = max(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.z - b.z))

    /** Players stand only on the room's middle (the lanes are theirs), so melee reaches what touches its edge. */
    private fun meleeReach(t: NyloSolo.Target): Boolean {
        val at = room.real(t.npc.coords)
        return overlaps(at, size(t), STAND_X.first - 1, STAND_Z.first - 1, STAND_X.last + 1, STAND_Z.last + 1)
    }

    /** Protect from the style of the nearest aggressive nylocas coming for this bot (the closest player is its target). */
    private fun guard(bot: Player) {
        val here = room.real(bot.coords)
        val near = everyone.filter { it.aggro && targetOf(it) === bot }.minByOrNull { dist(here, room.real(it.npc.coords), size(it)) } ?: return
        val prayer = PROTECT.getValue(near.style)
        if (bot.vars[prayer] != 1) bots.pray(bot, prayer)
    }

    private fun targetOf(t: NyloSolo.Target): Player? {
        val at = room.real(t.npc.coords)
        return members.map { it.first }.filter { it.isSlotAssigned }.minByOrNull { chebyshev(room.real(it.coords), at) }
    }

    /* ---- doing it */

    private fun act(bot: Player, role: NyloRole, plan: Plan) {
        for ((slot, obj) in plan.weapon.set.worn) {
            val id = obj.asRSCM(RSCMType.OBJ)
            if (bot.worn[slot.slot]?.id != id) bots.equip(bot, slot.slot, id)
        }
        if (role.freezes && plan.weapon.set.worn.none { it.first == Wearpos.Ring }) {
            val ring = NyloLoadout.LIGHTBEARER.asRSCM(RSCMType.OBJ)
            if (bot.worn[Wearpos.Ring.slot]?.id != ring) bots.equip(bot, Wearpos.Ring.slot, ring)
        }
        if (plan.weapon != Weapon.Barrage) VarPlayerIntMapSetter.set(bot, "varp.com_mode", plan.weapon.stance)
        if (plan.weapon == Weapon.Dinh || plan.weapon == Weapon.Claws || plan.weapon == Weapon.TomeSpec) VarPlayerIntMapSetter.set(bot, "varp.sa_attack", 1)
        if (plan.weapon in ONCE) specced.getOrPut(bot) { HashSet() } += plan.weapon
        val npc = plan.target.npc
        if (current(bot) == npc && plan.weapon != Weapon.Barrage) return
        if (plan.weapon == Weapon.Barrage) bots.cast(bot, npc, barrage) else bots.attack(bot, npc)
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
        const val SPLIT_CHECK_TICKS = 11
        const val YOUNG_TICKS = 14
        const val LAST_SLACK = 2
        const val LATE_FROM = 26
        const val END_FROM = 28
        const val OLD_WAVES = 5.0
        const val OWN_LANE_WORTH = 4.607
        const val BIG_LANE_WORTH = 1.706
        val LANES = listOf("W", "S", "E")

        /** What each role swings: its kit's weapons, the mage and ranger's scythe and claws too. */
        val KITS =
            mapOf(
                NyloRole.Melee to listOf(Weapon.Scythe, Weapon.Smash, Weapon.Thrust, Weapon.Dinh, Weapon.Crossbows, Weapon.Ayak, Weapon.Tbow),
                NyloRole.Ranger to listOf(Weapon.Tbow, Weapon.Chins, Weapon.Crossbows, Weapon.Ayak, Weapon.Scythe, Weapon.Claws),
                NyloRole.Mage to listOf(Weapon.Barrage, Weapon.Tome, Weapon.Crossbows, Weapon.Tbow, Weapon.Scythe, Weapon.Claws, Weapon.TomeSpec),
            )
        val PROTECT = mapOf('M' to "varbit.prayer_protectfrommelee", 'R' to "varbit.prayer_protectfrommissiles", 'G' to "varbit.prayer_protectfrommagic")
        const val LINE_TILES = 3
        const val LIFE = 52
        val TRACING = System.getenv("TOB_TRIO_TRACE") != null
        const val TRACE_FROM = 20
        const val PREFIRE_SLACK = 3
        val MOUTHS = mapOf("W" to CoordGrid(3290, 4248, 0), "E" to CoordGrid(3301, 4248, 0), "S" to CoordGrid(3295, 4243, 0))
        const val BASH_RADIUS = 5
        const val BASH_MAX = 10
        const val DINH_ENERGY = 500
        const val CLAW_ENERGY = 500
        const val TOME_ENERGY = 500
        val ONCE = setOf(Weapon.Dinh, Weapon.TomeSpec)
        const val DEATH_CHARGE = "component.magic_spellbook:death_charge"
        const val DEATH_CHARGES = 2
        const val DC_ACTIVE = "varbit.arceuus_death_charge_active"
        const val DC_COOLDOWN = "varbit.arceuus_death_charge_cooldown"
        const val FLOW_STACKS = "varp.zoryas_tome_stacks"
        val STAND_X = 3290..3301
        val STAND_Z = 4243..4254

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
