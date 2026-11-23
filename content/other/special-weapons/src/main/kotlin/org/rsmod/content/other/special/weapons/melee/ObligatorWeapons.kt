package org.rsmod.content.other.special.weapons.melee

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.manager.AreaTargets
import org.rsmod.api.config.refs.params
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.weapons.MeleeWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.map.CardinalDirection
import org.rsmod.game.map.Direction
import org.rsmod.map.CoordGrid
import org.rsmod.map.util.Bounds

/**
 * Obligator (charged and uncharged). Its Smash (the third style, 3 ticks) is an ordinary crush hit. Its two Thrusts
 * (5 ticks) pierce: a straight line of 3 tiles out from the wielder towards the target, hitting the target and, in
 * multi-combat, up to two more npcs that line passes through - each npc once.
 *
 * Charged, every npc the thrust hits gets the best of 2 damage rolls, plus one more roll for each of its stab and
 * slash defence that's higher than its crush defence; and a bonus by how far the line runs into it: 20% through 2 of
 * its tiles, 40% through 3 (the wiki marks this as needing confirmation; e.g. a 2x2 crab in front of Maiden takes
 * +20% and Maiden, pierced one tile deep, takes the normal hit). Uncharged, the thrust still pierces, one roll each.
 */
class ObligatorWeapons @Inject constructor(private val worldRepo: WorldRepository, private val areaTargets: AreaTargets) :
    WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        register("obj.obligator", Obligator(manager, areaTargets, charged = true))
        register("obj.obligator_uncharged", Obligator(manager, areaTargets, charged = false))
    }

    private class Obligator(
        private val manager: WeaponAttackManager,
        private val areaTargets: AreaTargets,
        private val charged: Boolean,
    ) : MeleeWeapon {
        override suspend fun ProtectedAccess.attack(target: Npc, attack: CombatAttack.Melee): Boolean {
            // the Smash: the default single hit
            if (player.vars["varp.com_mode"] == SMASH_STANCE) return false
            manager.playWeaponFx(this, attack)
            spotanim(if (charged) "spotanim.vfx_obligator_charged_thrust_male" else "spotanim.vfx_obligator_uncharged_thrust_male")
            val line = line(target)
            val hits = LinkedHashMap<Npc, Int>()
            hits[target] = line.count { target.occupies(it) }.coerceAtLeast(1)
            if (mapMultiway()) {
                for (tile in line) {
                    if (hits.size >= MAX_TARGETS) break
                    for (npc in areaTargets.npcsIn(player, Bounds(tile), exclude = hits.keys.toList())) {
                        if (hits.size >= MAX_TARGETS) break
                        hits[npc] = line.count { npc.occupies(it) }
                    }
                }
            }
            for ((npc, depth) in hits) {
                val bonus = if (charged) 1.0 + 0.2 * (depth.coerceIn(1, 3) - 1) else 1.0
                val rolls = if (charged) 2 + extraRolls(npc) else 1
                var best = 0
                repeat(rolls) {
                    val roll = manager.rollMeleeDamage(this, npc, attack, accuracyMultiplier = 1.0, maxHitMultiplier = bonus)
                    if (roll > best) best = roll
                }
                manager.giveCombatXp(this, npc, attack, best)
                manager.queueMeleeHit(this, npc, best)
            }
            manager.continueCombat(this, target)
            return true
        }

        override suspend fun ProtectedAccess.attack(target: Player, attack: CombatAttack.Melee): Boolean = false

        /** The 3 tiles in a straight line out from the wielder towards [target]. */
        private fun ProtectedAccess.line(target: Npc): List<CoordGrid> {
            val direction = Direction.cardinalBetween(player.bounds(), target.bounds())
            val (dx, dz) =
                when (direction) {
                    CardinalDirection.North -> 0 to 1
                    CardinalDirection.East -> 1 to 0
                    CardinalDirection.South -> 0 to -1
                    CardinalDirection.West -> -1 to 0
                }
            return (1..REACH).map { player.coords.translate(dx * it, dz * it) }
        }

        /** One more roll for each of the npc's stab and slash defence that's higher than its crush defence. */
        private fun extraRolls(npc: Npc): Int {
            val type = npc.visType
            val crush = type.paramOrNull(params.defence_crush) ?: 0
            var extra = 0
            if ((type.paramOrNull(params.defence_stab) ?: 0) > crush) extra++
            if ((type.paramOrNull(params.defence_slash) ?: 0) > crush) extra++
            return extra
        }
    }

    private companion object {
        const val SMASH_STANCE = 2
        const val REACH = 3
        const val MAX_TARGETS = 3
    }
}

private fun Npc.occupies(tile: CoordGrid): Boolean =
    tile.level == coords.level && tile.x in coords.x until coords.x + size && tile.z in coords.z until coords.z + size
