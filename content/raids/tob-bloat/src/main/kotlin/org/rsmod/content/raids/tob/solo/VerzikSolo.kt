package org.rsmod.content.raids.tob.solo

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.util.PathingEntityCommon
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid
import kotlin.math.abs
import kotlin.math.max

/**
 * Verzik Vitur, solo (P1 1500 hitpoints, P2 and P3 2437 each), every phase and the transitions between them.
 *
 * - P1: on her throne behind a shield - melee hits are capped at 10 a hitsplat, ranged and magic at 3; the Dawnbringer
 *   (it comes to you as the fight starts) has a special that hits for 75-150 through it. Every 14 ticks from tick 20
 *   she throws a blast for up to 137 (Protect from Magic halves it); a pillar between you and her takes it instead, and
 *   collapses when it's taken enough. When her shield breaks she climbs down to the middle - the last pillars fall.
 * - P2: every 4 ticks: an urn bomb at your tile (44, 22 on Protect from Missiles - move off it), and every 5th attack a
 *   lightning bolt (48, 25 in insulated boots). Stand next to her or under her and she bounces you back 3 tiles and
 *   stuns you instead. Nylocas come with a purple crab (the Athanatos) that heals her until it's killed - a poisoned
 *   weapon kills it outright and the poison hurts her. At 35% the reds: she stops to summon a Nylocas Matomenos that
 *   walks to her and heals her its hitpoints (hits on her heal her while her shield is up), then seven attacks - mostly a
 *   blood blast that heals her half of what it does - and again.
 * - P3: every 7 ticks a melee attack if you're beside her (never her first), else a ranged or magic one (33, half on the
 *   right prayer).
 *   Every fifth attack is a special, in turn: nylocas, webs (she goes to the middle and shoots webs - one on your tile
 *   holds you until you break it, and it snaps if you don't), yellows (stand on the pool or take up to 80) and the green
 *   ball (three-quarters of your hitpoints level). At 20% she enrages: attacks every 5 ticks, and a tornado chases you -
 *   it takes half your hitpoints and heals her three times that, coming back 16 ticks later.
 */
