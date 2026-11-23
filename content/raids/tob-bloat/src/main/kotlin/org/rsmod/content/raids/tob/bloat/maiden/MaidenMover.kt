package org.rsmod.content.raids.tob.bloat.maiden

/**
 * Where the bots stand, tick by tick, in the room's tiles: what the sim leaves out (it moves players for free). It plays
 * the clicks a player makes, the way the game moves them:
 *  - an attack goes the tick its bot is in range (the weapon's own: [MaidenEngine.RANGE]); out of range, the bot has
 *    clicked what it's attacking and is dragged at it (2 tiles a tick, running) - stopping the tick it's in range,
 *    where the attack goes that same tick;
 *  - the team runs in from the entrance on tick [RUN_FROM], each having clicked what their first attack is on;
 *  - the tick an attack goes, the bot clicks on to where its next one goes from ([MaidenSpots.goal]) and keeps running:
 *    the opening bow shot is fired on the run in, and the bot carries on toward her (no tick stood still);
 *  - a bot that goes after a walking crab once it thaws heads its way early, on the side of her nearest it.
 * Each tick moves first, then attacks (as the game does: the attack goes from the tile the bot ran to that tick).
 *
 * Plain arithmetic on the engine, nothing of the server: the live room walks each bot to [pos] every tick, and the raid
 * index plays the same raids headless - so a seed gives the same raid in both, and the index's times are the room's.
 */
class MaidenMover(private val e: MaidenEngine) {
    val pos: List<IntArray> = e.ps.map { intArrayOf(it.pos[0], it.pos[1]) }
    private val goal: List<IntArray> = e.ps.map { intArrayOf(it.pos[0], it.pos[1]) }
    private val heldAt = IntArray(e.ps.size) { -1 }

    /** The attack each bot has clicked and is being dragged in range of (null: walking to a tile, or standing). */
    private val drag = arrayOfNulls<String>(e.ps.size)

    /** Attacks held so far (a tick each), for the checks. */
    var holds = 0
        private set

    /** Called on a hold: player, attack, where they are, where they're going. */
    var onHold: ((MaidenEngine.P, String, IntArray, IntArray) -> Unit)? = null

    init {
        e.gate = { p, _, a -> gate(p, a) }
    }

    private fun gate(p: MaidenEngine.P, a: String): Boolean {
        val xy = pos[p.i]
        if (MaidenSpots.canAttack(e, p, a, xy[0], xy[1])) {
            drag[p.i] = null
            return true
        }
        heldAt[p.i] = e.t
        holds++
        drag[p.i] = a
        MaidenSpots.approach(e, p, a, xy[0], xy[1])?.let { set(p, it) }
        onHold?.invoke(p, a, xy, goal[p.i])
        return false
    }

    /**
     * Before the engine's tick: every bot runs up to 2 tiles of the way - a dragged one stays put once it's in range (the
     * game checks between ticks, not between the two tiles: the bow's opening shot goes from 9 tiles, not 10).
     */
    fun move() {
        if (e.t + 1 < RUN_FROM) return
        for (p in e.ps) {
            val xy = pos[p.i]
            val d = drag[p.i]
            if (d != null) {
                if (MaidenSpots.canAttack(e, p, d, xy[0], xy[1])) continue
                MaidenSpots.approach(e, p, d, xy[0], xy[1])?.let { set(p, it) }
            }
            repeat(RUN) {
                val s = MaidenSpots.step(xy[0], xy[1], goal[p.i][0], goal[p.i][1])
                xy[0] = s[0]
                xy[1] = s[1]
            }
        }
    }

    /** After the engine's tick: every bot not being dragged picks where it's heading (its click for the next attack). */
    fun afterTick() {
        for (p in e.ps) {
            val xy = pos[p.i]
            if (!p.started) {
                val a = e.lineAt(p)
                drag[p.i] = a
                MaidenSpots.approach(e, p, a, xy[0], xy[1])?.let { set(p, it) }
            } else if (heldAt[p.i] != e.t && p.pending == null) {
                drag[p.i] = null
                val chase = e.chaseOf(p)?.let { "scyc:${it.key}" }
                MaidenSpots.goal(e, p, e.ahead(p, e.lineAt(p))!!, xy[0], xy[1], chase ?: e.ahead(p, e.lineAfter(p)))?.let { set(p, it) }
            }
        }
    }

    fun goalOf(p: MaidenEngine.P): IntArray = goal[p.i]

    private fun set(p: MaidenEngine.P, g: IntArray) {
        goal[p.i][0] = g[0]
        goal[p.i][1] = g[1]
    }

    companion object {
        /**
         * The tick the team runs in from the entrance, each having clicked her (or what their first attack is on): the
         * opening bow shot goes on tick 5 at 9 tiles, Ralos and the Zaryte crossbow a tick after at 7 (blert's 1:01.2s).
         */
        const val RUN_FROM = 2

        /** Tiles a tick, running. */
        const val RUN = 2

        /** One raid, headless: the engine with the bots' movement. */
        fun play(seed: String, log: MutableList<String>? = null, onHold: ((MaidenEngine, MaidenEngine.P, String, IntArray, IntArray) -> Unit)? = null): MaidenEngine {
            val e = MaidenEngine(5, MaidenPlans.FIVE_STACK, SimRng(seed), MaidenPlans.FIVE_TARGET, log = log)
            val m = MaidenMover(e)
            if (onHold != null) m.onHold = { p, a, xy, g -> onHold(e, p, a, xy, g) }
            while (!e.over && e.t < MAX_T) {
                m.move()
                e.tick()
                m.afterTick()
            }
            return e
        }

        const val MAX_T = 600
    }
}
