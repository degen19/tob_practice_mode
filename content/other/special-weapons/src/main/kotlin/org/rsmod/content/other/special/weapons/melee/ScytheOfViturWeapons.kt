package org.rsmod.content.other.special.weapons.melee

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.manager.AreaTargets
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.weapons.MeleeWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.map.CardinalDirection
import org.rsmod.game.map.Direction
import org.rsmod.game.map.translate
import org.rsmod.map.CoordGrid
import org.rsmod.map.util.Bounds

/**
 * Scythe of vitur (and its Holy and Sanguine versions, which are cosmetic), on the community's current best theory
 * of the arc: a 1x3 line across the swing - `centre` (the tile in front of the wielder, towards the target),
 * `left` and `right` (its neighbours: `left` is the facing turned clockwise, so south of a target it's east).
 *
 * The target takes the first hit (100% max). Then up to two more (50%, then 25%) go, in order, to an npc on `centre`
 * other than the target, then one on `left`, then one on `right` - the target counts again on `left` / `right`,
 * which is how a 2x2 takes two hits and a 3x3 three. Each roll is independent. Other npcs are only hit in
 * multi-combat. A crab leaking into Maiden: from its north tiles the crab takes 100% + 50% and she takes 25%; from
 * its south tiles 100% + 25% and her 50%. Against players only the target is hit, once.
 */
class ScytheOfViturWeapons
@Inject
constructor(private val worldRepo: WorldRepository, private val areaTargets: AreaTargets) :
    WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        val scythe = ScytheOfVitur(manager, worldRepo, areaTargets)
        register("obj.scythe_of_vitur", scythe)
        register("obj.scythe_of_vitur_or", scythe)
        register("obj.scythe_of_vitur_bl", scythe)
    }

    private class ScytheOfVitur(
        private val manager: WeaponAttackManager,
        private val worldRepo: WorldRepository,
        private val areaTargets: AreaTargets,
    ) : MeleeWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            manager.playWeaponFx(this, attack)
            val arc = playSweep(target)

            val hits = ArrayList<Pair<Npc, Double>>()
            hits += target to HIT_MULTIPLIERS[0]
            val multi = mapMultiway()
            for ((i, tile) in arc.withIndex()) {
                if (hits.size >= HIT_MULTIPLIERS.size) break
                val npc =
                    when {
                        i != 0 && target.occupies(tile) -> target
                        multi -> areaTargets.npcsIn(player, Bounds(tile), exclude = listOf(target)).firstOrNull()
                        else -> null
                    } ?: continue
                hits += npc to HIT_MULTIPLIERS[hits.size]
            }
            // a big target always takes its own hits (2 for a 2x2, 3 for 3x3 and up) even when the arc runs off its
            // edge - e.g. Verzik on her throne from her corner
            val want = target.size.coerceIn(1, HIT_MULTIPLIERS.size)
            while (hits.size < HIT_MULTIPLIERS.size && hits.count { it.first === target } < want) {
                hits += target to HIT_MULTIPLIERS[hits.size]
            }
            val dealt = HashMap<Npc, Int>()
            for ((npc, multiplier) in hits) {
                val damage =
                    manager.rollMeleeDamage(
                        source = this,
                        target = npc,
                        attack = attack,
                        accuracyMultiplier = 1.0,
                        maxHitMultiplier = multiplier,
                    )
                dealt[npc] = (dealt[npc] ?: 0) + damage
                manager.queueMeleeHit(this, npc, damage)
            }
            for ((npc, damage) in dealt) manager.giveCombatXp(this, npc, attack, damage)

            manager.continueCombat(this, target)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            manager.playWeaponFx(this, attack)
            playSweep(target)
            val damage =
                manager.rollMeleeDamage(
                    source = this,
                    target = target,
                    attack = attack,
                    accuracyMultiplier = 1.0,
                    maxHitMultiplier = 1.0,
                )
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMeleeHit(this, target, damage)
            manager.continueCombat(this, target)
            return true
        }

        /** Plays the swing graphic and returns the arc's tiles: centre, left, right. */
        private fun ProtectedAccess.playSweep(target: PathingEntity): List<CoordGrid> {
            val direction = Direction.cardinalBetween(player.bounds(), target.bounds())
            val selfCentre = player.coords.translate(player.size / 2, player.size / 2)
            val anchor = selfCentre.translate(direction.toDirection())
            spotanimMap(
                repo = worldRepo,
                internal = "spotanim.dragon_halberd_special_${direction.suffix()}_red",
                coord = anchor,
                height = 96,
                delay = 20,
            )
            // left: the facing turned clockwise
            val (lx, lz) =
                when (direction) {
                    CardinalDirection.North -> 1 to 0
                    CardinalDirection.East -> 0 to -1
                    CardinalDirection.South -> -1 to 0
                    CardinalDirection.West -> 0 to 1
                }
            return listOf(anchor, anchor.translate(lx, lz), anchor.translate(-lx, -lz))
        }

        private companion object {
            val HIT_MULTIPLIERS = listOf(1.0, 0.5, 0.25)
        }
    }
}

private fun CardinalDirection.toDirection(): Direction =
    when (this) {
        CardinalDirection.North -> Direction.North
        CardinalDirection.South -> Direction.South
        CardinalDirection.East -> Direction.East
        CardinalDirection.West -> Direction.West
    }

private fun CardinalDirection.suffix(): String =
    when (this) {
        CardinalDirection.North -> "north"
        CardinalDirection.South -> "south"
        CardinalDirection.East -> "east"
        CardinalDirection.West -> "west"
    }

private fun Npc.occupies(tile: CoordGrid): Boolean =
    tile.level == coords.level && tile.x in coords.x until coords.x + size && tile.z in coords.z until coords.z + size
