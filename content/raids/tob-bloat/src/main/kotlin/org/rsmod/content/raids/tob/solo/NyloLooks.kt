package org.rsmod.content.raids.tob.solo

/** The cache names of each nylocas colour's npcs, animations and spotanims ('M' melee, 'R' ranged, anything else magic). */
internal object NyloLooks {
    fun npc(big: Boolean, fighting: Boolean, s: Char): String =
        when (s) {
            'M' -> if (big) if (fighting) "npc.tob_nylocas_big_fighting_melee" else "npc.tob_nylocas_big_incoming_melee"
            else if (fighting) "npc.tob_nylocas_fighting_melee" else "npc.tob_nylocas_incoming_melee"
            'R' -> if (big) if (fighting) "npc.tob_nylocas_big_fighting_ranged" else "npc.tob_nylocas_big_incoming_ranged"
            else if (fighting) "npc.tob_nylocas_fighting_ranged" else "npc.tob_nylocas_incoming_ranged"
            else -> if (big) if (fighting) "npc.tob_nylocas_big_fighting_magic" else "npc.tob_nylocas_big_incoming_magic"
            else if (fighting) "npc.tob_nylocas_fighting_magic" else "npc.tob_nylocas_incoming_magic"
        }

    fun verzikCrawler(s: Char): String =
        when (s) {
            'M' -> "npc.verzik_nylocas_melee"
            'R' -> "npc.verzik_nylocas_ranged"
            else -> "npc.verzik_nylocas_magic"
        }

    fun attack(s: Char): String =
        when (s) {
            'M' -> "seq.top_spider_melee_attack"
            'R' -> "seq.top_spider_ranged_attack"
            else -> "seq.top_spider_magic_attack"
        }

    /** The close-range swing of a ranged or magic nylocas (a melee one has only the one attack). */
    fun meleeAttack(s: Char): String =
        when (s) {
            'M' -> "seq.top_spider_melee_attack"
            'R' -> "seq.top_spider_ranged_meleeattack"
            else -> "seq.top_spider_magic_meleeattack"
        }

    fun detonateSeq(s: Char): String =
        when (s) {
            'M' -> "seq.top_spider_melee_death_detonate"
            'R' -> "seq.top_spider_ranged_death_detonate"
            else -> "seq.top_spider_magic_death_detonate"
        }

    fun deathGfx(s: Char): String =
        when (s) {
            'M' -> "spotanim.tob_nylocas_death_melee_standard"
            'R' -> "spotanim.tob_nylocas_death_ranged_standard"
            else -> "spotanim.tob_nylocas_death_magic_standard"
        }

    fun detonateGfx(s: Char): String =
        when (s) {
            'M' -> "spotanim.tob_nylocas_death_melee_detonate"
            'R' -> "spotanim.tob_nylocas_death_ranged_detonate"
            else -> "spotanim.tob_nylocas_death_magic_detonate"
        }
}
