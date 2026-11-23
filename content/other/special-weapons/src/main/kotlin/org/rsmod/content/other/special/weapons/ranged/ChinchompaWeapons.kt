package org.rsmod.content.other.special.weapons.ranged

import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import dev.openrune.util.Wearpos
import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.ranged.RangedAmmunition
import org.rsmod.api.combat.manager.AreaTargets
import org.rsmod.api.config.refs.params
import org.rsmod.api.player.PracticeSettings
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.righthand
import org.rsmod.api.weapons.RangedWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.type.getInvObj
import org.rsmod.map.util.Bounds

/**
 * Chinchompas (grey, red and black): the thrown chinchompa explodes on the target and, in
 * multi-combat, on every npc in the 3x3 area around it (up to 11 targets in all, 12 for black
 * chinchompas). One accuracy roll is made against the main target, which uses its heavy ranged
 * defence: if it hits, every target is hit and rolls its own damage; if it misses, all miss. The fuse
 * (attack style) changes accuracy by distance, which is part of the ranged formulas.
 *
 * A thrown chinchompa is used up and never dropped.
 */
class ChinchompaWeapons
@Inject
constructor(private val areaTargets: AreaTargets, private val eventBus: EventBus) : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        register("obj.chinchompa_captured", Chinchompa(manager, areaTargets, eventBus, 11))
        register("obj.chinchompa_big_captured", Chinchompa(manager, areaTargets, eventBus, 11))
        register("obj.chinchompa_black", Chinchompa(manager, areaTargets, eventBus, 12))
    }

    private class Chinchompa(
        private val manager: WeaponAttackManager,
        private val areaTargets: AreaTargets,
        private val eventBus: EventBus,
        private val maxTargets: Int,
    ) : RangedWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Ranged,
        ): Boolean {
            val (accurate, hitDelay, clientDelay) = throwAt(target, attack)
            hit(target, attack, accurate, hitDelay, clientDelay)
            if (mapMultiway()) {
                val area = Bounds(target.coords.translate(-1, -1), target.size + 2)
                for (npc in areaTargets.npcsIn(player, area, listOf(target), maxTargets - 1)) {
                    hit(npc, attack, accurate, hitDelay, clientDelay)
                }
            }
            finish(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Ranged,
        ): Boolean {
            val (accurate, hitDelay, clientDelay) = throwAt(target, attack)
            hit(target, attack, accurate, hitDelay, clientDelay)
            finish(target, attack)
            return true
        }

        private fun ProtectedAccess.throwAt(
            target: PathingEntity,
            attack: CombatAttack.Ranged,
        ): Triple<Boolean, Int, Int> {
            val type = getInvObj(attack.weapon)
            manager.playWeaponFx(this, attack)
            val travel = type.paramOrNull(params.proj_travel)
            val travelName =
                travel?.let { RSCM.getReverseMapping(RSCMType.SPOTANIM, it.id) }
                    ?: "spotanim.chinchompa_grenade"
            val proj = manager.spawnProjectile(this, target, travelName, "projanim.chinchompa")
            val (serverDelay, clientDelay) = proj.durations
            val accurate =
                manager.rollRangedAccuracy(
                    source = this,
                    target = target,
                    attackType = attack.type,
                    attackStyle = attack.style,
                    blockType = attack.type,
                    multiplier = 1.0,
                )
            return Triple(accurate, serverDelay, clientDelay)
        }

        private fun ProtectedAccess.hit(
            target: PathingEntity,
            attack: CombatAttack.Ranged,
            accurate: Boolean,
            hitDelay: Int,
            clientDelay: Int,
        ) {
            val damage =
                if (accurate) {
                    manager.rollRangedMaxHit(this, target, attack.type, attack.style, 1.0, 0)
                } else {
                    0
                }
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueRangedHit(this, target, null, damage, clientDelay, hitDelay)
        }

        private fun ProtectedAccess.finish(target: PathingEntity, attack: CombatAttack.Ranged) {
            useChinchompa(getInvObj(attack.weapon))
            if (player.righthand == null) {
                mes("That was your last one!")
                manager.stopCombat(this)
                return
            }
            manager.continueCombat(this, target)
        }

        private fun ProtectedAccess.useChinchompa(type: ItemServerType) {
            if (PracticeSettings.infiniteSupplies) {
                return
            }
            RangedAmmunition.detractAmmo(player, Wearpos.RightHand, type, 1, eventBus)
        }
    }
}
