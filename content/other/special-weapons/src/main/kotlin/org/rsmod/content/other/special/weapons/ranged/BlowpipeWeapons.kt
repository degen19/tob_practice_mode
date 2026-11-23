package org.rsmod.content.other.special.weapons.ranged

import jakarta.inject.Inject
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.config.refs.params
import org.rsmod.api.mechanics.burn.Burn
import org.rsmod.api.mechanics.toxins.impl.NpcVenom
import org.rsmod.api.mechanics.toxins.impl.PlayerVenom
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.worn.BlowpipeDarts
import org.rsmod.api.weapons.RangedWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.type.getInvObj

/**
 * Toxic blowpipe (and the Blazing blowpipe), and the Drygore blowpipe: they shoot the darts loaded
 * in them (see [BlowpipeDarts]), whose ranged strength adds to the blowpipe's. 3 ticks per shot,
 * 2 on rapid.
 * - Toxic blowpipe: a hit that deals damage has a 1 in 4 chance to envenom the target.
 * - Drygore blowpipe: rolls accuracy twice and hits if either succeeds; each hit has a 25% chance to
 *   burn the target.
 *
 * Darts and Zulrah's scales are not used up on this server.
 */
class BlowpipeWeapons @Inject constructor(private val burn: Burn) : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        val toxic = Blowpipe(manager, burn, drygore = false)
        register("obj.toxic_blowpipe_loaded", toxic)
        register("obj.toxic_blowpipe_loaded_ornament", toxic)
        register("obj.drygore_blowpipe_loaded", Blowpipe(manager, burn, drygore = true))
    }

    private class Blowpipe(
        private val manager: WeaponAttackManager,
        private val burn: Burn,
        private val drygore: Boolean,
    ) : RangedWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Ranged,
        ): Boolean {
            shoot(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Ranged,
        ): Boolean {
            shoot(target, attack)
            return true
        }

        private fun ProtectedAccess.shoot(target: PathingEntity, attack: CombatAttack.Ranged) {
            val dart = BlowpipeDarts.dart(attack.weapon)
            if (dart == null) {
                manager.stopCombat(this)
                mes("Your blowpipe has no darts in it.")
                return
            }

            val hasAnim = getInvObj(attack.weapon).paramOrNull(params.attack_anim_stance1) != null
            if (hasAnim) {
                manager.playWeaponFx(this, attack)
            } else {
                anim(DRYGORE_ANIM)
            }

            val proj = manager.spawnProjectile(this, target, dartTravel(dart), "projanim.thrown")
            val (serverDelay, clientDelay) = proj.durations

            val accurate = accurate(target, attack) || (drygore && accurate(target, attack))
            val damage =
                if (accurate) {
                    manager.rollRangedMaxHit(this, target, attack.type, attack.style, 1.0, 0)
                } else {
                    0
                }
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueRangedHit(this, target, dart, damage, clientDelay, serverDelay)

            if (accurate && drygore && random.of(100) < DRYGORE_BURN_CHANCE) {
                burn.inflict(target)
            }
            if (damage > 0 && !drygore && random.of(VENOM_ODDS) == 0) {
                when (target) {
                    is Npc -> NpcVenom.tryVenom(target)
                    is Player -> PlayerVenom.tryVenom(target)
                }
            }
            manager.continueCombat(this, target)
        }

        private fun ProtectedAccess.accurate(
            target: PathingEntity,
            attack: CombatAttack.Ranged,
        ): Boolean =
            manager.rollRangedAccuracy(
                source = this,
                target = target,
                attackType = attack.type,
                attackStyle = attack.style,
                blockType = attack.type,
                multiplier = 1.0,
            )
    }

    companion object {
        private const val DRYGORE_ANIM = "seq.drygore_blowpipe_attack"
        private const val DRYGORE_BURN_CHANCE = 25
        private const val VENOM_ODDS = 4

        /** The travel graphic of [dart], falling back to the dragon dart's. */
        fun dartTravel(dart: ItemServerType): String {
            val travel = dart.paramOrNull(params.proj_travel)
            if (travel != null) {
                return RSCM.getReverseMapping(RSCMType.SPOTANIM, travel.id)
            }
            val amethyst = dart.id == RSCM.getRSCM("obj.amethyst_dart")
            return if (amethyst) "spotanim.amethyst_dart_travel" else "spotanim.ii_dragon_dart_projanim"
        }
    }
}
