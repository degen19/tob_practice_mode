package org.rsmod.content.other.special.attacks.melee

import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatEffects
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.config.constants
import org.rsmod.api.npc.stat.stat
import org.rsmod.api.npc.stat.statSub
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.specials.SpecialAttackMap
import org.rsmod.api.specials.SpecialAttackRepository
import org.rsmod.api.specials.combat.MeleeSpecialAttack
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import kotlin.math.max
import kotlin.math.min

/**
 * The four godsword specials. Each doubles accuracy, rolls against slash defence and lands one
 * hit on the next tick:
 * - Armadyl godsword, The Judgement: 37.5% more damage.
 * - Bandos godsword, Warstrike: 21% more damage; the damage dealt drains the target's Defence,
 *   then whatever is left drains Strength, Attack, Magic and Ranged in that order.
 * - Saradomin godsword, Healing Blade: 10% more damage; heals 50% of the damage dealt (at least
 *   10) and restores 25% of it as Prayer (at least 5). Nothing happens on a miss.
 * - Zamorak godsword, Ice Cleave: 10% more damage; a successful hit freezes the target for 20
 *   seconds (32 ticks).
 */
class GodswordSpecialAttacks : SpecialAttackMap {
    override fun SpecialAttackRepository.register(manager: SpecialAttackManager) {
        registerMelee("obj.ags", Godsword(manager, Kind.Armadyl))
        registerMelee("obj.bgs", Godsword(manager, Kind.Bandos))
        registerMelee("obj.sgs", Godsword(manager, Kind.Saradomin))
        registerMelee("obj.zgs", Godsword(manager, Kind.Zamorak))
    }

    private enum class Kind(val seq: String, val spot: String, val damage: Double) {
        Armadyl(
            "seq.ags_special_player",
            "spotanim.dh_sword_update_armadyl_special_spotanim",
            1.375,
        ),
        Bandos("seq.bgs_special_player", "spotanim.dh_sword_update_bandos_special_spotanim", 1.21),
        Saradomin(
            "seq.sgs_special_player",
            "spotanim.dh_sword_update_saradomin_special_spotanim",
            1.1,
        ),
        Zamorak("seq.zgs_special_player", "spotanim.dh_sword_update_zamorak_special_spotanim", 1.1),
    }

    private class Godsword(private val manager: SpecialAttackManager, private val kind: Kind) :
        MeleeSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            strike(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            strike(target, attack)
            return true
        }

        private fun ProtectedAccess.strike(target: PathingEntity, attack: CombatAttack.Melee) {
            anim(kind.seq)
            spotanim(kind.spot, slot = constants.spotanim_slot_combat, height = 0)

            val accurate =
                manager.rollMeleeAccuracy(
                    source = this,
                    target = target,
                    attackType = attack.type,
                    attackStyle = attack.style,
                    blockType = MeleeAttackType.Slash,
                    multiplier = 2.0,
                )
            val damage =
                if (accurate) {
                    manager.rollMeleeMaxHit(this, target, attack.type, attack.style, kind.damage)
                } else {
                    0
                }

            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMeleeHit(this, target, damage)

            if (accurate) {
                when (kind) {
                    Kind.Bandos -> if (target is Npc) warstrike(target, damage)
                    Kind.Saradomin -> healingBlade(damage)
                    Kind.Zamorak -> iceCleave(target)
                    Kind.Armadyl -> Unit
                }
            }
            manager.continueCombat(this, target)
        }

        private fun warstrike(target: Npc, damage: Int) {
            var remaining = damage
            for (stat in WARSTRIKE_ORDER) {
                if (remaining <= 0) {
                    break
                }
                val drained = min(remaining, target.stat(stat))
                if (drained > 0) {
                    target.statSub(stat, constant = drained, percent = 0)
                }
                remaining -= drained
            }
        }

        private fun ProtectedAccess.healingBlade(damage: Int) {
            statHeal("stat.hitpoints", constant = max(10, damage / 2), percent = 0)
            statHeal("stat.prayer", constant = max(5, damage / 4), percent = 0)
        }

        private fun iceCleave(target: PathingEntity) {
            when (target) {
                is Npc -> CombatEffects.freeze(target, ICE_CLEAVE_TICKS)
                is Player -> CombatEffects.freeze(target, ICE_CLEAVE_TICKS)
            }
        }

        private companion object {
            const val ICE_CLEAVE_TICKS = 32
            val WARSTRIKE_ORDER =
                listOf("stat.defence", "stat.strength", "stat.attack", "stat.magic", "stat.ranged")
        }
    }
}
