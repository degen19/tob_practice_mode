package org.rsmod.content.raids.tob.bloat.xarpus

/** The Xarpus room (static map: region 12612, level 1): his 5x5 in the middle of the arena. */
object XarpusRules {
    const val REGION = 12612
    const val LEVEL = 1
    /** His south-west tile (the 5x5 fighting one; the 3x3 sits on X+1, Y+1, the same middle). */
    const val X = 3168
    const val Y = 4385
    const val SIZE = 5
    const val STATIC = "npc.tob_xarpus_static"
    /** The 3x3 Xarpus in his pool, healed by the exhumeds. */
    const val FEEDING = "npc.tob_xarpus_feeding"
    const val COMBAT = "npc.tob_xarpus_combat"
    const val DEATH = "npc.xarpus_death"
    /** The bots' stack: the middle of his south side. */
    const val STACK_X = X + 2
    const val STACK_Y = Y - 1
    /** Where the five stand round him (the tiles used in fast real 5s raids on Blert): south, north, east, west and
     * south-east. */
    val SPOTS = arrayOf(intArrayOf(3170, 4384), intArrayOf(3170, 4390), intArrayOf(3173, 4387), intArrayOf(3167, 4387), intArrayOf(3172, 4384))
    /** The entry barrier: three tiles wide on the room's south edge (you come in going north). */
    const val BARRIER_X = 3169
    const val BARRIER_W = 3
    const val BARRIER_Y = 4379
    /** The column the team comes in by, all on one tile (Blert's 5s raids). */
    const val ENTRY_X = 3170
    const val WATCH_X = X + 4
    /** Where you watch from: just behind the entry barrier: out of shot, yet in drawing range (15 tiles) of the whole room. */
    const val WATCH_Y = BARRIER_Y - 2
    /** The fixed camera: high behind the entry barrier, looking north over him. */
    const val CAM_EYE_X = 3170
    const val CAM_EYE_Y = 4373
    const val CAM_EYE_H = 1350
    const val CAM_LOOK_X = 3170
    const val CAM_LOOK_Y = 4387
}
