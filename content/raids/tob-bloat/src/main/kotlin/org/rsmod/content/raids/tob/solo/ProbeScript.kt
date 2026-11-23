package org.rsmod.content.raids.tob.solo

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.combat.weapon.styles.AttackStyles
import org.rsmod.api.combat.weapon.types.AttackTypes
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.script.onEvent
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.content.raids.tob.bloat.RaidBots
import org.rsmod.content.raids.tob.bloat.SupplyCatalog
import org.rsmod.game.MapClock
import org.rsmod.game.inv.InvObj
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/** `TOB_PROBE=1`: a bot takes the supply chest's runes and logs which spells and weapon stances resolve. */
class ProbeScript
@Inject
constructor(
    private val bots: RaidBots,
    private val spells: MagicSpellRegistry,
    private val runes: MagicRuneManager,
    private val types: AttackTypes,
    private val styles: AttackStyles,
    private val mapClock: MapClock,
    private val specials: org.rsmod.api.specials.SpecialAttackRegistry,
) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        if (System.getenv("TOB_PROBE") == null) return
        onEvent<GameLifecycle.StartCycle> { if (mapClock.cycle == PROBE_AT) probe() }
    }

    private fun probe() {
        val bot = bots.spawn("Probe", SoloRaidScript.EXIT, emptyList(), emptyList()) ?: return
        val chest = SupplyCatalog.items(SupplyCatalog.Tab.Runes)
        logger.info { "probe runes: " + chest.joinToString { "${it.name}=${it.id}" } }
        for ((i, rune) in chest.withIndex()) bot.inv[i] = InvObj(rune, 100_000)
        for (name in SPELLS) {
            val obj = ServerCacheManager.getItem(name.asRSCM(RSCMType.OBJ))
            val spell = obj?.let(spells::getObjSpell)
            if (spell == null) {
                logger.info { "probe spell $name: not registered" }
                continue
            }
            val bad = runes.validateSpell(bot, spell).filter { it.isInvalid }
            logger.info { "probe spell $name: ${if (bad.isEmpty()) "castable" else "missing $bad"}" }
        }
        for (weapon in WEAPONS) {
            val type = ServerCacheManager.getItem(weapon.asRSCM(RSCMType.OBJ))
            val row = (0..3).joinToString { "${types.resolve(type, it)}/${styles.resolve(type, it)}" }
            logger.info { "probe weapon $weapon (${type?.weaponCategory}): $row" }
        }
        for (row in COMBAT_ROWS) {
            val r = org.rsmod.api.table.combat.CombatInterfaceWeaponCategoryRow.getRow(row)
            logger.info { "probe combat tab $row (category ${r.id}): ${r.button}" }
        }
        auditCombatTabs()
        for (tab in listOf(SupplyCatalog.Tab.Gear, SupplyCatalog.Tab.Ammo)) {
            for (item in SupplyCatalog.items(tab)) {
                if (item.wearpos1 != WEAPON_SLOT) continue
                val spec = specials[InvObj(item)]
                logger.info { "probe spec ${item.name}: ${spec?.javaClass?.simpleName ?: "none"}" }
            }
        }
        for (tab in SupplyCatalog.Tab.entries) {
            val picks = SupplyCatalog.items(tab).map { "${it.name}=${dev.openrune.rscm.RSCM.getReverseMapping(dev.openrune.rscm.RSCMType.OBJ, it.id)}" }
            logger.info { "probe chest ${tab.label}: $picks" }
        }
        bots.remove(bot)
    }

    /** Every combat-tab button in the cache against the server's attack type and style for that stance. */
    private fun auditCombatTabs() {
        for (row in org.rsmod.api.table.combat.CombatInterfaceWeaponCategoryRow.all()) {
            val category = dev.openrune.util.WeaponCategory.entries.firstOrNull { it.id == row.id }
            for (b in row.button) {
                val stance = b.t0
                val text = b.t2.lowercase()
                val type = category?.let { types.resolve(it, stance) }
                val style = category?.let { styles.resolve(it, stance) }
                val wantType = listOf("stab", "slash", "crush", "magic", "light", "standard", "heavy").firstOrNull { "($it)" in text }
                val wantStyle = listOf("accurate", "aggressive", "controlled", "defensive", "rapid", "longrange").firstOrNull { "($it)" in text }
                val okType = wantType == null || type?.name?.lowercase() == wantType
                val okStyle = wantStyle == null || style?.name?.lowercase()?.startsWith(wantStyle) == true
                if (!okType || !okStyle) {
                    logger.info { "probe tab mismatch: row ${row.rowId} category ${row.id} ($category) stance $stance ${b.t1}: cache $wantType/$wantStyle, server $type/$style" }
                }
            }
        }
        for (tab in listOf(SupplyCatalog.Tab.Gear, SupplyCatalog.Tab.Ammo)) {
            for (item in SupplyCatalog.items(tab)) {
                if (item.wearpos1 != WEAPON_SLOT) continue
                val c = item.weaponCategory
                val row = org.rsmod.api.table.combat.CombatInterfaceWeaponCategoryRow.all().firstOrNull { it.id == c.id }
                val stances = row?.button?.map { it.t0 } ?: listOf(0, 1, 2, 3)
                val bad = stances.filter { types.resolve(c, it) == null }
                if (c == dev.openrune.util.WeaponCategory.Unarmed || bad.isNotEmpty()) {
                    logger.info { "probe chest weapon ${item.name} (${item.id}): category $c, stances without a type $bad" }
                }
            }
        }
        logger.info { "probe tab audit done" }
    }

    private companion object {
        const val PROBE_AT = 30
        val SPELLS =
            listOf(
                "obj.94_ice_barrage",
                "obj.92_blood_barrage",
                "obj.94_vengeance",
                "obj.66_monster_examine",
                "obj.95_fire_surge",
            )
        const val WEAPON_SLOT = 3
        val COMBAT_ROWS =
            listOf("dbrow.combat_interface_extending_chainmace", "dbrow.combat_interface_bow", "dbrow.combat_interface_blunt")
        val WEAPONS =
            listOf(
                "obj.obligator",
                "obj.scythe_of_vitur",
                "obj.dragon_claws",
                "obj.bgs",
                "obj.dragon_warhammer",
                "obj.twisted_bow",
                "obj.zaryte_xbow",
            )
    }
}
