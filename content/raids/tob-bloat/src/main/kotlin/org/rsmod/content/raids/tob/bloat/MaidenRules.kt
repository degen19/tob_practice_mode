package org.rsmod.content.raids.tob.bloat

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType

/** The Maiden room: where it is, her and her crabs, and what each attack code in the sim's plans looks like. */
object MaidenRules {
    /** The room's static south-west corner (sim tile (0, 23)); the room is 28 x 24. */
    const val ROOM_X = 3160
    const val ROOM_Y = 4435
    const val ROOM_H = 24
    const val REGION = 12613

    const val CRAB_NPC = "npc.maiden_elemental"
    const val CRAB_HP = 100
    val PHASES = listOf("npc.tob_maiden_100", "npc.tob_maiden_70", "npc.tob_maiden_50", "npc.tob_maiden_30")
    val HP = mapOf(5 to 3500, 4 to 3062, 3 to 2625, 2 to 2625, 1 to 2625)

    const val FIRST_ATTACK = 9
    const val ATTACK_EVERY = 10
    const val ATTACK_LANDS = 5
    const val HER_ATTACK = "seq.maiden_attack_special"
    const val HER_PROJ = "spotanim.maiden_shadow_proj"
    const val HER_PROJANIM = "projanim.magic_spell"

    const val FREEZE_SPOT = "spotanim.ice_barrage_impact"
    const val CAST_AOE = "seq.zaros_vertical_casting_walkmerge"
    const val CAST_ONE = "seq.zaros_casting_walkmerge"
    const val HALBERD_SPEC = "seq.dragon_halberd_special_attack"

    /** Her 6x6's south-west tile in the sim's tiles, and the entry barrier's column. */
    const val MAIDEN_X = 2
    const val MAIDEN_Y = 14
    const val BARRIER_X = 25

    /** Where the team waits before the raid: the column just outside the barrier. */
    const val OUTSIDE_X = 26

    /** The room's barriers (the same loc as Bloat's: Pass walks you through). */
    const val BARRIER = "loc.tob_arena_barrier"
    val BARRIER_ID by lazy { BARRIER.asRSCM(RSCMType.LOC) }

    /**
     * What each of the sim's attack codes looks like - the weapon (obj id), the armour set, the attack animation (seq id)
     * and ammo - exactly as the sim's own Maiden render draws its players (verzikSim render kits): ranged in elite void,
     * melee in Torva / Oathplate, magic in Ancestral.
     */
    class Kit(val weapon: Int, val style: String, val seq: Int, val ammo: Int? = null)

    private const val TBOW = 20997
    private const val ZCB = 26374
    private const val RALOS = 28922
    private const val DINHS = 21015
    private const val CHINS = 11959
    private const val BLOWPIPE = 12926
    private const val KODAI = 21006
    private const val MAUL = 21003
    private const val SCYTHE = 22325
    private const val HALBERD = 23987
    private const val SCEPTRE = 25490
    private const val AYAK = 31113
    private const val CLAWS = 13652
    private const val BGS = 11804
    private const val ASCENSION = 34681
    private const val SHADOW = 27275
    private const val DAWNBRINGER = 22516
    private const val SANG = 22323
    private const val DRAGON_ARROW = 11212
    private const val RUBY_BOLTS_E = 21944

    val KITS: Map<String, Kit> =
        mapOf(
            "TB" to Kit(TBOW, "range", 426, DRAGON_ARROW),
            "BP" to Kit(BLOWPIPE, "range", 5061),
            "AX" to Kit(ASCENSION, "range", 15113),
            "Z" to Kit(ZCB, "range", 4230, RUBY_BOLTS_E),
            "ZCB" to Kit(ZCB, "range", 9168, RUBY_BOLTS_E),
            "Ra" to Kit(RALOS, "range", 10914),
            "CH" to Kit(CHINS, "range", 2779),
            "EM" to Kit(MAUL, "melee", 11124),
            "BGS" to Kit(BGS, "melee", 7642),
            "C" to Kit(CLAWS, "melee", 7514),
            "S" to Kit(SCYTHE, "melee", 8056),
            "HB" to Kit(HALBERD, "melee", 1203),
            "DB" to Kit(DINHS, "melee", 7511),
            "IB" to Kit(KODAI, "mage", 1979),
            "IR" to Kit(KODAI, "mage", 1979),
            "IBS" to Kit(SCEPTRE, "mage", 1979),
            "IBL" to Kit(AYAK, "mage", 1978),
            "IBU" to Kit(AYAK, "mage", 1979),
            "AY" to Kit(AYAK, "mage", 1979),
            "SH" to Kit(SHADOW, "mage", 1979),
            "D" to Kit(DAWNBRINGER, "mage", 1167),
            "T" to Kit(SANG, "mage", 1167),
        )

    /** The render kits' armour (obj ids). */
    val STYLE_SETS: Map<String, List<Int>> =
        mapOf(
            // Torva full helm, Oathplate chest / legs, Infernal cape, Amulet of torture, Primordial boots, Ferocious gloves
            "melee" to listOf(26382, 30753, 30756, 21295, 19553, 13239, 22981),
            // Void ranger helm, Elite void top / robe, Void knight gloves, Dizana's quiver, Necklace of anguish, Pegasian boots
            "range" to listOf(11664, 13072, 13073, 8842, 28951, 19547, 13237),
            // Ancestral hat / top / bottom, Imbued zamorak cape, Occult necklace, Tormented bracelet, Eternal boots
            "mage" to listOf(21018, 21021, 21024, 21795, 12002, 19544, 13235),
        )
}
