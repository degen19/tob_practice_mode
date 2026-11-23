package org.rsmod.content.other.special.weapons.passives

import jakarta.inject.Inject
import org.rsmod.api.npc.hit.NpcHitImpactHook
import org.rsmod.api.player.front
import org.rsmod.api.player.stat.statHeal
import org.rsmod.api.random.GameRandom
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.isType

/**
 * Amulet of blood fury: every melee hit that deals damage has a 20% chance to heal the wearer for
 * 30% of the damage dealt (rounded down). Multi-hit weapons such as the Scythe of vitur and Dragon
 * claws roll it for each hit. Healing never goes above the wearer's base hitpoints.
 *
 * The amulet's blood shard charges are not used up on this server.
 */
class BloodFuryPassive @Inject constructor(private val random: GameRandom) : NpcHitImpactHook {
    override fun impact(npc: Npc, source: Player, hit: Hit) {
        if (hit.type != HitType.Melee || hit.damage <= 0) {
            return
        }
        if (!source.front.isType("obj.blood_amulet")) {
            return
        }
        if (random.of(100) >= HEAL_CHANCE) {
            return
        }
        val heal = hit.damage * HEAL_PERCENT / 100
        if (heal > 0) {
            source.statHeal("stat.hitpoints", constant = heal, percent = 0)
        }
    }

    private companion object {
        const val HEAL_CHANCE = 20
        const val HEAL_PERCENT = 30
    }
}
