package org.rsmod.content.other.special.attacks.melee

import jakarta.inject.Inject
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.CombatEffects
import org.rsmod.api.combat.commons.types.MeleeAttackType
import org.rsmod.api.combat.manager.AreaTargets
import org.rsmod.api.config.constants
import org.rsmod.api.mechanics.toxins.impl.NpcPoison
import org.rsmod.api.mechanics.toxins.impl.PlayerPoison
import org.rsmod.api.npc.stat.stat
import org.rsmod.api.npc.stat.statSub
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.basePrayerLvl
import org.rsmod.api.player.stat.prayerLvl
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.specials.SpecialAttackMap
import org.rsmod.api.specials.SpecialAttackRepository
import org.rsmod.api.specials.combat.MeleeSpecialAttack
import org.rsmod.api.player.vars.intVarp
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.map.util.Bounds

class MeleeWeaponSpecialAttacks @Inject constructor(private val areaTargets: AreaTargets) :
    SpecialAttackMap {
    override fun SpecialAttackRepository.register(manager: SpecialAttackManager) {
        registerMelee("obj.voidwaker", Disrupt(manager))

        val tentacle = BindingTentacle(manager)
        registerMelee("obj.abyssal_tentacle", tentacle)
        registerMelee("obj.league_3_whip_tentacle", tentacle)

        registerMelee("obj.abyssal_bludgeon", Penance(manager))

        val fang = Eviscerate(manager)
        registerMelee("obj.osmumtens_fang", fang)
        registerMelee("obj.osmumtens_fang_ornament", fang)

        val behead = Behead(manager)
        registerMelee("obj.soulreaper", behead)
        registerMelee("obj.soulreaper_axe_orn", behead)

        val shieldBash = ShieldBash(manager, areaTargets)
        registerMelee("obj.dinhs_bulwark", shieldBash)
        registerMelee("obj.dinhs_bulwark_ornament", shieldBash)
    }

    /**
     * Voidwaker, Disrupt: a guaranteed hit of magic damage between 50% and 150% of the wielder's
     * melee max hit.
     */
    private class Disrupt(private val manager: SpecialAttackManager) : MeleeSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            disrupt(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            disrupt(target, attack)
            return true
        }

        private fun ProtectedAccess.disrupt(target: PathingEntity, attack: CombatAttack.Melee) {
            anim("seq.human_special_voidwaker")
            target.spotanim("spotanim.fx_voidwaker_impact", height = 0)
            val max = manager.calculateMeleeMaxHit(this, target, attack.type, attack.style, 1.0)
            val damage = random.of(max / 2, max * 3 / 2)
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMagicHit(this, target, damage, clientDelay = 0, hitDelay = 1)
            manager.continueCombat(this, target)
        }
    }

    /**
     * Abyssal tentacle, Binding Tentacle: 25% more accuracy against slash defence. Binds the target
     * for 8 ticks and has a 50% chance to poison it (starting at 4 damage), even on a miss.
     */
    private class BindingTentacle(private val manager: SpecialAttackManager) : MeleeSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            strike(target, attack)
            CombatEffects.freeze(target, BIND_TICKS)
            if (random.randomBoolean()) {
                NpcPoison.tryPoison(target, PlayerPoison.severityForInitialDamage(POISON_DAMAGE))
            }
            manager.continueCombat(this, target)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            strike(target, attack)
            CombatEffects.freeze(target, BIND_TICKS)
            if (random.randomBoolean()) {
                CombatEffects.poison(target, POISON_DAMAGE)
            }
            manager.continueCombat(this, target)
            return true
        }

        private fun ProtectedAccess.strike(target: PathingEntity, attack: CombatAttack.Melee) {
            anim("seq.slayer_abyssal_whip_attack")
            target.spotanim("spotanim.bind_impact", height = 0, delay = 30)
            val damage =
                manager.rollMeleeDamage(
                    source = this,
                    target = target,
                    attack = attack,
                    accuracyMultiplier = 1.25,
                    maxHitMultiplier = 1.0,
                    blockType = MeleeAttackType.Slash,
                )
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMeleeHit(this, target, damage)
        }

        private companion object {
            const val BIND_TICKS = 8
            const val POISON_DAMAGE = 4
        }
    }

    /**
     * Abyssal bludgeon, Penance: 0.5% more damage for every Prayer point the wielder is missing.
     */
    private class Penance(private val manager: SpecialAttackManager) : MeleeSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            penance(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            penance(target, attack)
            return true
        }

        private fun ProtectedAccess.penance(target: PathingEntity, attack: CombatAttack.Melee) {
            anim("seq.abyssal_bludgeon_special_attack")
            target.spotanim("spotanim.abyssal_miasma_spotanim_bludgeon", height = 0, delay = 30)
            val missing = (player.basePrayerLvl - player.prayerLvl).coerceAtLeast(0)
            val damage =
                manager.rollMeleeDamage(
                    source = this,
                    target = target,
                    attack = attack,
                    accuracyMultiplier = 1.0,
                    maxHitMultiplier = 1.0 + missing * 0.005,
                )
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMeleeHit(this, target, damage)
            manager.continueCombat(this, target)
        }
    }

    /**
     * Osmumten's fang, Eviscerate: 50% more accuracy (on top of the fang's double accuracy roll
     * with stab) and the damage is rolled between 15% of the max hit and the full max hit, instead
     * of the usual 15%-85%.
     */
    private class Eviscerate(private val manager: SpecialAttackManager) : MeleeSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            eviscerate(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            eviscerate(target, attack)
            return true
        }

        private fun ProtectedAccess.eviscerate(target: PathingEntity, attack: CombatAttack.Melee) {
            anim("seq.weapon_sword_osmumten03_special")
            spotanim(
                "spotanim.spotanim_weapon_sword_osmumten_special",
                slot = constants.spotanim_slot_combat,
                height = 0,
            )
            val accurate =
                manager.rollMeleeAccuracy(
                    source = this,
                    target = target,
                    attackType = attack.type,
                    attackStyle = attack.style,
                    blockType = attack.type,
                    multiplier = 1.5,
                )
            val damage =
                if (accurate) {
                    val max =
                        manager.calculateMeleeMaxHit(this, target, attack.type, attack.style, 1.0)
                    random.of(max * 15 / 100, max)
                } else {
                    0
                }
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMeleeHit(this, target, damage)
            manager.continueCombat(this, target)
        }
    }

    /**
     * Soulreaper axe, Behead: uses no special energy but consumes every soul stack (at least one
     * is needed). Each stack consumed adds 12% accuracy and 6% to both the max hit and the minimum
     * hit (the minimum hit is 6% of the max hit per stack). The stacks' Strength boost is consumed
     * first, so the damage bonus replaces it.
     */
    private class Behead(private val manager: SpecialAttackManager) : MeleeSpecialAttack {
        private var Player.soulStacks by intVarp("varp.soulreaper_stacks")

        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean = behead(target, attack)

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean = behead(target, attack)

        private fun ProtectedAccess.behead(
            target: PathingEntity,
            attack: CombatAttack.Melee,
        ): Boolean {
            val stacks = player.soulStacks
            if (stacks <= 0) {
                mes("You need at least one soul stack to use this special attack.")
                return false
            }
            player.soulStacks = 0

            anim("seq.ancient_axe_special")
            spotanim(
                "spotanim.ancient_axe_special_spotanim",
                slot = constants.spotanim_slot_combat,
                height = 0,
            )
            val accurate =
                manager.rollMeleeAccuracy(
                    source = this,
                    target = target,
                    attackType = attack.type,
                    attackStyle = attack.style,
                    blockType = attack.type,
                    multiplier = 1.0 + stacks * 0.12,
                )
            val damage =
                if (accurate) {
                    val bonus = 1.0 + stacks * 0.06
                    val max =
                        manager.calculateMeleeMaxHit(this, target, attack.type, attack.style, bonus)
                    random.of((max * stacks * 6 / 100).coerceAtMost(max), max)
                } else {
                    0
                }
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMeleeHit(this, target, damage)
            manager.continueCombat(this, target)
            return true
        }
    }

    /**
     * Dinh's bulwark, Shield Bash: 20% more accuracy against crush defence. In multi-combat it hits
     * up to 10 targets in the 11x11 area around the wielder; the main target is hit twice and the
     * rest once. Every target the first hit damages loses 5% of its highest offensive stat.
     */
    private class ShieldBash(
        private val manager: SpecialAttackManager,
        private val areaTargets: AreaTargets,
    ) : MeleeSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            anim("seq.human_dinhs_bulwark_bash")
            bash(target, attack)
            bash(target, attack, drain = false)
            if (mapMultiway()) {
                val area = Bounds(player.coords.translate(-AREA_RADIUS, -AREA_RADIUS), AREA_SIZE)
                val others = areaTargets.npcsIn(player, area, listOf(target), MAX_TARGETS - 1)
                for (npc in others) {
                    bash(npc, attack)
                }
            }
            manager.continueCombat(this, target)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            anim("seq.human_dinhs_bulwark_bash")
            bash(target, attack)
            manager.continueCombat(this, target)
            return true
        }

        private fun ProtectedAccess.bash(
            target: PathingEntity,
            attack: CombatAttack.Melee,
            drain: Boolean = true,
        ) {
            val damage =
                manager.rollMeleeDamage(
                    source = this,
                    target = target,
                    attack = attack,
                    accuracyMultiplier = 1.2,
                    maxHitMultiplier = 1.0,
                    blockType = MeleeAttackType.Crush,
                )
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueMeleeHit(this, target, damage)
            if (drain && damage > 0 && target is Npc) {
                drainHighestOffence(target)
            }
        }

        private fun drainHighestOffence(npc: Npc) {
            val highest = OFFENSIVE_STATS.maxOf { npc.stat(it) }
            for (stat in OFFENSIVE_STATS) {
                if (npc.stat(stat) == highest) {
                    val drain = npc.stat(stat) * 5 / 100
                    if (drain > 0) {
                        npc.statSub(stat, constant = drain, percent = 0)
                    }
                }
            }
        }

        private companion object {
            const val AREA_RADIUS = 5
            const val AREA_SIZE = 11
            const val MAX_TARGETS = 10
            val OFFENSIVE_STATS = listOf("stat.attack", "stat.strength", "stat.ranged", "stat.magic")
        }
    }
}
