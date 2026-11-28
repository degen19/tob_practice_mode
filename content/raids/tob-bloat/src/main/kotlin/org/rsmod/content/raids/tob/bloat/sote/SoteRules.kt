package org.rsmod.content.raids.tob.bloat.sote

/** The Sotetseg room (static map: region 13123, level 0): her 5x5 on the north platform, the maze south of it. */
object SoteRules {
    const val REGION = 13123

    /** Her south-west tile (as Blert has her). */
    const val X = 3277
    const val Y = 4326
    const val SIZE = 5
    const val NPC = "npc.tob_sotetseg_combat"

    /** The maze: 14 columns from MAZE_X, 15 rows; row A (where the stallers stand) is MAZE_A. */
    const val MAZE_X = 3273
    const val MAZE_A = 4310

    /** The bots' tiles: along her south side. */
    const val STACK_Y = Y - 1

    /**
     * Where each team swings from (the tiles used in fast real raids on Blert), and the tile one step back, out of her
     * reach, each steps out to the tick after a swing and in from the tick before the next (the flinch). 5s: south,
     * south-west, south-east, east (north end), north (west end); 4s: east, north, south-west, south-east; trios:
     * south-east, east, west; duos: south-east and west; solos: west.
     */
    val SPOTS =
        mapOf(
            5 to listOf(3279 to 4325, 3276 to 4326, 3282 to 4326, 3282 to 4330, 3277 to 4331),
            4 to listOf(3282 to 4330, 3277 to 4331, 3276 to 4326, 3281 to 4325),
            3 to listOf(3281 to 4325, 3282 to 4330, 3276 to 4326),
            2 to listOf(3281 to 4325, 3276 to 4326),
            1 to listOf(3276 to 4326),
        )
    val OUT =
        mapOf(
            5 to listOf(3279 to 4324, 3275 to 4326, 3283 to 4326, 3283 to 4330, 3277 to 4332),
            4 to listOf(3283 to 4330, 3277 to 4332, 3275 to 4326, 3281 to 4324),
            3 to listOf(3281 to 4324, 3283 to 4330, 3275 to 4326),
            2 to listOf(3281 to 4324, 3275 to 4326),
            1 to listOf(3275 to 4325),
        )

    /** The entry barrier: four tiles wide on the room's south edge (you come in going north). */
    const val BARRIER_X = 3278
    const val BARRIER_W = 4
    const val BARRIER_Y = 4307

    /** The columns each team comes in by: the barrier's two ends, as on Blert. */
    val ENTRY_COLUMNS =
        mapOf(
            5 to listOf(3278, 3278, 3281, 3281, 3278),
            4 to listOf(3281, 3278, 3278, 3281),
            3 to listOf(3281, 3281, 3278),
            2 to listOf(3281, 3278),
            1 to listOf(3278),
        )

    /**
     * The maze crossing (every raid on Blert): the players skipping it are teleported 3 ticks after the proc to column
     * 1, three south of row A; they set off up the column the tick before the stall starts, two tiles a tick, cast NPC
     * Contact on row 3 (the stall's first tick) and are on row 15, by her south-west corner, as it ends.
     */
    const val CROSS_X = 3274
    const val CROSS_START_Y = 4307
    const val CROSS_TOP_Y = 4325

    /** A shark skipper with others stalling crosses up column 11, away from their splash (Blert 5s). */
    const val SKIP_X = 3284
    const val TELEPORT_AFTER = 3

    /**
     * The shark a player drops by her south-west corner at the start (top of column 1, as on Blert): the crossers click
     * it to run up the column through their NPC Contact. The player on the west tile steps out for it on t12.
     */
    const val SHARK = "obj.shark"
    const val SHARK_TICK = 12
    val SHARK_Y = mapOf(5 to 4327, 4 to 4327, 3 to 4326, 2 to 4326, 1 to 4325)

    /** The tornado that walks the maze's path once anyone is past its third row. */
    const val TORNADO = "npc.tob_sotetseg_creeper"
    const val TORNADO_ROW = 3

    /** Under: out of sight (the shadow realm isn't copied). */
    const val UNDER_X = 3320
    const val UNDER_Y = 4345

    /** Off the floor, in the west wall: out of shot, yet in drawing range (15 tiles) of the entry and the stack. */
    const val WATCH_X = 3269
    const val WATCH_Y = 4319

    const val ATTACK = "seq.tob_sotetseg_attack_ranged"
    const val MELEE = "seq.tob_sotetseg_attack_melee"
    const val DEATH = "seq.tob_sotetseg_death"
    const val ORB = "spotanim.tob_sotetseg_maging"
    const val ORB_RANGE = "spotanim.tob_sotetseg_ranging"
    const val BALL = "spotanim.tob_sotetseg_sharedattack"

    /** NPC Contact (the stall): its cast animation and graphic, by id. */
    const val CONTACT_SEQ = 4413
    const val CONTACT_GFX = 728
}
