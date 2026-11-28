package org.rsmod.content.raids.tob.bloat.verzik

/** The Verzik room (static map: region 12611, level 0): her throne at the north end of the arena. */
object VerzikRules {
    const val REGION = 12611

    /** P1 on the throne (her south-west tile). */
    const val P1_X = 3166
    const val P1_Y = 4320

    /** P2 / P3: the arena's middle. */
    const val MID_X = 3166
    const val MID_Y = 4310
    const val WATCH_X = 3167
    const val WATCH_Y = 4308
    val PHASE_NPC = mapOf("P1" to "npc.verzik_phase1", "P1 dies" to "npc.verzik_phase1_to2_transition", "P2" to "npc.verzik_phase2", "P2 dies" to "npc.verzik_phase2_to3_transition", "P3" to "npc.verzik_phase3")
    const val INITIAL = "npc.verzik_initial"

    /** Her P2 and P3 hitpoints by team size, as verzikSim has them (p2.js P2_HP, p3.js P3_HP). */
    val P2_HP = mapOf(1 to 2625, 2 to 2625, 3 to 2625, 4 to 3062, 5 to 3500)
    const val RED_CRAB_5S = 200
    const val RED = "npc.tob_verzik_phase2_bloodnylocas"
    const val PURPLE = "npc.tob_verzik_phase2_armourednylocas"
    const val P1_ATTACK = "seq.verzik_phase1_attack_magic"
    const val P1_DEATH = "seq.verzik_phase1_death"
    const val P2_SPAWN = "seq.verzik_phase2_spawn"
    const val P2_ATTACK = "seq.verzik_phase2_attack_magic"
    const val P2_LIGHTNING = "seq.verzik_phase2_attack_magic"
    const val P2_HEAL = "seq.verzik_phase2_heal"
    const val P2_MELEE = "seq.verzik_phase2_attack_melee"
    const val STUNNED = "seq.human_stunned"
    const val STUNNED_GFX = "spotanim.stunned_thieving"
    const val P2_DEATH = "seq.verzik_phase2_death"
    const val P3_SPAWN = "seq.verzik_phase3_spawn"
    const val P3_MAGIC = "seq.verzik_phase3_attack_magic"
    const val P3_RANGED = "seq.verzik_phase3_attack_ranged"
    const val P3_MELEE = "seq.verzik_phase3_attack_melee"
    const val P3_WEBS = "seq.verzik_phase3_attack_webspin"
    const val P3_YELLOWS = "seq.verzik_phase3_attack_powerblast"
    const val P3_DEATH = "seq.verzik_phase3_death_a"
    const val P1_PROJ = "spotanim.verzik_phase1_projectile"
    const val P2_PROJ = "spotanim.verzik_phase2_ranged"
    const val P2_BOLT = "spotanim.verzik_phase2_lightning"
    const val P3_RANGE_PROJ = "spotanim.verzik_phase3_rangeproj"
    const val P3_MAGE_PROJ = "spotanim.verzik_phase3_mageproj"
    const val P3_BALL_PROJ = "spotanim.verzik_acidbomb_projanim"
    const val P2_BLOOD_PROJ = "spotanim.verzik_phase2_bloodproj"
}
