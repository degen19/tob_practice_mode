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
    override fun starts(): List<CoordGrid> = members.map { room.entry }

    override val walksIn: Boolean
        get() = true

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
        Tbow(NyloLoadout.TBOW, 'R', 5, 10, Area.Single, 1),
        Chins(NyloLoadout.CHINS, 'R', 3, 9, Area.Square, 1),
        Crossbows(NyloLoadout.CROSSBOWS, 'R', 2, 7, Area.Single, 1),
        Ayak(NyloLoadout.AYAK, 'G', 3, 10, Area.Single, 0),
        Tome(NyloLoadout.TOME, 'G', 3, 10, Area.Single, 0),
        Barrage(NyloLoadout.BARRAGE, 'G', 5, 10, Area.Square, 0),
        Dinh(NyloLoadout.DINH, 'M', 5, 1, Area.Bash, 0),
        Claws(NyloLoadout.CLAWS, 'M', 4, 1, Area.Single, 1),
    }

    enum class Area { Single, Square, Line, Scythe, Bash }

    class Play(val role: Char, val firstAge: Int, val deathAge: Int, val special: Weapon?, val specialRole: Char, val specialAge: Int)

    private class Plan(val weapon: Weapon, val target: NyloSolo.Target, val victims: List<NyloSolo.Target>, val score: Double)

    /** A Blert attack: on which nylocas, at what age, with which of our weapons (null: one our kits don't carry). */
    class Step(val role: Char, val key: String, val age: Int, val weapon: Weapon?)

    /** One Blert room: each role's attacks, and where it stood (by wave * [SPOT_STRIDE] + ticks since that wave). */
    class Copy(val steps: Map<Char, List<Step>>, val spots: Map<Char, Map<Int, CoordGrid>>, val deaths: Map<String, Pair<Int, Boolean>>) {
        /** A role's attacks, and every split attack of the other roles: a split's colour is the room's roll, not Blert's. */
        fun splitSteps(role: NyloRole): List<Step> = mine.getOrPut(role) { steps[role.style].orEmpty() + steps.filterKeys { it != role.style }.values.flatten().filter { it.key.count { c -> c == ':' } == 4 } }

        private val mine = HashMap<NyloRole, List<Step>>()
    }

    private val weapons: Map<NyloRole, List<Weapon>> =
        mapOf(
            NyloRole.Melee to listOf(Weapon.Scythe, Weapon.Smash, Weapon.Thrust, Weapon.Dinh, Weapon.Crossbows, Weapon.Ayak, Weapon.Tbow),
            NyloRole.Ranger to listOf(Weapon.Tbow, Weapon.Chins, Weapon.Crossbows, Weapon.Ayak),
            NyloRole.Mage to listOf(Weapon.Barrage, Weapon.Tome, Weapon.Crossbows, Weapon.Tbow),
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
        val all = everyone.filter { params.lanes || room.wavesSpawned >= params.lanesFrom || onFloor(room.real(it.npc.coords)) }
        val wanted = all
        spare = spareOnes(all)
        if (TRACING && room.wavesSpawned >= TRACE_FROM && room.bossTick < 0 && room.t % 2 == 0) {
            println("nylo ${members[0].first.username} t${room.t} alive " + everyone.joinToString(" ") { "w${it.wave}${if (it.big) "B" else ""}${it.style}:${it.npc.hitpoints}/${room.lifeLeft(it)}${if (it.npc in spare) "s" else ""}${if (onFloor(room.real(it.npc.coords))) "" else "L"}${if (it.nulledBy.isNotEmpty()) "n" + it.nulledBy.joinToString("") { p -> p.username.take(2) } else ""}${if (it.doomed) "d" else ""}${if (it.onPillar) "p" + room.real(it.npc.coords).let { c -> "@${c.x},${c.z}" } + if (meleeReach(it)) "+" else "-" else ""}" })
        }
        for ((bot, role) in members) {
            if (!bot.isSlotAssigned || bot.hitpoints <= 0) continue
            if (bot.actionDelay > now + 1) continue
            guard(bot)
            noteStuck(bot, now)
            val plan = if (policy != null) learned(bot, role, policy) else if (copy != null) copied(bot, role, wanted) else setPlay(bot, role) ?: scripted(bot, role) ?: best(bot, role, wanted.filter { bot !in it.nulledBy && !it.doomed })
            if (TRACING && room.wavesSpawned >= TRACE_FROM && room.bossTick < 0) {
                println("nylo ${members[0].first.username} t${room.t} $role " + (plan?.let { "${it.weapon} w${it.target.wave}${if (it.target.big) "B" else ""}${it.target.style}:${it.target.npc.hitpoints} x${it.victims.size}" } ?: "prefire"))
            }
            if (plan != null) act(bot, plan) else if (copy != null) stand(bot, role) else if (bot !in covering) prefire(bot, role)
        }
    }

    /* ---- the learned choice */

    private val policy: NyloPolicy? = if (params.learned) NyloPolicy.LOADED else null

    /** The lane each bot went to cover for the next wave (set by its learned choice, until it attacks). */
    private val posts = HashMap<Player, String>()

    /** Bots whose learned choice this tick was a lane to cover: walking there, no prefire. */
    private val covering = HashSet<Player>()

    /**
     * Every weapon and target the bot could take, and each lane mouth of the next wave to cover, scored on
     * [NyloPolicy]'s weights; the best one over 0 goes, else a prefire.
     */
    private fun learned(bot: Player, role: NyloRole, policy: NyloPolicy): Plan? {
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
        val attack = learnedAttack(bot, role, policy, here, weights)
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

    private fun learnedAttack(bot: Player, role: NyloRole, policy: NyloPolicy, here: CoordGrid, weights: DoubleArray): Plan? {
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
        for (weapon in LEARNED_KITS.getValue(role)) {
            if (weapon == Weapon.Dinh && bot.vars["varp.sa_energy"] < DINH_ENERGY) continue
            if (weapon == Weapon.Claws && bot.vars["varp.sa_energy"] < CLAW_ENERGY) continue
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
                var score = weights[NyloPolicy.FEATURES.size + weapon.ordinal]
                for (i in f.indices) score += weights[i] * f[i]
                if (score > (best?.score ?: 0.0)) best = Plan(weapon, target, victims, score)
            }
        }
        return best
    }

    /* ---- a Blert room, played again */

    /** The Blert room the crew plays ([NyloCrewParams.copy]: its place among `tob/nylo_clone.tsv`'s, -1 none). */
    private val copy: Copy? = COPIES.getOrNull(params.copy)

    /**
     * The Blert player's next attack, on the same nylocas (by spawn, a split by its parent) at the same age with our
     * kit's weapon for it, once it's due; until then a fill from the weights, but only one that's done before the next
     * attack is due, so the bot is on the Blert tick. A split that came out another colour than Blert's is the attack of
     * whoever's colour it is. Fills go first on what Blert had dead by now, and never on what Blert let go off.
     */
    private fun copied(bot: Player, role: NyloRole, wanted: List<NyloSolo.Target>): Plan? {
        val copy = copy ?: return null
        val byKey = everyone.associateBy { it.key }
        val at = room.t + params.copyLead
        var due: Pair<Weapon, NyloSolo.Target>? = null
        var dueAge = Int.MAX_VALUE
        var soonest = Int.MAX_VALUE
        for (step in copy.splitSteps(role)) {
            val target = byKey[step.key] ?: continue
            val age = at - target.born
            if (age < step.age) {
                soonest = minOf(soonest, step.age - age)
                continue
            }
            if (age > step.age + params.copyLate || target.doomed || bot in target.nulledBy) continue
            val matches = step.weapon?.hits == target.style
            val weapon = when {
                step.role == role.style -> if (matches) kitWeapon(role, step.weapon) else null
                else -> if (!matches) MAIN[role]?.takeIf { it.hits == target.style } else null
            } ?: continue
            if (weapon.range <= 1 && !meleeReach(target)) continue
            if (step.age < dueAge) {
                due = weapon to target
                dueAge = step.age
            }
        }
        due?.let { (weapon, target) -> return Plan(weapon, target, victims(weapon, target, everyone, room.real(bot.coords)), Double.MAX_VALUE) }
        val open = wanted.filter { bot !in it.nulledBy && !it.doomed && copy.deaths[it.key]?.second != false }
        val late = open.filter { t -> copy.deaths[t.key]?.let { at - t.born >= it.first } == true }
        val fill = (if (late.isNotEmpty()) best(bot, role, late) else null) ?: best(bot, role, open) ?: return null
        return fill.takeIf { soonest >= it.weapon.speed - params.copySlack }
    }

    /** What each role kills its own colour with when Blert's player had another colour in front of them. */
    private val MAIN = mapOf(NyloRole.Mage to Weapon.Tome, NyloRole.Ranger to Weapon.Crossbows, NyloRole.Melee to Weapon.Smash)

    /** Our kit's weapon for a Blert one: the mage's Ayak is the Tome. */
    private fun kitWeapon(role: NyloRole, weapon: Weapon?): Weapon? =
        when {
            weapon == null -> null
            weapon == Weapon.Ayak && role == NyloRole.Mage -> Weapon.Tome
            else -> weapon
        }

    /** Nothing due and no fill: stand where the Blert player stood this long after the latest wave. */
    private fun stand(bot: Player, role: NyloRole) {
        val spots = copy?.spots?.get(role.style) ?: return
        val dt = room.t + 2 - room.waveSpawnedAt
        val tile = spots[room.wavesSpawned * SPOT_STRIDE + dt] ?: return
        val real = CoordGrid(OX + tile.x, OY + tile.z, 0)
        if (room.real(bot.coords) != real && bot.routeDestination.isEmpty()) bots.walk(bot, room.at(real))
    }

    /* ---- what's worth hitting */

    /** This tick's nylocas not worth a hit unless nothing better is up (see [spareOnes]). */
    private var spare: Set<Npc> = emptySet()

    /** Every nylocas someone can still hurt, lanes included: an area hit nulls any of the wrong colour it catches. */
    private var everyone: List<NyloSolo.Target> = emptyList()

    /**
     * Players' logic: a nylocas that will go off on its own before the room could be done anyway isn't worth the ticks
     * - unless the room is heading for a stall at the next wave check and it would still be standing then. A big one
     * going off leaves two splits with a full life, so it counts as standing that much longer. The aggressive ones are
     * always worth it. Once every wave is out, the room ends when the last one goes: the crew kills the ones that would
     * last longest, as many as finishing them sooner than the rest go off on their own pays for.
     */
    private fun spareOnes(all: List<NyloSolo.Target>): Set<Npc> = spareFrom(all).filterTo(HashSet()) { npc -> !params.mustKill || all.none { it.npc === npc && mustKill(it) } }

    /**
     * Blert's 50 fastest trios: a big from the cap rise (wave 20) on is killed, never left to go off (1 of 950, at a
     * median 15 ticks old), and so is every nylocas from wave 28 on (none of 1,770 went off).
     */
    private fun mustKill(t: NyloSolo.Target): Boolean = (t.big && t.wave >= POST_CAP) || t.wave >= LATE_WAVE

    /** The room is heading for a stall at the next wave check: the script gives way to clearing the count. */
    private var stalling = false

    private fun spareFrom(all: List<NyloSolo.Target>): Set<Npc> {
        stalling = false
        if (room.wavesSpawned >= room.wavesTotal) return cleanupSpare(all)
        val check = room.nextWaveCheck
        val standingAtCheck = all.filter { room.t + room.lifeLeft(it) > check }
        val stallAhead = standingAtCheck.size >= room.cap
        stalling = stallAhead
        val end = room.lastWaveEta + params.cleanupTicks
        return all.filter { t -> !t.aggro && gone(t) <= end && !(stallAhead && t in standingAtCheck) }.mapTo(HashSet()) { it.npc }
    }

    /** The tick a nylocas and anything it leaves are gone if no one touches it. */
    private fun gone(t: NyloSolo.Target): Int = room.t + room.lifeLeft(t) + if (t.big) LIFE else 0

    private fun cleanupSpare(all: List<NyloSolo.Target>): Set<Npc> = if (params.goalGrid) goalSpare(all) else bestEndSpare(all)

    /**
     * Teams aim at the Vasilias times on the room's 4-tick grid (2:52.8, 2:55.2, ...): he drops 16 to 19 ticks after the
     * last nylocas goes, so a goal leaves a deadline for that. Anything that goes off on its own by the deadline of the
     * earliest goal the crew can still make (killing everything that wouldn't, at [NyloCrewParams.cleanupRate] ticks a
     * kill) is left alone; the rest is killed.
     */
    private fun goalSpare(all: List<NyloSolo.Target>): Set<Npc> {
        if (all.isEmpty()) return emptySet()
        var goal = Math.floorDiv(room.t + BOSS_LEAD + GRID - 1, GRID) * GRID
        repeat(GOALS_TRIED) {
            val deadline = goal - BOSS_LEAD
            val must = all.filter { gone(it) > deadline || it.aggro || it.wave >= params.killFrom }
            val work = must.sumOf { if (it.big) BIG_WORK else 1.0 }
            if (room.t + work * params.cleanupRate <= deadline) return all.filter { it !in must }.mapTo(HashSet()) { it.npc }
            goal += GRID
        }
        return emptySet()
    }

    private fun bestEndSpare(all: List<NyloSolo.Target>): Set<Npc> {
        val byEnd = all.sortedByDescending { gone(it) }
        var work = 0.0
        var bestK = 0
        var bestEnd = byEnd.firstOrNull()?.let { gone(it) } ?: return emptySet()
        for (k in 1..byEnd.size) {
            val t = byEnd[k - 1]
            work += if (t.big) BIG_WORK else 1.0
            val end = maxOf(room.t + work * params.cleanupRate, byEnd.getOrNull(k)?.let { gone(it).toDouble() } ?: 0.0)
            if (end < bestEnd) {
                bestEnd = end.toInt()
                bestK = k
            }
        }
        return byEnd.drop(bestK).filter { !it.aggro }.mapTo(HashSet()) { it.npc }
    }

    private fun best(bot: Player, role: NyloRole, wanted: List<NyloSolo.Target>): Plan? {
        val here = room.real(bot.coords)
        var best: Plan? = null
        for (weapon in weapons.getValue(role)) {
            if (weapon.area == Area.Square && weapon != Weapon.Barrage && room.wavesSpawned < params.chinsFrom) continue
            if (weapon == Weapon.Barrage && room.wavesSpawned >= params.barrageTo) continue
            for (target in wanted) {
                if (target.style != weapon.hits) continue
                if (weapon == Weapon.Dinh && !dinhReady(bot)) continue
                if (weapon.range <= 1 && !meleeReach(target)) continue
                if (unreachable.containsKey(bot to target.npc)) continue
                val holder = claimed[target.npc]?.first
                if (holder != null && holder != bot && !(target.big && overdue(target))) continue
                val victims = victims(weapon, target, wanted, here)
                if (weapon == Weapon.Dinh && victims.size < params.dinhMin) continue
                if (weapon == Weapon.Scythe && !target.big && victims.size < 2) continue
                val nulls = nulled(bot, weapon, target, here)
                if ((params.keepBigs || weapon == Weapon.Dinh) && nulls.any { it.big }) continue
                if (weapon.area == Area.Square && room.wavesSpawned >= params.cleanAreaFrom && nulls.isNotEmpty()) continue
                val score = score(bot, role, weapon, target, victims, nulls, here)
                if (best == null || score > best.score) best = Plan(weapon, target, victims, score)
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

    private fun score(bot: Player, role: NyloRole, weapon: Weapon, target: NyloSolo.Target, victims: List<NyloSolo.Target>, nulls: List<NyloSolo.Target>, here: CoordGrid): Double {
        var value = victims.sumOf { worth(role, weapon, it) }
        if (target.style == role.style) value *= params.ownColour
        else if (role == NyloRole.Mage) value *= params.mageOff
        if (weapon.area == Area.Square) value *= params.areaWorth
        value -= params.nullCost * nulls.sumOf { worth(role, Weapon.Ayak, it) }
        if (value <= 0.0) return value
        val reach = dist(here, room.real(target.npc.coords), size(target))
        val walk = (reach - weapon.range).coerceAtLeast(0) * params.walkCost
        val swap = if (wearing(bot, weapon.set)) 0.0 else params.swapCost
        val stay = if (current(bot) == target.npc) 1.0 + params.stickiness else 1.0
        val ahead = 1.0 / (1.0 + params.lookahead * drift(role, target) / LOOKAHEAD_SCALE)
        return value / (weapon.speed + walk + swap) * stay * ahead
    }

    private fun worth(role: NyloRole, weapon: Weapon, v: NyloSolo.Target): Double {
        var worth = if (v.big) params.bigWorth else 1.0
        playOf(v)?.let { play ->
            if (play.role != role.style) worth *= params.bookOther
            else if (room.t - v.born >= play.firstAge - params.bookLead) worth *= params.bookWorth
            if (room.t - v.born >= play.deathAge - params.overdueLead) worth *= params.overdue
        }
        if (v.big && splitsWouldStall()) worth *= params.bigBeforeCheck
        else if (v.big && room.wavesSpawned >= POST_CAP) worth *= params.postCapBig
        if (v.aggro) worth *= params.aggroWorth
        if (v.onPillar) worth *= params.pillarWorth
        if (weapon.area == Area.Scythe && v.big) worth *= params.scytheBig
        val left = room.lifeLeft(v) + if (v.big && params.bigYouth) LIFE else 0
        worth *= 1.0 + params.youth * left.coerceIn(0, LIFE) / LIFE
        if (v.npc in spare) worth *= if (room.wavesSpawned >= room.wavesTotal) params.cleanupSpareWorth else params.spareWorth
        return worth
    }

    /**
     * The playbook as a script ([NyloCrewParams.script]): a wave nylocas Blert's fastest trios give this role is this
     * bot's to hit from the age they first hit it (lanes included - those are the prefires), the one due soonest
     * first, with whichever of its weapons scores best on it; everything else (splits, the rest) goes on the scoring.
     */
    private fun scripted(bot: Player, role: NyloRole): Plan? {
        if (!params.script || (stalling && params.scriptYield)) return null
        val here = room.real(bot.coords)
        val mine =
            everyone.filter { t ->
                val play = playOf(t) ?: return@filter false
                play.role == role.style && bot !in t.nulledBy && !t.doomed && room.t - t.born >= startAge(play) && t.wave >= params.scriptFrom &&
                    !(t.big && params.scriptGuard && splitsWouldStall()) && (t.big || !params.scriptBigs) &&
                    !unreachable.containsKey(bot to t.npc)
            }.sortedBy { it.born + (playOf(it)?.let(::startAge) ?: 0) }
        for (target in mine) {
            var best: Plan? = null
            for (weapon in weapons.getValue(role)) {
                if (target.style != weapon.hits || weapon.area == Area.Bash) continue
                if (weapon.range <= 1 && !meleeReach(target)) continue
                if (weapon == Weapon.Barrage && room.wavesSpawned >= params.barrageTo) continue
                val victims = victims(weapon, target, everyone, here)
                if (weapon == Weapon.Scythe && !target.big && victims.size < 2) continue
                val nulls = nulled(bot, weapon, target, here)
                val score = score(bot, role, weapon, target, victims, nulls, here)
                if (best == null || score > best.score) best = Plan(weapon, target, victims, score)
            }
            if (best != null) return best
        }
        return null
    }

    /**
     * Blert's set plays, each the job of one role: the mage's barrages on waves 1, 11 and 21, the ranger's scythe on
     * wave 12's west pair and wave 22's west big, a claw spec on wave 12's south big (mage) and wave 20's west big
     * (ranger, scythe without the energy), the melee's Shield Bash on waves 26 and 29's south big.
     */
    private fun setPlay(bot: Player, role: NyloRole): Plan? {
        if (!params.setPlays) return null
        val here = room.real(bot.coords)
        for (target in everyone) {
            if (bot in target.nulledBy || target.doomed) continue
            val play = playOf(target) ?: continue
            if (play.specialRole != role.style || room.t - target.born < play.specialAge) continue
            val holder = claimed[target.npc]?.first
            if (holder != null && holder != bot) continue
            var weapon = play.special ?: continue
            if (!playing(weapon)) continue
            if (weapon.range <= 1 && !meleeReach(target)) continue
            val energy = bot.vars["varp.sa_energy"]
            if (weapon == Weapon.Dinh && energy < DINH_ENERGY) continue
            if (weapon == Weapon.Claws && energy < CLAW_ENERGY) weapon = Weapon.Scythe
            return Plan(weapon, target, victims(weapon, target, everyone, here), Double.MAX_VALUE)
        }
        return null
    }

    /** Which kinds of set play the crew makes (a search can drop any). */
    private fun playing(weapon: Weapon): Boolean =
        when (weapon) {
            Weapon.Barrage -> params.playBarrage
            Weapon.Dinh, Weapon.Claws -> params.playSpec
            else -> params.playSwitch
        }

    /** A big still up past the age Blert's trios have it dead by: anyone may help finish it. */
    private fun overdue(t: NyloSolo.Target): Boolean {
        val play = playOf(t) ?: return room.t - t.born >= OVERDUE_AGE
        return room.t - t.born >= play.deathAge
    }

    /** When the script starts on it: Blert's first hit (less a lead), or with [NyloCrewParams.bookDeath] that many ticks before Blert's death age. */
    private fun startAge(play: Play): Int = if (params.bookDeath > 0) play.deathAge - params.bookDeath else play.firstAge - params.bookLead

    /** Who Blert's fastest trios have hit this wave nylocas first, and from what age (null: a split, or no clear call). */
    private fun playOf(v: NyloSolo.Target): Play? {
        val at = v.spawnTile ?: return null
        return PLAYBOOK["${v.wave}:${at.x}:${at.z}:${if (v.big) 1 else 0}"]
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
        if (plan.weapon == Weapon.Dinh || plan.weapon == Weapon.Claws) VarPlayerIntMapSetter.set(bot, "varp.sa_attack", 1)
        val npc = plan.target.npc
        claimed[npc] = bot to room.t + plan.weapon.speed
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
        const val SPLIT_CHECK_TICKS = 11
        const val YOUNG_TICKS = 14
        const val LAST_SLACK = 2
        const val LATE_FROM = 26
        const val END_FROM = 28
        const val OLD_WAVES = 5.0
        val LANES = listOf("W", "S", "E")

        /** What each role swings for the learned choice: its kit's weapons, the mage and ranger's scythe and claws too. */
        val LEARNED_KITS =
            mapOf(
                NyloRole.Melee to listOf(Weapon.Scythe, Weapon.Smash, Weapon.Thrust, Weapon.Dinh, Weapon.Crossbows, Weapon.Ayak, Weapon.Tbow),
                NyloRole.Ranger to listOf(Weapon.Tbow, Weapon.Chins, Weapon.Crossbows, Weapon.Ayak, Weapon.Scythe, Weapon.Claws),
                NyloRole.Mage to listOf(Weapon.Barrage, Weapon.Tome, Weapon.Crossbows, Weapon.Tbow, Weapon.Scythe, Weapon.Claws),
            )
        val PROTECT = mapOf('M' to "varbit.prayer_protectfrommelee", 'R' to "varbit.prayer_protectfrommissiles", 'G' to "varbit.prayer_protectfrommagic")
        const val LINE_TILES = 3
        const val LIFE = 52
        val TRACING = System.getenv("TOB_TRIO_TRACE") != null

        /** Blert's 50 fastest regular trios, by wave spawn (`tob/nylo_playbook.tsv`): its role, first-hit age, death age. */
        val PLAYBOOK: Map<String, Play> by lazy {
            val text = NyloCrew::class.java.getResourceAsStream(PLAYBOOK_FILE)?.bufferedReader()?.readText() ?: return@lazy emptyMap()
            text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
                .associate { f ->
                    val special = when (f.getOrNull(7)) {
                        "barrage" -> Weapon.Barrage
                        "dinh" -> Weapon.Dinh
                        "claw" -> Weapon.Claws
                        "scythe" -> Weapon.Scythe
                        else -> null
                    }
                    "${f[0]}:${f[1]}:${f[2]}:${f[3]}" to Play(f[4][0], f[5].toInt(), f[6].toInt(), special, f.getOrNull(8)?.firstOrNull() ?: f[4][0], f.getOrNull(9)?.toInt() ?: 0)
                }
        }
        const val PLAYBOOK_FILE = "/tob/nylo_playbook.tsv"
        const val COPY_FILE = "/tob/nylo_clone.tsv"
        const val SPOT_STRIDE = 1000
        const val OX = 3264
        const val OY = 4224

        /** Blert's fastest trio rooms (`tob/nylo_clone.tsv`), fastest first. */
        val COPIES: List<Copy> by lazy {
            val text = NyloCrew::class.java.getResourceAsStream(COPY_FILE)?.bufferedReader()?.readText() ?: return@lazy emptyList()
            val rows = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }.toList()
            rows.groupBy { it[0] }.values.map { raid ->
                val steps = HashMap<Char, MutableList<Step>>()
                val spots = HashMap<Char, HashMap<Int, CoordGrid>>()
                val deaths = HashMap<String, Pair<Int, Boolean>>()
                for (f in raid) {
                    val role = f[2][0]
                    if (f[1] == "D") deaths[f[3]] = f[4].toInt() to (f[5] == "1")
                    else if (f[1] == "A") steps.getOrPut(role) { ArrayList() } += Step(role, f[3], f[4].toInt(), Weapon.entries.firstOrNull { it.name == f[5] })
                    else spots.getOrPut(role) { HashMap() }.putIfAbsent(f[3].toInt() * SPOT_STRIDE + f[4].toInt(), CoordGrid(f[5].toInt(), f[6].toInt(), 0))
                }
                Copy(steps, spots, deaths)
            }
        }
        const val TRACE_FROM = 20

        /** The wave the cap goes up on: from then, Blert's guide pops bigs fast so their splits come out early. */
        const val POST_CAP = 20

        /** Vasilias drops this many ticks after the last nylocas goes, at the least (the next wave tick, then 4 cycles). */
        const val BOSS_LEAD = 16
        const val GRID = 4
        const val GOALS_TRIED = 12
        const val LATE_WAVE = 28
        const val BIG_WORK = 3.0
        const val PREFIRE_SLACK = 3
        const val LANE_TICKS = 9
        const val LOOKAHEAD_SCALE = 10.0
        val MOUTHS = mapOf("W" to CoordGrid(3290, 4248, 0), "E" to CoordGrid(3301, 4248, 0), "S" to CoordGrid(3295, 4243, 0))
        const val BASH_RADIUS = 5
        const val BASH_MAX = 10
        const val DINH_ENERGY = 500
        const val CLAW_ENERGY = 500
        const val OVERDUE_AGE = 15
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
    val ownColour: Double = 4.607,
    val bigWorth: Double = 1.706,
    val aggroWorth: Double = 1.0,
    val scytheBig: Double = 1.339,
    val bigBeforeCheck: Double = 0.181,
    val bigCheckTicks: Int = 11,
    val spareWorth: Double = 0.726,
    val cleanupTicks: Int = 31,
    val swapCost: Double = 0.136,
    val stickiness: Double = 1.654,
    val pillarWorth: Double = 2.494,
    val walkCost: Double = 0.986,
    val lookahead: Double = 3.631,
    val lookaheadTicks: Int = 1,
    val dinhWave: Int = 20,
    val dinhMin: Int = 3,
    val youth: Double = 4.0,
    val nullCost: Double = 0.0,
    val areaWorth: Double = 1.234,
    val cleanupRate: Double = 1.412,
    val cleanupSpareWorth: Double = 0.0,
    val postCapBig: Double = 1.0,
    val lanes: Boolean = true,
    val mustKill: Boolean = false,
    val keepBigs: Boolean = false,
    val bookWorth: Double = 1.0,
    val bookOther: Double = 1.0,
    val bookLead: Int = 0,
    val chinsFrom: Int = 0,
    val barrageTo: Int = 99,
    val bigYouth: Boolean = false,
    val script: Boolean = false,
    val bookDeath: Int = 0,
    val scriptFrom: Int = 1,
    val lanesFrom: Int = 99,
    val cleanAreaFrom: Int = 99,
    val scriptBigs: Boolean = false,
    val scriptGuard: Boolean = true,
    val goalGrid: Boolean = true,
    val mageOff: Double = 0.3,
    val scriptYield: Boolean = true,
    val setPlays: Boolean = true,
    val overdue: Double = 3.0,
    val overdueLead: Int = 2,
    val playBarrage: Boolean = true,
    val playSpec: Boolean = true,
    val playSwitch: Boolean = true,
    val killFrom: Int = 99,
    val copy: Int = -1,
    val copyLate: Int = 3,
    val copyLead: Int = 2,
    val copySlack: Int = 0,
    val learned: Boolean = false,
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
                cleanupRate = num("cleanupRate", d.cleanupRate),
                cleanupSpareWorth = num("cleanupSpareWorth", d.cleanupSpareWorth),
                postCapBig = num("postCapBig", d.postCapBig),
                lanes = num("lanes", if (d.lanes) 1.0 else 0.0) != 0.0,
                mustKill = num("mustKill", if (d.mustKill) 1.0 else 0.0) != 0.0,
                keepBigs = num("keepBigs", if (d.keepBigs) 1.0 else 0.0) != 0.0,
                bookWorth = num("bookWorth", d.bookWorth),
                bookOther = num("bookOther", d.bookOther),
                bookLead = int("bookLead", d.bookLead),
                chinsFrom = int("chinsFrom", d.chinsFrom),
                barrageTo = int("barrageTo", d.barrageTo),
                bigYouth = num("bigYouth", if (d.bigYouth) 1.0 else 0.0) != 0.0,
                script = num("script", if (d.script) 1.0 else 0.0) != 0.0,
                bookDeath = int("bookDeath", d.bookDeath),
                scriptFrom = int("scriptFrom", d.scriptFrom),
                lanesFrom = int("lanesFrom", d.lanesFrom),
                cleanAreaFrom = int("cleanAreaFrom", d.cleanAreaFrom),
                scriptBigs = num("scriptBigs", if (d.scriptBigs) 1.0 else 0.0) != 0.0,
                scriptGuard = num("scriptGuard", if (d.scriptGuard) 1.0 else 0.0) != 0.0,
                goalGrid = num("goalGrid", if (d.goalGrid) 1.0 else 0.0) != 0.0,
                mageOff = num("mageOff", d.mageOff),
                scriptYield = num("scriptYield", if (d.scriptYield) 1.0 else 0.0) != 0.0,
                setPlays = num("setPlays", if (d.setPlays) 1.0 else 0.0) != 0.0,
                overdue = num("overdue", d.overdue),
                overdueLead = int("overdueLead", d.overdueLead),
                playBarrage = num("playBarrage", if (d.playBarrage) 1.0 else 0.0) != 0.0,
                playSpec = num("playSpec", if (d.playSpec) 1.0 else 0.0) != 0.0,
                playSwitch = num("playSwitch", if (d.playSwitch) 1.0 else 0.0) != 0.0,
                killFrom = int("killFrom", d.killFrom),
                copy = int("copy", d.copy),
                copyLate = int("copyLate", d.copyLate),
                copyLead = int("copyLead", d.copyLead),
                copySlack = int("copySlack", d.copySlack),
                learned = num("learned", if (d.learned) 1.0 else 0.0) != 0.0,
            )
        }
    }
}
