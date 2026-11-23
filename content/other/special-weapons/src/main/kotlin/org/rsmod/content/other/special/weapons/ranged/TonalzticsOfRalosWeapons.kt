package org.rsmod.content.other.special.weapons.ranged

import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.config.constants
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.weapons.RangedWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player

/**
 * Tonalztics of Ralos: each throw hits for 0-75% of the ranged max hit, rolled against light
 * ranged defence. Charged, both glaives are thrown and hit with independent rolls; uncharged, only
 * one. 7 ticks per throw (6 on rapid).
 *
 * Charges are not used up on this server.
 */
class TonalzticsOfRalosWeapons : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        register("obj.tonalztics_of_ralos_charged", Ralos(manager, charged = true))
        register("obj.tonalztics_of_ralos_uncharged", Ralos(manager, charged = false))
    }

    private class Ralos(private val manager: WeaponAttackManager, private val charged: Boolean) :
        RangedWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Ranged,
        ): Boolean {
            throwGlaives(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Ranged,
        ): Boolean {
            throwGlaives(target, attack)
            return true
        }

        private fun ProtectedAccess.throwGlaives(
            target: PathingEntity,
            attack: CombatAttack.Ranged,
        ) {
            anim(if (charged) CHARGED_THROW else UNCHARGED_THROW)
            if (charged) {
                spotanim(CHARGED_LAUNCH, height = 0, slot = constants.spotanim_slot_combat)
            }
            val glaives = if (charged) GLAIVE_TRAVELS else GLAIVE_TRAVELS.take(1)
            var totalDamage = 0
            for (travel in glaives) {
                val proj = manager.spawnProjectile(this, target, travel, "projanim.thrown")
                val (serverDelay, clientDelay) = proj.durations
                val damage =
                    manager.rollRangedDamage(
                        source = this,
                        target = target,
                        attack = attack,
                        maxHitMultiplier = THROW_MAX_HIT,
                        blockType = RangedAttackType.Light,
                    )
                totalDamage += damage
                manager.queueRangedHit(this, target, null, damage, clientDelay, serverDelay)
            }
            manager.giveCombatXp(this, target, attack, totalDamage)
            manager.continueCombat(this, target)
        }
    }

    private companion object {
        const val THROW_MAX_HIT = 0.75
        const val CHARGED_THROW = "seq.human_glaive_ralos01_charged_throw"
        const val UNCHARGED_THROW = "seq.human_glaive_ralos01_uncharged_throw"
        const val CHARGED_LAUNCH = "spotanim.vfx_glaive_charged_regular"
        val GLAIVE_TRAVELS =
            listOf("spotanim.projanim_glaive_01_regular", "spotanim.projanim_glaive_02_regular")
    }
}