class VerzikSolo(owner: Player, session: InstanceSession, d: SoloDeps, origin: CoordGrid, copyBase: CoordGrid) :
    SoloRoom(TobRoom.VERZIK, owner, session, d, origin, copyBase) {
    override val entry = CoordGrid(3167, 4304, 0)
    override val chest = CoordGrid(3170, 4303, 0) to LocAngle.North

    override fun inArena(c: CoordGrid): Boolean = c.x in 3150..3184 && c.z in 4297..4334

    private var verzik: Npc? = null
    private var phase = 0
    private var nextAttack = 0
    private var attacks = 0
    private var phaseAt = 0
    private var dead = -1
    private var p2Hp = P2_HP

    private inner class Pillar(val x: Int, val y: Int) {
        var hp = PILLAR_HP
        var npc: Npc? = null
        var loc: LocInfo? = null
        val up: Boolean
            get() = npc != null
    }

    private val pillars = PILLARS.map { (x, y) -> Pillar(x, y) }

    /* P2 */
    private var zaps = 0
    private var sincePurple = 0
    private var reds = false
    private var redsAt = -1
    private val redBorn = HashMap<Npc, Int>()
    private val redHome = HashMap<Npc, CoordGrid>()
    private val redGoal = HashMap<Npc, CoordGrid>()
    private val redHeals = ArrayList<Pair<Int, Npc>>()
    private var p2Killed = false

    /** P2: damage thrown at her that hasn't landed yet (a hit counts against the hitpoints she had when it was thrown). */
    private var summoning = -1
    private var athanatos: Npc? = null
    private var athanatosNext = 0

    /* P3 */
    private var special = 0
    private var specialUntil = -1

    /** The tick her last yellows started (-1 until then). */
    private var yellowAt = -1
    private var invulnerable = false
    private var enraged = false
    private var tornado: Npc? = null
    private var tornadoAt = -1
    private var yellow: CoordGrid? = null

    private inner class Crawler(val npc: Npc, val style: Char, val born: Int) {
        var lockAt = -1
    }

    private val crawlers = ArrayList<Crawler>()
    private val redCrabs = ArrayList<Npc>()

    /** A web: the NPC you're held by (it has no model of its own) and the web you see on its tile. */
    private inner class Web(val npc: Npc, val born: Int, val holding: Boolean, val look: LocInfo)

    private val webs = ArrayList<Web>()
    private var webBlock = 0

    override fun build() {
        owner.throneVisible = true
        phase = 0
        dead = -1
        attacks = 0
        zaps = 0
        sincePurple = 0
        reds = false
        redsAt = -1
        summoning = -1
        special = 0
        specialUntil = -1
        yellowAt = -1
        invulnerable = false
        enraged = false
        tornado = null
        tornadoAt = -1
        yellow = null
        athanatos = null
        crawlers.clear()
        redCrabs.clear()
        redHeals.clear()
        p2Killed = false
        redBorn.clear()
        redHome.clear()
        redGoal.clear()
        webs.clear()
        for (p in pillars) {
            p.hp = PILLAR_HP
            p.npc = null
            p.loc = null
        }
        verzik = spawn(INITIAL, CoordGrid(P1_X, P1_Y, 0), hp(P1_HP, entryHp(1200)))
        // the pillars stand from the start, before she's woken
        for (p in pillars) {
            p.npc = spawn(PILLAR_NPC, CoordGrid(p.x, p.y, 0), PILLAR_HP)
            p.loc = loc("loc.tob_dungeon_verzik_pillar", CoordGrid(p.x, p.y, 0), shape = LocShape.CentrepieceStraight)
        }
    }

    /** Her throne turns into the way to the treasure room once she's gone. */
    private fun openThrone() {
        val throne = findLocs(THRONE).firstOrNull()?.second ?: return logger.warn { "verzik: no throne in the room" }
        owner.throneVisible = false
        val at = throne.coords.translate(1, 0)
        val turning = d.locRepo.add(at, THRONE_TRANSFORMING, Int.MAX_VALUE, throne.angle, throne.shape)
        d.worldRepo.locAnim(turning, "seq.verzik_throne_transform")
        later(THRONE_TRANSFORM_TICKS) { d.locRepo.add(at, THRONE_DOOR, Int.MAX_VALUE, throne.angle, throne.shape) }
    }

    /** She starts when you speak to her on her throne. */
    suspend fun begin(access: ProtectedAccess) {
        if (t >= 0) return
        val choice = access.menu("Is your party ready to fight?", "Yes, let's go.", "No, don't start yet.")
        access.ifClose()
        if (choice != 0) return
        SoloRespawn.save(owner)
        start()
    }

    override fun onStart() {
        val v = verzik ?: return
        transmog(v, P1)
        phase = 1
        phaseAt = 0
        nextAttack = P1_FIRST
    }

    override fun owns(npc: Npc): Boolean = super.owns(npc)

    override fun floor(npc: Npc): Int? =
        when {
            npc === verzik && dead < 0 -> 1
            pillars.any { it.npc === npc } -> 1
            else -> null
        }

    override fun land(npc: Npc, hit: Hit): Int? {
        if (pillars.any { it.npc === npc }) return 0
        if (npc === athanatos) {
            if (poisoned(hit)) {
                npc.hitpoints = 0
                verzik?.let { v ->
                    v.hitpoints = (v.hitpoints - rand(60, 70)).coerceAtLeast(2)
                    v.spotanim("spotanim.verzik_phase2_poisonaura")
                }
                return 0
            }
            return hit.damage
        }
        crawlers.firstOrNull { it.npc === npc }?.let { c ->
            if (hit.type == HitType.Typeless) return hit.damage
            return if (hit.type == styleType(c.style)) hit.damage else 0
        }
        if (npc !== verzik) return null
        if (dead >= 0 || invulnerable || phase == 0 || phase == 12 || phase == 23) return 0
        if (phase == 1) {
            val dawn = hit.righthandType()?.id == DAWNBRINGER.asRSCM(dev.openrune.rscm.RSCMType.OBJ)
            if (dawn && hit.damage >= DAWN_SPEC_MIN) return hit.damage
            return when (hit.type) {
                HitType.Melee -> minOf(hit.damage, P1_MELEE_CAP)
                HitType.Typeless -> hit.damage
                else -> minOf(hit.damage, P1_RANGED_CAP)
            }
        }
        return hit.damage
    }

    /**
     * The reds run on a 44-tick cycle from the summon (her shield animation and the red, the last cycle's R44): her shield
     * R1-R10, when hits on her heal her (pink heal splats); her attacks from 12 ticks after the summon, every 4, seven of
     * them; the red pops and heals her 40 ticks after the summon, 4 after her last attack; the next summon 4 after that.
     * Checked against Blert (hits landing on the summon tick hurt her, R1-R10 heal, R11 hurts; the heal 40 ticks after the
     * red appears) and the live recording (first attack 12 ticks after the summon; the pop and its heal splat on the same
     * tick, 4 after her last attack).
     */
    /**
     * Her yellows: no one can attack her for the 14 ticks after the powerblast starts (the first attack goes on the
     * 15th and lands on the 16th); hits already on their way still land (the sim's P3 rules). Players act after the
     * room's step, by when [t] has moved on to the next tick.
     */
    override fun attackable(npc: Npc): Boolean = !(npc === verzik && yellowAt >= 0 && t - 1 in yellowAt + 1..yellowAt + YELLOW_TICKS)

    override fun healedByHits(npc: Npc, hit: Hit): Boolean = npc === verzik && phase == 2 && shielded()

    private fun shielded(): Boolean = summoning >= 0 && t - 1 - summoning in 1..SHIELD_TICKS

        private fun poisoned(hit: Hit): Boolean {
        val name = hit.righthandType()?.name?.lowercase() ?: return false
        return name.contains("(p") || name.contains("toxic") || name.contains("serpentine") || name.contains("blowpipe")
    }

    private fun styleType(s: Char): HitType = when (s) {
        'M' -> HitType.Melee
        'R' -> HitType.Ranged
        else -> HitType.Magic
    }


    override fun step() {
        val v = verzik ?: return
        if (dead >= 0) {
            when (t - dead) {
                DEATH_B_AT -> transmog(v, DEATH_BAT)
                DEATH_B_AT + DEATH_B_TICKS -> despawn(v)
                DEATH_TICKS -> {
                    openThrone()
                    cleared = true
                    mes("Verzik Vitur is defeated. The Theatre is complete - the supply chest outside takes you to any room.")
                }
            }
            return
        }
        when (phase) {
            1 -> p1(v)
            12 -> p1to2(v)
            2 -> p2(v)
            23 -> p2to3(v)
            3 -> p3(v)
        }
        crawlersTick()
        websTick()
    }

    /* ---- P1 */

    private fun p1(v: Npc) {
        if (v.hitpoints <= 1) {
            phase = 12
            phaseAt = t
            v.hitpoints = 0
            v.anim("seq.verzik_phase1_death")
            for (p in pillars) collapse(p)
            for (p in party()) takeDawnbringer(p)
            dawnOnFloor?.let { d.objRepo.del(it) }
            dawnOnFloor = null
            mes("Verzik Vitur's shield breaks!")
            return
        }
        val p = inside() ?: return
        for (pl in pillars) if (pl.up) pl.npc?.hitpoints = pl.hp.coerceIn(1, PILLAR_HP)
        if (t < nextAttack) return
        nextAttack = t + P1_EVERY
        v.anim("seq.verzik_phase1_attack_magic")
        recordAttack(v, 12)
        val target = real(p.coords)
        val pillar = pillars.firstOrNull { it.up && blocks(it, center(v), target) }
        if (pillar != null) {
            val lands = projTiles("spotanim.verzik_phase1_projectile", center(v), CoordGrid(pillar.x + 1, pillar.y + 1, 0), delay = P1_FLIGHT * 30, speed = 0, startHeight = 120, endHeight = 60, start = 30)
            val dmg = rand(0, P1_MAX)
            later(lands) {
                gfx("spotanim.verzik_phase1_impactpillar", CoordGrid(pillar.x + 1, pillar.y + 1, 0), height = 60)
                sound(PILLAR_HIT_SOUND, CoordGrid(pillar.x + 1, pillar.y + 1, 0), SOUND_RADIUS)
                pillar.hp -= dmg
                pillar.npc?.let { showDamage(it, dmg, pillar.hp, PILLAR_HP) }
                if (pillar.hp <= 0) collapse(pillar)
            }
            return
        }
        val lands = proj("spotanim.verzik_phase1_projectile", v, p, delay = 30, speed = 0, offset = (P1_FLIGHT - 1) * 30, startHeight = 120)
        val roll = rand(0, P1_MAX)
        later(lands) {
            val q = inside() ?: return@later
            q.spotanim("spotanim.verzik_phase1_impact")
            sound(P1_IMPACT_SOUND, real(q.coords), SOUND_RADIUS)
            hurt(q, if (prays(q, "magic")) roll / 2 else roll, source = v)
        }
    }

    private fun center(n: Npc): CoordGrid = real(n.coords).let { CoordGrid(it.x + n.size / 2, it.z + n.size / 2, 0) }

    /** A pillar is between her and you when the line from her to you crosses it. */
    private fun blocks(p: Pillar, from: CoordGrid, to: CoordGrid): Boolean {
        val n = max(abs(to.x - from.x), abs(to.z - from.z)).coerceAtLeast(1)
        for (i in 1 until n) {
            val x = from.x + (to.x - from.x) * i / n
            val y = from.z + (to.z - from.z) * i / n
            if (x in p.x..p.x + 2 && y in p.y..p.y + 2) return true
        }
        return false
    }

    private fun collapse(p: Pillar) {
        val npc = p.npc ?: return
        despawn(npc)
        p.npc = null
        p.loc?.let { delLoc(it) }
        val wide = CoordGrid(p.x - 1, p.y - 1, 0)
        val falling = loc("loc.tob_dungeon_verzik_pillar_collapse", wide, shape = LocShape.CentrepieceStraight)
        p.loc = falling
        later(PILLAR_COLLAPSE_TICKS) {
            delLoc(falling)
            p.loc = loc("loc.tob_dungeon_verzik_pillar_rubble", wide, shape = LocShape.CentrepieceStraight)
        }
        later(PILLAR_FADE) { p.loc?.let { d.worldRepo.locAnim(it, "seq.verzik_pillar_fade") } }
        later(PILLAR_FADE + PILLAR_FADE_TICKS) { p.loc?.let { delLoc(it) } }
        val q = inside() ?: return
        val c = real(q.coords)
        if (boxDist(c.x, c.z, p.x, p.y, p.x + 2, p.y + 2) <= 1) {
            hurt(q, rand(PILLAR_FALL_MIN, PILLAR_FALL_MAX))
            q.spotanim("spotanim.stunned_thieving")
            q.routeDestination.clear()
        }
    }

    private fun p1to2(v: Npc) {
        val since = t - phaseAt
        if (since == T12_AT) {
            despawn(v)
            verzik = spawn(T12, CoordGrid(P1_X, P1_Y, 0), null).also { it.walk(at(P1_X, P2_Y)) }
        }
        if (since == T12_TICKS) {
            verzik?.let { despawn(it) }
            val nv = spawn(P2, CoordGrid(P2_X, P2_Y, 0), hp(P2_HP, entryHp(1600)))
            p2Hp = nv.baseHitpointsLvl
            later(1) { if (nv.isSlotAssigned) nv.anim("seq.verzik_phase2_spawn") }
            verzik = nv
            phase = 2
            phaseAt = t
            attacks = 0
            zaps = 0
            sincePurple = 0
            nextAttack = t + P2_FIRST
        }
    }

    /* ---- P2 */

    private fun p2(v: Npc) {
        if (v.hitpoints <= 1 || p2Killed) {
            p2Killed = false
            redHeals.clear()
            phase = 23
            phaseAt = t
            v.hitpoints = 0
            v.anim("seq.verzik_phase2_death")
            athanatos?.let { despawn(it) }
            athanatos = null
            for (r in redCrabs) despawn(r)
            redCrabs.clear()
            return
        }
        athanatosTick(v)
        redsTick(v)
        val p = inside() ?: return
        if (!reds && v.hitpoints * 100 <= p2Hp * REDS_PCT) {
            reds = true
            summonReds(v)
            return
        }
        if (reds && redsAt >= 0 && t - redsAt >= RED_CYCLE) {
            summonReds(v)
            return
        }
        if (summoning >= 0 && t - summoning < SUMMON_TICKS) return
        if (t < nextAttack) return
        nextAttack = t + P2_EVERY
        attacks++
        val here = real(p.coords)
        val vr = real(v.coords)
        val d = boxDist(here.x, here.z, vr.x, vr.z, vr.x + v.size - 1, vr.z + v.size - 1)
        if (d <= 1) {
            bounce(v, p, under = d == 0)
            return
        }
        if (reds) {
            // seven attacks a cycle (R12-R36), then nothing until the next summon
            if (t - redsAt > REDS_LAST_ATTACK) return
            if (chance(0.75)) bloodBlast(v, p) else if (chance(0.5)) urn(v, p) else zap(v, p)
            return
        }
        sincePurple++
        if (attacks == 5 || (sincePurple >= MIN_PURPLE && zaps % 3 == 0 && zaps > 0 && chance(0.25) && athanatos == null)) {
            purple(v, p)
            sincePurple = 0
            return
        }
        if ((attacks - if (attacks > 5) 1 else 0) % 5 == 0) zap(v, p) else urn(v, p)
    }

    private fun urn(v: Npc, p: Player) {
        recordAttack(v, 14)
        v.facePlayer(p)
        v.anim("seq.verzik_phase2_attack_magic")
        val tile = real(p.coords)
        // Blert, real solos: a hit from it shows 6 ticks after her attack - a slow lob that bursts on the tile then
        val lands = projTiles("spotanim.verzik_phase2_ranged", center(v), tile, delay = URN_TICKS * 30, speed = 0, startHeight = 100, endHeight = 0, angle = 40, start = 30)
        val roll = rand(0, URN_MAX)
        later(lands) {
            gfx("spotanim.verzik_ranged_impact", tile)
            val q = inside() ?: return@later
            if (real(q.coords) == tile) hurt(q, if (prays(q, "missiles")) roll / 2 else roll, source = v)
        }
    }

    private fun zap(v: Npc, p: Player) {
        recordAttack(v, 15)
        zaps++
        v.facePlayer(p)
        v.anim("seq.verzik_phase2_attack_magic")
        // a slow spark: in a real clip it took 5 ticks to cross about 7 tiles, and the hit shows as it arrives
        val lands = proj("spotanim.verzik_phase2_lightning", v, p, delay = 30, speed = ZAP_SPEED, startHeight = 100)
        later(lands) {
            val q = inside() ?: return@later
            val boots = q.worn[10]?.let { ServerCacheManager.getItem(it.id)?.name?.lowercase()?.contains("insulated") } == true
            hurt(q, rand(0, if (boots) ZAP_BOOTS else ZAP_MAX), source = v)
        }
    }

    private fun bounce(v: Npc, p: Player, under: Boolean) {
        recordAttack(v, 13)
        v.facePlayer(p)
        v.anim("seq.verzik_phase2_attack_melee")
        hurt(p, rand(0, if (under) STOMP_MAX else BOUNCE_MAX), source = v, delay = 1)
        knock(v, p, BOUNCE_TILES, slide = true)
        p.spotanim("spotanim.stunned_thieving")
        p.delay = p.currentMapClock + STUN
    }

    /** Pushes [p] away from [v] by up to [tiles] free tiles. */
    private fun knock(v: Npc, p: Player, tiles: Int, slide: Boolean = false) {
        val c = center(v)
        val here = real(p.coords)
        val dx = sign(here.x - c.x).let { if (it == 0 && here.z == c.z) 1 else it }
        val dy = sign(here.z - c.z)
        var to = here
        for (i in 1..tiles) {
            val n = CoordGrid(to.x + dx, to.z + dy, 0)
            if (!inArena(n) || blocked(n)) break
            to = n
        }
        p.routeDestination.clear()
        if (!slide || to == here) {
            PathingEntityCommon.telejump(p, d.collision, at(to))
            return
        }
        // thrown back: the player slides over the tiles (a forced move) rather than appearing there
        p.anim("seq.human_stunned")
        val face = when {
            dx > 0 -> 1536
            dx < 0 -> 512
            dy > 0 -> 0
            else -> 1024
        }
        PathingEntityCommon.exactMove(p, at(here), at(to), 0, 30, (face + 1024) % 2048, d.collision)
    }

    private fun purple(v: Npc, p: Player) {
        recordAttack(v, 16)
        v.anim("seq.verzik_phase2_attack_magic")
        // it's thrown at the tile the player she's attacking stood on, and climbs out where it lands
        val tile = real(p.coords)
        val from = center(v)
        val dist = max(1, max(abs(from.x - tile.x), abs(from.z - tile.z)))
        // Blert: it always climbs out 6 ticks after the throw (with the crabs that come out at the throw)
        projTiles("spotanim.verzik_phase2_spawn_armouredtank_proj", from, tile, delay = 30, speed = (ATHANATOS_FLIGHT * 30 - 31) / dist, startHeight = 100, endHeight = 0, angle = 40)
        later(ATHANATOS_FLIGHT) {
            if (phase != 2) return@later
            gfx("spotanim.verzik_phase2_spawning", tile)
            athanatos = spawn(ATHANATOS, tile, ATHANATOS_HP)
            athanatosNext = t + ATHANATOS_EVERY
        }
        crawler(p)
    }

    private fun athanatosTick(v: Npc) {
        val a = athanatos ?: return
        if (!a.isSlotAssigned || a.hitpoints <= 0) {
            athanatos = null
            return
        }
        // through her shield it idles; it goes again at the start of her next cycle, her first attack after the summon
        if (summoning >= 0 && t - summoning < SUMMON_TICKS) {
            athanatosNext = summoning + SUMMON_TICKS
            return
        }
        if (t >= athanatosNext) {
            athanatosNext = t + ATHANATOS_EVERY
            projTiles("spotanim.verzik_phase2_poisonglobule", real(a.coords), center(v), speed = 6, startHeight = 20, endHeight = 80)
            healNpc(v, rand(9, 10))
        }
    }

    private fun summonReds(v: Npc) {
        summoning = t
        nextAttack = t + SUMMON_TICKS
        v.anim("seq.verzik_phase2_heal")
        // one red a cycle in a solo (west); with two or more players alive, one west and one east (Blert's tiles)
        val tiles = listOf(CoordGrid(P2_X - 4, P2_Y + 1, 0), CoordGrid(P2_X + 4, P2_Y + 1, 0))
        for (tile in tiles.take(if (alivePlayers() >= 2) 2 else 1)) {
            gfx("spotanim.verzik_phase2_spawning", tile)
            val r = spawn(RED, tile, hp(RED_HP, ENTRY_RED_HP))
            // the spawn animation a tick on, once the client has the crab
            later(1) { if (r.isSlotAssigned) r.anim(RED_SPAWN_SEQ) }
            redBorn[r] = t
            redHome[r] = tile
            redCrabs += r
        }
        if (redsAt < 0) mes("Verzik Vitur summons her Nylocas!")
        redsAt = t
    }

    /** Players alive in the room (a solo for now; the party once grouping is in). */
    private fun alivePlayers(): Int = if (inside() != null) 1 else 0

    /**
     * The reds pop (their death animation) 40 ticks after the summon (r40) and heal her on r41 for what they have left,
     * after that tick's damage queue: a swing on r40 lands first, so if it takes her down the heal still shows but she
     * dies (the sim's reds rules). [late] runs after the tick's npc and player processing, which is that order.
     */
    override fun late() {
        val now = t - 2
        if (redHeals.none { it.first <= now }) return
        val v = verzik ?: return redHeals.clear()
        if (phase != 2 || dead >= 0) return redHeals.clear()
        if (v.hitpoints <= 1) p2Killed = true
        for ((_, r) in redHeals.filter { it.first <= now }) {
            if (!r.isSlotAssigned || r.hitpoints <= 0) continue
            healNpc(v, r.hitpoints)
            r.hitpoints = 0
        }
        redHeals.removeAll { it.first <= now }
    }

    private fun redsTick(v: Npc) {
        val it = redCrabs.iterator()
        while (it.hasNext()) {
            val r = it.next()
            if (!r.isSlotAssigned) {
                redBorn.remove(r)
                redHome.remove(r)
                redGoal.remove(r)
                it.remove()
                continue
            }
            if (r.hitpoints <= 0) continue
            val age = t - (redBorn[r] ?: t)
            if (age >= RED_POP_AT) {
                projTiles(RED_HEAL_PROJ, center(r), center(v), speed = RED_HEAL_SPEED, startHeight = 40, endHeight = 100)
                redHeals += t to r
                r.routeDestination.clear()
                r.anim(RED_POP_SEQ)
                later(RED_LIFE - RED_POP_AT) { if (r.isSlotAssigned) despawn(r) }
                redBorn.remove(r)
                it.remove()
                continue
            }
            if (r.vars["varn.freeze_end_clock"] > d.mapClock.cycle) continue
            wander(r, age)
        }
    }

    /**
     * A red's wander, the game's NPC random walk round where it was summoned (Blert: never more than 3 tiles from it, so it
     * stays on its own side and never comes under her): standing, each tick a 1 in 8 chance of a new spot, walked to a tile a
     * tick diagonal-first.
     */
    private fun wander(r: Npc, age: Int) {
        val home = redHome[r] ?: return
        val here = real(r.coords)
        val goal = redGoal[r]
        if (goal != null && goal != here) {
            if (!stepToward(r, goal.x, goal.z)) redGoal.remove(r)
            return
        }
        redGoal.remove(r)
        if (age < RED_STILL || rand(RED_WANDER_CHANCE) != 0) return
        val to = CoordGrid(home.x + rand(-RED_RANGE, RED_RANGE), home.z + rand(-RED_RANGE, RED_RANGE), 0)
        if (to == here || !inArena(to)) return
        redGoal[r] = to
        stepToward(r, to.x, to.z)
    }

    private fun bloodBlast(v: Npc, p: Player) {
        recordAttack(v, 17)
        v.facePlayer(p)
        v.anim("seq.verzik_phase2_attack_magic")
        val lands = proj("spotanim.verzik_phase2_bloodproj", v, p, delay = 20, speed = 8, startHeight = 100)
        val roll = rand(0, BLOOD_MAX)
        later(lands) {
            val q = inside() ?: return@later
            q.spotanim("spotanim.verzik_phase2_bloodimpact")
            val praying = prays(q, "magic")
            hurt(q, if (praying) 0 else roll, source = v)
            val heal = rand(0, BLOOD_HEAL_MAX) / 2
            verzik?.let { healNpc(it, if (praying) heal / 2 else heal) }
        }
    }

    private fun p2to3(v: Npc) {
        val since = t - phaseAt
        if (since == T23_AT) {
            despawn(v)
            // the transition form plays her rise (4 ticks); P3 itself comes in standing, free to turn and attack
            val tv = spawn(T23, CoordGrid(P3_X, P3_Y, 0), null)
            tv.anim("seq.verzik_phase3_spawn")
            verzik = tv
        }
        if (since == T23_TICKS) {
            verzik?.let { despawn(it) }
            val nv = spawn(P3, CoordGrid(P3_X, P3_Y, 0), hp(P3_HP, entryHp(1600)))
            verzik = nv
            phase = 3
            phaseAt = t
            attacks = 0
            special = 0
            nextAttack = t + P3_FIRST
        }
    }

    /* ---- P3 */

    private fun p3(v: Npc) {
        if (v.hitpoints <= 1) {
            dead = t
            v.hitpoints = 0
            v.routeDestination.clear()
            v.anim("seq.verzik_phase3_death_a")
            sound(P3_DEATH_SOUND, center(v), SOUND_RADIUS)
            unblockWebs()
            tornado?.let { vanish(it) }
            tornado = null
            for (c in crawlers) despawn(c.npc)
            crawlers.clear()
            for (w in webs) {
                despawn(w.npc)
                delLoc(w.look)
            }
            webs.clear()
            return
        }
        if (!enraged && v.hitpoints * 100 <= v.baseHitpointsLvl * ENRAGE_PCT) {
            enraged = true
            v.say("I'm not done with you just yet!")
            tornadoAt = t + 1
        }
        val p = inside() ?: return
        tornadoTick(v, p)
        if (specialUntil >= 0) {
            specialTick(v, p)
            return
        }
        val here = real(p.coords)
        val vr = real(v.coords)
        val dist = boxDist(here.x, here.z, vr.x, vr.z, vr.x + v.size - 1, vr.z + v.size - 1)
        // Blert: she steps toward you from her south-west tile, not her middle (173 of 190 chase steps; the rest blocked)
        if (dist > 1 && v.vars["varn.freeze_end_clock"] <= d.mapClock.cycle) stepToward(v, here.x, here.z)
        if (t < nextAttack) return
        nextAttack = t + if (enraged) P3_EVERY_ENRAGED else P3_EVERY
        attacks++
        if (attacks % 5 == 0) {
            // the nylocas and the green ball come with an ordinary attack; webs and yellows take its place (Blert)
            val which = special % 4
            startSpecial(v, p)
            if (which == 1 || which == 2) return
        }
        v.facePlayer(p)
        // her first attack of the phase is never her melee, beside her or not
        if (dist == 1 && attacks > 1) {
            v.anim("seq.verzik_phase3_attack_melee")
            recordAttack(v, 19)
            val roll = rand(0, P3_MELEE_MAX)
            later(1) {
                val q = inside() ?: return@later
                val r = real(q.coords)
                val vv = verzik ?: return@later
                val rv = real(vv.coords)
                if (boxDist(r.x, r.z, rv.x, rv.z, rv.x + vv.size - 1, rv.z + vv.size - 1) != 1) return@later
                hurt(q, roll, prayer = "melee", source = vv)
            }
            return
        }
        val magic = chance(0.5)
        v.anim(if (magic) "seq.verzik_phase3_attack_magic" else "seq.verzik_phase3_attack_ranged")
        recordAttack(v, if (magic) 21 else 20)
        val lands = proj(if (magic) "spotanim.verzik_phase3_mageproj" else "spotanim.verzik_phase3_rangeproj", v, p, delay = 30, speed = 6, startHeight = 120)
        val max = if (enraged) P3_MAX_ENRAGED else P3_MAX
        val roll = rand(0, max)
        val prayer = if (magic) "magic" else "missiles"
        later(lands) {
            val q = inside() ?: return@later
            hurt(q, if (prays(q, prayer)) roll / 2 else roll, source = verzik)
        }
    }

    private fun startSpecial(v: Npc, p: Player) {
        val which = special % 4
        special++
        when (which) {
            0 -> {
                v.anim("seq.verzik_phase3_attack_summon")
                crawler(p)
            }
            1 -> {
                invulnerable = true
                specialUntil = t + WEB_TICKS
                v.routeDestination.clear()
                v.walk(at(P3_X, P3_Y))
                v.anim("seq.verzik_phase3_attack_webspin")
                webBlock = v.size
                blockPlayers(CoordGrid(P3_X, P3_Y, 0), v.size, true)
                recordAttack(v, 22)
                val here = real(p.coords)
                val vr = real(v.coords)
                if (boxDist(here.x, here.z, vr.x, vr.z, vr.x + v.size - 1, vr.z + v.size - 1) <= 1) knock(v, p, 3)
            }
            2 -> {
                yellowAt = t
                specialUntil = t + YELLOW_TICKS
                v.anim("seq.verzik_phase3_attack_powerblast")
                recordAttack(v, 23)
                yellow = yellowTile(real(p.coords))
                gfx("spotanim.verzik_powerblast_safezone", yellow!!)
            }
            else -> greenBall(v, p)
        }
    }

    private fun yellowTile(from: CoordGrid): CoordGrid {
        repeat(60) {
            val c = CoordGrid(rand(3155, 3180), rand(4300, 4328), 0)
            if (abs(c.x - from.x) + abs(c.z - from.z) >= 4 && inArena(c) && !blocked(c)) return c
        }
        return CoordGrid(from.x + 3, from.z, 0)
    }

    private fun specialTick(v: Npc, p: Player) {
        val left = specialUntil - t
        if (yellow != null) {
            val y = yellow!!
            // the pool stays lit as she charges
            if (left % 4 == 0 && left > 0) gfx("spotanim.verzik_powerblast_safezone", y)
            if (left == 0) {
                val safe = real(p.coords) == y
                projTiles("spotanim.verzik_powerblast_projanim_down", real(p.coords), real(p.coords), delay = 0, speed = 30, startHeight = 400, endHeight = 0, target = p)
                if (safe) p.spotanim("spotanim.verzik_powerblast_protection") else hurt(p, rand(YELLOW_MIN, YELLOW_MAX), source = v, delay = 1)
                yellow = null
                endSpecial(YELLOW_AFTER)
            }
            return
        }
        // webs: three at a time every other tick, one on your tile
        if (left > WEB_AFTER && left % 2 == 0) {
            val here = real(p.coords)
            for (i in 0 until 3) {
                val tile = if (i == 0) here else CoordGrid(here.x + rand(-3, 3), here.z + rand(-3, 3), 0)
                if (!inArena(tile) || blocked(tile)) continue
                val lands = projTiles("spotanim.verzik_p3_web_proj", center(v), tile, delay = 10, speed = 8, startHeight = 100, endHeight = 0, angle = 30)
                later(lands) { web(tile) }
            }
        }
        if (left == 0) endSpecial(1)
    }

    private fun endSpecial(next: Int) {
        unblockWebs()
        specialUntil = -1
        invulnerable = false
        nextAttack = t + next
    }

    /** While she spins webs in the middle no one can stand under her. */
    private fun unblockWebs() {
        if (webBlock > 0) blockPlayers(CoordGrid(P3_X, P3_Y, 0), webBlock, false)
        webBlock = 0
    }

    override fun onCleanup() {
        unblockWebs()
        dawnOnFloor?.let { d.objRepo.del(it) }
        dawnOnFloor = null
    }

    private fun web(tile: CoordGrid) {
        if (dead >= 0 || phase != 3 || webs.any { real(it.npc.coords) == tile }) return
        val q = inside()
        val holding = q != null && real(q.coords) == tile
        val n = spawn(WEB, tile, WEB_HP)
        webs += Web(n, t, holding, loc(WEB_LOOK, tile, shape = LocShape.CentrepieceStraight))
        if (holding && q != null) {
            q.frozen = true
            q.routeDestination.clear()
            mes("You're stuck in the web!")
        }
    }

    private fun websTick() {
        val it = webs.iterator()
        while (it.hasNext()) {
            val w = it.next()
            val n = w.npc
            val gone = !n.isSlotAssigned || n.hitpoints <= 0
            if (gone || t - w.born >= WEB_LIFE || (w.holding && t - w.born >= WEB_SNAP)) {
                if (w.holding) {
                    owner.frozen = false
                    if (!gone) {
                        // not broken in time: it snaps
                        inside()?.let { hurt(it, rand(WEB_SNAP_MIN, WEB_SNAP_MAX)) }
                    }
                }
                if (n.isSlotAssigned) despawn(n)
                delLoc(w.look)
                it.remove()
            }
        }
    }

    private fun greenBall(v: Npc, p: Player) {
        recordAttack(v, 24)
        v.facePlayer(p)
        v.anim("seq.verzik_phase3_attack_magic")
        val lands = proj("spotanim.verzik_acidbomb_projanim", v, p, delay = 30, speed = 10, startHeight = 120)
        later(lands) {
            val q = inside() ?: return@later
            q.spotanim("spotanim.verzik_acidbomb_impact")
            hurt(q, q.baseHitpointsLvl * 3 / 4, source = verzik)
        }
    }

    private fun tornadoTick(v: Npc, p: Player) {
        if (!enraged) return
        val tor = tornado
        if (tor == null) {
            if (tornadoAt in 0..t) {
                tornado = spawn(TORNADO, tornadoTile(v), null)
                tornadoAt = -1
            }
            return
        }
        val tr = real(tor.coords)
        val here = real(p.coords)
        if (tr == here) {
            val dmg = p.hitpoints / 2
            hurt(p, dmg)
            healNpc(v, dmg * 3)
            v.spotanim("spotanim.verzik_p3_creeper_spot")
            vanish(tor)
            tornado = null
            tornadoAt = t + TORNADO_BACK
            return
        }
        val dx = here.x - tr.x
        val dz = here.z - tr.z
        val m = max(abs(dx), abs(dz))
        // Blert: it steps along the straight line at you, diagonal only when the short side is over half the long one
        // (598 of 645 steps)
        tor.walk(at(tr.x + if (2 * abs(dx) > m) sign(dx) else 0, tr.z + if (2 * abs(dz) > m) sign(dz) else 0))
    }

    /**
     * Where a tornado comes up: Blert's solos put it beside her, outside her body, anywhere from her edge to six tiles
     * off (it appears there as it is, upright, and starts after you a tick later).
     */
    private fun tornadoTile(v: Npc): CoordGrid {
        val sw = real(v.coords)
        repeat(TORNADO_TRIES) {
            val c = CoordGrid(sw.x + rand(-TORNADO_REACH, v.size - 1 + TORNADO_REACH), sw.z + rand(-TORNADO_REACH, v.size - 1 + TORNADO_REACH), 0)
            val inside = c.x in sw.x until sw.x + v.size && c.z in sw.z until sw.z + v.size
            if (!inside && inArena(c) && !blocked(c)) return c
        }
        return CoordGrid(sw.x - 1, sw.z, 0)
    }

    private fun vanish(tor: Npc) {
        tor.routeDestination.clear()
        tor.anim(TORNADO_DESPAWN_SEQ)
        later(TORNADO_DESPAWN_TICKS) { despawn(tor) }
    }

    /* ---- the nylocas that chase you (P2 and P3) */

    private fun crawler(p: Player) {
        val style = "MRG"[rand(3)]
        val n = spawn(NyloLooks.verzikCrawler(style), CoordGrid(P2_X, P2_Y + 7, 0), CRAWLER_HP)
        crawlers += Crawler(n, style, t)
    }

    /**
     * Blert's solo crabs, every one: they stand for 3 ticks, then step at you a tile a tick (diagonally when that
     * doesn't put them on your tile). From 5 ticks old, the first tick they end up right beside you (not diagonally)
     * they stop for good and pop 2 ticks later, gone 3 ticks after that, wherever you've gone.
     */
    private fun crawlersTick() {
        val p = inside()
        val it = crawlers.iterator()
        while (it.hasNext()) {
            val c = it.next()
            val n = c.npc
            if (!n.isSlotAssigned) {
                it.remove()
                continue
            }
            if (n.hitpoints <= 0) continue
            var here = real(n.coords)
            val age = t - c.born
            if (c.lockAt < 0) {
                val pc = p?.let { real(it.coords) }
                if (pc != null && age >= CRAWLER_FIRST_STEP && !besideCrab(here, pc) && n.vars["varn.freeze_end_clock"] <= d.mapClock.cycle) {
                    val next = crabStep(here, pc)
                    if (next != here) {
                        n.walk(at(next))
                        here = next
                    }
                }
                if ((pc != null && age >= CRAWLER_LOCK_AGE && besideCrab(here, pc)) || age >= CRAWLER_LIFE) c.lockAt = t
            }
            if (c.lockAt >= 0 && t - c.lockAt == CRAWLER_POP) {
                gfx(NyloLooks.detonateGfx(c.style), here)
                n.anim(NyloLooks.detonateSeq(c.style))
                val dmg = p?.let { q -> crabDamage(here, real(q.coords))?.let { hurt(q, it, source = n) } }
                logger.info { "verzik crab popped at age $age (stopped at ${c.lockAt - c.born}) at $here, player ${p?.let { real(it.coords) }}, damage $dmg" }
                n.hitpoints = 0
                later(CRAWLER_POP_TICKS) { if (n.isSlotAssigned) despawn(n) }
            }
        }
    }

    /** One step toward [target] for a 2x2 crab at [sw]: diagonal, else along x, else along z - never onto the target. */
    private fun crabStep(sw: CoordGrid, target: CoordGrid): CoordGrid {
        val dx = sign(target.x - sw.x)
        val dz = sign(target.z - sw.z)
        for ((mx, mz) in listOf(dx to dz, dx to 0, 0 to dz)) {
            if (mx == 0 && mz == 0) continue
            val next = CoordGrid(sw.x + mx, sw.z + mz, sw.level)
            if (crabGap(next, target) > 0) return next
        }
        return sw
    }

    /** Tiles between [target] and the 2x2 crab at [sw] (0: under it, 1: beside it). */
    private fun crabGap(sw: CoordGrid, target: CoordGrid): Int {
        val gx = if (target.x < sw.x) sw.x - target.x else if (target.x > sw.x + 1) target.x - sw.x - 1 else 0
        val gz = if (target.z < sw.z) sw.z - target.z else if (target.z > sw.z + 1) target.z - sw.z - 1 else 0
        return max(gx, gz)
    }

    private fun besideCrab(sw: CoordGrid, target: CoordGrid): Boolean {
        val inX = target.x in sw.x..sw.x + 1
        val inZ = target.z in sw.z..sw.z + 1
        return (inX && (target.z == sw.z - 1 || target.z == sw.z + 2)) || (inZ && (target.x == sw.x - 1 || target.x == sw.x + 2))
    }

    /**
     * A pop's damage by ring around the 2x2 crab: under it, then each ring out, to three tiles away. The south-east
     * corner tile of each ring (and of the crab) counts as the next ring out.
     */
    private fun crabDamage(sw: CoordGrid, target: CoordGrid): Int? {
        val dx = target.x - sw.x
        val dz = target.z - sw.z
        var ring = crabGap(sw, target)
        if (dx >= 1 && dz <= 0 && dx - 1 == -dz) ring++
        return CRAWLER_RING_DAMAGE.getOrNull(ring)
    }

    /* ---- the room's doors */

    override suspend fun opLoc(name: String, at: CoordGrid, op: Int, access: ProtectedAccess): Boolean {
        if (name == "loc.tob_dungeon_verzik_entrance_door") {
            val here = real(access.player.coords)
            access.telejump(this.at(if (here.z < at.z) CoordGrid(at.x, at.z + 1, 0) else CoordGrid(at.x, at.z - 1, 0)), TeleportType.Exempt)
            return true
        }
        return false
    }

    override fun reset() {
        owner.frozen = false
        super.reset()
    }

    private var dawnOnFloor: org.rsmod.game.obj.Obj? = null

    /** Restart room here starts you in the middle before P1, with the Dawnbringer taken back off everyone and left on the floor. */
    override fun restart(access: ProtectedAccess) {
        for (p in d.players) if (p.isSlotAssigned && contains(p.coords)) takeDawnbringer(p)
        restartAt(access, CENTRE)
        dawnOnFloor = d.objRepo.add(DAWNBRINGER, at(CENTRE), Int.MAX_VALUE)
    }

    private fun takeDawnbringer(p: Player) {
        val id = DAWNBRINGER.asRSCM(dev.openrune.rscm.RSCMType.OBJ)
        for (slot in p.inv.indices) if (p.inv[slot]?.id == id) p.inv[slot] = null
        for (slot in p.worn.indices) if (p.worn[slot]?.id == id) {
            p.worn[slot] = null
            p.rebuildAppearance()
        }
    }

    override fun autoTarget(): Npc? = athanatos?.takeIf { it.isSlotAssigned && it.hitpoints > 0 } ?: redCrabs.firstOrNull { it.isSlotAssigned && it.hitpoints > 0 } ?: verzik?.takeIf { phase == 1 || phase == 2 || phase == 3 }

    override val autoStart: CoordGrid
        get() = CoordGrid(3166, 4321, 0)

    override fun barBoss(): Npc? = verzik

    override fun debug(): String = "verzik ${verzik?.hitpoints} phase $phase attacks $attacks special $special/${specialUntil} reds $reds enraged $enraged crawlers ${crawlers.size} webs ${webs.size} pillars ${pillars.count { it.up }}"

    private companion object {
        const val INITIAL = "npc.verzik_initial"
        const val P1 = "npc.verzik_phase1"
        const val T12 = "npc.verzik_phase1_to2_transition"
        const val P2 = "npc.verzik_phase2"
        const val T23 = "npc.verzik_phase2_to3_transition"
        const val P3 = "npc.verzik_phase3"
        const val PILLAR_NPC = "npc.verzik_pillar_npc"
        const val ATHANATOS = "npc.tob_verzik_phase2_armourednylocas"
        const val RED = "npc.tob_verzik_phase2_bloodnylocas"
        const val WEB = "npc.verzik_web_npc"
        const val WEB_LOOK = "loc.verzik_webspin_loc"
        const val TORNADO = "npc.tob_verzik_creeper"
        const val THRONE = "loc.tob_dungeon_verzik_throne_empty"
        const val THRONE_TRANSFORMING = "loc.tob_dungeon_verzik_throne_transforming"
        const val THRONE_DOOR = "loc.tob_dungeon_verzik_throne_door_opened"
        const val THRONE_TRANSFORM_TICKS = 5
        const val TORNADO_REACH = 6
        const val TORNADO_TRIES = 40
        const val TORNADO_DESPAWN_SEQ = "seq.tob_shadow_projectile_despawn"
        const val TORNADO_DESPAWN_TICKS = 1
        const val DAWNBRINGER = "obj.verzik_special_weapon"
        val CENTRE = CoordGrid(3167, 4315, 0)
        const val P1_X = 3166
        const val P1_Y = 4323
        const val P2_X = 3167
        const val P2_Y = 4313
        const val P3_X = 3165
        const val P3_Y = 4311
        const val P1_HP = 1500
        const val P2_HP = 2437
        const val P3_HP = 2437
        const val P1_FIRST = 20
        const val P1_EVERY = 14
        const val P1_MAX = 137
        const val P1_MELEE_CAP = 10
        const val P1_RANGED_CAP = 3
        const val DAWN_SPEC_MIN = 75
        const val PILLAR_HP = 600
        const val PILLAR_FALL_MIN = 30
        const val PILLAR_FALL_MAX = 60
        const val PILLAR_FADE = 10
        const val PILLAR_COLLAPSE_TICKS = 4
        const val PILLAR_FADE_TICKS = 2
        // Transition timings from Blert recordings, counted from the tick the room sees the phase die (one after the
        // killing hit lands): P1 sits dead 3 more ticks, her climb-down form lasts 13 and P2 attacks 3 ticks in; P2's
        // body goes the tick after, the P2-to-P3 form lasts 4 and P3 attacks 6 ticks in
        const val T12_AT = 3
        const val T12_TICKS = 16
        const val P2_FIRST = 3
        const val P2_EVERY = 4
        const val URN_MAX = 44
        const val ZAP_MAX = 48
        const val ZAP_SPEED = 17
        const val URN_TICKS = 6

        /** Her P1 blast's flight, in ticks: a slow, heavy orb (an estimate - Blert has no hits on it to time). */
        const val P1_FLIGHT = 5
        const val PILLAR_HIT_SOUND = "synth.tob_pillar_hit"
        const val P1_IMPACT_SOUND = "synth.tob_verzik_spark_2"
        const val P3_DEATH_SOUND = "synth.tob_verzik_spider_death_1"

        /** How far her sounds carry: her own animations' sound reach in the cache. */
        const val SOUND_RADIUS = 15
        const val CRAWLER_POP_TICKS = 3
        const val CRAWLER_FIRST_STEP = 3
        const val CRAWLER_LOCK_AGE = 5
        const val CRAWLER_POP = 2
        val CRAWLER_RING_DAMAGE = listOf(46, 31, 17, 7)
        const val ZAP_BOOTS = 25
        const val BOUNCE_MAX = 45
        const val STOMP_MAX = 82
        const val BOUNCE_TILES = 3
        const val STUN = 5
        const val MIN_PURPLE = 16
        const val ATHANATOS_HP = 50
        const val ATHANATOS_FLIGHT = 6
        const val ATHANATOS_EVERY = 4
        const val REDS_PCT = 35
        const val RED_CYCLE = 44
        const val REDS_LAST_ATTACK = 36
        const val RED_STILL = 3
        const val RED_WANDER_CHANCE = 8
        const val RED_RANGE = 3
        const val RED_LIFE = 42
        const val RED_SPAWN_SEQ = "seq.elemental_spawn"
        const val RED_POP_SEQ = "seq.elemental_death"
        const val RED_POP_AT = 40
        const val RED_HEAL_PROJ = "spotanim.verzik_phase2_bloodproj"
        const val RED_HEAL_SPEED = 4
        const val SUMMON_TICKS = 12
        const val SHIELD_TICKS = 10
        const val RED_HP = 150
        /** Not recorded; scaled from the Maiden crabs (75 a solo on Normal, 16 on Entry). */
        const val ENTRY_RED_HP = 32
        const val BLOOD_MAX = 45
        /** Her heal rolls 0-47 and she takes half of it, halved again through Protect from Magic. */
        const val BLOOD_HEAL_MAX = 47
        const val T23_AT = 1
        const val T23_TICKS = 5
        const val P3_FIRST = 6
        const val P3_EVERY = 7
        const val P3_EVERY_ENRAGED = 5
        const val P3_MAX = 33
        const val P3_MAX_ENRAGED = 34
        const val P3_MELEE_MAX = 63
        const val ENRAGE_PCT = 20
        const val WEB_TICKS = 44
        const val WEB_AFTER = 14
        const val WEB_HP = 10
        const val WEB_LIFE = 30
        const val WEB_SNAP = 6
        const val WEB_SNAP_MIN = 40
        const val WEB_SNAP_MAX = 60
        const val YELLOW_TICKS = 14
        const val YELLOW_AFTER = 7
        const val YELLOW_MIN = 40
        const val YELLOW_MAX = 80
        const val TORNADO_BACK = 16
        const val CRAWLER_HP = 11
        const val CRAWLER_LIFE = 25
        const val DEATH_B_AT = 2
        const val DEATH_TICKS = 10
        const val DEATH_B_TICKS = 2
        const val DEATH_BAT = "npc.verzik_death_bat"

        val PILLARS = listOf(3161 to 4318, 3161 to 4312, 3161 to 4306, 3173 to 4318, 3173 to 4312, 3173 to 4306)
    }
}

private var Player.throneVisible by boolVarBit("varbit.tob_verzik_throne_visible")
