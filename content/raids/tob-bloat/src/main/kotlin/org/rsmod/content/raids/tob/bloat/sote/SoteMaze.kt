package org.rsmod.content.raids.tob.bloat.sote

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import org.rsmod.content.raids.tob.bloat.maiden.SimRng

/**
 * Sotetseg's maze (verzikSim engine/sotemaze.js, from devqhp's maze trainer): how the path is made and the best route
 * through it. Columns 0-13 from the west, rows 0-14 from the top (row 14 is "A", where players step on; row 1 is "N").
 */
object SoteMaze {
    const val W = 14
    const val H = 15
    private const val MAX_X_CHANGE = 5
    private const val PATH_TURNS = 8

    class Tile(val x: Int, val y: Int)

    /** A rolled maze: its seed, the first tile's column, the best route tick by tick, and the moves to row N. */
    class Maze(val seed: List<Int>, val route: List<Tile>) {
        val start: Int
            get() = seed[0]

        val ticks: Int
            get() = route.size - 1

        val toN: Int
            get() = route.indexOfFirst { it.y <= 1 }
    }

    /** The seed: 8 columns - the first never the far west column, each next within 5 of the one before. */
    fun seed(rng: SimRng): List<Int> {
        val s = arrayListOf(rng.randint(1, W - 1))
        for (i in 1 until PATH_TURNS) s += rng.randint(max(s[i - 1] - MAX_X_CHANGE, 0), min(s[i - 1] + MAX_X_CHANGE, W - 1))
        return s
    }

    /** The safe tiles: odd rows run sideways from one seed column to the next, even rows are one tile. */
    fun tiles(seed: List<Int>): Array<BooleanArray> {
        val m = Array(W) { BooleanArray(H) }
        var s = 0
        var x = seed[0]
        for (y in H - 1 downTo 0) {
            if (y % 2 == 1) {
                val nx = seed[++s]
                for (i in min(x, nx)..max(x, nx)) m[i][y] = true
                x = nx
            } else m[x][y] = true
        }
        return m
    }

    private fun safe(m: Array<BooleanArray>, x: Int, y: Int) = x in 0 until W && y in 0 until H && m[x][y]

    private fun pathOrder(m: Array<BooleanArray>, seed: List<Int>): Array<IntArray> {
        val w = Array(W) { IntArray(H) }
        var c: Tile? = Tile(seed[0], H - 1)
        var prev = Tile(-1, -1)
        var n = 1
        while (c != null) {
            val cur: Tile = c
            w[cur.x][cur.y] = n++
            val nb = listOf(cur.x to cur.y + 1, cur.x + 1 to cur.y, cur.x to cur.y - 1, cur.x - 1 to cur.y).firstOrNull { (x, y) -> safe(m, x, y) && !(x == prev.x && y == prev.y) }
            prev = cur
            c = nb?.let { Tile(it.first, it.second) }
        }
        return w
    }

    /** The tiles walked through on a move of up to 2 (diagonal first, then straight - as in game). */
    private fun passed(a: Tile, b: Tile): List<Tile> {
        var cx = a.x
        var cy = a.y
        val r = ArrayList<Tile>()
        while (r.size < 2 && !(cx == b.x && cy == b.y)) {
            val dx = b.x - cx
            val dy = b.y - cy
            if (abs(dx) == abs(dy)) {
                cx += dx.sign
                cy += dy.sign
            } else if (abs(dx) > abs(dy)) cx += dx.sign else cy += dy.sign
            r += Tile(cx, cy)
        }
        return r
    }

    private val MOVES = listOf(-2 to -2, -1 to -2, 0 to -2, 1 to -2, 2 to -2, -2 to -1, -1 to -1, 0 to -1, 1 to -1, 2 to -1, -2 to 0, -1 to 0, 1 to 0, 2 to 0)

    /** Every safe tile in walking order, from the first on row A (what the tornado follows). */
    fun path(m: Array<BooleanArray>, seed: List<Int>): List<Tile> {
        val w = pathOrder(m, seed)
        return (0 until W).flatMap { x -> (0 until H).filter { w[x][it] > 0 }.map { y -> Tile(x, y) } }.sortedBy { w[it.x][it.y] }
    }

    /** The trainer's best route: each tick, the safe tile furthest along the path a move of up to 2 reaches safely. */
    fun bestRoute(m: Array<BooleanArray>, seed: List<Int>): List<Tile> {
        val w = pathOrder(m, seed)
        val pos = arrayListOf(Tile(seed[0], H - 1))
        while (pos.last().y > 0) {
            val c = pos.last()
            var best: Tile? = null
            var bs = -1
            for ((dx, dy) in MOVES) {
                val t = Tile(c.x + dx, c.y + dy)
                if (safe(m, t.x, t.y) && passed(c, t).all { m[it.x][it.y] } && w[t.x][t.y] > bs) {
                    best = t
                    bs = w[t.x][t.y]
                }
            }
            pos += checkNotNull(best)
        }
        return pos
    }

    fun roll(rng: SimRng): Maze {
        val seed = seed(rng)
        return Maze(seed, bestRoute(tiles(seed), seed))
    }

    /** The player under (teleported to column 6, two south of row A): ticks to the first tile, by its column. */
    fun underToStart(col: Int) = if (col in 4..8) 1 else if (col <= 3 || col <= 11) 2 else 3

    /** The runners (south of 3A, then 5A): ticks from beside 5A to the first tile, by its column. */
    fun runnersToStart(col: Int) = if (col in 3..7) 1 else if (col <= 2 || col <= 9) 2 else if (col <= 11) 3 else 4
}
