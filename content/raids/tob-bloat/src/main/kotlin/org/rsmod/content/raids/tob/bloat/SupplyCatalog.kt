package org.rsmod.content.raids.tob.bloat

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType

/**
 * What the supply chest's tabs offer. Gear and ammo are the sim's item list (verzikSim engine/gear.js);
 * runes are every rune in the game; supplies are the sim's supplies plus an empty vial. Names are
 * resolved against the cache when the chest is first opened; any name the cache doesn't have is
 * logged and left out.
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
        )

    private val GEAR =
        listOf(
            "Torva full helm", "Torva platebody", "Torva platelegs",
            "Oathplate helm", "Oathplate chest", "Oathplate legs",
            "Ancestral hat", "Ancestral robe top", "Ancestral robe bottom",
            "Virtus mask", "Virtus robe top", "Virtus robe bottom",
            "Masori mask (f)", "Masori body (f)", "Masori chaps (f)",
            "Void ranger helm", "Elite void top", "Elite void robe", "Void knight gloves",
            "Sanguine torva full helm", "Sanguine torva platebody", "Sanguine torva platelegs",
            "Radiant oathplate helm", "Radiant oathplate chest", "Radiant oathplate legs",
            "Twisted ancestral hat", "Twisted ancestral robe top", "Twisted ancestral robe bottom",
            "Infernal cape", "Imbued saradomin cape", "Blessed dizana's quiver",
            "Infernal max cape", "Imbued saradomin max cape", "Dizana's max cape",
            "Amulet of rancour", "Amulet of blood fury", "Salve amulet (e)", "Salve amulet(ei)",
            "Phoenix necklace", "Occult necklace", "Necklace of anguish", "Necklace of rupture",
            "Ferocious gloves", "Confliction gauntlets", "Zaryte vambraces",
            "Avernic treads (max)",
            "Ultor ring", "Lightbearer", "Magus ring", "Venator ring",
            "Elidinis' ward (f)", "Book of the dead",
            "Scythe of vitur", "Holy scythe of vitur", "Sanguine scythe of vitur", "Dragon claws", "Bandos godsword", "Elder maul", "Crystal halberd",
            "Dinh's bulwark", "Sulphur blades", "Obligator", "Swift blade",
            "Twisted bow", "Toxic blowpipe", "Zaryte crossbow", "Ascension crossbow",
            "Tonalztics of ralos", "Black chinchompa",
            "Tumeken's shadow", "Sanguinesti staff", "Shadow ancient sceptre", "Ice ancient sceptre",
            "Kodai wand", "Eye of ayak", "Zorya's tome",
        )

    private val AMMO =
        listOf("Dragon arrow", "Seeking dragon arrow", "Ruby dragon bolts (e)", "Diamond ascension bolts (e)", "Dragon dart")

    private val SUPPLIES =
        listOf(
            "Saradomin brew(4)", "Super restore(4)", "Super combat potion(4)",
            "Divine super combat potion(4)", "Divine ranging potion(4)", "Divine magic potion(4)",
            "Divine bastion potion(4)", "Divine battlemage potion(4)",
            "Divine super attack potion(4)", "Divine super strength potion(4)", "Divine super defence potion(4)",
            "Ranging potion(4)", "Magic potion(4)", "Stamina potion(4)", "Anti-venom+(4)", "Prayer potion(4)",
            "Shark", "Anglerfish", "Manta ray", "Cooked karambwan", "Vial", "Rotten potato",
        )

    private val lists: Map<Tab, List<ItemServerType>> by lazy { build() }

    fun items(tab: Tab): List<ItemServerType> = lists.getValue(tab)

    private val byName: Map<String, List<ItemServerType>> by lazy {
        ServerCacheManager.getItems().values
            .filter { !it.isCert && !it.isPlaceholder && !it.isDummyItem && it.name.isNotBlank() }
            .groupBy { it.name.lowercase() }
    }

    // a name can belong to several items (minigame and quest copies): the tradeable one, else the first
    private fun best(all: List<ItemServerType>): ItemServerType = all.firstOrNull { it.tradeable } ?: all.minBy { it.id }

    /** The item by its name in game (the sim's names): exact first, else the shortest name containing it. */
    fun item(name: String): ItemServerType? =
        byName[name.lowercase()]?.let(::best)
            ?: byName.keys.filter { it.contains(name.lowercase()) }.minByOrNull { it.length }?.let { best(byName.getValue(it)) }

    private fun build(): Map<Tab, List<ItemServerType>> {
        fun pick(name: String): ItemServerType? = item(name)
        fun resolve(tab: Tab, names: List<String>): List<ItemServerType> =
            names.mapNotNull { n -> pick(n).also { if (it == null) logger.warn { "supply chest: no item named \"$n\" (${tab.label})" } } }
        // the real runes by their gamevals: several minigame copies share the names (Rogue Trader's, the
        // board games', Nightmare Zone's), and only these count for spells - client and server
        val runes =
            RUNES.mapNotNull { n ->
                ServerCacheManager.getItem(n.asRSCM(RSCMType.OBJ)).also { if (it == null) logger.warn { "supply chest: no rune $n" } }
            }
        logger.info { "supply chest runes: " + runes.joinToString { "${it.name}=${it.id}" } }
        return mapOf(
            Tab.Runes to runes,
            Tab.Gear to resolve(Tab.Gear, GEAR),
            Tab.Ammo to resolve(Tab.Ammo, AMMO),
            Tab.Supplies to resolve(Tab.Supplies, SUPPLIES),
        )
    }
}
