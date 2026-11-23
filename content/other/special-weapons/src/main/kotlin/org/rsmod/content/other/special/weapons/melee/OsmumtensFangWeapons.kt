package org.rsmod.content.other.special.weapons.melee

import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.player.cheat.adminMaxHit
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.weapons.MeleeWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player

/**
 * Osmumten's fang: a successful hit deals between 15% and 85% of the max hit (the max hit minus
 * that same 15%), instead of anywhere up to the max hit. The fang's double accuracy roll on stab
 * styles is part of the accuracy formula.
 */
class OsmumtensFangWeapons : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        val fang = OsmumtensFang(manager)
        register("obj.osmumtens_fang", fang)
        register("obj.osmumtens_fang_ornament", fang)
    }

    private class OsmumtensFang(private val manager: WeaponAttackManager) : MeleeWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            stab(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            stab(target, attack)
            return true
        }

        private fun ProtectedAccess.stab(target: PathingEntity, attack: CombatAttack.Melee) {
            manager.playWeaponFx(this, attack)
            val accurate =
                manager.rollMeleeAccuracy(
                    source = this,
                    target = target,
                    attackType = attack.type,
                    attackStyle = attack.style,
                    blockType = attack.type,
                    multiplier = 1.0,
                )
            val damage =
                if (accurate) {
                    val max =
                        manager.calculateMeleeMaxHit(this, target, attack.type, attack.style, 1.0)
                    val trim = max * 15 / 100
                    if (player.adminMaxHit) max - trim else random.of(trim, max - trim)
                } else {
                    0
                }
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMeleeHit(this, target, damage)
            manager.continueCombat(this, target)
        }
    }
}
