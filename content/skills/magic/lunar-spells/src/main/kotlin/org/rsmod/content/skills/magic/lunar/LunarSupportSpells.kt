package org.rsmod.content.skills.magic.lunar

import jakarta.inject.Inject
import kotlin.math.min
import org.rsmod.api.mechanics.toxins.impl.PlayerPoison
import org.rsmod.api.mechanics.toxins.impl.PlayerVenom
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.statHeal
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Lunar spells cast on yourself and the players around you: Cure Me, Cure Group, Heal Group
 * and Dream.
 */
class LunarSupportSpells @Inject constructor(private val caster: LunarCaster) : PluginScript() {
    override fun ScriptContext.startup() {
        onIfOverlayButton("component.magic_spellbook:cure_me") { cureMe() }
        onIfOverlayButton("component.magic_spellbook:cure_group") { cureGroup() }
        onIfOverlayButton("component.magic_spellbook:heal_group") { healGroup() }
        onIfOverlayButton("component.magic_spellbook:dream") { dream() }
        onPlayerQueueWithArgs<DreamState>(DREAM_QUEUE) { dreamTick(it.args) }
    }

    /** Cure Me (71): cures your own poison the way an antipoison would (venom becomes poison). */
    private fun ProtectedAccess.cureMe() {
        if (!player.isPoisonedOrVenomed()) {
            mes("You are not poisoned.")
            return
        }
        val spell = caster.spell("obj.71_cure_me") ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim(PUSH_ANIM)
        spotanim("spotanim.quest_lunar_cure_me_spot", height = 92)
        soundSynth(CURE_SOUND)
        player.curePoison()
        mes("You have cured yourself of poison.")
    }

    /**
     * Cure Group (74): cures the poison on you and on every player in the 3x3 area around you who
     * has Accept Aid on.
     */
    private fun ProtectedAccess.cureGroup() {
        val spell = caster.spell("obj.74_cure_group") ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim(GROUP_ANIM)
        spotanim("spotanim.quest_lunar_spellbook_cure_group_spot_anim", height = 92)
        soundSynth(CURE_SOUND)
        player.curePoison()
        for (other in caster.playersAround(player, GROUP_RADIUS)) {
            if (!other.acceptAid || !other.isPoisonedOrVenomed()) {
                continue
            }
            other.curePoison()
            other.spotanim("spotanim.quest_lunar_spellbook_cure_group_spot_anim", height = 92)
            other.mes("You have been cured by ${player.displayName}.")
        }
    }

    /**
     * Heal Group (95): splits 75% of your current hitpoints evenly between the injured players in
     * the 3x3 area around you who have Accept Aid on (never more than each is missing). You need at
     * least 11% of your hitpoints.
     */
    private fun ProtectedAccess.healGroup() {
        if (player.hitpoints * 100 < player.baseHitpointsLvl * MIN_HP_PERCENT) {
            mes("You don't have enough hitpoints to cast this spell.")
            return
        }
        val injured =
            caster.playersAround(player, GROUP_RADIUS).filter {
                it.acceptAid && it.hitpoints < it.baseHitpointsLvl
            }
        if (injured.isEmpty()) {
            mes("There is nobody around you who needs healing.")
            return
        }
        val share = player.hitpoints * 3 / 4 / injured.size
        if (share <= 0) {
            mes("You don't have enough hitpoints to cast this spell.")
            return
        }
        val spell = caster.spell("obj.95_heal_group") ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim(GROUP_ANIM)
        spotanim("spotanim.quest_lunar_spellbook_heal_group_spot_anim", height = 92)
        soundSynth("synth.heal")
        var given = 0
        for (other in injured) {
            val amount = min(share, other.baseHitpointsLvl - other.hitpoints)
            other.statHeal("stat.hitpoints", constant = amount, percent = 0)
            other.spotanim("spotanim.quest_lunar_spellbook_heal_group_spot_anim", height = 92)
            other.mes("${player.displayName} has healed you.")
            given += amount
        }
        player.statSub("stat.hitpoints", constant = given, percent = 0)
    }

    /**
     * Dream (79): you lie down and your hitpoints come back three times as fast as normal (natural
     * regeneration gives one every 100 ticks; dreaming adds one every 50) until you move, act, are
     * attacked or are back to full. It can't be cast in combat or with full hitpoints.
     */
    private fun ProtectedAccess.dream() {
        if (player.hitpoints >= player.baseHitpointsLvl) {
            mes("You have no need to cast this spell since your hitpoints are already full.")
            return
        }
        if (isInCombat()) {
            mes("You can't cast this spell while you're in combat.")
            return
        }
        val spell = caster.spell("obj.79_dream") ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim(DREAM_LOOP_ANIM)
        spotanim("spotanim.dream_zeds_spotanim")
        clearWeakQueue(DREAM_QUEUE)
        weakQueue(DREAM_QUEUE, 1, DreamState(coords.packed, 0))
    }

    private fun ProtectedAccess.dreamTick(state: DreamState) {
        if (CoordGrid(state.coords) != coords || isInCombat()) {
            resetAnim()
            return
        }
        val tick = state.tick + 1
        if (tick % DREAM_HEAL_TICKS == 0) {
            statHeal("stat.hitpoints", constant = 1, percent = 0)
        }
        if (player.hitpoints >= player.baseHitpointsLvl) {
            resetAnim()
            mes("You wake up feeling refreshed.")
            return
        }
        if (tick % DREAM_LOOP_TICKS == 0) {
            anim(DREAM_LOOP_ANIM)
            spotanim("spotanim.dream_zeds_spotanim")
        }
        weakQueue(DREAM_QUEUE, 1, DreamState(state.coords, tick))
    }

    private data class DreamState(val coords: Int, val tick: Int)

    private fun Player.isPoisonedOrVenomed(): Boolean =
        PlayerPoison.isPoisoned(this) || PlayerVenom.isEnvenomed(this)

    private fun Player.curePoison() {
        if (!PlayerVenom.reduceToPoison(this)) {
            PlayerPoison.clear(this)
        }
    }

    private companion object {
        const val GROUP_RADIUS = 1
        const val MIN_HP_PERCENT = 11
        const val PUSH_ANIM = "seq.quest_lunar_pushing_magic_animation"
        const val GROUP_ANIM = "seq.quest_lunar_spell_cast_spell_on_group"
        const val CURE_SOUND = "synth.tbcu_diseasecure"
        const val DREAM_QUEUE = "queue.lunar_dream"
                const val DREAM_LOOP_ANIM = "seq.dream_player_dream_spell_ready"
        const val DREAM_HEAL_TICKS = 50
        const val DREAM_LOOP_TICKS = 4
    }
}
