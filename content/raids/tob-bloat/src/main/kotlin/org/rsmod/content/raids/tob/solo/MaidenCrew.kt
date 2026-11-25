package org.rsmod.content.raids.tob.solo

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.util.Wearpos
import org.rsmod.api.player.clearInteractionRoute
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.statBoost
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.content.raids.tob.bloat.RaidBots
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionNpc
import org.rsmod.map.CoordGrid
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bot teammates for the Maiden, playing the sim's trio procedure line for line (`runMaiden` with `procedureOpts(3)`):
 * each bot works through its role's lists - the prefires, then a list per proc - and each smart line picks its target
 * the way the sim's does (`tfz` the mage's freezes by spawns, `tdps` a DPS's crab or a straggler, `tdin` the melee's
 * Dinh's timed and placed by value, `tpile` the mage's pile barrage when it's worth it). Positions are the sim's room
 * tiles (x east, y south, a crab by its north-west tile), so every rule reads as it does there.
 *
 * The melee also carries an Obligator, which the sim doesn't have: [MaidenCrewParams] switches on its uses and holds
 * the values a search tunes.
 */
class MaidenCrew(
    override val room: MaidenSolo,
    members: List<Pair<Player, NyloRole>>,
    private val bots: RaidBots,
    private val clock: MapClock,
    private val barrage: Int,
    private val rush: Int,
    private val params: MaidenCrewParams = MaidenCrewParams(),
) : BotCrew {
    enum class Move(val set: GearSet, val stance: Int, val cost: Int = 0, val ammo: String? = null) {
        Tbow(NyloLoadout.TBOW, 1),
        Blowpipe(NyloLoadout.BLOWPIPE, 1),
        Crossbows(NyloLoadout.CROSSBOWS, 1, ammo = NyloLoadout.DIAMOND_BOLTS),
        Ralos(NyloLoadout.RALOS, 1, 500),
        Zcb(NyloLoadout.ZCB, 1, 750, NyloLoadout.RUBY_BOLTS),
        Scythe(NyloLoadout.SCYTHE, 1),
        Obligator(NyloLoadout.OBLIGATOR, 0),
        Dinh(NyloLoadout.DINH, 0, 500),
        Claws(NyloLoadout.CLAWS, 1, 500),
        Barrage(NyloLoadout.BARRAGE, 0),
        Rush(NyloLoadout.BARRAGE, 0),
        Tome(NyloLoadout.TOME, 0),
        ;

        val spec: Boolean
            get() = cost > 0
    }

    /** A line's attack: its speed (ticks to the next), style, whether it goes at a crab, and its spec cost (in %). */
    private class Atk(val speed: Int, val style: Char, val crab: Boolean = false, val spec: Int = 0)

    private inner class Bot(val player: Player, val role: NyloRole, val i: Int) {
        val dps: Int? = DPS_SLOT[role]
        val lists = LISTS.getValue(role)
        var seq: List<String> = lists[0]
        var step = 0
        var dinDone = false
        var din2Done = false
        var surged = false
        var swungAt = 0
        var predodgeAt = -1
        var aimed: Npc? = null
        val route = ArrayDeque<CoordGrid>()
        var routeAt: CoordGrid? = null
        var aimedFor = -1
        var sw50 = 0
        var dinPos: IntArray? = null
        var dinNext: DinOpt? = null
        var walkedFor: String? = null
        var force: String? = null
        val tfzD = HashMap<Int, MutableSet<String>>()
        val dpsD = HashMap<Int, MutableSet<String>>()
        var order: Order? = null
        val spec: Int
            get() = player.vars["varp.sa_energy"] / 10

        val next: Int
            get() = t + max(0, player.actionDelay - clock.cycle)

        fun raw(): String = seq[min(step, seq.size - 1)]

        fun owns(weapon: String): Boolean = weapon in OWNS.getValue(role)
    }

    private class Order(val move: Move, val target: Npc, val at: Int, var walkTo: CoordGrid?, val issued: Int, var held: Boolean = false)

    private class Meta(val wave: Int, val idx: Int) {
        var chunk = ""
        var entered = 0
        var rushed = false
        var scyBy = -1
    }

    private class Crab(val npc: Npc, val id: String, val wave: Int, val x: Int, val y: Int, val hp: Int, val frozenTo: Int, val immuneTo: Int, val born: Int, val meta: Meta) {
        val key = "$id@$wave"
        val base = id.trimEnd('a', 'b')

        fun copy(x: Int = this.x, y: Int = this.y, hp: Int = this.hp) = Crab(npc, id, wave, x, y, hp, frozenTo, immuneTo, born, meta)
    }

    private class WavePlan(val fz: List<String>, var left: List<String>, val rush: Boolean) {
        var lord: List<String>? = null
    }

    private class DinOpt(val key: String, val pos: IntArray, val n: Int, val value: Double)

    private class Inflight(val land: Int, val mean: Double, val variance: Double, val crab: Npc?)

    private val leaks = HashSet<Npc>()
    private val crew = members.mapIndexed { i, (p, r) -> Bot(p, r, i) }
    private val mage = crew.first { it.role == NyloRole.Mage }

    /** Each wave's first tick the mage could cast on it: the tick after its proc, or its cooldown's end. */
    private val mageFree = HashMap<Int, Int>()
    private val metas = HashMap<Npc, Meta>()
    private val spawned = HashMap<Int, MutableSet<String>>()
    private val plans = HashMap<Int, WavePlan>()
    private val inflight = ArrayList<Inflight>()
    private val barrages = ArrayList<Pair<Int, Npc>>()
    private val procAt = ArrayList<Int>()
    private var crabs: List<Crab> = emptyList()
    private var t = 0
    private var curT = 0

    private val swappedAt = HashMap<Player, Int>()
    private val thralled = HashSet<Player>()
    private val opened = HashSet<Player>()
    private var charged = false

    override fun starts(): List<CoordGrid> = crew.indices.map { START_TILES[it % START_TILES.size] }

    override val walksIn: Boolean
        get() = true

    override fun doneAt(): Int = room.deadAt

    override fun summary(): String =
        "Maiden dead at room tick ${room.deadAt} (${raidTime(room.deadAt)}), procs at ${procAt.joinToString("/")}, crabs leaked ${room.leaked}, healed ${room.healed}, bled ${room.bled}"

    override val keepsHp: Boolean
        get() = true

    override fun prepare(bot: Player) {
        bot.statBoost("stat.hitpoints", anglerBoost(bot.baseHitpointsLvl), ANGLER_PERCENT)
        VarPlayerIntMapSetter.set(bot, "varbit.vengeance_rebound", 1)
        VarPlayerIntMapSetter.set(bot, "varp.option_run", 1)
        bot.runEnergy = FULL_RUN
        if (crew.first { it.player === bot }.role == NyloRole.Mage) return
        VarPlayerIntMapSetter.set(bot, "varbit.death_charge_scroll_used", 1)
        VarPlayerIntMapSetter.set(bot, "varbit.death_charge_charges", DEATH_CHARGES)
        VarPlayerIntMapSetter.set(bot, "varbit.arceuus_death_charge_active", 1)
        VarPlayerIntMapSetter.set(bot, "varbit.spellbook", ARCEUUS)
        VarPlayerIntMapSetter.set(bot, "varbit.spellbook_swap_book", 1)
    }

    /* ---- the tick */

    override fun tick() {
        if (room.deadAt >= 0) {
            if (TRACING && procAt.size < TALLIED) {
                procAt += -1
                trace { "tally ${room.tally}" }
            }
            return
        }
        t = room.t - 1
        if (!charged) {
            charged = true
            for (b in crew) {
                if (b.role == NyloRole.Mage) continue
                VarPlayerIntMapSetter.set(b.player, DC_COOLDOWN, 1)
                b.player.queue(DC_EXPIRE, DC_LEFT)
            }
        }
        while (procAt.size < room.phase) {
            procAt += t - 1
            onProc(procAt.size)
            trace { "t$t proc ${procAt.size}: def ${her.defenceLvl} party ${room.party().size} " + crew.joinToString { "${it.role} hp ${it.player.hitpoints} at ${room.real(it.player.coords)}" } }
        }
        snapshot()
        inflight.removeAll { it.land < t }
        barrages.removeAll { it.first < t }
        for (b in crew) thrown(b)
        if (TRACING) for (b in crew) swung(b)
        val flinching = herAttack()
        for (b in crew) {
            val p = b.player
            if (!p.isSlotAssigned || p.hitpoints <= 0) continue
            protect(b)
            if (p in flinching || !room.inArena(room.real(p.coords)) || t < FIRST_ACT) continue
            if (b.role != NyloRole.Mage && spells(b)) continue
            if (stall(b)) continue
            stepRoute(b)
            if (t >= b.predodgeAt && t - b.predodgeAt <= PREDODGE_TICKS && predodge(b)) continue
            if (dodge(b)) continue
            val o = b.order
            if (o != null) {
                if (o.target.isSlotAssigned && o.target.hitpoints > 0 && t - o.at <= ORDER_TICKS) {
                    if (o.held) {
                        if (p.actionDelay <= clock.cycle) {
                            o.held = false
                            o.walkTo = null
                            fire(b, o)
                        }
                    } else o.walkTo?.let { dest -> if (room.real(p.coords) == dest || t - o.at >= WALK_TICKS) fire(b, o) }
                    continue
                }
                b.order = null
            }
            if (p.actionDelay > clock.cycle + if (b.dps != null) params.lookahead else 0) {
                position(b)
                continue
            }
            decide(b)
        }
        for (b in crew) unaimed(b)
    }

    /** A swing going off this cycle at a crab no order of this swing picked (the game's next swing on its last target) is stopped. */
    private fun unaimed(b: Bot) {
        val p = b.player
        if (p.actionDelay > clock.cycle) return
        val target = current(p)?.takeIf { it !== her } ?: return
        if (b.aimed === target && b.aimedFor >= p.actionDelay) return
        if (b.order?.let { it.target === target && !it.held } == true) return
        trace { "t$t ${b.role} stray swing at ${crabs.firstOrNull { it.npc === target }?.key} stopped" }
        p.clearInteractionRoute()
    }

    /**
     * Her next attack, as the sim has it: it goes at whoever is closest to her centre, so when someone with Vengeance up
     * isn't, the ones in front without it flinch two tiles out on her attack tick - if that puts the Vengeance player in
     * front of them all, and none of them has a swing due that tick (players flinch between swings). Anyone too low to
     * take a prayed max hit flinches the same way behind someone who can. Her target flicks a crossbow and quiver on for the tick, so her drain goes on Ranged.
     */
    private fun herAttack(): Set<Player> {
        if (!room.attacksNext) return emptySet()
        val party = room.party()
        if (party.isEmpty()) return emptySet()
        val bots = crew.associateBy { it.player }
        val near = party.withIndex().map { (i, p) -> Triple(p, room.centreDist(room.real(p.coords)), i) }.sortedWith(compareBy({ it.second }, { it.third }))
        var target = near.first().first
        val flinching = HashSet<Player>()
        val safe = { p: Player -> p.hitpoints > room.autoMax / 2 + TANK_MARGIN }
        val tanks = near.indices.filter { near[it].first.vars["varbit.vengeance_rebound"] == 1 && safe(near[it].first) } + near.indices.filter { safe(near[it].first) }
        for (k in tanks) {
            if (k == 0) break
            val q = near[k]
            val before = near.subList(0, k)
            if (before.any { b -> b.first !in bots || b.first.actionDelay <= clock.cycle || !(q.second < b.second + FLINCH || (q.second == b.second + FLINCH && q.third < b.third)) }) continue
            before.forEach { flinching += it.first }
            target = q.first
            break
        }
        for (p in flinching) flinch(p)
        val tank = bots[target]
        if (tank != null && tank.player.actionDelay > clock.cycle) flick(tank.player)
        for (b in crew) if (b.player !in flinching) b.predodgeAt = t + 1
        trace { "t$t her attack: ${tank?.role ?: target.username}, flinching ${flinching.mapNotNull { bots[it]?.role }} ${near.joinToString { "${bots[it.first]?.role}:${it.second}" }}" }
        return flinching
    }

    /** Two tiles out from her centre, never onto her blood: straight out first, else the nearest clean tile as far. */
    private fun flinch(p: Player) {
        val here = room.real(p.coords)
        val dx = Integer.signum(here.x - room.centre.x).takeIf { it != 0 } ?: 1
        val dz = Integer.signum(here.z - room.centre.z)
        val out = room.centreDist(here) + FLINCH
        val ring = (-FLINCH..FLINCH).flatMap { ax -> (-FLINCH..FLINCH).map { az -> CoordGrid(here.x + ax, here.z + az, here.level) } }
        val dest = (listOf(CoordGrid(here.x + dx * FLINCH, here.z + dz * FLINCH, here.level)) + ring.sortedBy { maxOf(abs(it.x - here.x - dx * FLINCH), abs(it.z - here.z - dz * FLINCH)) })
            .firstOrNull { room.centreDist(it) >= out && room.inArena(it) && !room.blocked(it) && !room.bloody(it) } ?: return
        VarPlayerIntMapSetter.set(p, "varp.option_run", 1)
        bots.walk(p, room.at(dest))
    }

    private fun flick(p: Player) {
        for ((slot, obj) in FLICK) {
            val id = obj.asRSCM(RSCMType.OBJ)
            if (p.worn[slot.slot]?.id != id) bots.equip(p, slot.slot, id)
        }
    }

    /**
     * Players pre-dodge her: on her attack tick everyone slides two tiles, so a blood throw's spots (every player's tile,
     * three round the furthest) land behind them - along her side for a bot beside her, at the same reach (or a tile
     * closer) for one at range, clear of the others' tiles. A swing due now goes first and the step a tick
     * later, still ahead of the splat.
     */
    private fun predodge(b: Bot): Boolean {
        val p = b.player
        if (b.order != null || p.actionDelay <= clock.cycle + 1) return false
        b.predodgeAt = -1
        if (p.routeDestination.isNotEmpty()) return false
        val here = room.real(p.coords)
        val beside = room.besideHer(here)
        val gap = room.gapToHer(here)
        val party = room.party().map { room.real(it.coords) }
        val far = party.maxByOrNull { room.gapToHer(it) }
        val struck = { c: CoordGrid -> c in party || (far != null && far != here && maxOf(abs(c.x - far.x), abs(c.z - far.z)) <= 1) }
        val to =
            (-FLINCH..FLINCH).flatMap { dx -> (-FLINCH..FLINCH).map { dz -> CoordGrid(here.x + dx, here.z + dz, here.level) } }
                .filter { maxOf(abs(it.x - here.x), abs(it.z - here.z)) == FLINCH }
                .filter { room.inArena(it) && !room.under(it) && !room.bloody(it) && !struck(it) && if (beside) room.besideHer(it) else room.gapToHer(it) in gap - 1..gap }
                .minWithOrNull(
                    compareBy(
                        { if (b.dps != null) room.gapToHer(it) else abs(room.gapToHer(it) - gap) },
                        { if (b.dps != null) fromPost(b, it) else 0 },
                        { if (b.dps != null) fromSpawns(it) else 0 },
                    )
                ) ?: return false
        trace { "t$t ${b.role} pre-dodges to ${to.x - ROOM_X},${ROOM_Y - to.z}" }
        walk(b, to)
        return true
    }

    /**
     * Off her blood: a bot standing in a splat or a trail steps to the nearest clean tile (beside her if it was), and
     * its order goes on from there - unless its next swing is due within [MaidenCrewParams.dodgeLead] ticks (it swings
     * first and steps after, as players do).
     */
    private fun dodge(b: Bot): Boolean {
        val p = b.player
        val here = room.real(p.coords)
        if (!room.bloody(here) || b.order?.walkTo != null) return false
        if (p.actionDelay <= clock.cycle + params.dodgeLead || p.routeDestination.isNotEmpty()) return false
        val beside = room.besideHer(here)
        val to =
            (-DODGE..DODGE).flatMap { dx -> (-DODGE..DODGE).map { dz -> CoordGrid(here.x + dx, here.z + dz, here.level) } }
                .filter { it != here && room.inArena(it) && !room.under(it) && !room.bloody(it) && (!beside || room.besideHer(it)) }
                .minWithOrNull(compareBy({ if (room.gapToHer(it) - 1 <= RUN) 0 else 1 }, { if (b.dps != null) fromPost(b, it) else 0 }, { maxOf(abs(it.x - here.x), abs(it.z - here.z)) })) ?: return false
        trace { "t$t ${b.role} steps off blood to ${to.x - ROOM_X},${ROOM_Y - to.z}" }
        val o = b.order
        if (o != null) {
            b.order = Order(o.move, o.target, t, to, o.issued)
        }
        walk(b, to)
        return true
    }

    /**
     * Between swings a DPS walks to where the crab its next swing goes for (its own of the wave, or a straggler) will be
     * when that swing is due, so the swing doesn't wait on the run - players stand where their crab walks in; the sim
     * swings from anywhere.
     */
    private fun position(b: Bot) {
        if (b.order != null) return
        dinhSpot(b)?.let { return toSpot(b, it) }
        if (b.dps == null || latestWave() == null) return toHer(b)
        val due = b.next
        if (due <= t) return
        val own = ownCrab(b, due)
        // the DPS on their one crab: her while the next swing still catches it as it steps beside her (Blert: that swing
        // lands as she'd heal and kills it first), so the swing due goes on her from beside her
        if (own != null && planOf(own.wave).left.size == 1 && !(own.wave == 50 && b.lists[2].any { it.startsWith("tdin") }) && reachOf(own) > due + SWING) return toHer(b)
        val c = own ?: nextCrab(b, due) ?: return toHer(b)
        var x = c.x
        var y = c.y
        for (k in t + 1..due) {
            if (k <= c.frozenTo || adjacent(x, y)) continue
            val sw = y + 1
            x -= 1
            if (sw < SWROW) y++ else if (sw > SWROW) y--
        }
        val here = room.real(b.player.coords)
        val me = intArrayOf(here.x - ROOM_X, ROOM_Y - here.z)
        b.walkedFor = c.key
        if (maxOf(x - me[0], me[0] - (x + 1), y - me[1], me[1] - (y + 1), 0) == 1 && (me[0] in x..x + 1 || me[1] in y..y + 1)) return
        val sides = listOf(x to y - 1, x + 1 to y - 1, x to y + 2, x + 1 to y + 2, x - 1 to y, x - 1 to y + 1, x + 2 to y, x + 2 to y + 1)
        val dest =
            sides.filter { (sx, sy) -> sx in 0..ROOM_W && sy in 0..ROOM_H && !(sx in MT_X..MX1 && sy in MT_Y..MY1) }
                .map { (sx, sy) -> CoordGrid(sx + ROOM_X, ROOM_Y - sy, here.level) }
                .filter { !room.bloody(it) && !room.blocked(it) && room.inArena(it) }
                .minWithOrNull(
                    compareBy(
                        { if (maxOf(abs(it.x - here.x), abs(it.z - here.z)) <= RUN * (due - t)) 0 else 1 },
                        { if (cleaves(it.x - ROOM_X, ROOM_Y - it.z, x, y)) 0 else 1 },
                        { if (room.besideHer(it)) 0 else 1 },
                        { maxOf(abs(it.x - here.x), abs(it.z - here.z)) },
                    )
                ) ?: return
        trace { "t$t ${b.role} moves to ${dest.x - ROOM_X},${ROOM_Y - dest.z} for ${c.key} (swing t$due)" }
        walk(b, dest)
    }

    /** Tiles from where the north spawns come in past her corner (a DPS side-stepping keeps to their way). */
    private fun fromSpawns(c: CoordGrid): Int = maxOf(abs(c.x - ROOM_X - (MX1 + 3)), abs(ROOM_Y - c.z - (MT_Y - 3)))

    /** Whether a scythe from ([sx], [sy]) on the crab at ([cx], [cy]) also catches her: her body on its arc's far tile. */
    private fun cleaves(sx: Int, sy: Int, cx: Int, cy: Int): Boolean {
        val (dx, dy) =
            when {
                sy < cy -> 0 to 1
                sy > cy + 1 -> 0 to -1
                sx < cx -> 1 to 0
                else -> -1 to 0
            }
        val ax = sx + dx
        val ay = sy + dy
        return listOf(ax + dy to ay + dx, ax - dy to ay - dx).any { (x, y) -> x in MT_X..MX1 && y in MT_Y..MY1 }
    }

    /**
     * The next attack on her from closer in (the sim has them there by then): a scythe beside her, a ranged attack
     * within its range, walked during the cooldown.
     */
    private fun toHer(b: Bot) {
        if (b.role == NyloRole.Mage && mageStand(b)) return
        val here = room.real(b.player.coords)
        val next = upcoming(b)
        REACH_OF[next]?.let { reach ->
            val gap = room.gapToHer(here)
            if (gap > reach) walk(b, CoordGrid(here.x - (gap - reach), here.z, here.level))
            return
        }
        if (atkOf(next).style != 'm') return
        val beside = room.besideHer(here)
        if (beside && fromPost(b, here) <= POST_NEAR) return
        val dist = { c: CoordGrid -> maxOf(abs(c.x - here.x), abs(c.z - here.z)) }
        val open =
            (-REACH..REACH).flatMap { dx -> (-REACH..REACH).map { dz -> CoordGrid(here.x + dx, here.z + dz, here.level) } }
                .filter { room.besideHer(it) && room.inArena(it) && !room.under(it) && !room.bloody(it) }
        val nearest = open.minOfOrNull(dist) ?: return
        val spare = RUN * (b.next - t - 1)
        val dest = open.filter { fromPost(b, it) <= POST_NEAR && dist(it) <= maxOf(if (beside) 0 else nearest, spare) }.minByOrNull(dist) ?: if (beside) return else open.minByOrNull(dist) ?: return
        trace { "t$t ${b.role} moves beside her to ${dest.x - ROOM_X},${ROOM_Y - dest.z} (swing t${b.next})" }
        walk(b, dest)
    }

    /**
     * Tiles from the bot's post by her north-east corner, where the north spawns walk in: the ranger north of her, the
     * melee on her east side (room to line up its Dinh's without losing a swing).
     */
    private fun fromPost(b: Bot, c: CoordGrid): Int {
        val (px, py) = if (b.dps == MELEE_DPS) MX1 + 1 to MT_Y + 1 else MX1 to MT_Y - 1
        return maxOf(abs(c.x - ROOM_X - px), abs(ROOM_Y - c.z - py))
    }

    /**
     * The room pacing for a proc before the mage has this wave's clump frozen (a 9s): the DPS's swings on her would
     * take her past it, so one of them - the ranger first, the melee too if one off still procs her - swings on a
     * crab out of the clump instead, and the proc comes a swing later, once the mage's clump is done.
     */
    private fun holdProc(b: Bot, tt: Int): Crab? {
        if (phase == 0 || b.dps == null) return null
        val w = latestWave() ?: return null
        val done = mage.tfzD[w].orEmpty()
        if (crabs.none { it.wave == w && it.frozenTo < 0 && (it.base in THREES || it.base in FOURS) && clumpOf(it) !in done }) return null
        val due = crew.filter { it.dps != null && it.player.hitpoints > 0 && it.next == tt && it.raw() in SCYTHE_LINES }.sortedBy { if (it.dps == MELEE_DPS) 1 else 0 }
        if (b !in due) return null
        val line = full * phase / 100.0
        val mean = landing(tt, tt + 1).first
        if (hp - mean >= line) return null
        val off = (1..due.size).firstOrNull { k -> hp - (mean - k * SCYTHE_MEAN) >= line } ?: return null
        if (due.indexOf(b) >= off) return null
        return crabs.filter { q -> q.base !in THREES && q.base !in FOURS && reachOf(q) > tt + 1 && !freezing(q) && canHit(b, q, tt) }
            .maxWithOrNull(compareBy({ if (tt > it.frozenTo) 1 else 0 }, { it.hp }))
    }

    /**
     * The line the bot's next swing comes off: spent freezes, the surge and a ZCB it hasn't the energy for skipped, a
     * pile barrage not worth it as its fallback.
     */
    private fun upcoming(b: Bot): String {
        val tt = max(b.next, t)
        val spec = b.spec + if (!b.surged && "surge" in b.seq.drop(b.step)) SURGE_SPEC else 0
        val a =
            (b.step until b.seq.size).map { b.seq[it] }
                .firstOrNull { it != "surge" && !(it == "tfz" && tfzSpent(b, tt)) && !(it == "zcbs" && spec < atkOf(it).spec && !clawsOver(b, spec)) } ?: b.raw()
        if (a == "zcbs" && clawsOver(b, spec)) return "claws"
        if (a != "tpile") return a
        if (phase == 0) return "scythe"
        return if (pileWorth(tt, true, "pile").second * 2 >= params.pileWorth) a else "tbr"
    }

    /**
     * Where the melee's next swing goes from when it's a Dinh's: the first one held a swing (re-picked for the swing's
     * tick as the crabs walk), or a second one that will reach enough crabs.
     */
    private fun dinhSpot(b: Bot): IntArray? {
        val next = upcoming(b)
        if (!b.dinDone && next in DINH_LINES) return b.dinNext?.pos
        val surge = if (!b.surged && "surge" in b.seq.drop(b.step)) SURGE_SPEC else 0
        val second = next == "tdin2" && b.dinDone && !b.din2Done && b.spec + surge >= DINH_COST
        if (!second) return null
        // drunk on the cooldown: a potion taken with the click cancels the attack queued behind it
        if (b.spec < DINH_COST) drink(b)
        if (b.dinNext == null) {
            val keep = curT
            curT = max(b.next, t)
            b.dinNext = dinPick(0, inflightKeys(curT, false), b, RUN * max(1, curT - t))?.takeIf { it.n >= params.secondDinhMin }
            curT = keep
        }
        return b.dinNext?.pos
    }

    /**
     * The Dinh's the melee lined up a swing ago, when it stands on its tile: it goes as planned (a player walks to their
     * Dinh's spot during the swing before; the sim, with no walking, re-picks), on the best target from there when the
     * planned one could die to a hit landing first.
     */
    private fun committed(b: Bot): DinOpt? {
        val plan = b.dinNext ?: return null
        val here = room.real(b.player.coords)
        if (maxOf(abs(plan.pos[0] - (here.x - ROOM_X)), abs(plan.pos[1] - (ROOM_Y - here.z))) > params.dinhWalk) return null
        if (b.spec < DINH_COST) return null
        if (plan.key == "maiden" || crabRef(plan.key)?.takeIf { it.hp > SAFE_HP && it.key !in inflightKeys(t + 1, false) && reachOf(it) > t + 2 } != null) return plan
        val keep = curT
        curT = t + 1
        val pk = dinPick(0, inflightKeys(curT, false), b)
        curT = keep
        return pk
    }

    /** Between swings, to the tile the melee's held Dinh's goes from (the sim's pick a swing ahead): there when it's due. */
    private fun toSpot(b: Bot, at: IntArray) {
        val dest = CoordGrid(at[0] + ROOM_X, ROOM_Y - at[1], 0)
        if (room.real(b.player.coords) == dest) return
        trace { "t$t ${b.role} moves to ${at[0]},${at[1]} for its Dinh's (swing t${b.next})" }
        walk(b, dest)
    }

    /**
     * The mage's spots east of her: tbows from any tile 9-10 out (they land in 4, so a proc off one leaves the barrage
     * ready on the proc's first tick), a ZCB from 7-8, barrages from 3-4 (by the clump, so they land sooner) - toward
     * the middle rows, in reach of the north and south spawns alike. It stays put on any clean tile that fits, else
     * takes the nearest, walked during the cooldown.
     */
    private fun mageStand(b: Bot): Boolean {
        val next = upcoming(b)
        val far =
            when (next) {
                "tbr" -> TBOW_GAP
                "zcbs" -> REACH_OF.getValue("zcbs")
                "tfz", "tpile" -> params.casterGap
                else -> return false
            }
        val here = room.real(b.player.coords)
        val fits = { c: CoordGrid ->
            room.gapToHer(c) in far - 1..far && c.x - ROOM_X > MX1 && abs(ROOM_Y - c.z - MAGE_ROW) <= MAGE_ROWS && room.inArena(c) && !room.bloody(c)
        }
        if (fits(here)) return true
        val dest =
            (-STAND_SEARCH..STAND_SEARCH).flatMap { dx -> (-STAND_SEARCH..STAND_SEARCH).map { dz -> CoordGrid(here.x + dx, here.z + dz, here.level) } }
                .filter(fits)
                .minWithOrNull(compareBy({ maxOf(abs(it.x - here.x), abs(it.z - here.z)) }, { abs(ROOM_Y - it.z - MAGE_ROW) })) ?: return true
        trace { "t$t ${b.role} moves to ${dest.x - ROOM_X},${ROOM_Y - dest.z} for its $next (swing t${b.next})" }
        walk(b, dest)
        return true
    }

    /**
     * The DPS's own crab of the wave (as the plan hands them out), seen on the spawn: still out, and still short of
     * her when the swing on [due] lands.
     */
    private fun ownCrab(b: Bot, due: Int): Crab? {
        splitStraggler(b, due)?.let { return it }
        val w = latestWave() ?: return null
        val plan = planOf(w)
        val done = b.dpsD[w] ?: emptySet<String>()
        val ids =
            when {
                plan.left.size == 1 -> plan.left
                w == 30 -> listOfNotNull(plan.left.getOrNull(b.dps ?: return null))
                else -> leftOrder(b, w, due)
            }
        return ids.filter { it !in done }.firstNotNullOfOrNull { id -> crabRef("$id@$w")?.takeIf { reachOf(it) > due } }
    }

    /** Whether the bot stands beside the crab (on a side, where it can swing at it). */
    private fun beside(b: Bot, c: Crab): Boolean {
        val r = room.real(b.player.coords)
        val x = r.x - ROOM_X
        val y = ROOM_Y - r.z
        val gx = maxOf(c.x - x, x - (c.x + 1), 0)
        val gy = maxOf(c.y - y, y - (c.y + 1), 0)
        return maxOf(gx, gy) == 1 && !(gx == 1 && gy == 1)
    }

    /** The crab the bot's next swing (on [due]) would go for, as things stand - a dry run of its pick. */
    private fun nextCrab(b: Bot, due: Int): Crab? {
        val snap = crabs.associate { it.meta to it.meta.scyBy }
        val dd = b.dpsD.mapValues { HashSet(it.value) }
        val keep = curT
        curT = due
        lead = due - t
        val a = dpsSwing(b, due)
        lead = 0
        curT = keep
        for ((m, v) in snap) m.scyBy = v
        b.dpsD.clear()
        b.dpsD.putAll(dd)
        return if (a.startsWith("scy") && ':' in a) crabRef(a.split(':')[1]) else null
    }

    /** Ticks a bot has to run before the swing being picked (a dry run ahead of it). */
    private var lead = 0

    /** Everyone on to the list for the proc that just came (a ZCB they didn't get to is kept for after their freezes). */
    private fun onProc(n: Int) {
        mageFree[WAVES[n - 1]] = max(procAt[n - 1] + 1, mage.next)
        for (b in crew) {
            val next = b.lists[n]
            b.seq = if (n == 1) next else carry(b, next)
            b.step = 0
        }
    }

    private fun carry(b: Bot, next: List<String>): List<String> {
        val rest = b.seq.drop(b.step)
        if ("zcbs" !in rest || "zcbs" in next) return next
        val at = next.indexOfLast { it == "tfz" } + 1
        val out = next.toMutableList()
        out.addAll(at, (if ("surge" in rest && !b.surged) listOf("surge") else emptyList()) + "zcbs")
        return out
    }

    private fun snapshot() {
        val wave = WAVES.getOrElse(room.phase - 1) { WAVES[0] }
        val views = room.crabViews()
        if (TRACING) {
            for (v in views.filter { it.arrived && it.npc !in leaks }) {
                leaks += v.npc
                val r = room.real(v.npc.coords)
                trace { "t$t leak ${v.spot}@${metas[v.npc]?.wave} hp ${v.npc.hitpoints} at ${r.x - ROOM_X},${ROOM_Y - r.z - 1} frozen to ${roomTick(v.frozenUntil)}" }
            }
        }
        crabs =
            views.filter { !it.arrived }.map { v ->
                val m = metas.getOrPut(v.npc) { Meta(wave, room.rand(MAX_IDX)) }
                val r = room.real(v.npc.coords)
                val frozenTo = roomTick(v.frozenUntil)
                var x = r.x - ROOM_X
                var y = ROOM_Y - r.z - 1
                if (t > frozenTo && t >= v.born && !adjacent(x, y)) {
                    val sw = y + 1
                    x -= 1
                    if (sw < SWROW) y++ else if (sw > SWROW) y--
                }
                val chunk = "${x / CHUNK}:${Math.floorDiv(y + 1 - CHUNK_Y0, CHUNK)}"
                if (chunk != m.chunk) {
                    m.chunk = chunk
                    m.entered = t
                }
                spawned.getOrPut(m.wave) { HashSet() } += v.spot
                val immune = v.npc.vars["varn.freeze_immunity_end_clock"]
                Crab(v.npc, v.spot, m.wave, x, y, v.npc.hitpoints, frozenTo, roomTick(immune), v.born - 1, m)
            }
        if (TRACING) trace { "t$t her $hp " + crabs.joinToString(" ") { "${it.key}:${it.hp}@${it.x},${it.y}${if (it.frozenTo >= t) "f${it.frozenTo}" else ""}" } }
    }

    private fun roomTick(clockEnd: Int): Int = if (clockEnd <= 0) -1 else t + (clockEnd - clock.cycle) - 1

    /** Traces: every attack that went off (ordered or the game's own next swing), by its weapon. */
    private fun swung(b: Bot) {
        val ad = b.player.actionDelay
        if (ad == b.swungAt || ad <= clock.cycle) return
        b.swungAt = ad
        trace { "t$t ${b.role} swung ${b.player.worn[3]?.id} at ${(b.player.interaction as? InteractionNpc)?.target?.let { n -> crabs.firstOrNull { it.npc === n }?.key ?: "her" }} ad $ad" }
    }

    /** An order went off once the bot's attack delay moves past the clock it was given on. */
    private fun thrown(b: Bot) {
        val o = b.order ?: return
        if (o.walkTo != null || b.player.actionDelay <= o.issued) return
        b.order = null
        trace { "t$t ${b.role} threw ${o.move} at ${crabs.firstOrNull { it.npc === o.target }?.key ?: "her"} (ordered t${o.at})" }
        if (b.seq === b.lists[0] && b.step >= THRALL_AFTER) opened += b.player
        val land = t - 1 + LAND_DELAY.getOrDefault(o.move, 1)
        val (mean, sd) = MEAN_HIT[o.move] ?: (0.0 to 0.0)
        val onHer = o.target === her
        inflight += Inflight(land, if (onHer) mean else 0.0, if (onHer) sd * sd else 0.0, o.target.takeIf { !onHer && o.move != Move.Barrage })
        if (o.move == Move.Barrage && !onHer) barrages += land to o.target
    }

    /**
     * Its Lunar and Arceuus casts; true when it swaps books for a Death Charge: on the tick after a swing, so the stall
     * ends as its next swing is due, and not when her attack falls inside it (no drain it can't flick for).
     */
    private fun spells(b: Bot): Boolean {
        val p = b.player
        if (p in opened && p !in thralled) {
            if (p.vars["varbit.arceuus_resurrection_active"] == 1) {
                thralled += p
                trace { "t$t ${b.role} has its thrall" }
                if (p.vars["varbit.spellbook"] == ARCEUUS) {
                    VarPlayerIntMapSetter.set(p, "varbit.spellbook_swap_book", 0)
                    VarPlayerIntMapSetter.set(p, "varbit.spellbook", LUNARS)
                }
            } else if (p.vars["varbit.spellbook"] == ARCEUUS) {
                bots.spell(p, GREATER_GHOST)
            } else if (swappedAt[p] == null) {
                bots.spell(p, SPELLBOOK_SWAP, SWAP_TO_ARCEUUS)
                swappedAt[p] = t
            }
            return false
        }
        if (p in thralled && p.vars["varbit.spellbook"] == ARCEUUS && p.vars["varbit.spellbook_swap_book"] == 1 && b.next - t == SWING - 1) {
            bots.spell(p, if (p.vars[DC_COOLDOWN] == 0) DEATH_CHARGE else MARK_OF_DARKNESS)
            trace { "t$t ${b.role} casts back to the Lunars" }
            return false
        }
        if (p in thralled && p.vars[DC_COOLDOWN] == 0 && p.vars["varbit.spellbook"] == LUNARS && b.next - t == SWING - 1 && b.order == null && !p.isDelayed && (1..SHORT_STALL).none { room.attacksAt(t + 1 + it) }) {
            trace { "t$t ${b.role} swaps back for a fresh Death Charge" }
            bots.spell(p, SPELLBOOK_SWAP, SWAP_TO_ARCEUUS)
            return true
        }
        if (p.vars["varbit.vengeance_rebound"] == 0 && p.vars["varbit.vengeance_timelimit"] == 0 && p.vars["varbit.spellbook"] == LUNARS) bots.spell(p, VENGEANCE)
        return false
    }

    /**
     * After the 30s, whether the mage's ZCB spec goes to its claws instead: a claw spec on her worth more than the ZCB's
     * (its ruby proc, a share of her hitpoints capped, is the whole hit), or energy for the claws and not the ZCB.
     */
    private fun clawsOver(b: Bot, spec: Int): Boolean {
        if (b.role != NyloRole.Mage || phase != 0 || !b.owns("claws") || spec < atkOf("claws").spec) return false
        return spec < atkOf("zcbs").spec || CLAW_MEAN > min(ZCB_CAP, hp * ZCB_PCT / 100)
    }

    /**
     * Protect from Magic up, except with Vengeance up and the hitpoints to take her hardest hit: players take a
     * Vengeance hit off prayer for the full rebound.
     */
    private fun protect(b: Bot) {
        val p = b.player
        val want = p.vars["varbit.vengeance_rebound"] != 1 || p.hitpoints <= room.autoMax
        if (want != (p.vars[PROTECT_MAGIC] == 1)) bots.pray(p, PROTECT_MAGIC)
    }

    /** An anglerfish's overheal: a tenth of base hitpoints plus this. */
    private fun anglerBoost(base: Int): Int = ANGLER_STEPS.firstOrNull { base < it.first }?.second ?: ANGLER_TOP

    /** The standard normal distribution's CDF (a logistic fit, good to about 1%). */
    private fun phi(z: Double): Double = 1.0 / (1.0 + exp(-PHI_K * z))

    /**
     * Players stall her hit on them a tick after their swing: a Spellbook Swap locks the three ticks up to their next swing, and
     * her hit, held till then, rebounds with that swing's damage - so a Vengeance that could take her past her proc
     * doesn't proc her off the swing tick. Not when the swing just landing procs her, nor with a crab to get to (the
     * swap's lock holds them still).
     */
    private fun stall(b: Bot): Boolean {
        val p = b.player
        if (b.dps == null || phase == 0 || b.order != null || b.next - t != SWING - 1) return false
        if (p.vars["varbit.vengeance_rebound"] != 1 || p.vars["varbit.spellbook"] != LUNARS || p.isDelayed) return false
        val (lands, max) = room.autos[p] ?: return false
        if (lands - clock.cycle !in 1 until STALL_TICKS) return false
        val due = inflight.filter { it.land >= t && it.mean > 0 }
        val gap = hp - full * phase / 100.0
        val mean = due.sumOf { it.mean }
        val sd = sqrt(due.sumOf { it.variance }).coerceAtLeast(1.0)
        val below = { x: Double -> phi((x - mean) / sd) }
        val procs = 1 - below(gap)
        val rebound = (0 until REBOUND_STEPS).sumOf { k -> below(gap) - below(gap - max * REBOUND * (k + 0.5) / REBOUND_STEPS) } / REBOUND_STEPS
        if (procs > params.stallProcPct / 100.0 || rebound < params.stallRisk / 100.0) return false
        if (upcoming(b) !in SCYTHE_LINES || !room.besideHer(room.real(p.coords)) || ownCrab(b, b.next) != null || nextCrab(b, b.next) != null) return false
        trace { "t$t ${b.role} stalls her hit (lands +${lands - clock.cycle}, ${(rebound * 100).toInt()}% it'd proc her)" }
        bots.spell(p, SPELLBOOK_SWAP, SWAP_TO_ARCEUUS)
        return true
    }

    private val her: Npc
        get() = room.boss

    private val full: Int
        get() = her.baseHitpointsLvl

    private val hp: Int
        get() = her.hitpoints

    /** The proc coming next, as the sim counts it: 70, 50, 30, then 0. */
    private val phase: Int
        get() = NEXT_PROC[room.phase]

    private val proc70: Int?
        get() = procAt.getOrNull(0)

    private val proc50: Int?
        get() = procAt.getOrNull(1)

    private val lastProc: Int?
        get() = procAt.lastOrNull()

    private fun latestWave(): Int? = WAVES.getOrNull(room.phase - 1)

    /* ---- a bot's next attack */

    private fun decide(b: Bot) {
        val tt = b.next
        curT = tt
        val stand = if (b.role == NyloRole.Mage && b.force == null) standFor(b, tt) else null
        val a = onTime(b, stand ?: obligate(b, nextAct(b, tt), tt), tt)
        b.walkedFor = null
        trace { "t$tt ${b.role}@${room.real(b.player.coords).let { "${it.x - ROOM_X},${ROOM_Y - it.z}" }} $a${if (a.startsWith("dinhs")) " from ${b.dinPos?.joinToString(",")}" else ""}" }
        perform(b, a, tt)
    }

    /**
     * A melee swing on her that would first have to walk (the bot went for a crab or its Dinh's, and the pick has moved
     * on since) goes on what it walked for instead, when that's still there to hit: a DPS never waits on a run.
     */
    private fun onTime(b: Bot, a: String, tt: Int): String {
        if (b.dps == null || (a != "scythe" && a != "tdps") || room.gapToHer(room.real(b.player.coords)) - 1 <= 2) return a
        committed(b)?.let { plan ->
            if (!b.dinDone || !b.din2Done) {
                if (b.dinDone) b.din2Done = true else b.dinDone = true
                trace { "t$tt ${b.role} keeps its lined-up Dinh's on ${plan.key}" }
                return "dinhs:${plan.key}"
            }
        }
        val c = b.walkedFor?.let { crabRef(it) }?.takeIf { canHit(b, it, tt) } ?: crabs.filter { canHit(b, it, tt) }.maxByOrNull { it.hp } ?: return a
        trace { "t$tt ${b.role} swings on ${c.key}, beside it, instead of walking to her" }
        return "scyk:${c.key}"
    }

    /**
     * The sim's freezer stand: with the next proc's list opening on a freeze, the mage won't start an attack that leaves
     * it on cooldown past the proc's first tick when that proc could come first (a 1 in 4 chance, from what's in flight
     * and the scythes due) or she's within [STAND_PCT] of it. It stands, or preloads a barrage on a pile worth it: queued
     * behind the DPS hits, it goes once they've landed without a proc, and only when it's off cooldown in time for a
     * proc off their next ones - a proc off theirs gets the freezes on time instead.
     */
    private fun standFor(b: Bot, tt: Int): String? {
        if (room.phase >= WAVES.size) return null
        val cur = b.raw()
        if (cur == "tfz" && !tfzSpent(b, tt)) return null
        val a = atkOf(cur)
        if (a.style == 'm' || a.speed <= 1) return null
        val by = tt + a.speed - 2
        if (!procRisk(tt, by) && hp * 100 - full * phase > full * STAND_PCT) return null
        val others = crew.filter { it !== b }.minOf { it.next }
        // queued behind the DPS hits: it goes only once their last swings have landed without a proc (seen two ticks on)
        val seen = crew.filter { it !== b && it.dps != null }.all { it.next - SWING + PROC_SEEN <= tt }
        if (seen && tt + atkOf("tfz").speed <= others + PROC_SEEN && pileWorth(tt, false).second >= params.preloadWorth) {
            b.force = "ibs:pile"
            return null
        }
        return "wait"
    }

    /** The sim's `procRisk`: a 1 in 4 chance or more that what lands by [by] takes her past her next proc. */
    private fun procRisk(tt: Int, by: Int): Boolean {
        val (mean, v) = landing(tt, by)
        return (hp - mean - RISK_Z * sqrt(v)) * 100 < full * phase
    }

    /** The mean and variance of what lands on her from [tt] to [by]: hits in flight and the DPS's scythes due. */
    private fun landing(tt: Int, by: Int): Pair<Double, Double> {
        var mean = 0.0
        var v = 0.0
        for (h in inflight) if (h.land in tt..by && h.mean > 0) {
            mean += h.mean
            v += h.variance
        }
        for (d in crew) {
            if (d.raw() !in SCYTHE_LINES || !d.owns(SCYTHE)) continue
            var f = max(d.next, tt)
            while (f + 1 <= by) {
                mean += SCYTHE_MEAN
                v += SCYTHE_SD * SCYTHE_SD
                f += 5
            }
        }
        return mean to v
    }

    /**
     * Whether the mage's ZCB would be what procs her (what else lands by then leaves her above it): a proc off its own
     * spec puts its freezes a tick late, so it tbows and keeps the ZCB for after them.
     */
    private fun zcbProcs(tt: Int): Boolean {
        if (phase == 0) return false
        val left = hp - landing(tt, tt + LAND_DELAY.getValue(Move.Zcb) - 1).first
        val line = full * phase / 100.0
        return left >= line && left - min(ZCB_CAP, hp * ZCB_PCT / 100) < line
    }

    /**
     * The melee's Obligator (not in the sim): a thrust pierces 3 tiles in a line, so from the right tile it takes a
     * frozen crab and what's behind it - more crabs of a pile, or her when the crab stands against her. A swing goes as a
     * thrust whenever the thrust is worth more: one on a frozen crab through its line, one on her through a frozen crab
     * against her or a stack of repeat spawns frozen on one tile.
     */
    private fun obligate(b: Bot, a: String, tt: Int): String {
        if (!b.owns("obligator")) return a
        val k = a.substringBefore(':')
        val (c, line) =
            when (k) {
                "scyk", "scyc", "scys" -> crabRef(a.split(':')[1])?.takeIf { it.frozenTo >= tt + 1 }?.let { it to bestLine(b, it) } ?: return a
                "scythe" ->
                    crabs.filter { it.frozenTo >= tt + 1 }
                        .mapNotNull { q ->
                            bestLine(b, q)?.takeIf { it.her || (stacked(q) && it.n - (if (it.her) 1 else 0) >= 2) }?.let { q to it }
                        }
                        .maxByOrNull { it.second.worth } ?: return a
                else -> return a
            }
        if (line == null) return a
        // the thrust has to beat the swing it replaces (a crab's hits keep twice their damage off her), and not land on
        // a crab something already on its way (a hit, or a barrage's splash) may kill first
        val swing = if (k == "scythe") SCYTHE_MEAN else min(c.hp, SCY_CRAB).toDouble() * 2
        if (line.worth <= swing || line.keys.any { it in inflightKeys(tt) || crabRef(it)?.let { q -> freezing(q) } == true }) return a
        // a thrust that has to walk further than the swing it replaces would lose the tick (and the Dinh's behind it)
        val here = room.real(b.player.coords)
        if (maxOf(abs(line.x + ROOM_X - here.x), abs(ROOM_Y - line.y - here.z)) > RUN * max(0, tt - t)) return a
        return "obl:${c.key}:${line.x},${line.y}"
    }

    private class Line(val x: Int, val y: Int, val n: Int, val her: Boolean, val crabHp: Int, val worth: Double, val keys: List<String>)

    /** A frozen crab with the same spawn from another proc frozen on its very tiles: one thrust takes both. */
    private fun stacked(q: Crab): Boolean =
        crabs.any { it.wave != q.wave && it.id == q.id && it.x == q.x && it.y == q.y && it.frozenTo > t && it.hp + q.hp >= STACK_HP }

    /** The tile beside [c] whose thrust through it hits the most (crabs, and her), nearest the bot on a tie. */
    private fun bestLine(b: Bot, c: Crab): Line? {
        val here = room.real(b.player.coords)
        val me = intArrayOf(here.x - ROOM_X, ROOM_Y - here.z)
        val starts =
            listOf(
                Triple(c.x + 2, c.y, -1 to 0), Triple(c.x + 2, c.y + 1, -1 to 0), Triple(c.x - 1, c.y, 1 to 0), Triple(c.x - 1, c.y + 1, 1 to 0),
                Triple(c.x, c.y - 1, 0 to 1), Triple(c.x + 1, c.y - 1, 0 to 1), Triple(c.x, c.y + 2, 0 to -1), Triple(c.x + 1, c.y + 2, 0 to -1),
            )
        var best: Line? = null
        var bestD = 0
        for ((x, y, d) in starts) {
            if (x !in 0..ROOM_W || y !in 0..ROOM_H || (x in MT_X..MX1 && y in MT_Y..MY1)) continue
            if (crabs.any { x in it.x..it.x + 1 && y in it.y..it.y + 1 }) continue
            val tiles = (1..OBL_REACH).map { x + d.first * it to y + d.second * it }
            val hit = crabs.filter { q -> tiles.any { (tx, ty) -> tx in q.x..q.x + 1 && ty in q.y..q.y + 1 } }.take(OBL_MAX)
            val her = tiles.any { (tx, ty) -> tx in MT_X..MX1 && ty in MT_Y..MY1 } && hit.size < OBL_MAX
            val n = hit.size + if (her) 1 else 0
            val dist = maxOf(abs(x - me[0]), abs(y - me[1]))
            if (best == null || n > best.n || (n == best.n && dist < bestD)) {
                val worth = hit.sumOf { min(it.hp, OBL_CRAB).toDouble() * 2 } + if (her) OBL_CRAB.toDouble() else 0.0
                best = Line(x, y, n, her, hit.sumOf { min(it.hp, PILE_CRAB_CAP * 2) }, worth, hit.map { it.key })
                bestD = dist
            }
        }
        return best
    }

    private fun fallback(b: Bot, a: String): String = if (atkOf(a).style == 'm' && b.owns(SCYTHE)) "scythe" else "tbr"

    private fun nextAct(b: Bot, tt: Int): String {
        b.force?.let {
            b.force = null
            if (b.step < b.seq.size - 1) b.step++
            return it
        }
        while (true) {
            val last = b.step >= b.seq.size - 1
            var a = b.raw()
            if (a == "surge") {
                if (!b.surged && b.spec < 100) drink(b)
                if (!last) {
                    b.step++
                    continue
                }
                return if (b.owns(SCYTHE) && b.dps != null) "scythe" else "tbr"
            }
            if (a == "scythe" && b.dps != null) a = "tdps"
            if (a == "tdin" && b.dinDone) {
                if (!last) {
                    b.step++
                    continue
                }
                return dpsSwing(b, tt)
            }
            // after the 30s the mage's freezes are its last barrages: on to her (the sim pulls up on the older piles)
            if (a == "tpile" && phase == 0) {
                if (!last) {
                    b.step++
                    continue
                }
                return "scythe"
            }
            // a healthy older crab thawing and walking in before the swing after takes this swing; the ZCB waits
            if (a == "zcbs" && b.dps != null) thawing(b, tt)?.let { q ->
                return if (reachOf(q) == tt + 1) "scys:${q.key}:${if (q.hp >= magenta()) "N" else "S"}" else "scyk:${q.key}"
            }
            // the mage's last freeze done, a claw spec worth more than the ZCB (or all the energy left would buy) goes first
            if (a == "zcbs" && clawsOver(b, b.spec)) {
                if (!last) b.step++
                trace { "t$tt Mage claws instead of its ZCB" }
                return "claws"
            }
            if (a == "zcbs" && b.role == NyloRole.Mage && zcbProcs(tt)) {
                trace { "t$tt Mage keeps its ZCB: it would proc her" }
                return "tbr"
            }
            if (a == "tpile") return if (pileWorth(tt, true, "pile").second * 2 >= params.pileWorth) "ibs:pile" else "tbr"
            if (a == "tfz" || a == "tdps") {
                val r = if (a == "tfz") tfzPick(b, tt) else dpsSwing(b, tt)
                // a free hit in the 30s freezes (a crab missing from the list) goes to the ZCB the mage has the energy for
                if (r == "tbr" && b.role == NyloRole.Mage && phase == 0 && b.owns("zcb") && b.spec >= atkOf("zcbs").spec) {
                    trace { "t$tt Mage ZCBs its free hit" }
                    return "zcbs"
                }
                if (r == "wait" || r == "tbr" || r == "ibs:old" || r?.startsWith("aya:") == true || r?.startsWith("ax:") == true) return r
                if (r == null) {
                    if (!last) {
                        b.step++
                        continue
                    }
                    return fallback(b, a)
                }
                a = r
            }
            if (a == "tdin2") {
                if (!b.dinDone) {
                    a = "tdin"
                } else {
                    val pk = if (!b.din2Done && b.spec >= DINH_COST) committed(b) ?: dinPick(0, inflightKeys(tt, false), b) else null
                    if (pk == null || pk.n < params.secondDinhMin || zcbOver(b, pk)) return dpsSwing(b, tt)
                    b.din2Done = true
                    b.dinPos = pk.pos
                    a = "dinhs:${pk.key}"
                }
            }
            if (a == "tdin") {
                val r = dinhTimed(b, tt)
                if (!r.startsWith("dinhs")) return r
                a = r
            }
            val atk = atkOf(a)
            val why = invalid(a, tt)
            val has = if (atk.style == 'f') b.role == NyloRole.Mage else weaponOf(a)?.let { b.owns(it) } ?: true
            val energy = atk.spec == 0 || b.spec >= atk.spec
            if (has && energy && why == null) {
                if (!last) b.step++
                if (proc50 != null && !b.dinDone && atk.style == 'm' && (a == "scythe" || a.startsWith("scyk") || a.startsWith("scyc"))) b.sw50++
                return a
            }
            if (has && !energy && why == null) {
                specFallback(b, a)?.let {
                    if (!last) b.step++
                    return it
                }
            }
            if (!last) {
                b.step++
                continue
            }
            return fallback(b, a)
        }
    }

    /**
     * The sim's value-timed Dinh's (`tdin`): after at least one scythe since the 50s, another scythe first whenever the
     * Dinh's would still be worth as much a swing later (crabs that reach her by then are lost to it), up to 30 ticks
     * after the 50s; then where it hits the most. Anything but a Dinh's is the swing that goes first.
     */
    private fun dinhTimed(b: Bot, tt: Int): String {
        if (b.sw50 < 1) {
            b.sw50 = 1
            val sw = dpsSwing(b, tt)
            b.dinNext = dinPick(5, inflightKeys(tt, false), b, PLAN_RUN)
            return sw
        }
        committed(b)?.let { plan ->
            b.dinDone = true
            b.dinPos = plan.pos
            return "dinhs:${plan.key}"
        }
        val snap = crabs.associate { it.meta to it.meta.scyBy }
        val dd = b.dpsD.mapValues { HashSet(it.value) }
        val sw0 = dpsSwing(b, tt)
        val swK = if (sw0.startsWith("sc") && sw0.contains(':')) sw0.split(':')[1] else null
        val strag = HashSet<String>()
        swK?.let { strag += it }
        strag += inflightKeys(tt, false)
        latestWave()?.let { w -> strag += planOf(w).left.map { "$it@$w" } }
        val now = dinPick(0, strag, b)
        val later = dinPick(5, strag, b, PLAN_RUN)
        val cap = proc50 != null && tt + 5 > proc50!! + params.dinhCap
        trace { "t$tt dinh now ${now?.key}:${now?.value?.toInt()}:${now?.n} later ${later?.key}:${later?.value?.toInt()}:${later?.n} skip $strag sw $sw0 crabs ${crabs.joinToString(" ") { "${it.key}:${it.hp}@${it.x},${it.y}${if (it.frozenTo >= tt) "f" else ""}" }}" }
        val here = room.real(b.player.coords)
        val reach = { o: DinOpt -> maxOf(abs(o.pos[0] - (here.x - ROOM_X)), abs(o.pos[1] - (ROOM_Y - here.z))) <= params.dinhWalk }
        val swingHere = swK?.let { k -> crabRef(k)?.let { beside(b, it) } ?: false } ?: room.besideHer(here)
        val goNow = now != null && reach(now) && !swingHere
        if (!cap && !goNow && later != null && (now == null || !reach(now) || later.value * 100 >= now.value * params.dinhHoldPct)) {
            b.sw50++
            b.dinNext = later
            return sw0
        }
        for ((m, s) in snap) m.scyBy = s
        b.dpsD.clear()
        b.dpsD.putAll(dd)
        swK?.let { strag -= it }
        val tg = dinPick(0, strag, b)
        b.dinDone = true
        b.dinPos = tg?.pos
        return if (tg != null) "dinhs:${tg.key}" else "scythe"
    }

    /**
     * A Dinh's worth less than a ZCB on her (its ruby proc a share of her hitpoints, and the quicker proc that brings
     * the scythes' cleave to the clump sooner) is held: its energy goes on the ZCB.
     */
    private fun zcbOver(b: Bot, pk: DinOpt): Boolean {
        if (!b.owns("zcb")) return false
        val near = crabs.filter { pk.key != it.key && abs(it.x - pk.pos[0]) <= DINH_REACH && abs(it.y + 1 - pk.pos[1]) <= DINH_REACH }
        val first = crabRef(pk.key)?.let { min(it.hp, 2 * SPLAT) } ?: SPLAT
        val dinh = first + near.map { min(it.hp, SPLAT) }.sortedDescending().take(DINH_HITS - 1).sum()
        return dinh < min(ZCB_CAP, hp * ZCB_PCT / 100)
    }

    private fun specFallback(b: Bot, a: String): String? {
        if (a != "zcbs" || b.spec < DINH_COST || phase == 0 || !b.owns(DINH_WEAPON)) return null
        val pk = dinPick(0, from = b) ?: return null
        if (pk.n < params.secondDinhMin || zcbOver(b, pk)) return null
        b.dinPos = pk.pos
        return "dinhs:${pk.key}"
    }

    private fun invalid(a: String, tt: Int): String? {
        val k = a.substringBefore(':')
        val ref = a.split(':').getOrNull(1) ?: return null
        if (k == "ibs" || k == "ir") {
            val grp = ref in GROUPS
            val c = if (grp) barragePrim(ref, tt) else crabRef(ref)
            if (c == null) return "gone"
            if (grp && ref != "old" && ref != "pile" && aoe3(c).none { tt > it.immuneTo && tt > it.frozenTo }) return "nothing to freeze"
            return null
        }
        if (k == "dinhs" && ref == "maiden") return null
        if (k == "dinhs" || k.startsWith("scy")) return if (crabRef(ref) == null) "gone" else null
        return null
    }

    /* ---- the wave plan and the mage's freezes */

    private fun planOf(w: Int): WavePlan =
        plans.getOrPut(w) {
            val here = { id: String -> spawned[w]?.contains(id) == true }
            val out = listOf("S1", "N1", "S2", "N2").filter(here)
            var rushIt = false
            val fz =
                when {
                    out.size == 4 -> listOf("S1", "S2")
                    !here("S1") && !here("N1") && here("S2") -> {
                        rushIt = true
                        listOf("S2") + listOfNotNull("N2".takeIf(here))
                    }
                    else -> {
                        val ones = listOf("S1", "N1").filter(here)
                        val twos = listOf("S2", "N2").filter(here)
                        if (ones.isNotEmpty()) listOf(ones[0]) + twos.take(1) else twos
                    }
                }
            WavePlan(fz, listOf("N1", "N2", "S1", "S2").filter { it in out && it !in fz }, rushIt)
        }

    private fun ofWave(w: Int, ids: List<String>): List<Crab> = crabs.filter { it.wave == w && it.base in ids }

    private fun waveHas(w: Int, re: Regex): Boolean = spawned[w]?.any { re.matches(it) } == true

    private fun clumpReady(w: Int, which: String, tt: Int): Boolean? {
        val mine = ofWave(w, if (which == "3s") THREES else FOURS)
        if (mine.isEmpty()) return null
        if (mine.any { reachOf(it) <= tt + 1 }) return true
        val lp = procOf(w) ?: return false
        if (which == "3s") return if (mine.size == 1) tt >= lp + THREES_DUE else mine.all { c -> mine.all { inArea(c, it) } }
        if (ofWave(w, THREES).any { tt > it.frozenTo && tt > it.immuneTo }) return false
        val has3 = waveHas(w, THREE_RE)
        if (!has3) {
            if (w == 30) {
                val d = mage.tfzD[w] ?: return false
                return planOf(w).fz.all { it in d } && mine.all { c -> mine.all { inArea(c, it) } } && (overPile(mine, tt)?.let { tt >= it } ?: true)
            }
            if (tt < lp + FOURS_DUE) return false
        }
        val threes = (if (has3) ofWave(w, THREES) else ofWave(w, listOf("S2"))).filter { tt <= it.frozenTo }
        if (threes.isEmpty() && w != 30 && tt < lp + FOURS_DUE) return false
        return if (threes.isNotEmpty()) mine.any { c -> threes.any { inArea(it, c) } } else mine.all { it.y + 1 == SWROW } && mine.all { c -> mine.all { inArea(c, it) } }
    }

    private fun tfzSpent(b: Bot, tt: Int): Boolean {
        val w = latestWave() ?: return false
        val done = b.tfzD[w] ?: return false
        return planOf(w).fz.all { it in done } && listOf("3s", "4s").all { it in done || clumpReady(w, it, tt) == null }
    }

    private fun tfzPick(b: Bot, tt: Int): String? {
        oddPlan(b, tt)?.let { return it }
        oldClump(b, tt)?.let { return it }
        val w = latestWave() ?: return null
        val plan = planOf(w)
        val done = b.tfzD.getOrPut(w) { HashSet() }
        for (which in listOf("3s", "4s")) {
            if (which !in done && clumpReady(w, which, tt) == true) {
                done += which
                return "ibs:$which"
            }
        }
        // the N2 the wave's only 1 / 2: the first barrage goes on the old pile, the N2 on the second, frozen closer in
        // (nearer the DPS when it thaws, and where a later wave's N2 stops, to stack)
        if (plan.fz == listOf("N2") && w != WAVES[0] && "N2" !in done && LATE_N2 !in done) {
            val c = crabRef("N2@$w")
            if (c != null && tt > c.frozenTo && tt + atkOf("ibs").speed <= reachOf(c) - FZ_LAND) {
                done += LATE_N2
                val pile = barragePrim("old", tt)
                return if (pile != null && aoe3(pile).sumOf { it.hp } >= OLD_PILE_HP) "ibs:old" else "tbr"
            }
        }
        val no3 = !waveHas(w, THREE_RE)
        val anchorS2 = no3 && !plan.rush && waveHas(w, S2_RE) && waveHas(w, FOUR_RE)
        for ((k, id) in plan.fz.withIndex()) {
            if (id in done) continue
            val c = crabRef("$id@$w")
            if (id == "S2" && anchorS2 && c != null && abs(c.y + 1 - SWROW) > 1 && reachOf(c) > tt + 1) return "wait"
            done += id
            val lateBy = !anchorS2 && w != 30
            if (c != null && lateBy && makesClumpLate(w, done, tt)) {
                leave(plan, id, w, tt)
                continue
            }
            if (c == null || tt <= c.frozenTo) continue
            val after = plan.fz.drop(k + 1).firstOrNull { it !in done }
            if (after != null && lateBy && makesClumpLate(w, done, tt + atkOf("ibs").speed)) {
                done += after
                // the DPS hold her north and east: the one they can reach is the north one, so the mage takes the south
                val south = crabRef("$after@$w")?.takeIf { id.startsWith('N') && after.startsWith('S') && tt > it.frozenTo }
                if (south != null) {
                    leave(plan, id, w, tt)
                    return "ibs:$after@$w"
                }
                leave(plan, after, w, tt)
            }
            if (id == "S2") {
                val cast = singleCast(c, tt)
                if (cast == null) {
                    leave(plan, id, w, tt)
                    continue
                }
                return "$cast:$id@$w"
            }
            return "ibs:$id@$w"
        }
        if (anchorS2 && w != 30 && "S2" in done && "aya" !in done) {
            done += "aya"
            val loose = crabs.filter { tt > it.frozenTo }.maxByOrNull { it.hp }
            return "aya:${loose?.key ?: "maiden"}"
        }
        for (which in listOf("3s", "4s")) {
            if (which in done) continue
            if (clumpReady(w, which, tt) == null) {
                done += which
                continue
            }
            val lp = lastProc ?: return "wait"
            val due =
                if (w == 30 && which == "4s" && !waveHas(w, THREE_RE)) ofWave(w, FOURS).let { overPile(it, tt) ?: clumpAt(it, tt) } ?: (lp + FOURS_DUE)
                else lp + if (which == "3s") THREES_DUE else FOURS_DUE
            if (w == 70 && plan.fz.isEmpty() && which == "3s") {
                if ("tb" !in done) {
                    if (tt < lp + 3) return "wait"
                    done += "tb"
                    return "tbr"
                }
                if ("aya" !in done && tt + 3 <= due) {
                    done += "aya"
                    val loose = crabs.filter { tt > it.frozenTo && it.base !in THREES && it.base !in FOURS }.maxByOrNull { it.hp }
                    return "aya:${loose?.key ?: "maiden"}"
                }
                return "wait"
            }
            if (tt + 5 <= due) {
                if (w != 70) {
                    val c = barragePrim("old", tt)
                    if (c != null && aoe3(c).sumOf { it.hp } >= OLD_PILE_HP) return "ibs:old"
                }
                return "tbr"
            }
            if (tt + atkOf("ax").speed <= due) return "ax:${looseNear(b, tt)?.key ?: "maiden"}"
            return "wait"
        }
        return null
    }

    /**
     * A proc that comes before the last wave's 3s / 4s are clumped (a 9s 50s, or quicker): the mage still freezes that
     * clump on its tick - those leak far worse than a single crab - and leaves the new wave's 1s / 2s it hasn't the
     * ticks for to the DPS (the first freeze of the new set on a 9s proc, the first two on a 6s one).
     */
    private fun oldClump(b: Bot, tt: Int): String? {
        val w = latestWave() ?: return null
        val ow = WAVES.getOrNull(WAVES.indexOf(w) - 1) ?: return null
        val op = procOf(ow) ?: return null
        val od = b.tfzD.getOrPut(ow) { HashSet() }
        for (which in listOf("3s", "4s")) {
            if (which in od) continue
            val mine = ofWave(ow, if (which == "3s") THREES else FOURS)
            if (mine.none { tt > it.frozenTo && tt > it.immuneTo && !freezing(it) }) {
                od += which
                continue
            }
            val speed = atkOf("ibs").speed
            if (clumpReady(ow, which, tt) == true) {
                od += which
                val prim = mine.maxByOrNull { c -> aoe3(c).let { n -> n.count { tt > it.immuneTo && tt > it.frozenTo } * 100 + n.size } } ?: continue
                trace { "t$tt Mage freezes the $ow $which first" }
                leaveUnreached(b, w, tt + speed, tt)
                return "ibs:${prim.key}"
            }
            val due = op + if (which == "3s") THREES_DUE else FOURS_DUE
            if (tt + speed <= due) return null
            leaveUnreached(b, w, due + speed, tt)
            return "wait"
        }
        return null
    }

    /**
     * The new wave's 1s / 2s the mage won't freeze before they reach her, free again on [free] and freezing the rest
     * in order (the new wave's own clump on its tick first): left to the DPS now, while they've the ticks to get there.
     */
    private fun leaveUnreached(b: Bot, w: Int, free: Int, tt: Int) {
        val plan = planOf(w)
        val done = b.tfzD.getOrPut(w) { HashSet() }
        val speed = atkOf("ibs").speed
        val lp = procOf(w) ?: return
        val clumps = listOf("3s" to THREES, "4s" to FOURS).filter { (k, ids) -> k !in done && ofWave(w, ids).isNotEmpty() }.map { (k, _) -> lp + if (k == "3s") THREES_DUE else FOURS_DUE }.toMutableList()
        var f = free
        for (id in plan.fz) {
            if (id in done) continue
            val c = crabRef("$id@$w") ?: continue
            if (tt <= c.frozenTo) continue
            while (clumps.isNotEmpty() && f + speed > clumps[0]) f = max(f, clumps.removeAt(0)) + speed
            if (reachOf(c) <= f + 1) {
                done += id
                leave(plan, id, w, tt)
            } else f += speed
        }
    }

    /** A freeze the mage could cast: a 1 / 2 (by id) or a wave's clump, the cast ticks it's good for, and what it saves. */
    private class Fz(val w: Int, val id: String, val clump: Boolean, val from: Int, val to: Int, val value: Double)

    /**
     * An odd proc (a 9.6s, 10.8s, 11.4s 50s, an early 70s or 30s, a mage a tick or a bow behind): when the freezes as usually ordered don't
     * all fit, the mage takes the set and order worth most - every clump (old wave and new) on its tick and the 1s / 2s
     * the DPS can't reach first - filling the ticks between, and leaves the rest to the DPS at once.
     */
    private fun oddPlan(b: Bot, tt: Int): String? {
        val w = latestWave() ?: return null
        val fs = fzTargets(b, w, tt)
        if (fs.isEmpty()) return null
        val usual = fs.filter { it.clump && it.w != w }.sortedBy { it.from } + planOf(w).fz.mapNotNull { id -> fs.firstOrNull { !it.clump && it.id == id } } + fs.filter { it.clump && it.w == w }.sortedBy { it.from }
        if (fits(usual, tt) != null) return null
        val best = bestFreezes(fs, tt)
        trace { "t$tt Mage plans ${best.joinToString { "${it.first.id}@${it.first.w}:t${it.second}" }} of ${fs.joinToString { "${it.id}@${it.w}[${it.from},${it.to}]=${it.value.toInt()}" }}" }
        val plan = planOf(w)
        val done = b.tfzD.getOrPut(w) { HashSet() }
        for (f in fs) if (!f.clump && best.none { it.first === f }) {
            done += f.id
            leave(plan, f.id, w, tt)
        }
        val (first, at) = best.firstOrNull() ?: return null
        if (at > tt) return if (at - tt >= atkOf("tbr").speed) "tbr" else if (at - tt >= atkOf("ax").speed) "ax:${looseNear(b, tt)?.key ?: "maiden"}" else "wait"
        b.tfzD.getOrPut(first.w) { HashSet() } += first.id
        if (first.clump) {
            val mine = ofWave(first.w, if (first.id == "3s") THREES else FOURS)
            val prim = mine.maxByOrNull { c -> aoe3(c).let { n -> n.count { tt > it.immuneTo && tt > it.frozenTo } * 100 + n.size } } ?: return null
            return "ibs:${prim.key}"
        }
        val c = crabRef("${first.id}@${first.w}") ?: return null
        val cast = singleCast(c, tt)
        if (cast == null) leave(plan, first.id, w, tt)
        return cast?.let { "$it:${c.key}" }
    }

    /**
     * A freeze on [c]: the S2 gets an ice rush on the mage's first two ticks for it (it thaws in time to walk into its
     * wave's 4s and be frozen again with them), later a barrage that catches no loose 3s/4s (null: leave it); any other
     * single a rush where a barrage would catch them.
     */
    private fun singleCast(c: Crab, tt: Int): String? {
        val early = aoe3(c).any { it !== c && (it.base in THREES || it.base in FOURS) && tt > it.frozenTo }
        return when {
            c.base != "S2" -> if (early) "ir" else "ibs"
            tt <= (mageFree[c.wave] ?: tt) + S2_SLACK -> "ir"
            early -> null
            else -> "ibs"
        }
    }

    private fun fzTargets(b: Bot, w: Int, tt: Int): List<Fz> {
        val out = ArrayList<Fz>()
        for (cw in listOfNotNull(WAVES.getOrNull(WAVES.indexOf(w) - 1), w)) {
            val lp = procOf(cw) ?: continue
            val done = b.tfzD[cw].orEmpty()
            for ((which, ids) in listOf("3s" to THREES, "4s" to FOURS)) {
                if (which in done) continue
                val mine = ofWave(cw, ids).filter { tt > it.frozenTo && tt > it.immuneTo && !freezing(it) }
                if (mine.isEmpty()) continue
                val due = if (cw == WAVES[2] && which == "4s" && !waveHas(cw, THREE_RE)) (overPile(mine, tt) ?: clumpAt(mine, tt)) ?: (lp + FOURS_DUE) else lp + if (which == "3s") THREES_DUE else FOURS_DUE
                val from = if (clumpReady(cw, which, tt) == true) tt else max(tt, due)
                out += Fz(cw, which, true, from, mine.minOf { reachOf(it) } - FZ_LAND, mine.sumOf { it.hp } * 2.0)
            }
        }
        val done = b.tfzD[w].orEmpty()
        for (id in planOf(w).fz) {
            if (id in done) continue
            val c = crabRef("$id@$w") ?: continue
            if (tt <= c.frozenTo || freezing(c)) continue
            val covered = crew.any { d -> d.dps != null && d.player.hitpoints > 0 && max(d.next, t + 1).let { s -> listOf(s, s + SWING).any { it < reachOf(c) && canHit(d, c, it) } } }
            out += Fz(w, id, false, tt, reachOf(c) - FZ_LAND + if (c.base == "S2") S2_SLACK else 0, c.hp * 2.0 * if (covered) COVERED_SHARE else 1.0)
        }
        return out
    }

    /** Each freeze in [order] cast as soon as it's good for after the one before, if they all fit. */
    private fun fits(order: List<Fz>, tt: Int): List<Pair<Fz, Int>>? {
        var at = tt
        val out = ArrayList<Pair<Fz, Int>>()
        for (f in order) {
            val x = max(at, f.from)
            if (x > f.to) return null
            out += f to x
            at = x + atkOf("ibs").speed
        }
        return out
    }

    /** The set and order of [fs] worth most (ties: done soonest). */
    private fun bestFreezes(fs: List<Fz>, tt: Int): List<Pair<Fz, Int>> {
        var best = emptyList<Pair<Fz, Int>>()
        var bestV = 0.0
        var bestEnd = Int.MAX_VALUE
        fun go(cur: List<Pair<Fz, Int>>, at: Int, v: Double, rest: List<Fz>) {
            val end = cur.lastOrNull()?.second ?: tt
            if (v > bestV + 1e-6 || (abs(v - bestV) < 1e-6 && end < bestEnd)) {
                best = cur
                bestV = v
                bestEnd = end
            }
            for (f in rest) {
                val x = max(at, f.from)
                if (x <= f.to) go(cur + (f to x), x + atkOf("ibs").speed, v + f.value, rest - f)
            }
        }
        go(emptyList(), tt, 0.0, fs)
        return best
    }

    private fun procOf(w: Int): Int? = procAt.getOrNull(WAVES.indexOf(w))?.takeIf { it >= 0 }

    /**
     * The 30s 4s with no 3s to walk into: the tick they first stand in one 3x3 together with enough of the 50s pile,
     * frozen there or still walking in, that their barrage cleaves (and refreezes) it too - if that's before they reach her. The mage fills till then
     * (a bow, or two crossbow hits a tick late) rather than freezing them early.
     */
    private fun overPile(mine: List<Crab>, tt: Int): Int? {
        val w = latestWave() ?: return null
        val pile = crabs.filter { it.wave != w && reachOf(it) - FZ_LAND > tt }
        if (pile.sumOf { it.hp } < OLD_PILE_HP) return null
        val walkers = mine + pile
        val pos = walkers.map { intArrayOf(it.x, it.y) }
        val last = mine.minOf { reachOf(it) } - FZ_LAND
        for (k in 0..WALK_LIMIT) {
            val at = tt + k
            if (at > last) return null
            for (c in pos.take(mine.size)) {
                if (!pos.take(mine.size).all { abs(it[0] - c[0]) <= 1 && abs(it[1] - c[1]) <= 1 }) continue
                val caught = pile.indices.filter { i -> reachOf(pile[i]) - FZ_LAND >= at && pos[mine.size + i].let { abs(it[0] - c[0]) <= 1 && abs(it[1] - c[1]) <= 1 } }
                if (caught.sumOf { pile[it].hp } >= OLD_PILE_HP) return at
            }
            for ((i, c) in walkers.withIndex()) {
                val p = pos[i]
                if (at < c.frozenTo || adjacent(p[0], p[1])) continue
                val sw = p[1] + 1
                p[0] -= 1
                if (sw < SWROW) p[1]++ else if (sw > SWROW) p[1]--
            }
        }
        return null
    }

    /** The tick [mine] first stand in one 3x3 as they walk in (the 30s 4s with no 3s to walk into), if they do. */
    private fun clumpAt(mine: List<Crab>, tt: Int): Int? {
        val pos = mine.map { intArrayOf(it.x, it.y) }
        for (k in 0..WALK_LIMIT) {
            if (pos.all { a -> pos.all { abs(a[0] - it[0]) <= 1 && abs(a[1] - it[1]) <= 1 } }) return tt + k
            for ((i, c) in mine.withIndex()) {
                val at = pos[i]
                if (tt + k < c.frozenTo || adjacent(at[0], at[1])) continue
                val sw = at[1] + 1
                at[0] -= 1
                if (sw < SWROW) at[1]++ else if (sw > SWROW) at[1]--
            }
        }
        return null
    }

    /** A crab walking in unfrozen (not one of the clump) the mage can shoot from where it stands, the healthiest. */
    private fun looseNear(b: Bot, tt: Int): Crab? {
        val here = room.real(b.player.coords)
        val me = intArrayOf(here.x - ROOM_X, ROOM_Y - here.z - 1)
        return crabs.filter { tt > it.frozenTo && it.base !in THREES && it.base !in FOURS && reachOf(it) > tt + 1 && maxOf(abs(it.x - me[0]), abs(it.y - me[1])) <= REACH_OF.getValue("ax") }.maxByOrNull { it.hp }
    }

    /** A 1 / 2 the late mage won't get to goes to the DPS, as soon as that's known (they have the ticks to reach it). */
    private fun leave(plan: WavePlan, id: String, w: Int, tt: Int) {
        plan.left = plan.left + id
        plan.lord = null
        trace { "t$tt Mage leaves $id@$w to the DPS: the clump comes first" }
    }

    /**
     * Whether freezing a 1 / 2 now leaves the next cast past the wave's clump tick (the 3s on proc + 11, the 4s on
     * proc + 16): a mage late off the proc skips it for the DPS and keeps the 3s and 4s in one clump.
     */
    private fun makesClumpLate(w: Int, done: Set<String>, tt: Int): Boolean {
        val lp = lastProc ?: return false
        val which = listOf("3s", "4s").firstOrNull { it !in done && ofWave(w, if (it == "3s") THREES else FOURS).isNotEmpty() } ?: return false
        val due = lp + if (which == "3s") THREES_DUE else FOURS_DUE
        return tt < due && tt + atkOf("ibs").speed > due
    }

    /* ---- the DPS */

    /**
     * The sim's trio DPS swing: the wave's 1s / 2s the mage won't freeze (two for them: both on the one reaching her
     * first, then the other), once each; then a straggler about to leak (walking, or thawing out of the pile before
     * their next swing) by jaggers_0's lines; else her.
     */
    private fun dpsSwing(b: Bot, tt: Int): String {
        val w = latestWave() ?: return "scythe"
        val dps = b.dps ?: return "scythe"
        holdProc(b, tt)?.let { q ->
            trace { "t$tt ${b.role} holds the proc: swings on ${q.key}" }
            return "scyk:${q.key}"
        }
        splitStraggler(b, tt)?.takeIf { canHit(b, it, tt) }?.let { q ->
            return if (reachOf(q) == tt + 1) "scys:${q.key}:${if (q.hp >= magenta()) "N" else "S"}" else "scyk:${q.key}"
        }
        val plan = planOf(w)
        val done = b.dpsD.getOrPut(w) { HashSet() }
        val id: String? =
            when {
                plan.left.size == 1 -> plan.left[0]
                w == 30 -> plan.left.getOrNull(dps)
                else -> {
                    val rch = { x: String -> crabRef("$x@$w")?.let { reachOf(it) } ?: -1 }
                    val lord = leftOrder(b, w, tt)
                    val dl = crew.firstOrNull { d -> d !== b && d.dps != null && d.raw() in DINH_LINES && d.spec >= DINH_COST && d.sw50 >= 1 && d.next <= tt + 1 }
                    val dk = if (dl != null) dinPick(0, from = dl) else null
                    lord.withIndex().firstOrNull { (k, x) -> x !in done && rch(x) >= tt + 1 && (k == 0 || dk?.key != "$x@$w") }?.value
                }
            }
        if (id != null && id !in done) {
            val c = crabRef("$id@$w")?.takeIf { canHit(b, it, tt) }
            if (plan.left.size == 1 && !(w == 50 && b.lists[2].any { it.startsWith("tdin") }) && c != null && reachOf(c) > tt + SWING && !beside(b, c)) return "scythe"
            if (c != null && reachOf(c) == tt + 1 && c.hp >= blue()) {
                done += id
                return "scys:${c.key}:${if (c.hp >= magenta()) "N" else "S"}"
            }
            if (c != null && reachOf(c) > tt + 1) {
                done += id
                return "scyk:${c.key}"
            }
        }
        val mdone = mage.tfzD[w]
        val taken = inflightKeys(tt)
        val planned = { q: Crab ->
            q.wave == w && tt > q.frozenTo &&
                if (q.id in plan.fz) mdone?.contains(q.id) != true else if (q.base in THREES || q.base in FOURS) mdone?.contains(clumpOf(q)) != true else false
        }
        val dpl = crew.firstOrNull { d -> d !== b && d.dps != null && d.raw() in DINH_LINES && d.spec >= DINH_COST && d.sw50 >= 1 }
        val dpk = if (dpl != null) dinPick(0, inflightKeys(tt, false) + plan.left.map { "$it@$w" }, dpl) else null
        val dinCovers = { q: Crab -> dpk != null && dpk.key == q.key && dpl!!.next <= tt + 1 && reachOf(q) > dpl.next + 1 }
        val red = red()
        val blue = blue()
        val mag = magenta()
        val st0 =
            crabs.asSequence()
                .filter { q ->
                    (tt > q.frozenTo || q.frozenTo <= tt + 5) && !planned(q) && q.key !in taken && !clumpCovers(q, tt) && !dinCovers(q) && !pushPast(q) && !freezing(q) &&
                        !(q.meta.rushed && mdone?.contains("4s") != true && q.wave == w) &&
                        !(q.wave == w && q.id in plan.left && crew.none { d -> d.dpsD[w]?.contains(q.id) == true }) &&
                        !(q.meta.scyBy >= 0 && q.meta.scyBy != b.i && q.hp <= SHARED_HP) &&
                        !(q.hp <= SHARED_HP && designated(q, tt)?.let { it !== b } == true)
                }
                .map { it to reachOf(it) }
                .filter { (q, r) -> (r in tt + 1..tt + params.straggleTicks || preThaw(q, tt)) && canHit(b, q, tt) }
                .sortedWith(compareBy<Pair<Crab, Int>> { if (it.second <= tt + 5) 0 else 1 }.thenByDescending { it.first.hp }.thenBy { it.second })
                .toList()
        for ((q, r) in st0) {
            if (r == tt + 1) {
                val side = if (q.hp >= mag) "N" else if (q.hp >= blue) "S" else continue
                if (q.meta.scyBy < 0) q.meta.scyBy = b.i
                return "scys:${q.key}:$side"
            }
            if (q.hp > red + params.redOffset) {
                if (q.meta.scyBy < 0) q.meta.scyBy = b.i
                return "scyk:${q.key}"
            }
        }
        return "scythe"
    }

    /**
     * Who takes a crab only worth one swing (both DPS due on it): the one with Death Charge up, then the one without
     * Vengeance (that one stays on her to take her hit), then the one without a special due on that swing, then the
     * nearer, then orb order.
     */
    private fun designated(q: Crab, tt: Int): Bot? {
        val due = crew.filter { it.dps != null && it.player.hitpoints > 0 && abs(it.next - tt) <= 1 && reachOf(q) > it.next && canHit(it, q, max(it.next, t + 1)) }
        if (due.size < 2) return due.firstOrNull()
        val dist = { d: Bot -> room.real(d.player.coords).let { maxOf(abs(it.x - ROOM_X - q.x), abs(ROOM_Y - it.z - q.y)) } }
        return due.minWithOrNull(
            compareBy<Bot>(
                { if (it.player.vars["varbit.arceuus_death_charge_active"] == 1) 0 else 1 },
                { if (it.player.vars["varbit.vengeance_rebound"] == 1) 1 else 0 },
                { if (atkOf(upcoming(it)).spec > 0) 1 else 0 },
                { dist(it) },
                { it.i },
            )
        )
    }

    /** A healthy older crab out of its freeze by the swing and walking in before the swing after (or thawing before the swing after, hit while it still stands), that the bot can reach. */
    private fun thawing(b: Bot, tt: Int): Crab? {
        val w = latestWave() ?: return null
        return crabs.filter { q ->
            q.wave != w && q.frozenTo > 0 && q.hp >= SPLIT_HP && ((q.frozenTo <= tt && reachOf(q) in tt + 1..tt + SWING) || preThaw(q, tt)) &&
                !freezing(q) && !clumpCovers(q, tt) && canHit(b, q, tt) && !(q.hp <= SHARED_HP && designated(q, tt)?.let { it !== b } == true)
        }.maxByOrNull { it.hp }
    }

    /**
     * The wave's unfrozen 1s / 2s in the order the bot goes for them: the one reaching her first (both DPS on it), then
     * the other - unless the other walks in before the swing after, when the melee takes it and the ranger the first (not
     * the 50s N1 and N2: both kill the N1, and the N2 is the melee's Dinh's).
     */
    private fun leftOrder(b: Bot, w: Int, tt: Int): List<String> {
        val plan = planOf(w)
        val rch = { x: String -> crabRef("$x@$w")?.let { reachOf(it) } ?: -1 }
        val lord = plan.lord ?: plan.left.sortedBy(rch).also { plan.lord = it }
        if (b.dps != MELEE_DPS || lord.size < 2) return lord
        val done = b.dpsD[w].orEmpty()
        val (first, second) = lord[0] to lord[1]
        if (w == WAVES[1] && lord.toSet() == N_PAIR && rch(second) > tt + SWING + 1) return lord
        if (first in done || second in done || rch(second) !in tt + 1..tt + SWING) return lord
        return listOf(second, first) + lord.drop(2)
    }

    /**
     * With both DPS on the wave's one unfrozen crab, the melee takes a healthy older crab that thaws and walks in before
     * its next swing instead (the ranger keeps the new one).
     */
    private fun splitStraggler(b: Bot, tt: Int): Crab? {
        if (b.dps != MELEE_DPS) return null
        val w = latestWave() ?: return null
        if (planOf(w).left.size != 1 || crew.none { it.dps != null && it !== b && it.player.hitpoints > 0 }) return null
        return crabs.filter { q ->
            q.wave != w && q.frozenTo <= tt && q.hp >= SPLIT_HP && reachOf(q) in tt + 1..tt + SWING && !pushPast(q) && !freezing(q) && !clumpCovers(q, tt)
        }.maxByOrNull { it.hp }
    }

    /**
     * Whether the bot's swing on the crab goes when it's due: from within a run of a tile beside it (two tiles a tick,
     * up to the swing; the game walks it in and swings the same tick), before the crab reaches her.
     */
    private fun canHit(b: Bot, q: Crab, tt: Int): Boolean {
        val me = room.real(b.player.coords).let { intArrayOf(it.x - ROOM_X, ROOM_Y - it.z) }
        if (tt + 1 > reachOf(q)) return false
        var x = q.x
        var y = q.y
        for (k in t + 1..tt) {
            if (k <= q.frozenTo || adjacent(x, y)) continue
            val sw = y + 1
            x -= 1
            if (sw < SWROW) y++ else if (sw > SWROW) y--
        }
        val gap = maxOf(x - me[0], me[0] - (x + 1), y - me[1], me[1] - (y + 1), 0)
        return gap - 1 <= 2 * max(lead, tt - t)
    }

    /**
     * A healthy single still frozen when the swing lands, thawing before the swing after: once it walks it's out of
     * reach before it heals her, so players hit it on the last swing while it stands.
     */
    private fun preThaw(q: Crab, tt: Int): Boolean =
        q.frozenTo in tt + 1..tt + SWING && q.base !in THREES && q.base !in FOURS && q.hp >= SPLIT_HP && !pushPast(q)

    private fun clumpOf(q: Crab): String = if (q.base in THREES) "3s" else "4s"

    /** jaggers_0's lines by her Defence: a full scythe on a crab over red; leaking as it lands, the cleave from its south over blue, north over magenta. */
    private fun red(): Int = RED_STEPS.indexOfFirst { her.defenceLvl < it }.let { if (it < 0) 21 else 26 - it }

    private fun blue(): Int = BLUE_STEPS.indexOfFirst { her.defenceLvl < it }.let { if (it < 0) 15 else 18 - it }

    private fun magenta(): Int = MAG_STEPS.indexOfFirst { her.defenceLvl < it }.let { if (it < 0) 35 else 40 - it }

    private fun pushPast(q: Crab): Boolean {
        if (phase == 0) return reachOf(q) - t >= killTicks()
        return phase == 30 && q.frozenTo > 0 && q.hp < 50 && crabs.any { it !== q && inArea(it, q) && it.frozenTo <= q.frozenTo }
    }

    /** Roughly how long she lasts once the procs are done, with all three on her (a crab reaching her later doesn't matter). */
    private fun killTicks(): Int = ceil(hp / (crew.count { it.player.hitpoints > 0 } * SCYTHE_MEAN / SWING)).toInt()

    /** A barrage on its way catches the crab (its 3x3 as the crab stands): it's as good as frozen. */
    private fun freezing(q: Crab): Boolean =
        barrages.any { (land, npc) -> land > q.immuneTo && crabs.firstOrNull { it.npc === npc }?.let { inArea(it, q) } == true }

    private fun clumpCovers(q: Crab, tt: Int): Boolean {
        val w = latestWave() ?: return false
        if (q.wave == w) return false
        if (q.frozenTo < 0 && clumpOf(q).let { k -> (q.base in THREES || q.base in FOURS) && mage.tfzD[q.wave]?.contains(k) != true }) return true
        val done = mage.tfzD[w]
        val lim = reachOf(q)
        for (which in listOf("3s", "4s")) {
            if (done?.contains(which) == true) continue
            for (c in ofWave(w, if (which == "3s") THREES else FOURS)) {
                var x = c.x
                var y = c.y
                var k = maxOf(tt, c.frozenTo, c.born)
                var n = 0
                while (n < WALK_LIMIT && !adjacent(x, y)) {
                    if (abs(x - q.x) <= 1 && abs(y - q.y) <= 1 && k + 2 < lim && ((k > q.frozenTo && k > q.immuneTo) || q.hp <= 25)) return true
                    val sw = y + 1
                    x -= 1
                    if (sw < SWROW) y++ else if (sw > SWROW) y--
                    k++
                    n++
                }
            }
        }
        return false
    }

    /**
     * Crabs being hit (in flight, or ordered this tick); barrages count only before they're cast, standing in for the
     * sim's freeze going on at once - a cast one has frozen its crab, and the sim's Dinh's pick never skips them.
     */
    private fun inflightKeys(tt: Int, ordered: Boolean = true): Set<String> {
        val npcs = inflight.filter { it.land >= tt && it.crab != null }.map { it.crab } + crew.mapNotNull { b -> b.order?.takeIf { ordered || it.move != Move.Barrage }?.target?.takeIf { it !== her } }
        return crabs.filter { it.npc in npcs }.map { it.key }.toSet()
    }

    /* ---- the melee's Dinh's */

    /**
     * The sim's Dinh's pick: on a 1/2/3 the barrages won't cleave, from the side of it whose 11x11 takes in the most crabs
     * (then the most value), or on her from her east side; a crab walking in alone counts at triple value (every point it
     * keeps heals her two), one in the pile or frozen at a fraction. [ahead]: the crabs walked on that many ticks. With
     * [from], only spots that bot runs to in time ([run] tiles): a Dinh's never waits on a walk.
     */
    private fun dinPick(ahead: Int, skip: Set<String>? = null, from: Bot? = null, run: Int = RUN): DinOpt? {
        val me = from?.let { room.real(it.player.coords) }?.let { intArrayOf(it.x - ROOM_X, ROOM_Y - it.z) }
        val far = { x: Int, y: Int -> me != null && maxOf(abs(x - me[0]), abs(y - me[1])) > run }
        val tt = curT + ahead
        val live =
            crabs.filter { !(skip != null && it.key in skip && it.hp <= 30) }.map { q0 ->
                var q = if (skip != null && q0.key in skip) q0.copy(hp = q0.hp - 30) else q0
                if (ahead > 0) {
                    var x = q.x
                    var y = q.y
                    for (k in 1..ahead) {
                        if (curT + k <= q.frozenTo) continue
                        val sw = y + 1
                        x -= 1
                        if (sw < SWROW) y++ else if (sw > SWROW) y--
                    }
                    q = q.copy(x = x, y = y)
                }
                q
            }.filter { !adjacent(it.x, it.y) }
        val w0 = latestWave()
        val md = mage.tfzD
        val fz0 = w0?.let { planOf(it).fz } ?: emptyList()
        val clumpDone = { q: Crab -> md[q.wave]?.contains("4s") == true }
        val big = { q: Crab -> q.base in THREES || q.base in FOURS }
        val toFreeze = { q: Crab ->
            q.wave == w0 && tt > q.frozenTo && ((q.id in fz0 && md[q.wave]?.contains(q.id) != true) || (big(q) && md[q.wave]?.contains(clumpOf(q)) != true))
        }
        val inPile = { q: Crab -> toFreeze(q) || big(q) || (q.meta.rushed && !clumpDone(q)) || live.any { r -> r !== q && big(r) && inArea(r, q) } }
        val weight = { q: Crab ->
            if (inPile(q)) {
                if (tt > q.frozenTo && big(q)) (if (q.wave != w0) params.walkWeight / 10.0 else W_PILE) else W_PILE
            } else if (tt > q.frozenTo) params.walkWeight / 10.0 else W_OUT
        }
        val near = { x: Int, y: Int, q: Crab -> abs(q.x - x) <= 5 && abs(q.y + 1 - y) <= 5 }
        val worth = { tgt: Crab?, x: Int, y: Int ->
            val first = if (tgt == null) SPLAT.toDouble() else weight(tgt) * min(tgt.hp, 2 * SPLAT)
            first + live.filter { it !== tgt && near(x, y, it) }.map { weight(it) * min(it.hp, SPLAT) }.sortedDescending().take(DINH_HITS - 1).sum()
        }
        val blocked = { x: Int, y: Int -> x < 0 || y < 0 || x > ROOM_W || y > ROOM_H || (x in MT_X..MX1 && y in MT_Y..MY1) }
        val under = { x: Int, y: Int -> live.any { x >= it.x && x <= it.x + 1 && y >= it.y && y <= it.y + 1 } }
        val opts = ArrayList<DinOpt>()
        for (c in live.filter { it.base in DINH_POOL && !(it.meta.rushed && !clumpDone(it)) && skip?.contains(it.key) != true && it.hp > SAFE_HP && (crabRef(it.key)?.let { q -> reachOf(q) > tt + 1 } ?: true) }) {
            val sides = if (c.id == "S2") listOf("west") else if (c.id == "S1") listOf("north", "west") else listOf("north", "south", "west", "east")
            var best: DinOpt? = null
            for (sd in sides) {
                val tiles =
                    when (sd) {
                        "north" -> listOf(c.x to c.y - 1, c.x + 1 to c.y - 1)
                        "south" -> listOf(c.x to c.y + 2, c.x + 1 to c.y + 2)
                        "west" -> listOf(c.x - 1 to c.y, c.x - 1 to c.y + 1)
                        else -> listOf(c.x + 2 to c.y, c.x + 2 to c.y + 1)
                    }
                for ((x, y) in tiles) {
                    if (blocked(x, y) || under(x, y) || far(x, y)) continue
                    val n = live.count { near(x, y, it) }
                    if (n < 2) continue
                    val sc = worth(c, x, y) + if (c.wave == w0) 0.5 else 0.0
                    if (best == null || n > best.n || (n == best.n && sc > best.value)) best = DinOpt(c.key, intArrayOf(x, y), n, sc)
                }
            }
            best?.let { opts += it }
        }
        var onHer: DinOpt? = null
        for (y in MT_Y..MY1) {
            val x = MX1 + 1
            if (blocked(x, y) || under(x, y) || far(x, y)) continue
            val n = live.count { near(x, y, it) }
            val sc = worth(null, x, y)
            if (onHer == null || n > onHer.n || (n == onHer.n && sc > onHer.value)) onHer = DinOpt("maiden", intArrayOf(x, y), n, sc)
        }
        onHer?.let { opts += it }
        val maxN = opts.maxOfOrNull { it.n } ?: 0
        return opts.filter { it.n >= maxN - 1 }.maxByOrNull { it.value }
    }

    /* ---- barrages */

    private fun inArea(a: Crab, b: Crab): Boolean = abs(a.x - b.x) <= 1 && abs(a.y - b.y) <= 1

    private fun chunkOrder(cx: Int, cy: Int, x: Int, y: Int): Int {
        val dc = x / CHUNK - cx / CHUNK
        val dr = Math.floorDiv(y - CHUNK_Y0, CHUNK) - Math.floorDiv(cy - CHUNK_Y0, CHUNK)
        return if (abs(dc) > 1 || abs(dr) > 1) FAR_CHUNK else (1 - dc) * 3 + (dr + 1) + 1
    }

    private fun aoe3(prim: Crab): List<Crab> =
        listOf(prim) +
            crabs.filter { it !== prim && inArea(prim, it) }
                .sortedWith(compareBy<Crab> { chunkOrder(prim.x, prim.y + 1, it.x, it.y + 1) }.thenByDescending { it.meta.entered }.thenBy { it.meta.idx })
                .take(BARRAGE_CAP - 1)

    private fun barragePrim(id: String, tt: Int): Crab? {
        if (id == "old" || id == "pile") {
            val w = latestWave()
            return crabs.filter { id == "pile" || it.wave != w }.maxByOrNull { c ->
                val n = aoe3(c)
                n.count { tt > it.immuneTo && tt > it.frozenTo } * 30 + n.sumOf { it.hp }
            }
        }
        if (id == "3s" || id == "4s") {
            val w = latestWave() ?: return null
            return ofWave(w, if (id == "3s") THREES else FOURS).maxByOrNull { c ->
                val n = aoe3(c)
                n.count { tt > it.immuneTo && tt > it.frozenTo } * 100 + n.size
            }
        }
        return crabRef(id)
    }

    /**
     * The sim's `pileWorth`: what a barrage on the best pile keeps from healing her (up to 20 a crab) - with [horizon],
     * only crabs that reach her before the next proc's clump barrage could cleave them (her HP to that proc at the rate
     * it's gone since the last one, at least 22 a tick, plus 12).
     */
    private fun pileWorth(tt: Int, horizon: Boolean, id: String = "pile"): Pair<Crab?, Int> {
        val c = barragePrim(id, tt) ?: return null to 0
        val since = if (phase == 30) proc50 else if (phase == 50) proc70 else null
        val rate = if (since != null && tt - since >= 5) maxOf(PROC_RATE, (full * (phase + 20) / 100.0 - hp) / (tt - since)) else PROC_RATE
        val eta = if (phase > 0) tt + maxOf(0.0, hp - full * phase / 100.0) / rate + 12 else Double.MAX_VALUE
        return c to aoe3(c).filter { !horizon || reachOf(it) <= eta }.sumOf { min(it.hp, PILE_CRAB_CAP) }
    }

    /* ---- crabs */

    private fun crabRef(ref: String): Crab? {
        if (ref.contains('@')) return crabs.firstOrNull { it.key == ref }
        val w = WAVES.lastOrNull { spawned[it]?.contains(ref) == true } ?: return null
        return crabs.firstOrNull { it.id == ref && it.wave == w }
    }

    private fun adjacent(x: Int, y: Int): Boolean {
        val gx = maxOf(MT_X - (x + 1), x - MX1, 0)
        val gy = maxOf(MT_Y - (y + 1), y - MY1, 0)
        return maxOf(gx, gy) <= 1 && !(gx == 1 && gy == 1)
    }

    /** The tick the crab reaches her: walking a tile a tick (west, and a row toward hers) once its freeze ends. */
    private fun reachOf(c: Crab): Int {
        var x = c.x
        var y = c.y
        var n = 0
        while (!adjacent(x, y) && n < WALK_LIMIT) {
            val sw = y + 1
            x -= 1
            if (sw < SWROW) y++ else if (sw > SWROW) y--
            n++
        }
        return maxOf(t + 1, c.frozenTo + 1, c.born + 1) + n - 1
    }

    /* ---- carrying it out */

    private fun drink(b: Bot) {
        if (bots.useItem(b.player, NyloLoadout.SURGE.asRSCM(RSCMType.OBJ))) b.surged = true
    }

    private fun perform(b: Bot, a: String, tt: Int) {
        val k = a.substringBefore(':')
        val ref = a.split(':').getOrNull(1)
        val crab = ref?.let { if (k == "ibs" || k == "ir") barragePrim(it, tt) else crabRef(it) }
        when (k) {
            "wait" -> hold(b)
            "tbr" -> act(b, Move.Tbow, her)
            "bp" -> act(b, Move.Blowpipe, her)
            "ax" -> act(b, Move.Crossbows, crab?.npc ?: her)
            "ralos" -> act(b, Move.Ralos, her)
            "zcbs" -> act(b, Move.Zcb, her)
            "claws" -> act(b, Move.Claws, her)
            "scyk", "scyc", "scys" -> crab?.let { act(b, Move.Scythe, it.npc) } ?: hold(b)
            "ibs" -> crab?.let { act(b, Move.Barrage, it.npc) } ?: hold(b)
            "ir" -> crab?.let {
                it.meta.rushed = true
                act(b, Move.Rush, it.npc)
            } ?: hold(b)
            "aya" -> act(b, Move.Tome, crab?.npc ?: her)
            "obl" -> {
                val (x, y) = a.split(':')[2].split(',').map { it.toInt() }
                crab?.let { act(b, Move.Obligator, it.npc, CoordGrid(x + ROOM_X, ROOM_Y - y, 0)) } ?: hold(b)
            }
            "dinhs" -> {
                val target = if (ref == "maiden") her else crab?.npc
                b.dinPos = null
                b.dinNext = null
                if (target == null) hold(b) else act(b, Move.Dinh, target)
            }
            else -> act(b, Move.Scythe, her)
        }
    }

    private fun hold(b: Bot) {
        b.player.clearInteractionRoute()
        b.order = null
        b.route.clear()
    }

    /**
     * A scythe on her from off her side: the game walks to the nearest tile beside her, blood or not. When blood is that
     * near, the bot runs to a clean one itself first - if its swing isn't due yet (the run costs nothing).
     */
    private fun cleanBeside(b: Bot): CoordGrid? {
        val here = room.real(b.player.coords)
        if (room.besideHer(here)) return null
        val ticks = b.player.actionDelay - clock.cycle
        if (ticks <= 0) return null
        val dist = { c: CoordGrid -> maxOf(abs(c.x - here.x), abs(c.z - here.z)) }
        val open =
            (-REACH..REACH).flatMap { dx -> (-REACH..REACH).map { dz -> CoordGrid(here.x + dx, here.z + dz, here.level) } }
                .filter { room.besideHer(it) && room.inArena(it) && !room.under(it) }
        val nearest = open.minOfOrNull(dist) ?: return null
        if (open.none { dist(it) <= nearest && room.bloody(it) }) return null
        return open.filter { !room.bloody(it) && dist(it) <= RUN * ticks }.minByOrNull(dist)
    }

    /** Walks [b] to [dest] (a real tile) never ending a tick in her blood: round it when the way runs through, two tiles a tick. */
    private fun walk(b: Bot, dest: CoordGrid) {
        b.route.clear()
        b.routeAt = null
        val path = safePath(room.real(b.player.coords), dest)
        if (path == null || path.size <= RUN) return bots.walk(b.player, room.at(dest))
        b.route.addAll(path)
        stepRoute(b)
    }

    /** The route's next run (its tile at the end of this tick), while the bot is still where the last one left it. */
    private fun stepRoute(b: Bot) {
        if (b.route.isEmpty()) return
        val at = b.routeAt
        if (at != null && room.real(b.player.coords) != at) {
            b.route.clear()
            return
        }
        var end = b.route.removeFirst()
        repeat(RUN - 1) { if (b.route.isNotEmpty()) end = b.route.removeFirst() }
        b.routeAt = end
        bots.walk(b.player, room.at(end))
    }

    /**
     * The shortest walk from [from] to [to] round her whose tiles at the end of each tick (every second step, and the
     * last) are clear of blood; null when there's none.
     */
    private fun safePath(from: CoordGrid, to: CoordGrid): List<CoordGrid>? {
        if (from == to) return null
        val open = { c: CoordGrid -> room.inArena(c) && !room.under(c) }
        val prev = HashMap<Pair<CoordGrid, Int>, Pair<CoordGrid, Int>?>()
        val queue = ArrayDeque<Pair<CoordGrid, Int>>()
        val start = from to 0
        prev[start] = null
        queue += start
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val (c, step) = cur
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val n = CoordGrid(c.x + dx, c.z + dz, c.level)
                if (!open(n) || (dx != 0 && dz != 0 && (!open(CoordGrid(c.x + dx, c.z, c.level)) || !open(CoordGrid(c.x, c.z + dz, c.level))))) continue
                val s = (step + 1) % RUN
                if ((s == 0 || n == to) && room.bloody(n) && n != to) continue
                val key = n to s
                if (key in prev) continue
                prev[key] = cur
                if (n == to) {
                    val path = ArrayList<CoordGrid>()
                    var k: Pair<CoordGrid, Int>? = key
                    while (k != null && k != start) {
                        path += k.first
                        k = prev[k]
                    }
                    return path.reversed()
                }
                queue += key
            }
        }
        return null
    }

    private fun act(b: Bot, move: Move, target: Npc, walkTo: CoordGrid? = null) {
        val p = b.player
        val to = walkTo ?: if (move == Move.Scythe && target === her) cleanBeside(b) else null
        // a special picked a tick early is clicked on the tick it's ready: switched in sooner, its spec toggle doesn't hold
        val o = Order(move, target, t, to?.takeIf { room.real(p.coords) != it }, max(clock.cycle, p.actionDelay), held = move.spec && p.actionDelay > clock.cycle)
        b.order = o
        val dest = o.walkTo
        if (o.held) {
            // on its way in now with what it holds, so it's beside the target when the special goes
            if (dest != null) walk(b, dest) else bots.attack(p, target)
            return
        }
        if (dest == null) fire(b, o) else walk(b, dest)
    }

    private fun fire(b: Bot, o: Order) {
        val p = b.player
        o.walkTo = null
        b.route.clear()
        b.aimed = o.target
        b.aimedFor = max(clock.cycle, p.actionDelay)
        trace { "t$t ${b.role} fires ${o.move} at ${crabs.firstOrNull { it.npc === o.target }?.key ?: "her"} from ${room.real(p.coords).let { "${it.x - ROOM_X},${ROOM_Y - it.z}" }} energy ${b.spec} delay ${p.actionDelay - clock.cycle} weapon ${p.worn[3]?.id}" }
        for ((slot, obj) in o.move.set.worn) {
            val id = obj.asRSCM(RSCMType.OBJ)
            if (p.worn[slot.slot]?.id != id) bots.equip(p, slot.slot, id)
        }
        o.move.ammo?.asRSCM(RSCMType.OBJ)?.let { id -> if (p.worn[Wearpos.Quiver.slot]?.id != id && p.inv.any { it?.id == id }) bots.equip(p, Wearpos.Quiver.slot, id) }
        if (o.move != Move.Barrage && o.move != Move.Rush) VarPlayerIntMapSetter.set(p, "varp.com_mode", o.move.stance)
        if (o.move.spec) VarPlayerIntMapSetter.set(p, "varp.sa_attack", 1)
        when (o.move) {
            Move.Barrage -> bots.cast(p, o.target, barrage)
            Move.Rush -> bots.cast(p, o.target, rush)
            else -> if (current(p) !== o.target || o.move.spec) bots.attack(p, o.target)
        }
    }

    private fun current(p: Player): Npc? = (p.interaction as? InteractionNpc)?.target

    private fun atkOf(a: String): Atk = ATTACKS[a.substringBefore(':')] ?: ATTACKS.getValue("scythe")

    private fun weaponOf(a: String): String? = WEAPON[a.substringBefore(':')]

    private inline fun trace(text: () -> String) {
        if (TRACING) println("maiden ${crew[0].player.username} ${text()}")
    }

    private companion object {
        val TRACING = System.getenv("TOB_TRIO_TRACE") != null
        const val SPELLBOOK_SWAP = "component.magic_spellbook:spellbook_swap"
        const val SWAP_TO_ARCEUUS = 4
        const val GREATER_GHOST = "component.magic_spellbook:resurrect_greater_ghost"
        const val VENGEANCE = "component.magic_spellbook:vengeance"
        const val DEATH_CHARGE = "component.magic_spellbook:death_charge"
        const val MARK_OF_DARKNESS = "component.magic_spellbook:mark_of_darkness"
        const val STALL_TICKS = 4
        const val PROTECT_MAGIC = "varbit.prayer_protectfrommagic"
        const val ANGLER_PERCENT = 10
        const val ANGLER_TOP = 13
        val ANGLER_STEPS = listOf(25 to 2, 50 to 4, 75 to 6, 93 to 8)
        const val REBOUND = 0.75
        const val REBOUND_STEPS = 4
        const val PHI_K = 1.702
        const val LUNARS = 2
        const val ARCEUUS = 3
        const val DEATH_CHARGES = 2
        /** The sim summons the thrall between the first prefire and the first melee hit. */
        const val THRALL_AFTER = 1
        val START_TILES = listOf(CoordGrid(3184, 4445, 0), CoordGrid(3184, 4447, 0), CoordGrid(3184, 4448, 0))

        /** The sim's room: x east from 3160, y south from 4458 (a 2x2 by its north-west tile); her 6x6 at (2, 9). */
        const val ROOM_X = 3160
        const val ROOM_Y = 4458
        const val ROOM_W = 27
        const val ROOM_H = 23
        const val MT_X = 2
        const val MT_Y = 9
        const val MX1 = 7
        const val MY1 = 14
        const val SWROW = 14
        const val TBOW_GAP = 10
        const val MAGE_ROW = 11
        const val MAGE_ROWS = 3
        const val SWING = 5
        const val POST_NEAR = 2
        const val SPLIT_HP = 40
        const val PROC_SEEN = 2
        const val STACK_HP = 100

        /** Mean damage a thrust does each crab it pierces, and a scythe does the one crab it's aimed at (our recordings). */
        const val OBL_CRAB = 30
        const val SCY_CRAB = 30
        const val MELEE_DPS = 1
        const val STAND_SEARCH = 14
        const val CHUNK = 8
        const val CHUNK_Y0 = 3
        const val FAR_CHUNK = 99
        const val MAX_IDX = 32768
        const val WALK_LIMIT = 60

        val WAVES = listOf(70, 50, 30)
        val NEXT_PROC = listOf(70, 50, 30, 0)
        val THREES = listOf("N3", "S3")
        val FOURS = listOf("N4", "S4")
        val THREE_RE = Regex("[NS]3")
        val FOUR_RE = Regex("[NS]4[ab]?")
        val S2_RE = Regex("S2")
        val GROUPS = setOf("3s", "4s", "old", "pile")
        val DINH_POOL = setOf("N1", "N2", "N3", "S1", "S2", "S3")
        val DINH_LINES = setOf("tdin", "dinhs")
        const val THREES_DUE = 11

        /** The last tick a barrage still catches a crab before it reaches her, and a freeze's worth on a 1 / 2 the DPS can reach. */
        const val FZ_LAND = 1
        const val LATE_N2 = "n2late"

        /** An ice rush on the S2 can go a tick late and still hold it. */
        const val S2_SLACK = 1
        const val COVERED_SHARE = 0.35
        const val ZCB_CAP = 110
        const val ZCB_PCT = 22

        /** A dragon claw spec's total on her (the mage's, 55 specs measured); a ZCB spec is its ruby proc alone. */
        const val CLAW_MEAN = 71.6
        const val CLAW_SD = 13.4
        const val FOURS_DUE = 16
        const val OLD_PILE_HP = 100
        const val PILE_CRAB_CAP = 20
        const val PROC_RATE = 22.0
        const val BARRAGE_CAP = 9
        const val SPLAT = 21
        const val DINH_HITS = 10
        const val DINH_REACH = 5
        const val DINH_COST = 50
        const val SHARED_HP = 60
        /** The 50s with both its N1 and N2 left to the DPS: both kill the N1, then the melee Dinh's the N2. */
        private val N_PAIR = setOf("N1", "N2")
        /** Dinh's worth per hp on a pile crab (barrages and cleave get it anyway) and on one frozen out of the pile (the double hit may save a scythe on it later). */
        const val W_PILE = 0.3
        const val W_OUT = 1.2
        const val ORDER_TICKS = 6
        const val FLINCH = 2
        /** Hitpoints over a prayed max hit that a tank keeps for the blood under it. */
        const val TANK_MARGIN = 5
        const val PREDODGE_TICKS = 2

        /** Tiles a bot runs in the tick before its swing (the game walks it in and swings on the same tick). */
        const val RUN = 2

        /** A surge potion's special attack energy. */
        const val SURGE_SPEC = 25
        const val DC_COOLDOWN = "varbit.arceuus_death_charge_cooldown"
        /** Spellbook Swap's stall: the ticks after the cast that it locks. */
        const val SHORT_STALL = 3
        const val DC_EXPIRE = "queue.death_charge_expire"
        /** The Death Charge the DPS cast outside: about 55 of its 60 seconds left when the room starts. */
        const val DC_LEFT = 92

        /** A crab with no more than this left can die to a hit landing first, taking a Dinh's on it as its target with it. */
        const val SAFE_HP = 30

        /** Tiles it runs to its Dinh's spot between swings. */
        const val PLAN_RUN = 6

        /** In over the barrier on tick 0, the sim's players start running in on tick 1 (a tbow prefire on 5). */
        const val FIRST_ACT = 1
        const val FULL_RUN = 10000
        const val REACH = 8

        /** The tiles out from her each ranged attack on her goes from. */
        val REACH_OF = mapOf("ralos" to 7, "zcbs" to 8, "zcb" to 8, "bp" to 5, "ax" to 7)
        val FLICK = listOf(dev.openrune.util.Wearpos.RightHand to "obj.zaryte_xbow", dev.openrune.util.Wearpos.Back to "obj.dizanas_quiver_infinite")
        const val WALK_TICKS = 3
        const val TALLIED = 4
        const val OBL_REACH = 3
        const val OBL_MAX = 3
        const val DODGE = 2
        const val RISK_Z = 0.674
        const val STAND_PCT = 1.5
        val SCYTHE_LINES = setOf("scythe", "tdps")
        const val SCYTHE_MEAN = 45.1
        const val TBOW_MEAN = 43.5
        const val TBOW_SD = 25.0
        const val SCYTHE_SD = 17.6
        const val SCYTHE = "scythe"
        const val DINH_WEAPON = "dinh"
        val RED_STEPS = listOf(13, 52, 92, 132, 173)
        val BLUE_STEPS = listOf(53, 110, 168)
        val MAG_STEPS = listOf(13, 52, 92, 133, 173)

        val DPS_SLOT = mapOf(NyloRole.Ranger to 0, NyloRole.Melee to 1)

        /** The sim's trio lists (ROLE_DEFAULT[3] as `procedureOpts(3)` plays them): the prefires, then the 70s, 50s and 30s. */
        val LISTS =
            mapOf(
                NyloRole.Mage to listOf(
                    listOf("tbr", "ralos", "ax", "tbr"),
                    listOf("tfz", "tfz", "tfz", "tfz", "tfz", "surge", "zcbs", "tbr"),
                    listOf("tfz", "tfz", "tfz", "tfz", "tfz", "tpile"),
                    listOf("tfz", "tfz", "tfz", "tfz", "tfz", "tpile", "scythe"),
                ),
                NyloRole.Ranger to listOf(
                    listOf("tbr", "ralos", "scythe"),
                    listOf("tdps"),
                    listOf("tdps", "surge", "zcbs", "tdps"),
                    listOf("tdps"),
                ),
                NyloRole.Melee to listOf(
                    listOf("tbr", "ralos", "scythe"),
                    listOf("tdps"),
                    listOf("tdin", "surge", "tdin2", "tdps"),
                    listOf("tdps", "zcbs", "tdps"),
                ),
            )

        val OWNS =
            mapOf(
                NyloRole.Mage to setOf("scythe", "tbow", "ralos", "xbows", "zcb", "claws"),
                NyloRole.Ranger to setOf("scythe", "tbow", "ralos", "bp", "zcb"),
                NyloRole.Melee to setOf("scythe", "tbow", "ralos", "bp", "zcb", "dinh", "obligator"),
            )

        val WEAPON =
            mapOf(
                "tbr" to "tbow", "bp" to "bp", "ax" to "xbows", "ralos" to "ralos", "zcbs" to "zcb", "claws" to "claws", "scythe" to "scythe", "scyk" to "scythe",
                "scyc" to "scythe", "scys" to "scythe", "tdps" to "scythe", "tdin" to "scythe", "tdin2" to "scythe", "dinhs" to "dinh",
            )

        /** Style: r ranged, m melee, f a freeze, g magic, - no attack. */
        val ATTACKS =
            mapOf(
                "tbr" to Atk(5, 'r'), "bp" to Atk(2, 'r'), "ax" to Atk(2, 'r'), "ralos" to Atk(6, 'r', spec = 50), "zcbs" to Atk(5, 'r', spec = 75), "claws" to Atk(4, 'm', spec = 50),
                "scythe" to Atk(5, 'm'), "scyk" to Atk(5, 'm', true), "scyc" to Atk(5, 'm', true), "scys" to Atk(5, 'm', true),
                "tdps" to Atk(5, 'm', true), "tdin" to Atk(5, 'm'), "tdin2" to Atk(5, 'm'), "dinhs" to Atk(5, 'm', true, 50), "obl" to Atk(5, 'm', true),
                "tfz" to Atk(5, 'f', true), "ibs" to Atk(5, 'f', true), "ir" to Atk(5, 'f', true), "aya" to Atk(3, 'g', true),
                "tpile" to Atk(5, 'r'), "wait" to Atk(1, '-'), "surge" to Atk(0, '-'),
            )

        val LAND_DELAY = mapOf(Move.Tbow to 3, Move.Blowpipe to 2, Move.Crossbows to 2, Move.Zcb to 3, Move.Ralos to 3, Move.Scythe to 1, Move.Obligator to 1, Move.Dinh to 1, Move.Claws to 1, Move.Barrage to 2)
        val MEAN_HIT = mapOf(Move.Tbow to (TBOW_MEAN to TBOW_SD), Move.Scythe to (SCYTHE_MEAN to SCYTHE_SD), Move.Blowpipe to (16.1 to 9.8), Move.Crossbows to (16.1 to 9.8), Move.Claws to (CLAW_MEAN to CLAW_SD))
    }
}

