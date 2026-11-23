package org.rsmod.content.skills.magic.lunar

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.player.PracticeSettings
import org.rsmod.api.player.output.UpdateInventory.resendSlot
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.protect.clearPendingAction
import org.rsmod.api.player.ui.IfOverlayButtonT
import org.rsmod.api.script.onEvent
import org.rsmod.api.table.PotionRow
import org.rsmod.content.other.consumables.potion.PotionEffectService
import org.rsmod.events.EventBus
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Stat Restore Pot Share (81) and Boost Potion Share (84), cast on a potion in the inventory. You
 * get the potion's effect for free and each player with Accept Aid on in the 5x5 area around you
 * gets it too for one dose each, up to four players or as many doses as are left. Cast with no
 * one around, you drink a dose yourself.
 *
 * Stat Restore Pot Share takes energy, super energy, stamina, prayer, restore, super restore,
 * anti-venom and sanfew potions; Boost Potion Share takes the stat boosting potions (no brews,
 * divine, combat, hunter, raid or minigame potions).
 */
class PotionShareScript
@Inject
constructor(
    private val caster: LunarCaster,
    private val eventBus: EventBus,
    private val protectedAccess: ProtectedAccessLauncher,
    private val effects: PotionEffectService,
) : PluginScript() {
    override fun ScriptContext.startup() {
        for (share in Share.entries) {
            val key =
                EventBus.composeLongKey(
                    share.component.asRSCM(RSCMType.COMPONENT),
                    "component.inventory:items".asRSCM(RSCMType.COMPONENT),
                )
            onEvent<IfOverlayButtonT>(key) { op(this, share) }
        }
    }

    private fun op(event: IfOverlayButtonT, share: Share) {
        val player = event.player
        if (player.isDelayed || player.isAccessProtected) {
            resendSlot(player.inv, 0)
            return
        }
        player.clearPendingAction(eventBus)
        protectedAccess.launch(player) { cast(share, event.targetSlot) }
    }

    private fun ProtectedAccess.cast(share: Share, slot: Int) {
        val obj = inv[slot] ?: return
        val potion = PotionRow.all().firstOrNull { row -> row.items.any { it.id == obj.id } }
        if (potion == null || potion.key !in share.potions) {
            mes(share.wrongPotion)
            return
        }
        if (!effects.canApply(this, potion.effect)) {
            return
        }
        val spell = caster.spell(share.obj) ?: return
        val dose = potion.items.indexOfFirst { it.id == obj.id }
        val dosesLeft = potion.items.size - dose
        val targets =
            caster
                .playersAround(player, SHARE_RADIUS)
                .filter { it.acceptAid }
                .take(minOf(MAX_TARGETS, dosesLeft))
        if (!caster.cast(player, spell)) {
            return
        }
        anim(SHARE_ANIM)
        spotanim(share.spotanim, height = 92)
        effects.apply(this, potion.effect)
        val sharer = player.displayName
        var used = 0
        for (target in targets) {
            val launched =
                protectedAccess.launch(target) {
                    effects.apply(this, potion.effect)
                    spotanim(share.spotanim, height = 92)
                    mes("$sharer has shared a ${potion.name.lowercase()} with you.")
                }
            if (launched) {
                used++
            }
        }
        val doses = if (targets.isEmpty()) 1 else used
        if (doses > 0 && !PracticeSettings.infiniteSupplies) {
            val replacement = potion.items.getOrNull(dose + doses) ?: potion.empty
            invReplaceSlot(inv, slot, 1, replacement)
        }
    }

    private enum class Share(
        val component: String,
        val obj: String,
        val spotanim: String,
        val wrongPotion: String,
        val potions: Set<String>,
    ) {
        StatRestore(
            component = "component.magic_spellbook:rest_pot_share",
            obj = "obj.81_res_pot_share",
            spotanim = "spotanim.quest_lunar_spellbook_cure_group_spot_anim",
            wrongPotion = "You can only cast this spell on a potion that restores stats.",
            potions =
                setOf(
                    "energy_potion",
                    "super_energy",
                    "stamina_potion",
                    "prayer_potion",
                    "restore_potion",
                    "super_restore",
                    "anti_venom",
                    "anti_venom_plus",
                    "extended_anti_venom_plus",
                    "sanfew_serum",
                ),
        ),
        Boost(
            component = "component.magic_spellbook:stren_pot_share",
            obj = "obj.84_statboost_pot_share",
            spotanim = "spotanim.quest_lunar_spellbook_heal_group_spot_anim",
            wrongPotion = "You can only cast this spell on a potion that boosts stats.",
            potions =
                setOf(
                    "attack_potion",
                    "strength_potion",
                    "defence_potion",
                    "agility_potion",
                    "fishing_potion",
                    "super_attack",
                    "super_strength",
                    "super_defence",
                    "super_combat",
                    "ranging_potion",
                    "magic_potion",
                    "bastion_potion",
                    "battlemage_potion",
                    "super_fishing_potion",
                ),
        ),
    }

    private companion object {
        const val SHARE_RADIUS = 2
        const val MAX_TARGETS = 4
        const val SHARE_ANIM = "seq.quest_lunar_spell_cast_spell_on_group"
    }
}
