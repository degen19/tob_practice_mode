package org.rsmod.content.raids.tob.bloat.nylo

/** The Nylocas room (static map: region 13122, level 0): the boss's 4x4 in the middle of the ring. */
object NyloRules {
    const val REGION = 13122
    /** His south-west tile. */
    const val X = 3293
    const val Y = 4246
    const val SIZE = 4
    const val SPAWNING = "npc.nylocas_boss_spawning"
    val FORM_NPC = mapOf("melee" to "npc.nylocas_boss_melee", "ranged" to "npc.nylocas_boss_ranged", "magic" to "npc.nylocas_boss_magic")
    const val SPAWN_SEQ = "seq.top_spider_melee_spawn"
    val DEATH_SEQ = mapOf("melee" to "seq.top_spider_melee_death", "ranged" to "seq.top_spider_ranged_death", "magic" to "seq.top_spider_magic_death")
    /** His attack by form (the small Nylocas' attacks - his model is theirs, scaled up). */
    val ATTACK_SEQ = mapOf("melee" to "seq.top_spider_melee_attack", "ranged" to "seq.top_spider_ranged_attack", "magic" to "seq.top_spider_magic_attack")
    /** The bots' stack: the middle of his south side (each a tile along). */
    const val STACK_Y = Y - 1
    /** The room's north barrier, two tiles wide (you come in going south). */
    const val BARRIER_X = 3295
    const val BARRIER_W = 2
    const val BARRIER_Y = 4255
    /** Under him (his ranged / magic forms). */
    const val UNDER_Y = Y + 1
    const val WATCH_X = X + 2
    const val WATCH_Y = Y - 3
    /** The fixed camera: high south of him, looking north over him to the barrier the team comes in by. */
    const val CAM_EYE_X = 3295
    const val CAM_EYE_Y = 4237
    const val CAM_EYE_H = 950
    const val CAM_LOOK_X = 3295
    const val CAM_LOOK_Y = 4249
}
