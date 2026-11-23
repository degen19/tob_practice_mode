package org.rsmod.content.other.special.attacks.magic

import kotlin.math.min
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.magicLvl
import org.rsmod.api.player.stat.prayerLvl
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.specials.SpecialAttackMap
import org.rsmod.api.specials.SpecialAttackRepository
import org.rsmod.api.specials.combat.MagicSpecialAttack
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player

/**
 * The orb specials of the Nightmare staves. They cast without runes, also while autocasting, and
 * their base max hit scales with the Magic level; magic damage gear (the staff's own +15%, Occult
 * necklace, etc.) then applies as for any magic attack:
 * - Volatile Nightmare staff, Immolate: 50% more accuracy; base max hit
 *   `min(floor(58 * Magic / 99) + 1, 58)`.
 * - Eldritch Nightmare staff, Invocate: base max hit `min(floor(44 * Magic / 99) + 1, 44)`;
 *   restores Prayer by half the damage dealt, which can raise it up to 120.
 */
class NightmareStaffSpecialAttacks : SpecialAttackMap {
    override fun SpecialAttackRepository.register(manager: SpecialAttackManager) {
        registerMagic("obj.nightmare_staff_volatile", Orb(manager, volatile = true))
        registerMagic("obj.nightmare_staff_eldritch", Orb(manager, volatile = false))
    }

    private class Orb(private val manager: SpecialAttackManager, private val volatile: Boolean) :
        MagicSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Staff,
        ): Boolean {
            cast(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Staff,
        ): Boolean {
            cast(target, attack)
            return true
        }

        private fun ProtectedAccess.cast(target: PathingEntity, attack: CombatAttack.Staff) {
            anim("seq.nightmare_staff_special")
            val castSpot =
                if (volatile) {
                    "spotanim.nightmare_staff_volatile_cast_spotanim"
                } else {
                    "spotanim.nightmare_staff_eldritch_cast_spotanim"
                }
            spotanim(castSpot, height = 0)

            val clientDelay: Int
            val hitDelay: Int
            if (volatile) {
                val distance = player.distanceTo(target.coords, target.size, target.size)
                hitDelay = 1 + (1 + distance) / 3
                clientDelay = hitDelay * CLIENT_CYCLES_PER_TICK
            } else {
                val proj =
                    manager.spawnProjectile(
                        this,
                        target,
                        "spotanim.eldritch_smoke_travel",
                        "projanim.magic_spell",
                    )
                hitDelay = proj.serverCycles
                clientDelay = proj.clientCycles
            }

            val hitSpot =
                if (volatile) {
                    "spotanim.nightmare_staff_volatile_hit_spotanim"
                } else {
                    "spotanim.nightmare_staff_eldritch_hit_spotanim"
                }

            val accuracy = if (volatile) 1.5 else 1.0
            val accurate = manager.rollStaffAccuracy(this, target, attack.style, accuracy)
            val damage =
                if (accurate) {
                    val cap = if (volatile) VOLATILE_CAP else ELDRITCH_CAP
                    val base = min(cap * player.magicLvl / 99 + 1, cap)
                    manager.rollStaffMaxHit(this, target, base, 1.0)
                } else {
                    0
                }
            if (accurate) {
                target.spotanim(hitSpot, height = 0, delay = clientDelay)
            } else {
                target.spotanim("spotanim.failedspell_impact", height = 124, delay = clientDelay)
            }
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMagicHit(this, target, damage, clientDelay, hitDelay)

            if (!volatile && damage > 0) {
                val restore = damage / 2
                val room = (PRAYER_CAP - player.prayerLvl).coerceAtLeast(0)
                val added = min(restore, room)
                if (added > 0) {
                    statAdd("stat.prayer", constant = added, percent = 0)
                }
            }
            manager.continueCombat(this, target)
        }

        private companion object {
            const val VOLATILE_CAP = 58
            const val ELDRITCH_CAP = 44
            const val PRAYER_CAP = 120
            const val CLIENT_CYCLES_PER_TICK = 30
        }
    }
}
