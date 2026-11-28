package org.rsmod.content.raids.tob.bloat

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.NpcMode
import dev.openrune.types.aconverted.SpotanimType
import org.rsmod.annotations.InternalApi
import org.rsmod.api.config.Constants
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.player.clearInteractionRoute
import org.rsmod.api.player.hit.modifier.PlayerHitModifier
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.content.raids.tob.bloat.BloatRules.DOWN_TICKS
import org.rsmod.content.raids.tob.bloat.BloatRules.LOOP
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.util.PathingEntityCommon
import org.rsmod.game.hit.HitType
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * One Bloat room: his walk, turns, speed, downs, stomp, hands and flies, run on the server tick
 * (ported from verzikSim engine/bloat.js `runBloat`). [tick] runs in the world step, before the
 * NPCs and players move, so it sees everyone where they stood at the end of the last tick - as the
 * game does (NPCs act before players). Hits on him are halved by [BloatHitLand] unless he is on one
 * of the 32 full-damage ticks of a down.
 *
 * He walks the loop from the moment the room is built; tick 0 (the room's start) is the first tick
 * anyone is inside.
 */
class BloatRoom(
    val session: InstanceSession,
    val npc: Npc,
    val base: CoordGrid,
    private val random: GameRandom,
    private val worldRepo: WorldRepository,
    private val hitModifier: PlayerHitModifier,
    private val collision: CollisionFlagMap,
    max: Int,
    private val southWest: CoordGrid,
    private val northEast: CoordGrid,
) {
    private val logger = InlineLogger()

    var max: Int = max
        private set

    /** The room's hitpoints for the team that came in (set as the room starts). */
    fun resize(hitpoints: Int) {
        max = hitpoints
        npc.baseHitpointsLvl = hitpoints
        npc.hitpoints = hitpoints
    }

    /** Which way round he walks: 1 anticlockwise, -1 clockwise. */
    val heading: Int
        get() = dir

    /** Hands falling: the tick each lands and its room tiles. */
    val falling: List<Pair<Int, List<Pair<Int, Int>>>>
        get() = hands

    /** Where his south-west tile will be [ticks] on if he keeps walking (no turn, no down). */
    fun ahead(ticks: Int): Pair<Int, Int> {
        var k = li
        var war = walkAfterTurn
        repeat(ticks) {
            val next = LOOP[Math.floorMod(k + dir, LOOP.size)]
            val stride = if (speed < 2 || war > 0 || next in BloatRules.CORNERS) 1 else 2
            k = Math.floorMod(k + dir * stride, LOOP.size)
            war = (war - 1).coerceAtLeast(0)
        }
        return LOOP[k]
    }

    /** Downs so far, the walk he's on counting from 1. */
    val walks: Int
        get() = walkN

    private val sight = org.rsmod.routefinder.LineValidator(collision)

    /**
     * Whether he sees a player at room tile (px, py) from his south-west tile (bx, by): the engine's line of sight
     * from his centre tile, stopped by the tank and walls. Fitted to Blert's real solos: from the centre, 13 of 17
     * fly hits had sight and only 4 sighted ticks went without one; from the nearest tile of his 5x5 the same hits
     * came with 71 sighted ticks that had none - the corner and tank-edge spots where players shoot him safely.
     */
    fun sees(bx: Int, by: Int, px: Int, py: Int): Boolean {
        val from = tile(bx + 2, by + 2)
        val to = tile(px, py)
        return sight.hasLineOfSight(from.level, from.x, from.z, to.x, to.z)
    }

    /** Teammate bots in this room. */
    val bots = ArrayList<RoomBot>()

    /** Log every tick (self-tests). */
    var log = false

    var t = -1
        private set

    var mode = Mode.Walk
        private set

    // his place on the loop and direction (1 anticlockwise, -1 clockwise); random until a start is set
    private var li = random.of(0, LOOP.size - 1)
    private var dir = if (random.randomBoolean()) 1 else -1
    private var walkT = 0
    private var walkN = 1
    private var turnCd = 0
    private var block = 0
    private var speed = 1
    private var walkAfterTurn = 0
    private var downAt = 0
    private var attackedInDown = false
    private var flipN = 0
    private var below40 = false
    private var handGap = 0
    private var lastHands = emptyList<Pair<Int, Int>>()
    private val hands = ArrayList<Pair<Int, List<Pair<Int, Int>>>>()
    private var swarm: Player? = null
    private val inSight = HashMap<Player, Int>()
    private var stompOn = emptyList<Player>()
    var defence = BloatRules.BASE_DEFENCE
        private set

    enum class Mode { Walk, Down }

    /** Down counter: 32 the tick he goes down (32..1 full damage), 0 the tick he steps again; null while he walks. */
    val down: Int?
        get() = if (mode == Mode.Down) DOWN_TICKS - 1 - (t - downAt) else null

    /**
     * Whether a hit landing now does full damage: attacks made on the counter's 32 to 1 do, and a melee hit lands the
     * tick after its swing, so a scythe on 1 lands on 0 (as the stomp lands) at full.
     */
    val fullDamage: Boolean
        get() = down?.let { it in 0..31 } ?: false

    val sw: Pair<Int, Int>
        get() = LOOP[li]

    fun local(c: CoordGrid): Pair<Int, Int> = (c.x - base.x) to (c.z - base.z)

    fun tile(x: Int, y: Int): CoordGrid = base.translate(x, y)

    /** Where he stands on tick 0, and which way he walks. */
    fun start(x: Int, y: Int, dir: Int) {
        val k = LOOP.indexOf(x to y)
        if (k < 0) return
        this.dir = dir
        li = Math.floorMod(k - dir, LOOP.size)
        npc.telejump(collision, tile(LOOP[li].first, LOOP[li].second))
    }

    /** Players inside the room (past the barriers). */
    /** Living players inside the room (past the barriers); the dead are nobody's target. */
    fun entrants(players: Iterable<Player>): List<Player> =
        players.filter { p ->
            val (x, y) = local(p.coords)
            p.coords.level == base.level && BloatRules.inRoom(x, y) && !BloatRules.inTank(x, y) && p.hitpoints > 0
        }

    fun contains(c: CoordGrid): Boolean =
        c.level == southWest.level && c.x in southWest.x..northEast.x && c.z in southWest.z..northEast.z

    /** Living players and bots inside, as of the last tick. */
    fun aliveInside(): Int = lastInside.count { it.hitpoints > 0 && contains(it.coords) && local(it.coords).let { (x, y) -> BloatRules.inRoom(x, y) } }

    private var lastInside = emptyList<Player>()

    private val losWatch = HashSet<Player>()
    private val losSeen = HashMap<Player, Boolean>()

    /** `::bloat los`: a chat line whenever his sight of you changes (with both tiles), to check his line of sight. */
    fun toggleLos(player: Player) {
        if (!losWatch.add(player)) {
            losWatch.remove(player)
            losSeen.remove(player)
            player.mes("Line-of-sight messages off.")
        } else {
            player.mes("Line-of-sight messages on: you'll see when Bloat starts and stops seeing you (room tiles, his south-west tile).")
        }
    }

    /**
     * Back to the start: full hitpoints and Defence, the room's counters cleared (he keeps patrolling and
     * the room starts again when someone comes in); bots back outside the barrier, restored.
     */
    @OptIn(InternalApi::class)
    fun reset() {
        t = -1
        mode = Mode.Walk
        walkT = 0
        walkN = 1
        turnCd = 0
        block = 0
        speed = 1
        walkAfterTurn = 0
        attackedInDown = false
        flipN = 0
        below40 = false
        handGap = 0
        hands.clear()
        swarm = null
        inSight.clear()
        stompOn = emptyList()
        defence = BloatRules.BASE_DEFENCE
        if (npc.isSlotAssigned) {
            npc.hitpoints = max
            npc.resetAnim()
        }
        for ((i, rb) in bots.withIndex()) {
            val p = rb.player
            p.clearQueue("queue.hit")
            p.statMap.setCurrentLevel("stat.hitpoints", p.statMap.getBaseLevel("stat.hitpoints"))
            PathingEntityCommon.telejump(p, collision, tile(25, 14 + i % 4))
            rb.clickedBarrier = false
        }
        logger.info { "bloat room reset" }
    }

    fun tick(players: List<Player>) {
        if (!npc.isSlotAssigned || npc.hitpoints <= 0) return
        npc.mode = NpcMode.None
        val inside = entrants(players)
        lastInside = inside
        // before anyone comes in he patrols the loop at a walk, the same way round; the room (its tick
        // counters: walk length, turns, downs, hands, flies) starts on the first tick someone is inside,
        // from wherever he has got to
        if (t < 0 && inside.isEmpty()) {
            val from = LOOP[li]
            li = Math.floorMod(li + dir, LOOP.size)
            walkTo(from, LOOP[li])
            return
        }
        t++
        // his flies see from the tile he stood on at the end of last tick, before this tick's step: Blert
        // recordings of real raids fit that (and a hit one tick later) where his new tile doesn't
        val seenFrom = sw

        // ---- him
        if (mode == Mode.Down && t - downAt >= DOWN_TICKS) {
            mode = Mode.Walk
            walkT = 0
            walkN++
            handGap = 0
        }
        if (mode == Mode.Walk) {
            walkT++
            turnCd++
            if (block > 0) block--
            val pct = npc.hitpoints * 100 / max
            val want = if (pct >= 60) 1 else if (pct >= 40) 2 else if (flipN % 2 == 0) 2 else 1
            // a speed change at 60% / 40% stops a down for 5 ticks; the flips below 40% don't
            if (want != speed) {
                if (pct >= 40) block = BloatRules.BLOCK
                speed = want
            }
            if (turnCd > BloatRules.TURN_CD && random.of(1000) < BloatRules.TURN_PER_MILLE) {
                dir = -dir
                turnCd = 0
                block = BloatRules.BLOCK
                walkAfterTurn = BloatRules.WALK_AFTER_TURN
            }
            // he goes down on a tick he doesn't step (the real game: his last step, then the down animation on
            // the first tick he stands still), so the decision comes before the step
            val window = if (walkN == 1) 39..46 else if (!attackedInDown) 38..45 else 34..41
            val walked = walkT - 1
            var goDown = false
            if (block == 0) {
                if (walked > window.last) goDown = true
                else if (walked >= window.first && random.of(4) == 0) goDown = true
            }
            if (!goDown) {
                val from = LOOP[li]
                li = Math.floorMod(li + dir * stride(), LOOP.size)
                walkTo(from, LOOP[li])
                if (walkAfterTurn > 0) walkAfterTurn--
            }
            if (pct < 90) {
                if (handGap <= 0) {
                    dropHands()
                    handGap = if (pct >= 40) 6 else 4
                }
                handGap--
            }
            if (goDown) {
                mode = Mode.Down
                downAt = t
                attackedInDown = false
                npc.anim(DOWN_SEQ)
                logger.debug { "bloat t$t down (walk $walkN, $walkT ticks)" }
            }
        } else if (t - downAt == BloatRules.STOMP_AT) {
            val (bx, by) = sw
            stompOn = inside.filter { p -> local(p.coords).let { (x, y) -> sees(bx, by, x, y) } }
            defence = BloatRules.BASE_DEFENCE
        } else if (t - downAt == BloatRules.STOMP_HIT) {
            for (p in inside) shake(p)
            for (p in stompOn) {
                if (p !in inside) continue
                p.queueHit(npc, BloatRules.STOMP_HIT_DELAY, HitType.Typeless, if (entry) random.of(BloatRules.STOMP_MIN / 2, BloatRules.STOMP_MAX / 2) else random.of(BloatRules.STOMP_MIN, BloatRules.STOMP_MAX), hitModifier)
            }
        }

        // the tick counter over him: ticks walked (the tick he goes down still shows his walk count), then
        // while down 32 (first full-damage tick) to 1 (the stomp lands; 3 is the stomp) and 0 as he gets up
        npc.say(if (mode == Mode.Walk || t == downAt) "$walkT" else "${down}")

        // ---- hands landing (the shadows fell 3 ticks ago)
        val landing = hands.filter { it.first == t }
        hands.removeAll(landing.toSet())
        for ((_, tiles) in landing) {
            for (p in inside) {
                if (local(p.coords) !in tiles) continue
                p.queueHit(npc, 1, HitType.Typeless, random.of(BloatRules.HAND_MIN, BloatRules.HAND_MAX), hitModifier)
                p.delay = p.currentMapClock + BloatRules.HAND_STUN
                // a delay only blocks new clicks: an attack on him would keep walking them after him
                p.clearInteractionRoute()
                p.spotanim(STUN_SPOT)
                p.anim("seq.tob_bloat_stunned_short")
            }
        }

        // ---- flies while he walks: one swarm on a player in his sight on this tick's check, hitting them
        // every tick while he still sees them; when he loses them it goes to another player he sees (one the
        // last can see first), else waits
        val (bx, by) = seenFrom
        for (p in players) {
            val (x, y) = local(p.coords)
            val sees = mode == Mode.Walk && p in inside && sees(bx, by, x, y)
            if (sees) inSight[p] = (inSight[p] ?: 0) + 1 else inSight.remove(p)
            if (p in losWatch) {
                val was = losSeen[p] ?: false
                if (sees != was) {
                    losSeen[p] = sees
                    p.mes(if (sees) "Bloat can see you at ($x,$y) - he's at (${bx},${by})." else "Bloat lost sight of you at ($x,$y) - he's at (${bx},${by}).")
                }
            }
        }
        if (mode == Mode.Walk) {
            val cur = swarm
            if (cur == null || cur !in inSight) {
                val ready = inSight.keys.toList()
                val next =
                    cur?.let { c ->
                        val (cx, cy) = local(c.coords)
                        ready.firstOrNull { r -> local(r.coords).let { (rx, ry) -> sight.hasLineOfSight(base.level, base.x + cx, base.z + cy, base.x + rx, base.z + ry) } }
                    } ?: ready.randomOrNull(random)
                swarm = next
            }
            swarm?.let { p ->
                val (px, py) = local(p.coords)
                logger.info { "bloat flies t$t on ${p.username} at $px,$py from B$bx,$by (sight ${inSight[p]} ticks)" }
                val roll = random.of(1, if (entry) ENTRY_MAX_FLIES else BloatRules.MAX_FLIES)
                val dmg = if (p.overheadIcon == Constants.overhead_protect_from_missiles) roll * 3 / 4 else roll
                val src = npc.coords.translate(2, 2)
                // the swarm drifts across the floor upright, like Maiden's shadow: no climb or drop, no arc
                worldRepo.projAnim(
                    org.rsmod.game.proj.ProjAnim(
                        FLIES_PROJ.asRSCM(RSCMType.SPOTANIM),
                        0,
                        0,
                        0,
                        FLY_TICKS * 30,
                        0,
                        64 * 5,
                        npc.slotId + 1,
                        -(p.slotId + 1),
                        src,
                        p.coords,
                    ),
                )
                p.spotanim(FLIES_SPOT, delay = FLY_TICKS * 30)
                worldRepo.soundArea(p.coords, FLIES_SOUNDS[random.of(0, FLIES_SOUNDS.size - 1)], radius = FLIES_SOUND_RADIUS)
                p.queueHit(npc, FLY_TICKS, HitType.Typeless, dmg, hitModifier)
            }
        } else {
            swarm = null
        }
    }

    /**
     * The stomp's screen shake: a random jitter both ways (no sway) through the counter's 2 and 1, about twice the size
     * the 6 we had was (a real clip: a pixel or two each frame for two ticks; ours barely showed).
     */
    private fun shake(p: Player) {
        org.rsmod.api.player.output.Camera.camShake(p, org.rsmod.api.player.output.CamShakeAxis.LEFT_RIGHT, SHAKE_RANDOM, 0, 0)
        org.rsmod.api.player.output.Camera.camShake(p, org.rsmod.api.player.output.CamShakeAxis.UP_DOWN, SHAKE_RANDOM, 0, 0)
        worldQueues?.add(SHAKE_TICKS) { if (p.isSlotAssigned) org.rsmod.api.player.output.Camera.camShakeResetAll(p) }
    }

    /** Entry mode: flies hit up to 8 and the stomp 20 to 40 (Normal: 20, and 40 to 80). */
    var entry = false

    /** Set by the room's owner: the world queue, for timed effects. */
    var worldQueues: org.rsmod.game.queue.WorldQueueList? = null

    private fun List<Player>.randomOrNull(random: GameRandom): Player? = if (isEmpty()) null else this[random.of(0, size - 1)]

    /**
     * Tiles he covers this tick. Blert: a run never carries him round a corner (he stops on it, then runs on down the
     * next side), and after turning back he walks three steps before he runs again.
     */
    private fun stride(): Int {
        if (speed < 2 || walkAfterTurn > 0) return 1
        return if (LOOP[Math.floorMod(li + dir, LOOP.size)] in BloatRules.CORNERS) 1 else 2
    }

    private fun walkTo(from: Pair<Int, Int>, to: Pair<Int, Int>) {
        npc.routeDestination.clear()
        val steps = maxOf(kotlin.math.abs(to.first - from.first), kotlin.math.abs(to.second - from.second))
        if (steps >= 2) {
            val mid = LOOP[Math.floorMod(li - dir, LOOP.size)]
            npc.routeDestination.add(tile(mid.first, mid.second))
        }
        npc.routeDestination.add(tile(to.first, to.second))
        npc.moveSpeed = if (steps >= 2) MoveSpeed.Run else MoveSpeed.Walk
        npc.forcedRoute = true
    }

    private fun dropHands() {
        val cur = ArrayList<Pair<Int, Int>>()
        for ((cx, cy) in BloatRules.HAND_POINTS) {
            var x = random.of(cx - 2, cx + 2)
            var y = random.of(cy - 2, cy + 2)
            if ((x to y) !in lastHands && (x to y) !in cur) {
                cur += x to y
                continue
            }
            val ox = x
            val oy = y
            var n = 0
            do {
                x = random.of(ox - 1, ox + 1)
                y = random.of(oy - 1, oy + 1)
                n++
            } while ((!BloatRules.handOk(x, y) || (x == ox && y == oy)) && n < 50)
            if (n < 50 && (x to y) !in lastHands && (x to y) !in cur) cur += x to y
        }
        lastHands = cur
        val tiles = cur.map { (x, y) -> (x + 8) to (y + 8) }
        hands += (t + BloatRules.HAND_FALL) to tiles
        for ((i, xy) in tiles.withIndex()) {
            worldRepo.spotanimMap(SpotanimType(FLESH[i % FLESH.size].asRSCM(RSCMType.SPOTANIM)), tile(xy.first, xy.second))
        }
    }

    /**
     * A hit landing on him: halved per hitsplat (rounded down) unless he's on a full-damage tick of a
     * down. Counts the splats that flip his speed below 40%.
     */
    fun land(damage: Int): Int {
        val full = fullDamage
        if (full) attackedInDown = true
        val dealt = if (full) damage else damage / 2
        val after = npc.hitpoints - dealt
        if (after * 100 < max * 40) {
            if (!below40) {
                below40 = true
                flipN = 0
            }
            flipN++
        }
        return dealt
    }

    fun drainDefence(amount: Int) {
        defence = (defence - amount).coerceAtLeast(0)
    }

    private companion object {
        const val DOWN_SEQ = "seq.tob_bloat_sleep"
        const val FLIES_SPOT = "spotanim.tob_bloat_flies_small"
        val FLIES_SOUNDS =
            listOf(
                "synth.tob_bloat_flies_attack_1",
                "synth.tob_bloat_flies_attack_2",
                "synth.tob_bloat_flies_attack_3",
                "synth.tob_bloat_flies_attack_4",
                "synth.tob_bloat_flies_attack_5",
                "synth.tob_bloat_flies_attack_6",
            )
        const val FLIES_SOUND_RADIUS = 10
        const val FLIES_PROJ = "spotanim.tob_bloat_flies_large"
        const val FLY_TICKS = 1
        const val ENTRY_MAX_FLIES = 8
        const val SHAKE_RANDOM = 12
        const val SHAKE_TICKS = 2
        const val STUN_SPOT = "spotanim.tob_bloat_stunned"
        val FLESH =
            listOf(
                "spotanim.tob_bloat_falling_flesh1",
                "spotanim.tob_bloat_falling_flesh2",
                "spotanim.tob_bloat_falling_flesh3",
                "spotanim.tob_bloat_falling_flesh4",
            )
    }
}
