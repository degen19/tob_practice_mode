package org.rsmod.content.other.special.weapons.ranged

import org.rsmod.api.combat.commons.CombatAttack
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
 * Bows that make their own arrows and use no ammunition:
 * - Bow of faerdhinen (every colour of the (c) version): crystal arrows; its accuracy and damage
 *   bonus with crystal armour is part of the ranged formulas.
 * - Webweaver bow: one revenant ether per shot; its Wilderness bonus is part of the formulas.
 *
 * Crystal shard and ether charges are not used up on this server.
 */
class ChargeBowWeapons : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        register(
            "obj.bow_of_faerdhinen",
            ChargeBow(manager, FAERDHINEN_TRAVEL, FAERDHINEN_LAUNCH),
        )
        register(
            "obj.bow_of_faerdhinen_infinite",
            ChargeBow(
                manager,
                "${FAERDHINEN_TRAVEL}_infinite",
                "${FAERDHINEN_LAUNCH}_infinite",
            ),
        )
        for (colour in FAERDHINEN_COLOURS) {
            register(
                "obj.bow_of_faerdhinen_infinite_$colour",
                ChargeBow(manager, "${FAERDHINEN_TRAVEL}_$colour", "${FAERDHINEN_LAUNCH}_$colour"),
            )
        }
        // The Meilyr bow has no arrow graphics of its own; it uses the (c) bow's.
        register(
            "obj.bow_of_faerdhinen_infinite_meilyr",
            ChargeBow(
                manager,
                "${FAERDHINEN_TRAVEL}_infinite",
                "${FAERDHINEN_LAUNCH}_infinite",
            ),
        )
        register(
            "obj.wild_cave_webweaver_charged",
            ChargeBow(
                manager,
                "spotanim.wild_cave_bow_arrow_travel02",
                "spotanim.wild_cave_bow_arrow_launch02",
            ),
        )
    }

    private class ChargeBow(
        private val manager: WeaponAttackManager,
        private val travel: String,
        private val launch: String,
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
            manager.playWeaponFx(this, attack)
            spotanim(launch, height = 96, slot = constants.spotanim_slot_combat)
            val proj = manager.spawnProjectile(this, target, travel, "projanim.arrow")
            val (serverDelay, clientDelay) = proj.durations
            val damage = manager.rollRangedDamage(this, target, attack)
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueRangedHit(this, target, null, damage, clientDelay, serverDelay)
            manager.continueCombat(this, target)
        }
    }

    private companion object {
        const val FAERDHINEN_TRAVEL = "spotanim.sp_attack_arrow_travel_faerdhinen"
        const val FAERDHINEN_LAUNCH = "spotanim.sp_attack_arrow_launch_faerdhinen"
        val FAERDHINEN_COLOURS =
            listOf("ithell", "iorwerth", "trahaearn", "cadarn", "crwys", "amlodd")
    }
}
