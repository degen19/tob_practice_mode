package org.rsmod.content.raids.tob.bloat

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType

/**
 * What the supply chest's tabs offer, by gameval: the sim's gear (verzikSim engine/gear.js), every rune,
 * and the sim's supplies plus an empty vial. Many names are shared by minigame and placeholder copies
 * (Last Man Standing, Deadman, Nightmare Zone, beta and dummy items) that look the same but don't
 * work as the real thing, so the real item is named here.
 */
object SupplyCatalog {
    enum class Tab(val label: String) { Runes("Runes"), Gear("Gear"), Ammo("Ammo"), Supplies("Supplies") }

    private val logger = InlineLogger()

    private val RUNES =
        listOf(
            "obj.firerune",
            "obj.waterrune",
            "obj.airrune",
            "obj.earthrune",
            "obj.mindrune",
            "obj.bodyrune",
            "obj.deathrune",
            "obj.naturerune",
            "obj.chaosrune",
            "obj.lawrune",
            "obj.cosmicrune",
            "obj.bloodrune",
            "obj.soulrune",
            "obj.steamrune",
            "obj.mistrune",
            "obj.dustrune",
            "obj.smokerune",
            "obj.mudrune",
            "obj.lavarune",
            "obj.astralrune",
            "obj.wrathrune",
            "obj.sunfirerune",
            "obj.aetherrune",
            "obj.divine_rune_pouch",
        )

    private val GEAR =
        listOf(
            "obj.torva_helm",
            "obj.torva_chest",
            "obj.torva_legs",
            "obj.oathplate_helm",
            "obj.oathplate_chest",
            "obj.oathplate_legs",
            "obj.ancestral_hat",
            "obj.ancestral_robe_top",
            "obj.ancestral_robe_bottom",
            "obj.virtus_mask",
            "obj.virtus_top",
            "obj.virtus_legs",
            "obj.masori_mask_fortified",
            "obj.masori_body_fortified",
            "obj.masori_chaps_fortified",
            "obj.game_pest_archer_helm",
            "obj.elite_void_knight_top",
            "obj.elite_void_knight_robes",
            "obj.pest_void_knight_gloves",
            "obj.torva_helm_sanguine",
            "obj.torva_chest_sanguine",
            "obj.torva_legs_sanguine",
            "obj.radiant_oathplate_helm",
            "obj.radiant_oathplate_chest",
            "obj.radiant_oathplate_legs",
            "obj.ancestral_hat_twisted",
            "obj.ancestral_robe_top_twisted",
            "obj.ancestral_robe_bottom_twisted",
            "obj.infernal_cape",
            "obj.ma2_saradomin_cape",
            "obj.dizanas_quiver_infinite",
            "obj.skillcape_max_infernalcape",
            "obj.skillcape_max_saradomin2",
            "obj.skillcape_max_dizanas",
            "obj.amulet_of_rancour",
            "obj.blood_amulet",
            "obj.lotr_crystalshard_necklace_upgrade",
            "obj.nzone_salve_amulet_e",
            "obj.jewl_necklace_of_phoenix",
            "obj.occult_necklace",
            "obj.zenyte_necklace_enchanted",
            "obj.necklace_of_rupture",
            "obj.ferocious_gloves",
            "obj.confliction_gauntlets",
            "obj.zaryte_vambraces",
            "obj.avernic_treads_max",
            "obj.ultor_ring",
            "obj.lightbearer",
            "obj.magus_ring",
            "obj.venator_ring",
            "obj.elidinis_ward_fortified",
            "obj.book_of_the_dead",
            "obj.scythe_of_vitur",
            "obj.scythe_of_vitur_or",
            "obj.scythe_of_vitur_bl",
            "obj.dragon_claws",
            "obj.bgs",
            "obj.elder_maul",
            "obj.crystal_halberd",
            "obj.dinhs_bulwark",
            "obj.sulphur_blades",
            "obj.obligator",
            "obj.swift_blade",
            "obj.twisted_bow",
            "obj.toxic_blowpipe_loaded",
            "obj.zaryte_xbow",
            "obj.ascension_crossbows",
            "obj.tonalztics_of_ralos_charged",
            "obj.chinchompa_black",
            "obj.tumekens_shadow",
            "obj.sanguinesti_staff",
            "obj.ancient_sceptre_shadow",
            "obj.ancient_sceptre_ice",
            "obj.kodai_wand",
            "obj.eye_of_ayak",
            "obj.zoryas_tome",
        )

    private val AMMO =
        listOf(
            "obj.dragon_arrow",
            "obj.seeking_dragon_arrow",
            "obj.dragon_bolts_enchanted_ruby",
            "obj.ascension_bolts_diamond_enchanted",
            "obj.dragon_dart",
        )

    private val SUPPLIES =
        listOf(
            "obj.4dosepotionofsaradomin",
            "obj.4dose2restore",
            "obj.4dose2combat",
            "obj.4dosedivinecombat",
            "obj.4dosedivinerange",
            "obj.4dosedivinemagic",
            "obj.4dosedivinebastion",
            "obj.4dosedivinebattlemage",
            "obj.4dosedivineattack",
            "obj.4dosedivinestrength",
            "obj.4dosedivinedefence",
            "obj.4doserangerspotion",
            "obj.4dose1magic",
            "obj.4dosestamina",
            "obj.antivenom+4",
            "obj.4doseprayerrestore",
            "obj.shark",
            "obj.anglerfish",
            "obj.mantaray",
            "obj.tbwt_cooked_karambwan",
            "obj.vial_empty",
            "obj.rotten_potato",
        )

    private val lists: Map<Tab, List<ItemServerType>> by lazy { build() }

    fun items(tab: Tab): List<ItemServerType> = lists.getValue(tab)

    private fun build(): Map<Tab, List<ItemServerType>> {
        fun resolve(tab: Tab, names: List<String>): List<ItemServerType> =
            names.mapNotNull { n ->
                ServerCacheManager.getItem(n.asRSCM(RSCMType.OBJ)).also { if (it == null) logger.warn { "supply chest: no item $n (${tab.label})" } }
            }
        val runes = resolve(Tab.Runes, RUNES)
        return mapOf(
            Tab.Runes to runes,
            Tab.Gear to resolve(Tab.Gear, GEAR),
            Tab.Ammo to resolve(Tab.Ammo, AMMO),
            Tab.Supplies to resolve(Tab.Supplies, SUPPLIES),
        )
    }
}
