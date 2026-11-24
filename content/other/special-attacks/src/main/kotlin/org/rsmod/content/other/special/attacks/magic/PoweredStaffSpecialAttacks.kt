package org.rsmod.content.other.special.attacks.magic

import dev.openrune.util.Wearpos
import jakarta.inject.Inject
import kotlin.math.max
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.manager.CombatChargeManager
import org.rsmod.api.npc.stat.statDrain
import org.rsmod.api.obj.charges.ObjChargeManager
import org.rsmod.api.obj.charges.ObjChargeManager.Companion.isFailure
import org.rsmod.api.player.cheat.adminMaxHit
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.magicLvl
import org.rsmod.api.player.stat.statDrain
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.specials.SpecialAttackMap
import org.rsmod.api.specials.SpecialAttackRepository
import org.rsmod.api.specials.combat.MagicSpecialAttack
import org.rsmod.content.other.special.weapons.magic.ZoryasFlow
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player

class PoweredStaffSpecialAttacks
@Inject
constructor(
    private val charges: CombatChargeManager,
    private val objCharges: ObjChargeManager,
) : SpecialAttackMap {
    override fun SpecialAttackRepository.register(manager: SpecialAttackManager) {
        registerMagic("obj.eye_of_ayak", SoulRend(manager, charges))
        registerMagic("obj.wild_cave_accursed_charged", Condemn(manager, objCharges))
        registerMagic("obj.wild_cave_accursed_charged_recol", Condemn(manager, objCharges))
        registerMagic("obj.verzik_special_weapon", Pulsate(manager))
        registerMagic("obj.zoryas_tome", ZoryasFlowSpecial(manager, charges))
    }

    private abstract class MagicSpecial : MagicSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Staff,
        ): Boolean = special(target, attack)

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Staff,
        ): Boolean = special(target, attack)

        abstract fun ProtectedAccess.special(
            target: PathingEntity,
            attack: CombatAttack.Staff,
        ): Boolean
    }

    private class SoulRend(
        private val manager: SpecialAttackManager,
        private val charges: CombatChargeManager,
    ) : MagicSpecial() {
        override fun ProtectedAccess.special(
            target: PathingEntity,
            attack: CombatAttack.Staff,
        ): Boolean {
            if (charges.attemptDetractWeapon(player, AYAK_CHARGES).isFailure()) {
                manager.stopCombat(this)
                return false
            }

            manager.setNextAttackDelay(this, SOUL_REND_DELAY)
            anim("seq.human_eye_of_ayak_special")
            spotanim("spotanim.vfx_ayak_player_special_spotanim")
            target.spotanim("spotanim.vfx_ayak_impact_special_spotanim", height = IMPACT_HEIGHT)

            val accurate = manager.rollStaffAccuracy(this, target, attack.style, multiplier = 2.0)
            val damage =
                if (accurate) {
                    val base = max(0, player.magicLvl / 3 - 6)
                    manager.rollStaffMaxHit(this, target, base, multiplier = 1.3)
                } else {
                    0
                }
            if (damage > 0 && target is Npc) {
                val drained = target.vars[MAGIC_DEFENCE_DRAIN] + damage
                target.vars[MAGIC_DEFENCE_DRAIN] = drained
            }
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMagicHit(this, target, damage, clientDelay = SOUL_REND_FLIGHT)
            manager.continueCombat(this, target)
            return true
        }
    }

    private class Condemn(
        private val manager: SpecialAttackManager,
        private val objCharges: ObjChargeManager,
    ) : MagicSpecial() {
        override fun ProtectedAccess.special(
            target: PathingEntity,
            attack: CombatAttack.Staff,
        ): Boolean {
            val ether = objCharges.getCharges(player.worn[Wearpos.RightHand.slot], ETHER_VAROBJ)
            if (ether <= ACTIVATION_ETHER) {
                mes("There is not enough revenant ether left powering your sceptre.")
                manager.stopCombat(this)
                return false
            }
            objCharges.setValue(player.worn, Wearpos.RightHand.slot, ETHER_VAROBJ, ether - 1)

            anim("seq.human_special_accursed")
            val proj =
                manager.spawnProjectile(
                    this,
                    target,
                    "spotanim.spells_thammaron01_travel01",
                    "projanim.powered_staff",
                )
            val (serverDelay, clientDelay) = proj.durations

            val accurate = manager.rollStaffAccuracy(this, target, attack.style, multiplier = 1.5)
            val damage =
                if (accurate) {
                    val base = max(0, player.magicLvl / 3 - 6)
                    manager.rollStaffMaxHit(this, target, base, multiplier = 1.5)
                } else {
                    0
                }
            if (accurate) {
                drain(target)
            }
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMagicHit(this, target, damage, clientDelay, serverDelay)
            manager.continueCombat(this, target)
            return true
        }

        private fun drain(target: PathingEntity) {
            when (target) {
                is Npc -> {
                    target.statDrain("stat.defence", constant = 0, percent = DRAIN_PERCENT)
                    target.statDrain("stat.magic", constant = 0, percent = DRAIN_PERCENT)
                }
                is Player -> {
                    target.statDrain("stat.defence", constant = 0, percent = DRAIN_PERCENT)
                    target.statDrain("stat.magic", constant = 0, percent = DRAIN_PERCENT)
                }
            }
        }
    }

    private class Pulsate(private val manager: SpecialAttackManager) : MagicSpecial() {
        override fun ProtectedAccess.special(
            target: PathingEntity,
            attack: CombatAttack.Staff,
        ): Boolean {
            if (target !is Npc || !target.visType.name.startsWith("Verzik")) {
                mes("The Dawnbringer's special attack can only be used against Verzik Vitur.")
                manager.stopCombat(this)
                return false
            }

            anim("seq.human_castwave_staff")
            spotanim("spotanim.dawnbringer_casting_spec", height = CAST_HEIGHT)
            val proj =
                manager.spawnProjectile(
                    this,
                    target,
                    "spotanim.dawnbringer_projectile_spec",
                    "projanim.powered_staff",
                )
            val (serverDelay, clientDelay) = proj.durations
            target.spotanim("spotanim.dawnbringer_impact_spec", delay = clientDelay)

            val damage = if (player.adminMaxHit) PULSATE_MAX else random.of(PULSATE_MIN, PULSATE_MAX)
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMagicHit(this, target, damage, clientDelay, serverDelay)
            manager.continueCombat(this, target)
            return true
        }
    }

    /**
     * Zorya's flow (50%): the tome's attack at +150% accuracy and +60% damage, 3 ticks; a stored buffed attack stacks on
     * top of it (+300% / +120%) and is used up. If it hits, three more buffed attacks are stored.
     */
    private class ZoryasFlowSpecial(
        private val manager: SpecialAttackManager,
        private val charges: CombatChargeManager,
    ) : MagicSpecial() {
        override fun ProtectedAccess.special(
            target: PathingEntity,
            attack: CombatAttack.Staff,
        ): Boolean {
            if (charges.attemptDetractWeapon(player, TOME_CHARGES).isFailure()) {
                manager.stopCombat(this)
                return false
            }
            manager.setNextAttackDelay(this, TOME_DELAY)
            val boosts = 1 + if (ZoryasFlow.take(player)) 1 else 0
            anim("seq.tfa_human_zorya_tome_special_attack")
            spotanim("spotanim.vfx_zorya_tome_spec_spotanim")
            val proj = manager.spawnProjectile(this, target, "spotanim.vfx_zorya_tome_special_proj", "projanim.powered_staff")
            val (serverDelay, clientDelay) = proj.durations
            val accurate =
                manager.rollStaffAccuracy(this, target, attack.style, multiplier = 1.0 + ZoryasFlow.ACCURACY_PER_BOOST * boosts)
            val damage =
                if (accurate) {
                    val base = max(0, player.magicLvl / 3 - TOME_BASE_OFFSET)
                    manager.rollStaffMaxHit(this, target, base, multiplier = 1.0 + ZoryasFlow.DAMAGE_PER_BOOST * boosts)
                } else {
                    0
                }
            if (accurate) {
                target.spotanim("spotanim.vfx_zorya_tome_special_impact", delay = clientDelay)
                ZoryasFlow.add(player)
            }
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMagicHit(this, target, damage, clientDelay, serverDelay)
            manager.continueCombat(this, target)
            return true
        }
    }

    private companion object {
        const val TOME_CHARGES = "varobj.charges_65535"
        const val TOME_DELAY = 3
        const val TOME_BASE_OFFSET = 10
        const val AYAK_CHARGES = "varobj.charges_65535"
        const val ETHER_VAROBJ = "varobj.tumeken_charges"
        const val ACTIVATION_ETHER = 1_000
        const val MAGIC_DEFENCE_DRAIN = "varn.magic_defence_drain"
        const val SOUL_REND_DELAY = 5
        const val SOUL_REND_FLIGHT = 120
        const val IMPACT_HEIGHT = 124
        const val CAST_HEIGHT = 92
        const val DRAIN_PERCENT = 15
        const val PULSATE_MIN = 75
        const val PULSATE_MAX = 150
    }
}
