@file:OptIn(dev.openrune.types.util.UncheckedType::class)

package org.rsmod.content.raids.tob.bloat

import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.rscm.RSCM.asRSCM
import jakarta.inject.Inject
import java.io.File
import org.rsmod.api.config.refs.params
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.script.onEvent
import org.rsmod.api.specials.weapon.SpecialAttackWeapons
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.api.spells.attack.SpellAttackRegistry
import org.rsmod.game.MapClock
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Dev audit (env TOB_AUDIT=<file>): writes every spell in the cache (book, level, name, type, its
 * spellbook button, spell obj, runes, whether a combat attack is registered) and the supply
 * chest's gear and ammo (category, projectile params, special energy), so the gaps can be listed.
 */
class EngineAudit
@Inject
constructor(
    private val spells: MagicSpellRegistry,
    private val attacks: SpellAttackRegistry,
    private val specialWeapons: SpecialAttackWeapons,
    private val specials: org.rsmod.api.specials.SpecialAttackRegistry,
    private val weapons: org.rsmod.api.weapons.WeaponRegistry,
    private val clock: MapClock,
) : PluginScript() {
    override fun ScriptContext.startup() {
        val path = System.getenv("TOB_AUDIT") ?: return
        onEvent<GameLifecycle.StartCycle> { if (clock.cycle == 3) write(File(path)) }
    }

    private fun write(file: File) {
        val out = StringBuilder()
        for (spell in spells.allSpells().sortedWith(compareBy({ it.spellbook?.name }, { it.levelReq }))) {
            val obj = RSCM.getReverseMapping(RSCMType.OBJ, spell.obj.id)
            val com = RSCM.getReverseMapping(RSCMType.COMPONENT, spell.component.packed)
            val runes = spell.objReqs.joinToString(",") { "${it.count}x${RSCM.getReverseMapping(RSCMType.OBJ, it.obj.id)}" }
            out.append("SPELL\t${spell.spellbook}\t${spell.levelReq}\t${spell.name}\t${spell.type}\t$com\t$obj\t${spell in attacks}\t${spell.questReq}\t$runes\n")
        }
        for (tab in listOf(SupplyCatalog.Tab.Gear, SupplyCatalog.Tab.Ammo)) {
            for (item in SupplyCatalog.items(tab)) {
                val name = RSCM.getReverseMapping(RSCMType.OBJ, item.id)
                val cat = item.category.takeIf { it >= 0 }?.let { runCatching { RSCM.getReverseMapping(RSCMType.CATEGORY, it) }.getOrNull() }
                val proj = item.paramOrNull(params.proj_type) != null
                val travel = item.paramOrNull(params.proj_travel) != null
                val anim = item.paramOrNull(params.attack_anim_stance1) != null
                val spec = specialWeapons.getSpecialEnergy(item.id)
                out.append("GEAR\t${item.name}\t$name\twearpos=${item.wearpos1}\tcat=$cat\tproj_type=$proj\tproj_travel=$travel\tanim=$anim\tspecEnergy=$spec\n")
            }
        }
        for (item in dev.openrune.ServerCacheManager.getItems().values) {
            if (item.isCert || item.isPlaceholder || item.wearpos1 != 3 && item.wearpos1 != 13) continue
            val name = runCatching { RSCM.getReverseMapping(RSCMType.OBJ, item.id) }.getOrNull() ?: continue
            val cat = item.category.takeIf { it >= 0 }?.let { runCatching { RSCM.getReverseMapping(RSCMType.CATEGORY, it) }.getOrNull() }
            val spec = specialWeapons.getSpecialEnergy(item.id)
            val obj = org.rsmod.game.inv.InvObj(item.id, 1)
            val hasSpec = specials[obj] != null
            val handler = weapons.getMelee(obj) != null || weapons.getRanged(obj) != null || weapons.getMagic(obj) != null
            val proj = item.paramOrNull(params.proj_type) != null
            val travel = item.paramOrNull(params.proj_travel) != null
            val anim = item.paramOrNull(params.attack_anim_stance1) != null
            out.append("ITEM\t${item.name}\t$name\t${item.wearpos1}\t$cat\tspec=$spec\thasSpec=$hasSpec\thandler=$handler\tproj_type=$proj\tproj_travel=$travel\tanim=$anim\ttradeable=${item.tradeable}\n")
        }
        val prayerId = dev.openrune.ParamReferences.param<Int>("prayer_id")
        val prayerCom = dev.openrune.ParamReferences.param<dev.openrune.definition.type.widget.ComponentType>("prayer_component")
        val prayerVar = dev.openrune.ParamReferences.param<dev.openrune.definition.type.VarBitType>("prayer_varbit")
        val prayerLvl = dev.openrune.ParamReferences.param<Int>("prayer_levelreq")
        val prayerDrain = dev.openrune.ParamReferences.param<Int>("prayer_drain_effect")
        val prayerUnlock = dev.openrune.ParamReferences.param<dev.openrune.definition.type.VarBitType>("prayer_unlock_varbit")
        for (item in dev.openrune.ServerCacheManager.getItems().values) {
            val pid = runCatching { item.paramOrNull(prayerId) }.getOrNull() ?: continue
            val com = runCatching { item.paramOrNull(prayerCom)?.let { RSCM.getReverseMapping(RSCMType.COMPONENT, it.packed) } }.getOrNull()
            val vb = runCatching { item.paramOrNull(prayerVar)?.let { RSCM.getReverseMapping(RSCMType.VARBIT, it.id) } }.getOrNull()
            val un = runCatching { item.paramOrNull(prayerUnlock)?.let { RSCM.getReverseMapping(RSCMType.VARBIT, it.id) } }.getOrNull()
            out.append("PRAYER\t$pid\t${item.name}\t${RSCM.getReverseMapping(RSCMType.OBJ, item.id)}\t$com\t$vb\tlvl=${runCatching { item.paramOrNull(prayerLvl) }.getOrNull()}\tdrain=${runCatching { item.paramOrNull(prayerDrain) }.getOrNull()}\tunlock=$un\n")
        }
        for (name in listOf("prayer_allactive", "prayer_piety", "prayer_augury", "prayer_rigour", "prayer_eagleeye", "prayer_mysticmight", "prayer_deadeye", "prayer_mysticvigour", "prayer_zeal", "prayer_deadeye_unlocked", "prayer_mystic_vigour_unlocked", "prayer_zeal_unlocked", "quickprayer_selected", "prayer_preserve")) {
            val id = runCatching { "varbit.$name".asRSCM(RSCMType.VARBIT) }.getOrNull() ?: continue
            val v = dev.openrune.ServerCacheManager.getVarbit(id) ?: continue
            out.append("VARBIT\t$name\tvarp=${v.varp}\tbits=${v.startBit}-${v.endBit}\n")
        }
        for (name in listOf("tob_dungeon_treasure_room_weapon_rack_a", "dwarf_keldagrim_weapon_rack_full", "cabinet_swords", "barrel_swords", "frisd_izso_table_swords", "mah3_sewer_wall_swords", "fai_varrock_swordcase_swords", "clan_medieval_cabinet_swords")) {
            val id = runCatching { "loc.$name".asRSCM(RSCMType.LOC) }.getOrNull() ?: continue
            val l = dev.openrune.ServerCacheManager.getObject(id) ?: continue
            out.append("LOC\t$name\t${l.name}\t${l.actions}\tsize=${l.width}x${l.length}\n")
        }
        for (item in dev.openrune.ServerCacheManager.getItems().values) {
            val n = item.name.lowercase()
            if (!n.contains("ascension") && !n.startsWith("dizana") && n != "twisted bow" && !n.startsWith("obligator") && n != "viggora's chainmace" && n != "zaryte crossbow" && n != "ruby dragon bolts (e)") continue
            out.append("PARAMS\t${item.id}\t${item.name}\twearpos=${item.wearpos1}\tcat=${item.category}\tops=${item.interfaceOptions}\tparams=${item.paramsRaw}\n")
        }
        for (r in SupplyCatalog.items(SupplyCatalog.Tab.Runes)) {
            val all = dev.openrune.ServerCacheManager.getItems().values.filter { it.name == r.name }.map { "${it.id}:${dev.openrune.rscm.RSCM.getReverseMapping(dev.openrune.rscm.RSCMType.OBJ, it.id)}:t=${it.tradeable}" }
            out.append("CHESTRUNE\t${r.id}\t${r.name}\t${dev.openrune.rscm.RSCM.getReverseMapping(dev.openrune.rscm.RSCMType.OBJ, r.id)}\tall=$all\n")
        }
        for (id in listOf(8366, 8367, 8360)) {
            val n = dev.openrune.ServerCacheManager.getNpc(id) ?: continue
            out.append("NPCANIM\t$id\t${n.name}\tstand=${n.standAnim}\twalk=${n.walkAnim}\tparams=${n.paramsRaw}\n")
        }
        for (row in org.rsmod.api.table.combat.CombatInterfaceWeaponCategoryRow.all()) {
            out.append("WEAPONCAT\t${row.rowId}\t${row.id}\t${row.button}\n")
        }
        file.writeText(out.toString())
    }
}
