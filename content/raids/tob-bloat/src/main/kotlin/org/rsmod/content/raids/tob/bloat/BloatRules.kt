package org.rsmod.content.raids.tob.bloat

/**
 * Pestilent Bloat's room and rules, ported from verzikSim (engine/bloat.js), where they were checked
 * against ~500 Blert recordings and in-game captures. Room-local tiles: x east / y north from the
 * room's static (3280, 4432). Bloat is 5x5; his south-west tile walks the square loop x, y in 8..19
 * around the tank (13..18 x 13..18, which blocks movement and sight).
 */
object BloatRules {
    const val ROOM_X = 3280
    const val ROOM_Y = 4432
    const val REGION = 13125

    const val TANK_MIN = 13
    const val TANK_MAX = 18
    const val ROOM_MIN = 8
    const val ROOM_MAX = 23

    /** Hitpoints by team size (normal mode). */
    fun hitpoints(team: Int): Int = when { team >= 5 -> 2000; team == 4 -> 1750; else -> 1500 }

    /** Down for 33 ticks from the first tick he stands still; he steps again on the 34th (Blert: always 33). */
    const val DOWN_TICKS = 33

    /** The stomp animation 29 ticks into a down; its damage lands 2 ticks later (Blert recordings). */
    const val STOMP_AT = 29
    const val STOMP_HIT = 30
    const val TURN_CD = 32
    const val TURN_PER_MILLE = 50
    const val BLOCK = 5
    const val HAND_FALL = 3
    const val HAND_STUN = 4
    const val MAX_FLIES = 20
    const val STOMP_MIN = 40
    const val STOMP_MAX = 80
    const val HAND_MIN = 30
    const val HAND_MAX = 50
    const val BASE_DEFENCE = 100

    /** The loop his south-west tile walks: anticlockwise from (8, 8). */
    val LOOP: List<Pair<Int, Int>> =
        buildList {
            for (x in 8..19) add(x to 8)
            for (y in 9..19) add(19 to y)
            for (x in 18 downTo 8) add(x to 19)
            for (y in 18 downTo 9) add(8 to y)
        }

    fun inTank(x: Int, y: Int): Boolean = x in TANK_MIN..TANK_MAX && y in TANK_MIN..TANK_MAX

    fun inRoom(x: Int, y: Int): Boolean = x in ROOM_MIN..ROOM_MAX && y in ROOM_MIN..ROOM_MAX

    /**
     * The game's line of sight (projectile ray) between tiles: fixed-point steps along the long axis;
     * each tile, and the tile the short axis rolls into, must not block. Only the tank blocks here.
     */
    fun rayClear(sx: Int, sy: Int, ex: Int, ey: Int): Boolean {
        val dx = ex - sx
        val dy = ey - sy
        val ax = kotlin.math.abs(dx)
        val ay = kotlin.math.abs(dy)
        if (ax > ay) {
            val xi = if (dx > 0) 1 else -1
            val slope = (dy * 65536) / ax
            var x = sx
            var yf = sy * 65536 + 32768
            if (dy < 0) yf -= 1
            while (x != ex) {
                x += xi
                val y = yf shr 16
                if (inTank(x, y)) return false
                yf += slope
                val ny = yf shr 16
                if (ny != y && inTank(x, ny)) return false
            }
        } else if (ay > 0) {
            val yi = if (dy > 0) 1 else -1
            val slope = (dx * 65536) / ay
            var y = sy
            var xf = sx * 65536 + 32768
            if (dx < 0) xf -= 1
            while (y != ey) {
                y += yi
                val x = xf shr 16
                if (inTank(x, y)) return false
                xf += slope
                val nx = xf shr 16
                if (nx != x && inTank(nx, y)) return false
            }
        }
        return true
    }

    /**
     * Bloat's sight: every tile on the side(s) of his 5x5 nearest the player is tried, and he sees them
     * if one has a clear ray both ways (the game's ray is not symmetric: from him it can slip past the
     * tank's corner while the one back from the player clips it). A player under him counts as seen.
     */
    fun bloatSees(bx: Int, by: Int, px: Int, py: Int): Boolean {
        val tiles = ArrayList<Pair<Int, Int>>()
        if (px < bx) for (y in by..by + 4) tiles += bx to y
        if (px > bx + 4) for (y in by..by + 4) tiles += (bx + 4) to y
        if (py < by) for (x in bx..bx + 4) tiles += x to by
        if (py > by + 4) for (x in bx..bx + 4) tiles += x to (by + 4)
        return tiles.isEmpty() || tiles.any { (x, y) -> rayClear(x, y, px, py) && rayClear(px, py, x, y) }
    }

    /** The hands' anchor points for one drop (room grid 0..15 is local 8..23); corners twice. */
    val HAND_POINTS =
        listOf(
            7 to 2, 8 to 2, 13 to 7, 13 to 8, 7 to 13, 8 to 13, 2 to 7, 2 to 8,
            2 to 2, 2 to 2, 13 to 2, 13 to 2, 13 to 13, 13 to 13, 2 to 13, 2 to 13,
        )

    fun handOk(x: Int, y: Int): Boolean = x in 0..15 && y in 0..15 && !(x in 5..10 && y in 5..10)
}