/**
 * The Maiden crew's choices (see [MaidenCrew]): the defaults are the sim's, plus the server's own (walking, the
 * Obligator); `parse` reads `name=value,...` and keeps the default for the rest.
 */
data class MaidenCrewParams(
    val lookahead: Int = 1,
    val pileWorth: Int = 45,
    val preloadWorth: Int = 15,
    val dinhHoldPct: Int = 97,
    val dinhCap: Int = 30,
    val secondDinhMin: Int = 4,
    val straggleTicks: Int = 6,
    val redOffset: Int = 0,
    val walkWeight: Int = 30,
    val dinhWalk: Int = 2,
    val dodgeLead: Int = 1,
    val casterGap: Int = 4,
    val stallRisk: Int = 15,
    val stallProcPct: Int = 50,
) {
    companion object {
        fun parse(text: String): MaidenCrewParams {
            val values = text.split(',').mapNotNull { it.split('=').takeIf { kv -> kv.size == 2 }?.let { (k, v) -> k.trim() to v.trim() } }.toMap()
            val d = MaidenCrewParams()
            fun int(name: String, default: Int) = values[name]?.toDoubleOrNull()?.toInt() ?: default
            return MaidenCrewParams(
                lookahead = int("lookahead", d.lookahead),
                pileWorth = int("pileWorth", d.pileWorth),
                preloadWorth = int("preloadWorth", d.preloadWorth),
                dinhHoldPct = int("dinhHoldPct", d.dinhHoldPct),
                dinhCap = int("dinhCap", d.dinhCap),
                secondDinhMin = int("secondDinhMin", d.secondDinhMin),
                straggleTicks = int("straggleTicks", d.straggleTicks),
                casterGap = int("casterGap", d.casterGap),
                stallRisk = int("stallRisk", d.stallRisk),
                stallProcPct = int("stallProcPct", d.stallProcPct),
                redOffset = int("redOffset", d.redOffset),
                walkWeight = int("walkWeight", d.walkWeight),
                dinhWalk = int("dinhWalk", d.dinhWalk),
                dodgeLead = int("dodgeLead", d.dodgeLead),
            )
        }
    }
}
