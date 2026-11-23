package org.rsmod.content.skills.magic.lunar

import jakarta.inject.Inject
import kotlin.math.min
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.config.aliases.ParamInt
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.params
import org.rsmod.api.mechanics.toxins.impl.PlayerPoison
import org.rsmod.api.mechanics.toxins.impl.PlayerVenom
import org.rsmod.api.player.output.UpdateRun
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.player.stat.statHeal
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.spells.attack.SpellAttack
import org.rsmod.api.spells.attack.SpellAttackManager
import org.rsmod.api.spells.attack.SpellAttackMap
import org.rsmod.api.spells.attack.SpellAttackRepository
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player

private var Player.specialEnergy by intVarp("varp.sa_energy")

/**
 * The Lunar spells cast on another player (Cure Other, Stat Spy, Energy Transfer, Heal Other and
 * Vengeance Other) and on a monster (Monster Examine). The client sends these through the
 * combat-spell path, so they are registered as spell "attacks" that never deal damage: each one
 * checks its own rules, pays its runes and then stops the combat interaction.
 */
class LunarTargetSpells @Inject constructor(private val caster: LunarCaster) : SpellAttackMap {
    override fun SpellAttackRepository.register(manager: SpellAttackManager) {
        for (spell in TargetSpell.entries) {
            register(spell.obj, TargetSpellAttack(manager, spell))
        }
    }

    private inner class TargetSpellAttack(
        private val manager: SpellAttackManager,
        private val spell: TargetSpell,
    ) : SpellAttack {
        override suspend fun ProtectedAccess.attack(target: Npc, attack: CombatAttack.Spell) {
            manager.stopCombat(this)
            if (spell != TargetSpell.MonsterExamine) {
                mes("You can only cast this spell on other players.")
                return
            }
            monsterExamine(target)
        }

        override suspend fun ProtectedAccess.attack(target: Player, attack: CombatAttack.Spell) {
            manager.stopCombat(this)
            when (spell) {
                TargetSpell.MonsterExamine -> mes("You can only cast this spell on monsters.")
                TargetSpell.CureOther -> cureOther(target)
                TargetSpell.StatSpy -> statSpy(target)
                TargetSpell.EnergyTransfer -> energyTransfer(target)
                TargetSpell.HealOther -> healOther(target)
                TargetSpell.VengeanceOther -> vengeanceOther(target)
            }
        }
    }

    private fun ProtectedAccess.castOn(target: Player, spell: TargetSpell, needsAid: Boolean = true): Boolean {
        val magic = caster.spell(spell.obj) ?: return false
        if (needsAid && !target.acceptAid) {
            mes("${target.displayName} doesn't have Accept Aid turned on.")
            return false
        }
        if (!caster.cast(player, magic)) {
            return false
        }
        faceEntitySquare(target)
        anim(spell.anim)
        return true
    }

    /**
     * Cure Other (68): cures the poison on another player, the way an antipoison would (venom is
     * turned back into poison). The target needs Accept Aid on.
     */
    private fun ProtectedAccess.cureOther(target: Player) {
        if (!PlayerPoison.isPoisoned(target) && !PlayerVenom.isEnvenomed(target)) {
            mes("${target.displayName} is not poisoned.")
            return
        }
        if (!castOn(target, TargetSpell.CureOther)) {
            return
        }
        target.spotanim(CURE_OTHER_SPOT, height = 92)
        soundSynth(CURE_SOUND)
        if (!PlayerVenom.reduceToPoison(target)) {
            PlayerPoison.clear(target)
        }
        target.mes("You have been cured by ${player.displayName}.")
    }

    /**
     * Stat Spy (75): opens a panel with the target's current and base levels in every skill. The
     * target does not need Accept Aid.
     */
    private fun ProtectedAccess.statSpy(target: Player) {
        if (!castOn(target, TargetSpell.StatSpy, needsAid = false)) {
            return
        }
        spotanim(STAT_SPY_CAST_SPOT)
        target.spotanim(STAT_SPY_TARGET_SPOT, height = 92)
        ifOpenSide(STAT_SPY_INTERFACE)
        ifSetText("component.dream_player_stats:stat_name", target.displayName)
        for ((component, stat) in STAT_SPY_SKILLS) {
            ifSetText("component.dream_player_stats:stat_$component", target.stat(stat).toString())
            ifSetText("component.dream_player_stats:statbase_$component", target.statBase(stat).toString())
        }
    }

