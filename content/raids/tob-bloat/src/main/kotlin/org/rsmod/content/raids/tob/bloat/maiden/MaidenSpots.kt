package org.rsmod.content.raids.tob.bloat.maiden

import kotlin.math.abs

/**
 * Where a bot goes for an attack, in the room's tiles (x east, y south; 28 x 24), and whether it can attack from where
 * it stands: ranged and spells from the sim's own spots (10 tiles out, the casters' row) and from anywhere in the weapon's range;
 * melee from the nearest tile next to what it's hitting (a halberd: within 2) - not the sim's fixed side, which can be
 * the far side of her (moving is free in the sim; in the room it costs ticks).
 */
object MaidenSpots {
    /**
     * Where to stand for attack [a], from (x, y): a tile it reaches from in the fewest steps (2 tiles a tick), and of
     * those the one nearest what the line after it ([then]) goes for - so a bot hitting a crab by her is already next
     * to her for the scythe after. Ranged and spells stay put when they reach from here (no walking back to a spot
     * only to come straight back), else they go to the sim's spot, or the reaching tile nearest it.
     */
    fun goal(engine: MaidenEngine, p: MaidenEngine.P, a: String, x: Int, y: Int, then: String? = null, lead: Int = 1): IntArray? {
        val (box, range) = engine.reach(p, a, lead) ?: return engine.spotFor(p, a)
        // P3 bows on her from the north side (Blert): at max range while more bows follow, else 5-7 out so the
        // walk back to the crabs is short; stays if already there, else the nearest such tile
        val north = engine.northBowTile(p, a) != null
        val far = if (then == null) !engine.procSoon(FAR_LOOKAHEAD) else then.substringBefore('|').substringBefore(':').let { it == "tbr" || it == "tba" }
        val min = if (north) MaidenEngine.TBOW_MIN else engine.standsFrom(p, a)
        fun northOf(tx: Int, ty: Int) = (if (far) ty <= box[1] - MaidenEngine.TBOW_STAND else ty in box[1] - NEAR_NORTH..box[1] - MaidenEngine.TBOW_MIN) && tx in box[0] - 3..box[2] + 5
        // a proc coming with no crab work listed yet: hold wherever on the north side (no walking in and out)
        val settled = north && then == null && !far && y <= box[1] - MaidenEngine.TBOW_MIN && x in box[0] - 3..box[2] + 5
        if (north && (northOf(x, y) || settled) && reaches(box, range, x, y, min)) return intArrayOf(x, y)
        if (!north && range >= 5 && reaches(box, range, x, y, min)) return intArrayOf(x, y)
        val spot = if (range >= 5 && !north) engine.spotFor(p, a) else null
        if (spot != null && reaches(box, range, spot[0], spot[1], min)) return spot
        val next = then?.let { engine.reach(p, it, lead)?.first }
        var best: IntArray? = null
        var bd = Long.MAX_VALUE
        for (tx in box[0] - range..box[2] + range) for (ty in box[1] - range..box[3] + range) {
            if (tx !in 0..27 || ty !in 0..23) continue
            if (!reaches(box, range, tx, ty, min) || insideHer(tx, ty) || (north && !northOf(tx, ty))) continue
            val far = maxOf(abs(tx - x), abs(ty - y))
            val ticks = (far + 1) / 2
            val tie = if (spot != null) maxOf(abs(tx - spot[0]), abs(ty - spot[1])) else if (next != null) boxDist(next, tx, ty) else far
            val d = ticks * 10_000L + tie * 100L + abs(tx - x) + abs(ty - y)
            if (d < bd) {
                bd = d
                best = intArrayOf(tx, ty)
            }
        }
        return best
    }

    /** How far north of her P3 waits for crab work after bowing (Blert: 5-7 out). */
    private const val NEAR_NORTH = 7

    /** Ticks ahead P3 looks for a proc before heading out to max range (the walk out and back). */
    private const val FAR_LOOKAHEAD = 15

    private fun boxDist(b: IntArray, x: Int, y: Int): Int = maxOf(maxOf(b[0] - x, x - b[2], 0), maxOf(b[1] - y, y - b[3], 0))

    fun canAttack(engine: MaidenEngine, p: MaidenEngine.P, a: String, x: Int, y: Int): Boolean {
        val (box, range) = engine.reach(p, a) ?: return true
        return reaches(box, range, x, y, engine.minReach(p, a))
    }

    /**
     * Where a click on what attack [a] is on walks a bot from (x, y): the tile beside it nearest the bot - the server's
     * route to an NPC, which the bot follows until it's in range (it stops there and the attack goes).
     */
    fun approach(engine: MaidenEngine, p: MaidenEngine.P, a: String, x: Int, y: Int): IntArray? {
        val (box, range) = engine.reach(p, a) ?: return null
        var best: IntArray? = null
        var bd = Int.MAX_VALUE
        for (tx in box[0] - 1..box[2] + 1) for (ty in box[1] - 1..box[3] + 1) {
            if (!reaches(box, range, tx, ty)) continue
            if (tx !in 0..27 || ty !in 0..23 || insideHer(tx, ty)) continue
            val d = maxOf(abs(tx - x), abs(ty - y)) * 100 + abs(tx - x) + abs(ty - y)
            if (d < bd) {
                bd = d
                best = intArrayOf(tx, ty)
            }
        }
        return best
    }

    fun insideHer(x: Int, y: Int): Boolean {
        val h = MaidenEngine.MAIDEN_TILE
        return x in h.x until h.x + h.size && y in h.y until h.y + h.size
    }

    /** Melee: next to it, not diagonally (range 1); else within [range] (the weapon's), and at least [min] out. */
    fun reaches(box: IntArray, range: Int, x: Int, y: Int, min: Int = 1): Boolean {
        val dx = maxOf(box[0] - x, x - box[2], 0)
        val dy = maxOf(box[1] - y, y - box[3], 0)
        return if (range == 1) (dx == 1 && dy == 0) || (dx == 0 && dy == 1) else maxOf(dx, dy) in min..range
    }

    /** A step toward (gx, gy), diagonal first, around her 6x6. */
    fun step(x: Int, y: Int, gx: Int, gy: Int): IntArray {
        if (x == gx && y == gy) return intArrayOf(x, y)
        val sx = Integer.signum(gx - x)
        val sy = Integer.signum(gy - y)
        for ((dx, dy) in listOf(sx to sy, sx to 0, 0 to sy, sx to -1, sx to 1, -1 to sy, 1 to sy)) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx
            val ny = y + dy
            if (!insideHer(nx, ny) && nx in 0..27 && ny in 0..23) return intArrayOf(nx, ny)
        }
        return intArrayOf(x, y)
    }
}
