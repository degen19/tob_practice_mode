package org.rsmod.content.other.special.attacks.melee

import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.config.constants
import org.rsmod.api.npc.stat.stat
import org.rsmod.api.npc.stat.statSub
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.stat as playerStat
import org.rsmod.api.player.stat.statSub as playerStatSub
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.specials.SpecialAttackMap
import org.rsmod.api.specials.SpecialAttackRepository
import org.rsmod.api.specials.combat.MeleeSpecialAttack
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player

/**
 * Crush specials that lower the target's current Defence when they deal damage (the reductions
 * stack):
 * - Dragon warhammer, Smash: 50% more damage, -30% of the current Defence.
 * - Elder maul, Pulverize: 25% more accuracy, -35% of the current Defence.
 */
class DefenceDrainSpecialAttacks : SpecialAttackMap {
    override fun SpecialAttackRepository.register(manager: SpecialAttackManager) {
        val smash =
            DefenceDrain(
                manager = manager,
                seq = "seq.dragon_warhammer_sa_player",
                spot = "spotanim.dragon_warhammer_sa_spotanim",
                targetSpot = null,
                accuracy = 1.0,
                damage = 1.5,
                drainPercent = 30,
            )
        registerMelee("obj.dragon_warhammer", smash)
        registerMelee("obj.bh_dragon_warhammer_corrupted", smash)

        registerMelee(
            "obj.elder_maul",
            DefenceDrain(
                manager = manager,
                seq = "seq.human_elder_maul_spec",
                spot = "spotanim.spotanim_elder_maul_special",
                targetSpot = "spotanim.spotanim_elder_maul_special_impact",
                accuracy = 1.25,
                damage = 1.0,
                drainPercent = 35,
            ),
        )
    }

    private class DefenceDrain(
        private val manager: SpecialAttackManager,
        private val seq: String,
        private val spot: String,
        private val targetSpot: String?,
        private val accuracy: Double,
        private val damage: Double,
        private val drainPercent: Int,
    ) : MeleeSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            val dealt = strike(target, attack)
            if (dealt > 0) {
                val drain = target.stat("stat.defence") * drainPercent / 100
                if (drain > 0) {
                    target.statSub("stat.defence", constant = drain, percent = 0)
                }
            }
            manager.continueCombat(this, target)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            val dealt = strike(target, attack)
            if (dealt > 0) {
                target.statSubPercentOfCurrent("stat.defence", drainPercent)
            }
            manager.continueCombat(this, target)
            return true
        }

        private fun ProtectedAccess.strike(target: PathingEntity, attack: CombatAttack.Melee): Int {
            anim(seq)
            spotanim(spot, slot = constants.spotanim_slot_combat, height = 0)
            if (targetSpot != null) {
                target.spotanim(targetSpot, height = 0, delay = 30)
            }
            val dealt =
                manager.rollMeleeDamage(
                    source = this,
                    target = target,
                    attack = attack,
                    accuracyMultiplier = accuracy,
                    maxHitMultiplier = damage,
                    blockType = MeleeAttackType.Crush,
                )
            manager.giveCombatXp(this, target, attack, dealt)
            manager.queueMeleeHit(this, target, dealt)
            return dealt
        }

        private fun Player.statSubPercentOfCurrent(stat: String, percent: Int) {
            val drain = playerStat(stat) * percent / 100
            if (drain > 0) {
                playerStatSub(stat, constant = drain, percent = 0)
            }
        }
    }
}
