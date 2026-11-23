package org.rsmod.content.skills.magic.lunar

import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onOpLocT
import org.rsmod.content.skills.farming.Compost
import org.rsmod.content.skills.farming.CureResult
import org.rsmod.content.skills.farming.FarmingComposts
import org.rsmod.content.skills.farming.FarmingPatches
import org.rsmod.content.skills.farming.FarmingService
import org.rsmod.content.skills.farming.Health
import org.rsmod.content.skills.farming.PatchDef
import org.rsmod.content.skills.farming.crop
import org.rsmod.content.skills.farming.TreatResult
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/** The Lunar farming spells: Cure Plant and Fertile Soil (cast on a patch) and Geomancy. */
class LunarFarmingSpells
@Inject
constructor(
    private val caster: LunarCaster,
    private val farming: FarmingService,
    private val world: WorldRepository,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onOpLocT("component.magic_spellbook:cure_plant") { cast(it.loc) { patch, loc -> curePlant(patch, loc) } }
        onOpLocT("component.magic_spellbook:fertile_soil") { cast(it.loc) { patch, loc -> fertileSoil(patch, loc) } }
        onIfOverlayButton("component.magic_spellbook:geomancy") { geomancy() }
    }

    private fun ProtectedAccess.cast(loc: BoundLocInfo, spell: ProtectedAccess.(PatchDef, BoundLocInfo) -> Unit) {
        val patch = FarmingPatches.byLoc(RSCM.getReverseMapping(RSCMType.LOC, loc.id))
        if (patch == null) {
            mes("You can only cast this spell on a farming patch.")
            return
        }
        faceLoc(loc)
        spell(patch, loc)
    }

    /** Cure Plant (66): cures a diseased crop on the patch it's cast on, like a plant cure. */
    private fun ProtectedAccess.curePlant(patch: PatchDef, loc: BoundLocInfo) {
        val spell = caster.spell("obj.66_cure_plant") ?: return
        if (!caster.canCast(player, spell)) {
            return
        }
        val result = farming.cure(player, patch) { caster.cast(player, spell) }
        when (result) {
            CureResult.CURED -> {
                anim(GROUP_ANIM)
                spotanimMap(world, "spotanim.quest_lunar_cure_plant_spot", loc.coords)
                mes("You cure the plant with a magical spell.")
            }
            CureResult.UNPAID, CureResult.STALE -> Unit
            else -> mes("This patch doesn't need curing.")
        }
    }

    /**
     * Fertile Soil (83): treats the patch it's cast on with supercompost, as if a bucket had been
     * poured on it, for the same Farming experience.
     */
    private fun ProtectedAccess.fertileSoil(patch: PatchDef, loc: BoundLocInfo) {
        val spell = caster.spell("obj.83_treat_soil") ?: return
        if (!caster.canCast(player, spell)) {
            return
        }
        val result = farming.treat(player, patch, Compost.SUPER) { caster.cast(player, spell) }
        when (result) {
            TreatResult.TREATED -> {
                anim("seq.quest_lunar_spell_raining_fertile_soil")
                spotanimMap(world, "spotanim.quest_lunar_spellbook_fertile_soil_spot_anim", loc.coords)
                farming.awardPatchXp(player, patch, FarmingComposts.get(Compost.SUPER).xp)
                mes("You fertilise the soil with supercompost.")
            }
            TreatResult.WEEDS -> mes("The patch needs weeding first.")
            TreatResult.ALREADY -> mes("This patch has already been fertilised.")
            TreatResult.DEAD, TreatResult.GROWN -> mes("You can't fertilise this patch now.")
            TreatResult.UNPAID, TreatResult.STALE -> Unit
        }
    }

    /**
     * Geomancy (65): reports the state of every farming patch. The real spell lists them in the
     * Geomancy panel; here each planted patch is reported in the chatbox.
     */
    private fun ProtectedAccess.geomancy() {
        val spell = caster.spell("obj.watering_can_dummy") ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim(GROUP_ANIM)
        var planted = 0
        for (patch in FarmingPatches.all) {
            val state = farming.state(player, patch)
            val crop = state.crop ?: continue
            planted++
            val status =
                when {
                    state.health == Health.DEAD -> "dead"
                    state.health == Health.DISEASED -> "diseased"
                    state.grown(crop) -> "fully grown"
                    else -> "growing (stage ${state.stage + 1}/${crop.stages + 1})"
                }
            mes("${patch.site.replace('_', ' ')} ${patch.kind.label}: ${crop.name}, $status.")
        }
        if (planted == 0) {
            mes("None of your farming patches have anything growing in them.")
        }
    }

    private companion object {
        const val GROUP_ANIM = "seq.quest_lunar_spell_cast_spell_on_group"
    }
}
