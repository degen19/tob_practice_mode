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
import kotlin.math.sign
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.done.hitmark_groups
import org.rsmod.api.config.refs.params
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.statHeal
import org.rsmod.api.player.stat.statRestore
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.content.raids.tob.bloat.BlertLooks
import org.rsmod.content.raids.tob.solo.NyloLooks
import org.rsmod.content.raids.tob.solo.TobHud
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
import org.rsmod.game.obj.Obj
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * A Verzik raid ([VerzikReplays]: one of Blert's fastest, or one of verzikSim's) played back 1:1: each bot stands on its player's recorded tile
 * every tick (walking one tile, running two, thrown back by her bounce, jumping otherwise), wears the recorded gear and
 * overhead, and shows each recorded attack as the server's combat would ([BlertLooks]); every NPC spawns, moves,
 * changes form and dies where and when it did, its hitpoints as recorded on our scale; Verzik's attacks, deaths,
 * rises, reds and purples look and sound as in the solo room ([VerzikLooks]). [botDrift] and [npcDrift] count the
 * ticks anything stood off its recorded tile, [hpDrift] the ticks her hitpoints weren't the raid's.
 */
class VerzikReplayRoom(
    val session: InstanceSession,
    val raid: VerzikReplays.Raid,
    private val at: (Int, Int) -> CoordGrid,
    private val instances: InstanceManager,
    private val npcRepo: NpcRepository,
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
    private val worldRepo: WorldRepository,
    private val collision: CollisionFlagMap,
) {
    val bots = ArrayList<Player>()
    val watchers = ArrayList<Player>()
    private val npcs = HashMap<Int, Npc>()
    private val shown = HashMap<Int, VerzikReplays.Step>()
    private val pillarLocs = HashMap<Int, LocInfo>()
    private val locs = ArrayList<LocInfo>()
    private val pending = ArrayList<Pair<Int, () -> Unit>>()
    private val bouncedAt = HashMap<Int, Int>()
    private val webs = ArrayList<Pair<Int, Pair<Npc, LocInfo>>>()
    private var websUntil = -1
    private val blocked = ArrayList<CoordGrid>()
    private var dawnOnFloor: Obj? = null
    private val dropsAt = raid.drops.groupBy { it.t }
    private val dawnDamage = raid.dawns.associate { (it.t to it.player) to it.damage }
    private val random = java.util.Random(raid.uuid.hashCode().toLong())

    private var clock = -PRE_TICKS
    private val looks = VerzikLooks(worldRepo, { at(it.x, it.z) }, ::later)
    private val attackLooks = BlertLooks(worldRepo)
    private val stepsAt = raid.npcs.mapIndexed { i, n -> i to n.steps.associateBy { it.t } }.toMap()
    private val spawnsAt = raid.npcs.indices.groupBy { raid.npcs[it].spawn }
    private val deathsAt = raid.npcs.indices.filter { raid.npcs[it].death != null }.groupBy { raid.npcs[it].death!! }
    private val attacksAt = raid.attacks.groupBy { it.t }
    private val spellsAt = raid.spells.groupBy { it.t }
    private val last = maxOf(raid.end, raid.npcs.maxOf { it.steps.last().t })
    private val usesAt = raid.uses.groupBy { it.t }
    private val lastHit = HashMap<Int, Int>()
    private var tank = -1
    private val herAt = raid.her.groupBy { it.t }
    private val gearAt = raid.gear.map { g -> g.groupBy { it.first } }
    private val overheadAt = raid.overheads.map { o -> o.associate { it.first to it.second } }
    private val herForms = HER_FORMS.map { it.asRSCM(RSCMType.NPC) }.toSet()
    private val crawlerStyle = CRAWLERS.mapKeys { it.key.asRSCM(RSCMType.NPC) }
    private val her = raid.npcs.indices.firstOrNull { i -> raid.npcs[i].steps.any { it.type in herForms } }

    /**
     * When each of her forms dies: Blert seldom has the tick her hitpoints reach 0, but every raid keeps the same gap
     * to her next form - P1 dies 4 ticks before her climb-down form, P2 and P3 2 ticks before the next one.
     */
    /** A nylocas that isn't killed turns on the spot (its detonate animation) for the ticks before it pops. */
    private val pivotsAt: Map<Int, List<Int>> by lazy {
        raid.npcs.indices.filter { i ->
            val n = raid.npcs[i]
            n.type0 in crawlerStyle && n.death != null && raid.attacks.none { it.target == i }
        }.groupBy { raid.npcs[it].death!! - (ServerCacheManager.getAnim(DETONATE_SEQ.asRSCM(RSCMType.SEQ))?.tickDuration ?: 2) }
    }

    private val herDeaths: Map<Int, Int> by lazy {
        val h = her ?: return@lazy emptyMap()
        val out = HashMap<Int, Int>()
        for ((a, b) in raid.npcs[h].steps.zipWithNext()) {
            val gap = DEATH_GAP[a.type] ?: continue
            if (b.type != a.type) out[b.t - gap] = a.type
        }
        out
    }

    var botDrift = 0
        private set
    var npcDrift = 0
        private set
    var hpDrift = 0
        private set
    var checked = 0
        private set

    val finished: Boolean
        get() = clock > last + END_HOLD

    fun spot(i: Int): CoordGrid = at(raid.x(i, 0), raid.y(i, 0))

    /** The empty throne shows only once she's left it (her seated forms are drawn in it). */
    fun throne(visible: Boolean) {
        for (w in watchers) w.throneVisible = visible
    }

    fun start() {
        throne(false)
        bots.forEachIndexed { i, b ->
            for (slot in 0 until b.worn.size) b.worn[slot] = null
            wear(i, b, 0)
            overhead(i, b, 0)
        }
        spawnsAt[0]?.forEach { spawn(it) }
    }

    private fun fx(msg: String) {
        if (FX) println("fx ${raid.scale}s#${raid.rank} t$clock $msg")
    }

    private fun later(ticks: Int, action: () -> Unit) {
        pending += (clock + ticks.coerceAtLeast(1)) to action
    }

    fun tick() {
        clock++
        val t = clock
        if (t < 0) return
        if (t >= 1 && t - 1 <= raid.end) check(t - 1)
        val due = pending.filter { it.first <= t }
        pending.removeAll { it.first <= t }
        due.forEach { it.second() }
        if (t > last) {
            if (t == last + END_HOLD) remove()
            return
        }
        if (t > 0) {
            spawnsAt[t]?.let { list ->
                list.forEach { spawn(it) }
                arrivals(list)
            }
        }
        for (i in npcs.keys.toList()) {
            val npc = npcs[i] ?: continue
            npc.mode = NpcMode.None
            val step = stepsAt.getValue(i)[t]
            if (step != null) step(i, npc, step) else shown[i]?.let { hitpoints(npc, it) }
        }
        pivotsAt[t]?.forEach { i ->
            val n = npcs[i] ?: return@forEach
            val style = shown[i]?.type?.let { crawlerStyle[it] } ?: return@forEach
            n.routeDestination.clear()
            n.anim(NyloLooks.detonateSeq(style))
            fx("nylo pivots")
        }
        herDeaths[t]?.let { form -> her?.let { h -> npcs[h]?.let { v -> herDies(form, v, h) } } }
        deathsAt[t]?.forEach { die(it) }
        herAt[t]?.forEach { herAttack(it) }
        dropsAt[t]?.forEach { dawnbringer(it) }
        websTick(t)
        bots.forEachIndexed { i, b ->
            if (t > 0) {
                wear(i, b, t)
                overhead(i, b, t)
                move(b, i, t)
            }
            hitpoints(i, b, t)
            b.runEnergy = constants.run_max_energy
        }
        attacksAt[t]?.forEach { attack(it) }
        spellsAt[t]?.forEach { spell(it) }
        usesAt[t]?.forEach { use(it) }
        faces()
    }

    /** Her bar on the Theatre's HUD: her hitpoints in the form she's in. */
    fun progress(): TobHud.Bar? {
        val v = her?.let { npcs[it] }?.takeIf { it.isSlotAssigned && it.baseHitpointsLvl > 0 } ?: return null
        return TobHud.bar(TobHud.FIGHT, v.hitpoints, v.baseHitpointsLvl)
    }

    private fun check(t: Int) {
        checked++
        bots.forEachIndexed { i, b ->
            if (b.coords != at(raid.x(i, t), raid.y(i, t))) {
                botDrift++
                fx("bot $i off at t$t: at ${b.coords}, planned ${at(raid.x(i, t), raid.y(i, t))}")
            }
        }
        for ((i, npc) in npcs) {
            val s = shown[i] ?: continue
            if (npc.isSlotAssigned && npc.coords != at(s.x, s.y)) npcDrift++
            if (i == her && npc.isSlotAssigned && ours(s).second > 0 && npc.hitpoints != ours(s).first.coerceIn(0, ours(s).second)) hpDrift++
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
            bouncedAt[i]?.let { t - it <= BOUNCE_WINDOW } == true -> {
                val dx = sign((dest.x - b.coords.x).toDouble()).toInt()
                val dy = sign((dest.z - b.coords.z).toDouble()).toInt()
                val face =
                    when {
                        dx > 0 -> 1536
                        dx < 0 -> 512
                        dy > 0 -> 0
                        else -> 1024
                    }
                b.routeDestination.clear()
                PathingEntityCommon.exactMove(b, b.coords, dest, 0, 30, (face + 1024) % 2048, collision)
            }
            else -> PathingEntityCommon.telejump(b, collision, dest)
        }
    }

    /** The player's recorded hitpoints, a splat for each drop (full for the players Blert has none for). */
    private fun hitpoints(i: Int, b: Player, t: Int) {
        val now = raid.hp(i, t)
        if (now < 0) {
            if (b.hitpoints < b.baseHitpointsLvl) b.statRestore("stat.hitpoints")
            return
        }
        val before = b.hitpoints
        if (now == before) return
        if (now > before) {
            b.statHeal("stat.hitpoints", constant = now - before, percent = 0)
            return
        }
        b.statSub("stat.hitpoints", constant = before - now, percent = 0)
        fx("player $i hit ${before - now} hp $now")
        val id = hitmark_groups.regular_damage.lit.asRSCM(RSCMType.HITMARK)
        b.showHitmark(Hitmark.fromNoSource(self = id, source = id, public = id, damage = before - now, delay = 0))
        val bar = PLAYER_HEADBAR.asRSCM(RSCMType.HEADBAR)
        val fill = (PLAYER_HEADBAR_SEGMENTS * now.coerceAtLeast(0) / b.baseHitpointsLvl.coerceAtLeast(1)).coerceIn(0, PLAYER_HEADBAR_SEGMENTS)
        b.showHeadbar(Headbar.fromNoSource(bar, bar, fill, fill, 0, 0))
    }

    /** The Dawnbringer on the floor from the tick it's dropped until it's picked up, where Blert saw it. */
    private fun dawnbringer(d: VerzikReplays.Drop) {
        dawnOnFloor?.let { objRepo.del(it) }
        dawnOnFloor = if (d.dropped) objRepo.add(DAWNBRINGER, at(d.x, d.y), Int.MAX_VALUE) else null
        fx("dawn ${if (d.dropped) "dropped" else "picked"} at ${d.x},${d.y}")
    }

    /**
     * Her webs (Blert doesn't record them): the solo room's volleys - three every other tick from the first 30 ticks of
     * her spin, one on a player's tile and two within 3 of it, each landing as a web on the floor for 30 ticks - and
     * no one can stand under her until she's done.
     */
    private fun websTick(t: Int) {
        webs.removeAll { (born, w) ->
            val gone = t - born >= WEB_LIFE
            if (gone) {
                if (w.first.isSlotAssigned) npcRepo.del(w.first, Int.MAX_VALUE)
                locRepo.del(w.second, Int.MAX_VALUE)
                locs.remove(w.second)
            }
            gone
        }
        val v = her?.let { npcs[it] }
        val vs = her?.let { shown[it] }
        unblock()
        if (t > websUntil || v == null || vs == null) return
        block(CoordGrid(vs.x, vs.y, 0), v.size)
        if (t == websUntil - WEB_TICKS + 1) fx("blocked under her: ${blocked.size} tiles, flag ${collision[blocked[0].x, blocked[0].z, blocked[0].level] and CollisionFlag.BLOCK_PLAYERS != 0}")
        val left = websUntil - t
        if (left <= WEB_AFTER || left % 2 != 0 || bots.isEmpty()) return
        val p = random.nextInt(bots.size)
        val here = CoordGrid(raid.x(p, t), raid.y(p, t), 0)
        for (k in 0 until WEBS_A_VOLLEY) {
            val tile = if (k == 0) here else CoordGrid(here.x + random.nextInt(7) - 3, here.z + random.nextInt(7) - 3, 0)
            if (!inArena(tile) || under(tile, vs, v.size) || trodden(tile, t + 1, t + WEB_FLIGHT_MAX + WEB_LIFE)) continue
            val lands = looks.web(centre(vs, v.size), tile)
            later(lands) { web(tile) }
        }
    }

    /** Whether a player stands on [tile] on any tick [from]..[to] (a web there would catch them; they dodge it). */
    private fun trodden(tile: CoordGrid, from: Int, to: Int): Boolean =
        (from..minOf(to, raid.end)).any { u -> bots.indices.any { raid.x(it, u) == tile.x && raid.y(it, u) == tile.z } }

    private fun web(tile: CoordGrid) {
        if (webs.any { it.second.first.isSlotAssigned && it.second.first.coords == at(tile.x, tile.z) }) return
        val type = ServerCacheManager.getNpc(WEB_NPC.asRSCM(RSCMType.NPC)) ?: return
        val npc = Npc(type, at(tile.x, tile.z))
        npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        npc.ignoreCombatInteractions = true
        instances.attachNpc(session.id, npc)
        val loc = locRepo.add(at(tile.x, tile.z), WEB_LOC, Int.MAX_VALUE, LocAngle.West, LocShape.CentrepieceStraight)
        locs += loc
        webs += clock to (npc to loc)
        fx("web at ${tile.x},${tile.z} (${webs.size} up)")
    }

    private fun block(sw: CoordGrid, size: Int) {
        for (x in 0 until size) for (z in 0 until size) {
            val c = at(sw.x + x, sw.z + z)
            collision.add(c.x, c.z, c.level, CollisionFlag.BLOCK_PLAYERS)
            blocked += c
        }
    }

    private fun unblock() {
        for (c in blocked) collision.remove(c.x, c.z, c.level, CollisionFlag.BLOCK_PLAYERS)
        blocked.clear()
    }

    private fun under(tile: CoordGrid, sw: VerzikReplays.Step, size: Int) = tile.x in sw.x until sw.x + size && tile.z in sw.y until sw.y + size

    private fun inArena(c: CoordGrid) = c.x in ARENA_X && c.z in ARENA_Y

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

    private fun newNpc(i: Int, s: VerzikReplays.Step): Npc? {
        val id = if (s.type == INITIAL_QUICKSTART.asRSCM(RSCMType.NPC)) INITIAL.asRSCM(RSCMType.NPC) else s.type
        val type = ServerCacheManager.getNpc(id) ?: return null
        val npc = Npc(type, at(s.x, s.y))
        fx("spawn $id size ${type.size} at ${s.x},${s.y}")
        npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        npc.moveRestrict = MoveRestrict.PassThru
        npc.ignoreCombatInteractions = true
        npc.regenClock = Int.MAX_VALUE
        instances.attachNpc(session.id, npc)
        npcs[i] = npc
        shown[i] = s
        hitpoints(npc, s)
        return npc
    }

    private fun spawn(i: Int) {
        val first = raid.npcs[i].steps.first()
        newNpc(i, first) ?: return
        if (first.type == PILLAR.asRSCM(RSCMType.NPC)) {
            val loc = locRepo.add(at(first.x, first.y), PILLAR_LOC, Int.MAX_VALUE, LocAngle.West, LocShape.CentrepieceStraight)
            pillarLocs[i] = loc
            locs += loc
        }
    }

    /** What a tick's new NPCs bring: her summon and the crabs' climb out for reds, a purple's landing, her P3 summon. */
    private fun arrivals(list: List<Int>) {
        val v = her?.let { npcs[it] }
        val red = VerzikRules.RED.asRSCM(RSCMType.NPC)
        if (v != null && list.any { raid.npcs[it].type0 == red }) looks.summonReds(v)
        for (i in list) {
            val s = raid.npcs[i].steps.first()
            val n = npcs[i] ?: continue
            when (s.type) {
                red -> {
                    looks.spawning(CoordGrid(s.x, s.y, 0))
                    looks.redArrives(n)
                    fx("red spawn anim")
                }
                VerzikRules.PURPLE.asRSCM(RSCMType.NPC) -> {
                    looks.spawning(centre(s, n.size))
                    looks.purpleArrives(n)
                }
            }
        }
        if (v != null && v.visType.id == P3.asRSCM(RSCMType.NPC) && list.any { raid.npcs[it].type0 in crawlerStyle }) looks.summonCrawler(v)
    }

    private fun step(i: Int, npc: Npc, s: VerzikReplays.Step) {
        val was = shown[i] ?: s
        if (s.type != was.type) {
            form(i, npc, was, s)
            return
        }
        shown[i] = s
        val dest = at(s.x, s.y)
        val dist = max(abs(dest.x - npc.coords.x), abs(dest.z - npc.coords.z))
        if (dist == 1) npc.walk(dest) else if (dist > 1) npc.telejump(collision, dest)
        val (hpWas, _) = ours(was)
        val (hp, base) = hitpoints(npc, s)
        if (base <= 0) return
        if (hp < hpWas) hits(i, npc, hpWas - hp, s.t)
        if (hp > hpWas) {
            splat(npc, hp - hpWas, hitmark_groups.heal.lit)
            if (i == her) purpleNear()?.let { (p, ps) -> looks.purpleHeal(centre(ps, p.size), centre(s, npc.size)) }
        }
    }

    private fun herDies(form: Int, v: Npc, h: Int) {
        fx("her death anim on $form")
        val vs = shown[h] ?: return
        when (form) {
            P1.asRSCM(RSCMType.NPC) -> looks.p1Dies(v)
            P2.asRSCM(RSCMType.NPC) -> looks.p2Dies(v)
            P3.asRSCM(RSCMType.NPC) -> looks.p3Dies(v, centre(vs, v.size))
        }
    }

    /**
     * Her change of form as the solo room plays it: the new form comes in as a new NPC (so nothing of the last one's
     * animation carries over), the P2 rises a tick after it appears, the P2-to-P3 form plays her rise, the death bat comes
     * up in the middle of where she fell; her first form turns on the spot.
     */
    private fun form(i: Int, npc: Npc, was: VerzikReplays.Step, s: VerzikReplays.Step) {
        if (was.type == INITIAL.asRSCM(RSCMType.NPC) || was.type == INITIAL_QUICKSTART.asRSCM(RSCMType.NPC)) {
            ServerCacheManager.getNpc(s.type)?.let { npc.transmog(it, Int.MAX_VALUE) }
            shown[i] = s
            hitpoints(npc, s)
            return
        }
        if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE)
        val next = newNpc(i, s) ?: return
        if (s.type == P1_TO_P2.asRSCM(RSCMType.NPC)) throne(true)
        if (s.type == P2_TO_P3.asRSCM(RSCMType.NPC)) looks.p3Rises(next)
    }

    private fun hitpoints(npc: Npc, s: VerzikReplays.Step): Pair<Int, Int> {
        val (hp, base) = ours(s)
        if (base > 0) {
            npc.baseHitpointsLvl = base
            npc.hitpoints = hp.coerceIn(0, base)
        }
        return hp to base
    }

    /** Blert's hitpoints on our scale: its Verzik P2/P3 (and so red crab) bases are off; the fraction left is right. */
    private fun ours(s: VerzikReplays.Step): Pair<Int, Int> {
        if (s.base <= 0) return 0 to 0
        val p2 = VerzikRules.P2_HP.getValue(raid.scale)
        val base =
            when (s.type) {
                in P2_P3_FORMS.map { it.asRSCM(RSCMType.NPC) } -> p2
                VerzikRules.RED.asRSCM(RSCMType.NPC) -> VerzikRules.RED_CRAB_5S * p2 / VerzikRules.P2_HP.getValue(5)
                else -> return s.hp to s.base
            }
        return (s.hp.toLong() * base / s.base).toInt() to base
    }

    /**
     * A tick's damage on an NPC as the hits that made it: each player's attack that lands this tick (its weapon's hit
     * delay after the swing, verzikSim's) gets its own splat - a scythe its three (4:2:1 of its share), a Dawnbringer
     * spec its recorded damage - and what's left over (thralls, venom) one more. Blert has each tick's total only.
     */
    private fun hits(i: Int, npc: Npc, total: Int, t: Int) {
        val landing = raid.attacks.filter { it.target == i && it.t + (HIT_DELAY[it.name] ?: 1) == t }
        if (landing.isEmpty()) return splat(npc, total, hitmark_groups.regular_damage.lit)
        var left = total
        val parts = ArrayList<Int>()
        for (a in landing.filter { it.name == "DAWN_SPEC" }) {
            val d = (dawnDamage[a.t to a.player] ?: continue).coerceAtMost(left)
            parts += d
            left -= d
        }
        val rest = landing.filter { it.name != "DAWN_SPEC" || (it.t to it.player) !in dawnDamage }
        if (rest.isNotEmpty() && left > 0) {
            val each = left / rest.size
            rest.forEachIndexed { k, a ->
                val share = if (k == rest.size - 1) left - each * (rest.size - 1) else each
                if (a.name == "SCYTHE" && share >= 3) {
                    val first = share * 4 / 7
                    val second = share * 2 / 7
                    parts += listOf(first, second, share - first - second)
                } else parts += share
            }
            left = 0
        }
        if (left > 0) parts += left
        for (d in parts.filter { it > 0 }) splat(npc, d, hitmark_groups.regular_damage.lit, others = true)
        fx("hits npc$i total $total splats ${parts.filter { it > 0 }} from ${landing.map { it.name }}")
    }

    private fun splat(npc: Npc, amount: Int, hitmark: String, others: Boolean = false) {
        val id = hitmark.asRSCM(RSCMType.HITMARK)
        val tint = if (others) hitmark_groups.regular_damage.tint?.asRSCM(RSCMType.HITMARK) ?: id else id
        val mark = Hitmark.fromNoSource(self = id, source = id, public = tint, damage = amount, delay = 0)
        npc.showHitmark(mark)
        val bar = npc.visHeadbar(params.headbar)
        val fill = (bar.segments * npc.hitpoints.coerceAtLeast(0) / npc.baseHitpointsLvl.coerceAtLeast(1)).coerceIn(0, bar.segments)
        npc.showHeadbar(Headbar.fromNoSource(bar.id, bar.id, fill, fill, 0, 0))
    }

    private fun die(i: Int) {
        val npc = npcs[i] ?: return
        val s = shown[i] ?: return
        pillarLocs.remove(i)?.let { collapse(it) }
        if (s.type in herForms) return
        val (hp, _) = ours(s)
        val killed = hp <= 0 || raid.attacks.any { it.target == i && it.t + (HIT_DELAY[it.name] ?: 1) in clock - KILL_WINDOW..clock }
        val style = crawlerStyle[s.type]
        when {
            s.type == VerzikRules.RED.asRSCM(RSCMType.NPC) && !killed -> {
                val h = her
                val v = h?.let { npcs[it] }
                val vs = h?.let { shown[it] }
                if (v != null && vs != null) looks.redPops(npc, centre(s, npc.size), centre(vs, v.size))
                gone(npc, RED_POP_TICKS)
            }
            s.type == TORNADO.asRSCM(RSCMType.NPC) -> {
                looks.tornadoGone(npc)
                val onSomeone = bots.indices.any { raid.x(it, clock) == s.x && raid.y(it, clock) == s.y }
                if (onSomeone) her?.let { npcs[it] }?.let { looks.tornadoHit(it) }
                gone(npc, TORNADO_DESPAWN_TICKS)
            }
            style != null && !killed -> {
                fx("nylo pops")
                worldRepo.spotanimMap(SpotanimType(NyloLooks.detonateGfx(style).asRSCM(RSCMType.SPOTANIM)), npc.coords)
                npcRepo.del(npc, Int.MAX_VALUE)
            }
            style != null -> {
                val seq = npc.visType.paramOrNull(params.death_anim)?.id
                val length = seq?.let { ServerCacheManager.getAnim(it)?.tickDuration } ?: NPC_DEATH_TICKS
                seq?.let { npc.anim(RSCM.getReverseMapping(RSCMType.SEQ, it)) }
                fx("nylo killed: death anim, pop in $length")
                later(length) {
                    fx("nylo pop")
                    if (npc.isSlotAssigned) worldRepo.spotanimMap(SpotanimType(NyloLooks.deathGfx(style).asRSCM(RSCMType.SPOTANIM)), npc.coords)
                    if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE)
                }
            }
            else -> {
                npc.visType.paramOrNull(params.death_anim)?.let { npc.anim(RSCM.getReverseMapping(RSCMType.SEQ, it.id)) }
                gone(npc, NPC_DEATH_TICKS)
            }
        }
    }

    private fun gone(npc: Npc, ticks: Int) = later(ticks) { if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE) }

    private fun collapse(pillar: LocInfo) {
        locRepo.del(pillar, Int.MAX_VALUE)
        locs.remove(pillar)
        val wide = CoordGrid(pillar.coords.x - 1, pillar.coords.z - 1, pillar.coords.level)
        val falling = locRepo.add(wide, PILLAR_COLLAPSE, Int.MAX_VALUE, LocAngle.West, LocShape.CentrepieceStraight)
        locs += falling
        later(PILLAR_COLLAPSE_TICKS) {
            locRepo.del(falling, Int.MAX_VALUE)
            locs.remove(falling)
            val rubble = locRepo.add(wide, PILLAR_RUBBLE, Int.MAX_VALUE, LocAngle.West, LocShape.CentrepieceStraight)
            locs += rubble
            later(PILLAR_FADE - PILLAR_COLLAPSE_TICKS) { worldRepo.locAnim(rubble, PILLAR_FADE_SEQ) }
            later(PILLAR_FADE + PILLAR_FADE_TICKS - PILLAR_COLLAPSE_TICKS) {
                locRepo.del(rubble, Int.MAX_VALUE)
                locs.remove(rubble)
            }
        }
    }

    private fun attack(a: VerzikReplays.Attack) {
        val bot = bots.getOrNull(a.player) ?: return
        val target = npcs[a.target]?.takeIf { it.isSlotAssigned }
        if (target == null) {
            if (a.anim >= 0) RSCM.getReverseMapping(RSCMType.SEQ, a.anim)?.let { bot.anim(it, priority = ATTACK_PRIORITY) }
            return
        }
        lastHit[a.player] = a.target
        attackLooks.attack(bot, a.name, a.anim, a.weapon, target)
    }

    private fun spell(s: VerzikReplays.Spell) {
        val bot = bots.getOrNull(s.player) ?: return
        bots.getOrNull(s.target)?.let { bot.facePlayer(it) }
        if (s.anim >= 0) RSCM.getReverseMapping(RSCMType.SEQ, s.anim)?.let { bot.anim(it, priority = ATTACK_PRIORITY) }
        if (s.gfx >= 0) RSCM.getReverseMapping(RSCMType.SPOTANIM, s.gfx)?.let { (bots.getOrNull(s.target) ?: bot).spotanim(it) }
    }

    /** A drink or a bite (the sim's brews, restores, surges, sharks): no attack that tick, so it never cuts one off. */
    private fun use(u: VerzikReplays.Use) {
        val bot = bots.getOrNull(u.player) ?: return
        if (attacksAt[u.t]?.any { it.player == u.player } != true) bot.anim(EAT_ANIM)
        worldRepo.soundArea(bot.coords, if (u.kind == "shark") EAT_SOUND else DRINK_SOUND, radius = USE_SOUND_RADIUS)
    }

    private fun herAttack(h: VerzikReplays.Her) {
        val v = npcs[h.npc]?.takeIf { it.isSlotAssigned } ?: return
        val vs = shown[h.npc] ?: return
        val vc = centre(vs, v.size)
        val target = bots.getOrNull(h.target)
        when (h.name) {
            "P1_AUTO" -> {
                fx("p1 orb, lands t${clock + 2}")
                looks.p1Auto(v, vc, bots.mapIndexed { i, b -> b to pillarBetween(vc, tile(i)) })
            }
            "P2_CABBAGE" -> bots.forEachIndexed { i, b -> looks.urn(v, vc, b, tile(i)) }
            "P2_ZAP" -> target?.let { looks.zap(v, it) } ?: v.anim(VerzikRules.P2_ATTACK)
            "P2_MAGE" -> target?.let { looks.blood(v, it) } ?: v.anim(VerzikRules.P2_ATTACK)
            "P2_BOUNCE" ->
                target?.let {
                    bouncedAt[h.target] = clock
                    looks.bounce(v, it)
                }
            "P2_PURPLE" -> nextPurple()?.let { looks.purple(v, vc, it) } ?: v.anim(VerzikRules.P2_ATTACK)
            "P3_MELEE" -> looks.p3Melee(v, target)
            "P3_RANGE", "P3_AUTO" -> looks.p3Ranged(v, target, bots)
            "P3_MAGE" -> looks.p3Magic(v, target, bots)
            "P3_WEBS" -> {
                looks.webs(v)
                websUntil = clock + WEB_TICKS
            }
            "P3_YELLOWS" -> looks.yellows(v)
            "P3_BALL" -> target?.let { looks.greenBall(v, it) }
        }
        if (h.name.startsWith("P3_") && h.target >= 0) tank = h.target
    }

    /** Who she faces in P3 (her tank), and each player what they last hit, kept through her changes of form. */
    private fun faces() {
        val v = her?.let { npcs[it] }?.takeIf { it.isSlotAssigned }
        if (v != null && v.visType.id == P3.asRSCM(RSCMType.NPC)) {
            bots.getOrNull(tank)?.let { b -> if (v.faceEntity.playerSlot != b.slotId || !v.faceEntity.isPlayer) v.facePlayer(b) }
        }
        bots.forEachIndexed { i, b ->
            val n = lastHit[i]?.let { npcs[it] }?.takeIf { it.isSlotAssigned } ?: v ?: return@forEachIndexed
            if (!b.faceEntity.isNpc || b.faceEntity.npcSlot != n.slotId) b.faceNpc(n)
        }
    }

    private fun tile(i: Int) = CoordGrid(raid.x(i, clock), raid.y(i, clock), 0)

    /** Where the purple she's throwing now climbs out (the next one to spawn), its middle tile. */
    private fun nextPurple(): CoordGrid? {
        val purple = VerzikRules.PURPLE.asRSCM(RSCMType.NPC)
        val n = raid.npcs.filter { it.type0 == purple && it.spawn in clock + 1..clock + PURPLE_LOOKAHEAD }.minByOrNull { it.spawn } ?: return null
        return centre(n.steps.first(), ServerCacheManager.getNpc(purple)?.size ?: 1)
    }

    private fun purpleNear(): Pair<Npc, VerzikReplays.Step>? {
        val purple = VerzikRules.PURPLE.asRSCM(RSCMType.NPC)
        val (i, n) = npcs.entries.firstOrNull { (i, n) -> n.isSlotAssigned && shown[i]?.type == purple && raid.npcs[i].death.let { it == null || it > clock } } ?: return null
        return n to shown.getValue(i)
    }

    /** A standing pillar's middle tile when the line from her to [to] crosses it (VerzikSolo.blocks). */
    private fun pillarBetween(from: CoordGrid, to: CoordGrid): CoordGrid? {
        val n = max(abs(to.x - from.x), abs(to.z - from.z)).coerceAtLeast(1)
        for (i in pillarLocs.keys) {
            val p = shown[i] ?: continue
            for (k in 1 until n) {
                val x = from.x + (to.x - from.x) * k / n
                val y = from.z + (to.z - from.z) * k / n
                if (x in p.x..p.x + 2 && y in p.y..p.y + 2) return CoordGrid(p.x + 1, p.y + 1, 0)
            }
        }
        return null
    }

    private fun centre(s: VerzikReplays.Step, size: Int) = CoordGrid(s.x + size / 2, s.y + size / 2, 0)

    fun remove() {
        unblock()
        dawnOnFloor?.let { objRepo.del(it) }
        dawnOnFloor = null
        for ((_, w) in webs) if (w.first.isSlotAssigned) npcRepo.del(w.first, Int.MAX_VALUE)
        webs.clear()
        for (n in npcs.values) if (n.isSlotAssigned) npcRepo.del(n, Int.MAX_VALUE)
        npcs.clear()
        for (l in locs) locRepo.del(l, Int.MAX_VALUE)
        locs.clear()
        pillarLocs.clear()
        pending.clear()
    }

    companion object {
        const val WEB_TICKS = 44
        const val WEB_FLIGHT_MAX = 4
        const val WEB_AFTER = 14
        const val WEB_LIFE = 30
        const val WEBS_A_VOLLEY = 3
        const val WEB_NPC = "npc.verzik_web_npc"
        const val WEB_LOC = "loc.verzik_webspin_loc"
        const val DAWNBRINGER = "obj.verzik_special_weapon"
        const val PLAYER_HEADBAR = "headbar.health_30"
        const val PLAYER_HEADBAR_SEGMENTS = 30
        const val INITIAL_QUICKSTART = "npc.verzik_initial_quickstart"
        val ARENA_X = 3150..3184
        val ARENA_Y = 4297..4334

        /** Ticks from a swing to its hit landing (verzikSim's weapon delays). */
        val HIT_DELAY =
            mapOf(
                "SCYTHE" to 1, "CLAW_SCRATCH" to 1, "CLAW_SPEC" to 1, "BURNING_CLAW_SPEC" to 1, "CHALLY_SPEC" to 1, "CHALLY_SWIPE" to 1,
                "SWIFT" to 1, "ELDER_MAUL" to 1, "SULPHUR_BLADES" to 1, "DUAL_MACUAHUITL" to 1, "GODSWORD_SMACK" to 1,
                "DAWN_SPEC" to 2, "DAWN_AUTO" to 2, "EYE_OF_AYAK_AUTO" to 2, "BLOWPIPE" to 2, "DART" to 2, "ZCB_AUTO" to 2, "ZCB_SPEC" to 2,
                "SANG" to 3, "SHADOW" to 3, "ZORYAS_TOME_AUTO" to 3, "ZORYAS_TOME_SPEC" to 3,
            )
        val FX = System.getenv("VERZIK_FX") != null
        const val KILL_WINDOW = 2
        const val DETONATE_SEQ = "seq.top_spider_melee_death_detonate"
        val DEATH_GAP by lazy { mapOf(P1.asRSCM(RSCMType.NPC) to 4, P2.asRSCM(RSCMType.NPC) to 2, P3.asRSCM(RSCMType.NPC) to 2) }
        const val PRE_TICKS = 4
        const val END_HOLD = 8
        const val NPC_DEATH_TICKS = 3
        const val RED_POP_TICKS = 2
        const val TORNADO_DESPAWN_TICKS = 1
        const val BOUNCE_WINDOW = 2
        const val PURPLE_LOOKAHEAD = 10
        const val ATTACK_PRIORITY = 6
        const val EAT_ANIM = "seq.human_eat"
        const val EAT_SOUND = "synth.eat"
        const val DRINK_SOUND = "synth.liquid"
        const val USE_SOUND_RADIUS = 5
        const val PILLAR_COLLAPSE_TICKS = 4
        const val PILLAR_FADE = 10
        const val PILLAR_FADE_TICKS = 2
        const val PILLAR = "npc.verzik_pillar_npc"
        const val PILLAR_LOC = "loc.tob_dungeon_verzik_pillar"
        const val PILLAR_COLLAPSE = "loc.tob_dungeon_verzik_pillar_collapse"
        const val PILLAR_RUBBLE = "loc.tob_dungeon_verzik_pillar_rubble"
        const val PILLAR_FADE_SEQ = "seq.verzik_pillar_fade"
        const val TORNADO = "npc.tob_verzik_creeper"
        const val INITIAL = "npc.verzik_initial"
        const val P1 = "npc.verzik_phase1"
        const val P1_TO_P2 = "npc.verzik_phase1_to2_transition"
        const val P2 = "npc.verzik_phase2"
        const val P2_TO_P3 = "npc.verzik_phase2_to3_transition"
        const val P3 = "npc.verzik_phase3"
        const val DEATH_BAT = "npc.verzik_death_bat"
        val P2_P3_FORMS = listOf(P2, P2_TO_P3, P3, DEATH_BAT)
        val HER_FORMS = listOf(INITIAL, P1, P1_TO_P2, P2, P2_TO_P3, P3, DEATH_BAT)
        val CRAWLERS = mapOf("npc.verzik_nylocas_melee" to 'M', "npc.verzik_nylocas_ranged" to 'R', "npc.verzik_nylocas_magic" to 'G')

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

private var Player.throneVisible by boolVarBit("varbit.tob_verzik_throne_visible")