    /**
     * Energy Transfer (91): with a full special attack bar, gives the target a full special attack
     * bar and full run energy. The caster pays all of their special attack energy and 10
     * hitpoints, so it needs more than 10 hitpoints. The target needs Accept Aid on.
     */
    private fun ProtectedAccess.energyTransfer(target: Player) {
        if (player.specialEnergy < FULL_SPECIAL) {
            mes("You need full special attack energy to cast this spell.")
            return
        }
        if (player.hitpoints <= ENERGY_TRANSFER_HP) {
            mes("You need more hitpoints to cast this spell.")
            return
        }
        if (!castOn(target, TargetSpell.EnergyTransfer)) {
            return
        }
        target.spotanim(ENERGY_TRANSFER_SPOT, height = 92)
        soundSynth(ENERGY_TRANSFER_SOUND)
        player.specialEnergy = 0
        player.statSub("stat.hitpoints", constant = ENERGY_TRANSFER_HP, percent = 0)
        target.specialEnergy = FULL_SPECIAL
        target.runEnergy = constants.run_max_energy
        UpdateRun.energy(target, target.runEnergy)
        target.mes("${player.displayName} has transferred energy to you.")
    }

    /**
     * Heal Other (92): moves up to 75% of the caster's current hitpoints to the target, never more
     * than the target is missing. The caster needs at least 11% of their hitpoints. The target
     * needs Accept Aid on.
     */
    private fun ProtectedAccess.healOther(target: Player) {
        if (player.hitpoints * 100 < player.baseHitpointsLvl * HEAL_OTHER_MIN_PERCENT) {
            mes("You don't have enough hitpoints to cast this spell.")
            return
        }
        val missing = target.baseHitpointsLvl - target.hitpoints
        if (missing <= 0) {
            mes("${target.displayName} doesn't need healing.")
            return
        }
        val amount = min(player.hitpoints * 3 / 4, missing)
        if (amount <= 0) {
            mes("You don't have enough hitpoints to cast this spell.")
            return
        }
        if (!castOn(target, TargetSpell.HealOther)) {
            return
        }
        target.spotanim(HEAL_OTHER_SPOT, height = 92)
        soundSynth(HEAL_SOUND)
        player.statSub("stat.hitpoints", constant = amount, percent = 0)
        target.statHeal("stat.hitpoints", constant = amount, percent = 0)
        target.mes("${player.displayName} has healed you.")
    }

    /**
     * Vengeance Other (93): gives the target Vengeance. It shares the caster's 30-second vengeance
     * cooldown, and fails if the target already has Vengeance. The target needs Accept Aid on.
     */
    private fun ProtectedAccess.vengeanceOther(target: Player) {
        if (player.vengeanceCooldown) {
            mes("You can only cast vengeance spells every 30 seconds.")
            return
        }
        if (target.vengeanceActive) {
            mes("${target.displayName} already has vengeance cast.")
            return
        }
        if (!castOn(target, TargetSpell.VengeanceOther)) {
            return
        }
        target.spotanim(VENGEANCE_OTHER_SPOT, height = 92)
        soundSynth(VENGEANCE_SOUND)
        target.vengeanceActive = true
        player.startVengeanceCooldown()
    }

    /**
     * Monster Examine (66): opens a panel with the monster's combat level, hitpoints, levels and
     * attack and defence bonuses.
     */
    private fun ProtectedAccess.monsterExamine(target: Npc) {
        val magic = caster.spell(TargetSpell.MonsterExamine.obj) ?: return
        if (!caster.cast(player, magic)) {
            return
        }
        faceEntitySquare(target)
        anim(TargetSpell.MonsterExamine.anim)
        spotanim(MONSTER_EXAMINE_CAST_SPOT)
        target.spotanim(MONSTER_EXAMINE_TARGET_SPOT, height = 92)
        ifOpenSide(MONSTER_EXAMINE_INTERFACE)
        runClientScript(MONSTER_POPULATE_SCRIPT, monsterReport(target))
    }

