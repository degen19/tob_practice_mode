package org.rsmod.content.raids.tob.solo

import dev.openrune.util.Wearpos

/**
 * The team's Nylocas gear (the user's trio loadouts): every role melees in Torva / Oathplate with a scythe, ranges in
 * void with a Twisted bow from Dizana's quiver, and carries its own wave weapons - the melee an Obligator, Dinh's bulwark,
 * blowpipe and Eye of Ayak with the Book of the dead; the ranger black chinchompas, Ascension crossbows and the Ayak
 * switch; the mage an ice ancient sceptre (barrage) in Ancestral top, confliction gauntlets and occult, plus the
 * crossbows.
 */
enum class NyloRole(val style: Char) {
    Mage('G'),
    Ranger('R'),
    Melee('M'),
}

/** A set of worn items keyed by slot: what a switch puts on. */
class GearSet(val worn: List<Pair<Wearpos, String>>)

object NyloLoadout {
    private val MELEE_ARMOUR =
        listOf(
            Wearpos.Hat to "obj.torva_helm",
            Wearpos.Torso to "obj.oathplate_chest",
            Wearpos.Legs to "obj.oathplate_legs",
            Wearpos.Back to "obj.infernal_cape",
            Wearpos.Front to "obj.amulet_of_rancour",
            Wearpos.Hands to "obj.ferocious_gloves",
            Wearpos.Feet to "obj.avernic_treads_max",
            Wearpos.Ring to "obj.ultor_ring",
        )

    private val RANGED_ARMOUR =
        listOf(
            Wearpos.Hat to "obj.game_pest_archer_helm",
            Wearpos.Torso to "obj.elite_void_knight_top",
            Wearpos.Legs to "obj.elite_void_knight_robes",
            Wearpos.Back to "obj.dizanas_quiver_infinite",
            Wearpos.Front to "obj.necklace_of_rupture",
            Wearpos.Hands to "obj.pest_void_knight_gloves",
        )

    private val MAGE_ARMOUR =
        listOf(
            Wearpos.Torso to "obj.ancestral_robe_top",
            Wearpos.Front to "obj.occult_necklace",
            Wearpos.Hands to "obj.confliction_gauntlets",
        )

    val SCYTHE = GearSet(MELEE_ARMOUR + (Wearpos.RightHand to "obj.scythe_of_vitur"))
    val OBLIGATOR = GearSet(MELEE_ARMOUR + (Wearpos.RightHand to "obj.obligator"))
    val DINH = GearSet(MELEE_ARMOUR + (Wearpos.RightHand to "obj.dinhs_bulwark"))
    val AYAK = GearSet(MELEE_ARMOUR + listOf(Wearpos.RightHand to "obj.eye_of_ayak", Wearpos.LeftHand to "obj.book_of_the_dead"))
    val BARRAGE = GearSet(MELEE_ARMOUR.filter { (slot, _) -> MAGE_ARMOUR.none { it.first == slot } } + MAGE_ARMOUR + (Wearpos.RightHand to "obj.ancient_sceptre_ice"))
    val TBOW = GearSet(RANGED_ARMOUR + (Wearpos.RightHand to "obj.twisted_bow"))
    val BLOWPIPE = GearSet(RANGED_ARMOUR + (Wearpos.RightHand to "obj.toxic_blowpipe_loaded"))
    val CHINS = GearSet(RANGED_ARMOUR + (Wearpos.RightHand to "obj.chinchompa_black"))
    val CROSSBOWS = GearSet(RANGED_ARMOUR + (Wearpos.RightHand to "obj.ascension_crossbows"))

    const val BOLTS = "obj.ascension_bolts"
    const val CHINCHOMPAS = "obj.chinchompa_black"
    const val ARROWS = "obj.dragon_arrow"
    const val ICE_BARRAGE = "obj.94_ice_barrage"
    val BARRAGE_RUNES = listOf("obj.waterrune", "obj.bloodrune", "obj.deathrune")

    /** The sets each role can switch into: its first set is what it walks in wearing. */
    fun sets(role: NyloRole): List<GearSet> =
        when (role) {
            NyloRole.Melee -> listOf(SCYTHE, OBLIGATOR, DINH, BLOWPIPE, AYAK, TBOW)
            NyloRole.Ranger -> listOf(TBOW, CHINS, CROSSBOWS, AYAK, SCYTHE)
            NyloRole.Mage -> listOf(BARRAGE, CROSSBOWS, TBOW, SCYTHE)
        }

    /** Worn at the start: the role's first set plus bolts in the ammo slot. */
    fun worn(role: NyloRole): List<Pair<Int, String>> =
        sets(role).first().worn.map { it.first.slot to it.second } + (Wearpos.Quiver.slot to BOLTS)

    /** Everything else the role's sets need, once each, in the pack. */
    fun pack(role: NyloRole): List<String> {
        val wearing = sets(role).first().worn.map { it.second }.toSet()
        val switches = sets(role).flatMap { set -> set.worn.map { it.second } }.distinct().filter { it !in wearing }
        return switches + if (role == NyloRole.Mage) BARRAGE_RUNES else emptyList()
    }
}
