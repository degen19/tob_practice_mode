package org.rsmod.content.raids.tob.bloat

import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid

/**
 * How the team comes into a room whose barrier runs east-west (Nylocas, Sotetseg, Xarpus): they wait in line just
 * outside it, all click Pass on [pass] (the walk through is drawn over two ticks, so they land inside as the room
 * starts), and run on to their tiles from [run] - no stop once they're through.
 */
class RoomEntry(
    private val clicks: RaidBots,
    /** The barrier's row, in the room's own (instanced) coordinates. */
    private val barrierRow: CoordGrid,
    private val pass: Int,
    private val run: Int,
) {
    /** True until the team is through the barrier and running: the room leaves their movement alone till then. */
    fun waiting(clock: Int): Boolean = clock < run

    /** The room's clock: Pass on the barrier in front of each bot at [pass], then the room's own walk clicks at [run]. */
    fun tick(clock: Int, bots: List<Player>) {
        if (clock != pass) return
        val loc = MaidenRules.BARRIER_ID
        for (b in bots) clicks.opLoc(b, loc, CoordGrid(b.coords.x, barrierRow.z, barrierRow.level))
    }

    companion object {
        /** The ToB barriers' columns: each room's is four (Nylocas two) tiles wide. */
        fun outside(at: (Int, Int) -> CoordGrid, x0: Int, width: Int, y: Int, i: Int): CoordGrid = at(x0 + i % width, y)
    }
}