    private fun monsterReport(npc: Npc): String {
        val type = npc.visType
        fun bonus(param: ParamInt): Int = type.paramOrNull(param) ?: 0
        val stats =
            listOf(
                    "Combat level: ${type.combatLevel}",
                    // current / base, so stat drains (a Dragon warhammer, a Bandos godsword) show
                    "Hitpoints: ${npc.hitpoints}/${npc.baseHitpointsLvl}",
                    "Attack: ${npc.attackLvl}/${npc.baseAttackLvl}",
                    "Strength: ${npc.strengthLvl}/${npc.baseStrengthLvl}",
                    "Defence: ${npc.defenceLvl}/${npc.baseDefenceLvl}",
                    "Magic: ${npc.magicLvl}/${npc.baseMagicLvl}",
                    "Ranged: ${npc.rangedLvl}/${npc.baseRangedLvl}",
                )
                .joinToString("<br>")
        val aggressive =
            listOf(
                    "Stab: ${bonus(params.attack_stab)}",
                    "Slash: ${bonus(params.attack_slash)}",
                    "Crush: ${bonus(params.attack_crush)}",
                    "Magic: ${bonus(params.attack_magic)}",
                    "Ranged: ${bonus(params.attack_ranged)}",
                    "Strength: ${bonus(params.melee_strength)}",
                    "Ranged strength: ${bonus(params.ranged_strength)}",
                    "Magic damage: ${bonus(params.magic_damage)}%",
                )
                .joinToString("<br>")
        val defensive =
            listOf(
                    "Stab: ${bonus(params.defence_stab)}",
                    "Slash: ${bonus(params.defence_slash)}",
                    "Crush: ${bonus(params.defence_crush)}",
                    "Magic: ${bonus(params.defence_magic)}",
                    "Ranged: ${bonus(params.defence_ranged)}",
                )
                .joinToString("<br>")
        val other = "Attack range: ${type.attackRange}<br>Size: ${type.size}"
        return listOf(stats, aggressive, defensive, other, type.name).joinToString("|")
    }

    internal enum class TargetSpell(val obj: String, val anim: String) {
        MonsterExamine("obj.66_monster_examine", "seq.dream_player_monsterexam_statspy"),
        CureOther("obj.68_cure_other", "seq.quest_lunar_pushing_magic_animation"),
        StatSpy("obj.75_stat_spy", "seq.dream_player_monsterexam_statspy"),
        EnergyTransfer("obj.91_energy_transfer", "seq.quest_lunar_pushing_magic_animation"),
        HealOther("obj.92_heal_other", "seq.quest_lunar_pushing_magic_animation"),
        VengeanceOther("obj.93_vengeance_other", "seq.quest_lunar_pushing_magic_animation"),
    }

    private companion object {
        const val FULL_SPECIAL = 1000
        const val ENERGY_TRANSFER_HP = 10
        const val HEAL_OTHER_MIN_PERCENT = 11

        const val CURE_OTHER_SPOT = "spotanim.quest_lunar_spellbook_energy_cure_spot_anim"
        const val ENERGY_TRANSFER_SPOT = "spotanim.quest_lunar_spellbook_energy_exchange_spot_anim"
        const val HEAL_OTHER_SPOT = "spotanim.quest_lunar_spellbook_energy_heal_spot_anim"
        const val VENGEANCE_OTHER_SPOT = "spotanim.quest_lunar_spellbook_vengeance_spot_anim"
        const val STAT_SPY_CAST_SPOT = "spotanim.dream_spell_monsterexam_statspy_spotanim"
        const val STAT_SPY_TARGET_SPOT = "spotanim.dream_spell_playerexam_statspy_spotanim"
        const val MONSTER_EXAMINE_CAST_SPOT = "spotanim.dream_spell_monsterexam_statspy_spotanim"
        const val MONSTER_EXAMINE_TARGET_SPOT = "spotanim.dream_spell_playerexam_statspy_spotanim"

        const val CURE_SOUND = "synth.tbcu_diseasecure"
        const val HEAL_SOUND = "synth.heal"
        const val ENERGY_TRANSFER_SOUND = "synth.myq4_energy_1"
        const val VENGEANCE_SOUND = "synth.lunar_vengence"

        const val STAT_SPY_INTERFACE = "interface.dream_player_stats"
        const val MONSTER_EXAMINE_INTERFACE = "interface.dream_monster_stat"
        const val MONSTER_POPULATE_SCRIPT = 1179

        val STAT_SPY_SKILLS =
            listOf(
                "attack" to "stat.attack",
                "strength" to "stat.strength",
                "defence" to "stat.defence",
                "ranged" to "stat.ranged",
                "prayer" to "stat.prayer",
                "magic" to "stat.magic",
                "runecraft" to "stat.runecrafting",
                "construction" to "stat.construction",
                "hitpoints" to "stat.hitpoints",
                "agility" to "stat.agility",
                "herblore" to "stat.herblore",
                "thieving" to "stat.thieving",
                "crafting" to "stat.crafting",
                "fletching" to "stat.fletching",
                "slayer" to "stat.slayer",
                "hunter" to "stat.hunter",
                "mining" to "stat.mining",
                "smithing" to "stat.smithing",
                "fishing" to "stat.fishing",
                "cooking" to "stat.cooking",
                "firemaking" to "stat.firemaking",
                "woodcutting" to "stat.woodcutting",
                "farming" to "stat.farming",
                "sailing" to "stat.sailing",
            )
    }
}
