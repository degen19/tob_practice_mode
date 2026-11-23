package org.rsmod.content.other.special.attacks.melee

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.config.constants
import org.rsmod.api.mechanics.burn.Burn
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.specials.SpecialAttackMap
import org.rsmod.api.specials.SpecialAttackRepository
import org.rsmod.api.specials.combat.MeleeSpecialAttack
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player

class ClawSpecialAttacks @Inject constructor(private val burn: Burn) : SpecialAttackMap {
    override fun SpecialAttackRepository.register(manager: SpecialAttackManager) {
        val dragonClaws = SliceAndDice(manager)
        registerMelee("obj.dragon_claws", dragonClaws)
        registerMelee("obj.bh_dragon_claws_corrupted", dragonClaws)

        registerMelee("obj.bone_claws", BurningBarrage(manager, burn))
    }

    /**
     * Dragon claws, Slice and Dice: four hits against the target's slash defence. Each accuracy
     * roll is a normal one until one succeeds, and the hits from that roll onwards all land:
     * - 1st roll lands: hit 1 is between half the max hit and the max hit minus one, hit 2 is half
     *   of hit 1, hit 3 half of hit 2 and hit 4 is hit 3 plus one.
     * - 2nd roll lands: hit 2 is between 3/8 and 7/8 of the max hit, hit 3 is half of it and hit 4
     *   is hit 3 plus one.
     * - 3rd roll lands: hit 3 is between 1/4 and 3/4 of the max hit and hit 4 is hit 3 plus one.
     * - 4th roll lands: hit 4 is between 1/4 and 5/4 of the max hit.
     * - All miss: two thirds of the time two of the hits deal 1 damage, else all four are 0.
     *
     * The first two hitsplats land on the next tick and the last two a tick later.
     */
    private class SliceAndDice(private val manager: SpecialAttackManager) : MeleeSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            slice(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            slice(target, attack)
            return true
        }

        private fun ProtectedAccess.slice(target: PathingEntity, attack: CombatAttack.Melee) {
            anim("seq.human_dragon_claws_spec")
            spotanim(
                spot = "spotanim.dragon_claws_spot",
                slot = constants.spotanim_slot_combat,
                height = 0,
            )

            val max = manager.calculateMeleeMaxHit(this, target, attack.type, attack.style, 1.0)
            val landed = (0 until HITS).firstOrNull { accurate(target, attack) }
            val hits = IntArray(HITS)
            when (landed) {
                0 -> {
                    hits[0] = random.of(max / 2, (max - 1).coerceAtLeast(max / 2))
                    hits[1] = hits[0] / 2
                    hits[2] = hits[1] / 2
                    hits[3] = hits[2] + 1
                }
                1 -> {
                    hits[1] = random.of(max * 3 / 8, max * 7 / 8)
                    hits[2] = hits[1] / 2
                    hits[3] = hits[2] + 1
                }
                2 -> {
                    hits[2] = random.of(max / 4, max * 3 / 4)
                    hits[3] = hits[2] + 1
                }
                3 -> hits[3] = random.of(max / 4, max * 5 / 4)
                else -> missedAll(hits)
            }

            manager.giveCombatXp(this, target, attack, hits.sum())
            for ((index, damage) in hits.withIndex()) {
                val delay = if (index < 2) 1 else 2
                manager.queueMeleeHit(this, target, damage, delay)
            }
            manager.continueCombat(this, target)
        }

        private fun ProtectedAccess.missedAll(hits: IntArray) {
            if (random.of(3) == 0) {
                return
            }
            val pattern = random.pick(MISS_PATTERNS)
            for (index in pattern) {
                hits[index] = 1
            }
        }

        private fun ProtectedAccess.accurate(
            target: PathingEntity,
            attack: CombatAttack.Melee,
        ): Boolean =
            manager.rollMeleeAccuracy(
                source = this,
                target = target,
                attackType = attack.type,
                attackStyle = attack.style,
                blockType = attack.type,
                multiplier = 1.0,
            )

        private companion object {
            const val HITS = 4
            val MISS_PATTERNS =
                arrayOf(intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(0, 2), intArrayOf(1, 3))
        }
    }

    /**
     * Burning claws, Burning barrage: three hits rolled against the defence of the selected style.
     * Up to three accuracy rolls are made, each only if the previous one missed:
     * - 1st roll lands: 75%-175% of the max hit, split 25% / 25% / 50%; each hitsplat has a 15%
     *   chance to burn.
     * - 2nd roll lands: 50%-150% of the max hit, split 50% / 50% / 0% with 1 moved from each of the
     *   first two hitsplats to the third; 30% burn chance per hitsplat.
     * - 3rd roll lands: 25%-125% of the max hit, all on the third hitsplat with 1 moved to each of
     *   the first two; 45% burn chance per hitsplat.
     * - All miss: 0 damage 20% of the time, else 1 or 2 damage (40% each), which cannot burn.
     *
     * Every value rounds down. A burn deals 10 damage over 40 ticks and up to five can stack.
     */
    private class BurningBarrage(
        private val manager: SpecialAttackManager,
        private val burn: Burn,
    ) : MeleeSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            barrage(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            barrage(target, attack)
            return true
        }

        private fun ProtectedAccess.barrage(target: PathingEntity, attack: CombatAttack.Melee) {
            anim("seq.human_weapon_burning_claws_02_spec")
            spotanim(
                spot = "spotanim.vfx_burning_claws_spec_02",
                slot = constants.spotanim_slot_combat,
                height = 0,
            )

            val max = manager.calculateMeleeMaxHit(this, target, attack.type, attack.style, 1.0)
            val landed = (0 until HITS).firstOrNull { accurate(target, attack) }
            val hits = IntArray(HITS)
            var burnChance = 0
            when (landed) {
                0 -> {
                    val total = random.of(max * 3 / 4, max * 7 / 4)
                    hits[0] = total / 4
                    hits[1] = total / 4
                    hits[2] = total / 2
                    burnChance = 15
                }
                1 -> {
                    val total = random.of(max / 2, max * 3 / 2)
                    hits[0] = (total / 2 - 1).coerceAtLeast(0)
                    hits[1] = (total / 2 - 1).coerceAtLeast(0)
                    hits[2] = 2
                    burnChance = 30
                }
                2 -> {
                    val total = random.of(max / 4, max * 5 / 4)
                    hits[0] = 1
                    hits[1] = 1
                    hits[2] = (total - 2).coerceAtLeast(0)
                    burnChance = 45
                }
                else -> {
                    val roll = random.of(5)
                    if (roll >= 1) {
                        hits[0] = 1
                    }
                    if (roll >= 3) {
                        hits[1] = 1
                    }
                }
            }

            manager.giveCombatXp(this, target, attack, hits.sum())
            for ((index, damage) in hits.withIndex()) {
                val delay = if (index < 2) 1 else 2
                manager.queueMeleeHit(this, target, damage, delay)
                if (burnChance > 0 && random.of(100) < burnChance) {
                    burn.inflict(target)
                }
            }
            manager.continueCombat(this, target)
        }

        private fun ProtectedAccess.accurate(
            target: PathingEntity,
            attack: CombatAttack.Melee,
        ): Boolean =
            manager.rollMeleeAccuracy(
                source = this,
                target = target,
                attackType = attack.type,
                attackStyle = attack.style,
                blockType = attack.type,
                multiplier = 1.0,
            )

        private companion object {
            const val HITS = 3
        }
    }
}
