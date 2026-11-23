package org.rsmod.content.raids.tob.bloat.sote

/** The Sotetseg room (static map: region 13123, level 0): her 5x5 on the north platform, the maze south of it. */
object SoteRules {
    const val REGION = 13123
    /** Her south-west tile. */
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
    /** Where the five stand round her (the tiles used in fast real 5s raids on Blert): south, south-west, south-east,
     * east (north end) and north (west end). */
    val SPOTS = arrayOf(intArrayOf(3279, 4325), intArrayOf(3276, 4326), intArrayOf(3282, 4326), intArrayOf(3282, 4330), intArrayOf(3277, 4331))
    /** The entry barrier: four tiles wide on the room's south edge (you come in going north). */
    const val BARRIER_X = 3278
    const val BARRIER_W = 4
    const val BARRIER_Y = 4307
    /** The columns the team comes in by: the barrier's two ends (Blert's 5s raids: three west, two east). */
    val ENTRY_COLUMNS = intArrayOf(3278, 3278, 3281, 3281, 3278)
    /** Under: out of sight (the shadow realm isn't copied). */
    const val UNDER_X = 3320
    const val UNDER_Y = 4345
    /** Off the floor, in the west wall: out of shot, yet in drawing range (15 tiles) of the entry and the stack. */
    const val WATCH_X = 3269
    const val WATCH_Y = 4319
    /** The fixed camera: high behind the entry barrier, looking north up the room to her. */
    const val CAM_EYE_X = 3279
    const val CAM_EYE_Y = 4302
    const val CAM_EYE_H = 1150
    const val CAM_LOOK_X = 3279
    const val CAM_LOOK_Y = 4318
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
