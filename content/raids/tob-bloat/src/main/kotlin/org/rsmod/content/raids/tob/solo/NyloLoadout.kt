package org.rsmod.content.raids.tob.solo

import dev.openrune.util.Wearpos

/**
 * The team's Nylocas gear (the user's trio loadouts): every role melees in Torva / Oathplate with a scythe, ranges in
 * void with a Twisted bow from Dizana's quiver, and carries its own wave weapons - the melee an Obligator, Dinh's bulwark,
 * blowpipe and Eye of Ayak with the Book of the dead; the ranger black chinchompas, Ascension crossbows and the Ayak
 * switch; the mage an ice ancient sceptre (barrage) and Zorya's tome in Ancestral top, confliction gauntlets and occult
 * over its ranged gear, plus the crossbows.
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
    val CLAWS = GearSet(MELEE_ARMOUR + (Wearpos.RightHand to "obj.dragon_claws"))
    val DINH = GearSet(MELEE_ARMOUR + (Wearpos.RightHand to "obj.dinhs_bulwark"))
    val AYAK = GearSet(MELEE_ARMOUR + listOf(Wearpos.RightHand to "obj.eye_of_ayak", Wearpos.LeftHand to "obj.book_of_the_dead"))
    /** Barrage in the ranged gear with the magic pieces over it: the highest magic accuracy the team has (as the sim). */
    val BARRAGE = GearSet(RANGED_ARMOUR.filter { (slot, _) -> MAGE_ARMOUR.none { it.first == slot } } + MAGE_ARMOUR + (Wearpos.RightHand to "obj.ancient_sceptre_ice"))
    val TBOW = GearSet(RANGED_ARMOUR + (Wearpos.RightHand to "obj.twisted_bow"))
    val BLOWPIPE = GearSet(RANGED_ARMOUR + (Wearpos.RightHand to "obj.toxic_blowpipe_loaded"))
    val CHINS = GearSet(RANGED_ARMOUR + (Wearpos.RightHand to "obj.chinchompa_black"))
    val CROSSBOWS = GearSet(RANGED_ARMOUR + (Wearpos.RightHand to "obj.ascension_crossbows"))
    val RALOS = GearSet(RANGED_ARMOUR + (Wearpos.RightHand to "obj.tonalztics_of_ralos_charged"))
    val TOME = GearSet(RANGED_ARMOUR.filter { (slot, _) -> MAGE_ARMOUR.none { it.first == slot } } + MAGE_ARMOUR + (Wearpos.RightHand to "obj.zoryas_tome"))
    val ZCB = GearSet(RANGED_ARMOUR + (Wearpos.RightHand to "obj.zaryte_xbow"))

    const val BOLTS = "obj.ascension_bolts"
    const val CHINCHOMPAS = "obj.chinchompa_black"
    const val ARROWS = "obj.dragon_arrow"
    const val SEEKING_ARROWS = "obj.seeking_dragon_arrow"
    const val RUBY_BOLTS = "obj.dragon_bolts_enchanted_ruby"
    const val DIAMOND_BOLTS = "obj.ascension_bolts_diamond_enchanted"
    const val BOOK = "obj.book_of_the_dead"
    const val SURGE = "obj.4dosesurge"

    /** Vengeance, Spellbook Swap, thralls and Death Charge: the runes the ranger and melee cast with. */
    val LUNAR_RUNES = listOf("obj.astralrune", "obj.earthrune", "obj.lawrune", "obj.cosmicrune", "obj.soulrune", "obj.bloodrune", "obj.deathrune", "obj.firerune")

    /** The ancients' barrage and rush. */
    val ANCIENT_RUNES = listOf("obj.waterrune", "obj.bloodrune", "obj.deathrune", "obj.chaosrune")

    /** Every rune any bot carries (in stacks). */
    val SPELL_RUNES = (LUNAR_RUNES + ANCIENT_RUNES).distinct()
    const val ICE_BARRAGE = "obj.94_ice_barrage"
    const val ICE_RUSH = "obj.58_ice_rush"
    val BARRAGE_RUNES = listOf("obj.waterrune", "obj.bloodrune", "obj.deathrune")

    /** The sets each role can switch into: its first set is what it walks in wearing. */
    fun sets(role: NyloRole): List<GearSet> =
        when (role) {
            NyloRole.Melee -> listOf(SCYTHE, OBLIGATOR, DINH, CROSSBOWS, AYAK, TBOW)
            NyloRole.Ranger -> listOf(TBOW, CHINS, CROSSBOWS, AYAK, SCYTHE, CLAWS)
            NyloRole.Mage -> listOf(BARRAGE, TOME, CROSSBOWS, TBOW, SCYTHE, CLAWS)
        }

    /**
     * At Maiden every role opens with a Twisted bow (seeking arrows) and the Tonalztics spec, then scythes, with a
     * Zaryte crossbow for its spec; the ranger and melee are on the Lunars with the Book of the dead for thralls.
     */
    fun maidenSets(role: NyloRole): List<GearSet> =
        when (role) {
            NyloRole.Melee -> listOf(TBOW, RALOS, SCYTHE, DINH, ZCB, OBLIGATOR)
            NyloRole.Ranger -> listOf(TBOW, RALOS, SCYTHE, ZCB)
            NyloRole.Mage -> listOf(TBOW, RALOS, BARRAGE, SCYTHE, ZCB, CROSSBOWS, TOME, CLAWS)
        }

    /** Worn at the start: the first set plus bolts in the ammo slot. */
    fun worn(sets: List<GearSet>): List<Pair<Int, String>> =
        sets.first().worn.map { it.first.slot to it.second } + (Wearpos.Quiver.slot to BOLTS)

    /** Everything else the sets need, once each, in the pack. */
    fun pack(role: NyloRole, sets: List<GearSet>): List<String> {
        val wearing = sets.first().worn.map { it.second }.toSet()
        val switches = sets.flatMap { set -> set.worn.map { it.second } }.distinct().filter { it !in wearing }
        return switches + if (role == NyloRole.Mage) BARRAGE_RUNES else emptyList()
    }
}
