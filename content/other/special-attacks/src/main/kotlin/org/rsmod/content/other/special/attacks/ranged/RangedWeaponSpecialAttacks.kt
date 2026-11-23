package org.rsmod.content.other.special.attacks.ranged

import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import jakarta.inject.Inject
import kotlin.math.ceil
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.combat.commons.types.RangedAttackType
import org.rsmod.api.combat.manager.AreaTargets
import org.rsmod.api.combat.manager.EnchantedBolts
import org.rsmod.api.combat.manager.RangedAmmoManager
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.params
import org.rsmod.api.mechanics.toxins.impl.NpcVenom
import org.rsmod.api.mechanics.toxins.impl.PlayerVenom
import org.rsmod.api.npc.stat.statSub
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.quiver
import org.rsmod.api.player.worn.BlowpipeDarts
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.specials.SpecialAttackMap
import org.rsmod.api.specials.SpecialAttackRepository
import org.rsmod.api.specials.combat.RangedSpecialAttack
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.type.getInvObj
import org.rsmod.game.type.getOrNull
import org.rsmod.map.util.Bounds

class RangedWeaponSpecialAttacks
@Inject
constructor(
    private val ammunition: RangedAmmoManager,
    private val bolts: EnchantedBolts,
    private val areaTargets: AreaTargets,
) : SpecialAttackMap {
    override fun SpecialAttackRepository.register(manager: SpecialAttackManager) {
        registerRanged("obj.zaryte_xbow", Evoke(manager, ammunition, bolts))
        registerRanged("obj.acb", ArmadylEye(manager, ammunition, bolts))

        val annihilate = Annihilate(manager, ammunition, areaTargets)
        registerRanged("obj.xbows_crossbow_dragon", annihilate)
        registerRanged("obj.bh_xbows_crossbow_dragon_corrupted", annihilate)

        val concentratedShot = ConcentratedShot(manager, ammunition)
        registerRanged("obj.heavy_ballista", concentratedShot)
        registerRanged("obj.light_ballista", concentratedShot)

        val snapshot = Snapshot(manager, ammunition)
        registerRanged("obj.magic_shortbow", snapshot)
        registerRanged("obj.magic_shortbow_i", snapshot)

        val toxicSiphon = ToxicSiphon(manager)
        registerRanged("obj.toxic_blowpipe_loaded", toxicSiphon)
        registerRanged("obj.toxic_blowpipe_loaded_ornament", toxicSiphon)

        registerRanged("obj.wild_cave_webweaver_charged", Swarm(manager))

        registerRanged("obj.tonalztics_of_ralos_charged", Division(manager, charged = true))
        registerRanged("obj.tonalztics_of_ralos_uncharged", Division(manager, charged = false))
    }

    /** Runs the same special against npcs and players. */
    private abstract class Special : RangedSpecialAttack {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Ranged,
        ): Boolean = fire(target, attack)

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Ranged,
        ): Boolean = fire(target, attack)

        abstract fun ProtectedAccess.fire(target: PathingEntity, attack: CombatAttack.Ranged): Boolean
    }

    /**
     * Zaryte crossbow, Evoke: double accuracy, and a successful hit guarantees the enchanted bolt
     * effect (with the Zaryte crossbow's stronger effects).
     */
    private class Evoke(
        private val manager: SpecialAttackManager,
        private val ammunition: RangedAmmoManager,
        private val bolts: EnchantedBolts,
    ) : Special() {
        override fun ProtectedAccess.fire(target: PathingEntity, attack: CombatAttack.Ranged): Boolean {
            val bolt = loadedAmmo(manager, ammunition, attack) ?: return false
            anim(weaponAnim(attack))
            val proj = manager.spawnProjectile(this, target, "spotanim.zcb_specialattack", "projanim.bolt")
            val (serverDelay, clientDelay) = proj.durations
            ammunition.useQuiverAmmo(player, bolt, target.coords, serverDelay)
            val shot =
                bolts.shoot(
                    player = player,
                    target = target,
                    attack = attack,
                    ammo = bolt,
                    clientDelay = clientDelay,
                    accuracyMultiplier = 2.0,
                    proc = EnchantedBolts.Proc.GuaranteedOnHit,
                )
            manager.giveCombatXp(this, target, attack, shot.damage)
            manager.queueRangedHit(this, target, bolt, shot.damage, clientDelay, serverDelay)
            manager.continueCombat(this, target)
            return true
        }
    }

    /**
     * Armadyl crossbow, Armadyl Eye: double accuracy and double the enchanted bolt activation
     * chance.
     */
    private class ArmadylEye(
        private val manager: SpecialAttackManager,
        private val ammunition: RangedAmmoManager,
        private val bolts: EnchantedBolts,
    ) : Special() {
        override fun ProtectedAccess.fire(target: PathingEntity, attack: CombatAttack.Ranged): Boolean {
            val bolt = loadedAmmo(manager, ammunition, attack) ?: return false
            anim("seq.xbows_human_fire_and_reload")
            val proj = manager.spawnProjectile(this, target, "spotanim.acb_specialattack", "projanim.bolt")
            val (serverDelay, clientDelay) = proj.durations
            ammunition.useQuiverAmmo(player, bolt, target.coords, serverDelay)
            val shot =
                bolts.shoot(
                    player = player,
                    target = target,
                    attack = attack,
                    ammo = bolt,
                    clientDelay = clientDelay,
                    accuracyMultiplier = 2.0,
                    proc = EnchantedBolts.Proc.DoubleChance,
                )
            manager.giveCombatXp(this, target, attack, shot.damage)
            manager.queueRangedHit(this, target, bolt, shot.damage, clientDelay, serverDelay)
            manager.continueCombat(this, target)
            return true
        }
    }

    /**
     * Dragon crossbow, Annihilate: 20% more damage on the target; in multi-combat it also hits up
     * to 8 more npcs in the 3x3 area around the target for 20% less damage. Enchanted bolt effects
     * cannot activate.
     */
    private class Annihilate(
        private val manager: SpecialAttackManager,
        private val ammunition: RangedAmmoManager,
        private val areaTargets: AreaTargets,
    ) : Special() {
        override fun ProtectedAccess.fire(target: PathingEntity, attack: CombatAttack.Ranged): Boolean {
            val bolt = loadedAmmo(manager, ammunition, attack) ?: return false
            anim(weaponAnim(attack))
            val proj = manager.spawnProjectile(this, target, travelOf(bolt), "projanim.bolt")
            val (serverDelay, clientDelay) = proj.durations
            ammunition.useQuiverAmmo(player, bolt, target.coords, serverDelay)
            target.spotanim("spotanim.firesurge_impact", height = 0, delay = clientDelay)

            val damage = manager.rollRangedDamage(this, target, attack, maxHitMultiplier = 1.2)
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueRangedHit(this, target, bolt, damage, clientDelay, serverDelay)

            if (target is Npc && mapMultiway()) {
                val area = Bounds(target.coords.translate(-1, -1), target.size + 2)
                for (npc in areaTargets.npcsIn(player, area, listOf(target), MAX_EXTRA_TARGETS)) {
                    val splash = manager.rollRangedDamage(this, npc, attack, maxHitMultiplier = 0.8)
                    manager.giveCombatXp(this, npc, attack, splash)
                    manager.queueRangedHit(this, npc, bolt, splash, clientDelay, serverDelay)
                }
            }
            manager.continueCombat(this, target)
            return true
        }

        private companion object {
            const val MAX_EXTRA_TARGETS = 8
        }
    }

    /** Light and Heavy ballista, Concentrated Shot: 25% more accuracy and 25% more damage. */
    private class ConcentratedShot(
        private val manager: SpecialAttackManager,
        private val ammunition: RangedAmmoManager,
    ) : Special() {
        override fun ProtectedAccess.fire(target: PathingEntity, attack: CombatAttack.Ranged): Boolean {
            val javelin = loadedAmmo(manager, ammunition, attack) ?: return false
            anim("seq.ballista_special_attack_pvn")
            spotanim("spotanim.ballista_special", height = 0, slot = constants.spotanim_slot_combat)
            val proj = manager.spawnProjectile(this, target, travelOf(javelin), "projanim.bolt")
            val (serverDelay, clientDelay) = proj.durations
            ammunition.useQuiverAmmo(player, javelin, target.coords, serverDelay)
            val damage =
                manager.rollRangedDamage(
                    source = this,
                    target = target,
                    attack = attack,
                    accuracyMultiplier = 1.25,
                    maxHitMultiplier = 1.25,
                )
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueRangedHit(this, target, javelin, damage, clientDelay, serverDelay)
            manager.continueCombat(this, target)
            return true
        }
    }

    /**
     * Magic shortbow and Magic shortbow (i), Snapshot: two arrows fired at once, each with 10/7
     * (about 43%) more accuracy. Needs two arrows.
     */
    private class Snapshot(
        private val manager: SpecialAttackManager,
        private val ammunition: RangedAmmoManager,
    ) : Special() {
        override fun ProtectedAccess.fire(target: PathingEntity, attack: CombatAttack.Ranged): Boolean {
            val arrow = loadedAmmo(manager, ammunition, attack) ?: return false
            if ((player.quiver?.count ?: 0) < 2) {
                manager.stopCombat(this)
                mes("You need at least 2 arrows in your quiver for this special attack.")
                return false
            }
            anim("seq.snapshot")
            spotanim(
                "spotanim.sp_attack_snapshot_spotanim",
                height = 96,
                slot = constants.spotanim_slot_combat,
            )
            var total = 0
            for (projanim in listOf("projanim.doublearrow_one", "projanim.doublearrow_two")) {
                val proj =
                    manager.spawnProjectile(this, target, "spotanim.sp_attack_glow_arrow_travel", projanim)
                val (serverDelay, clientDelay) = proj.durations
                ammunition.useQuiverAmmo(player, arrow, target.coords, serverDelay)
                val damage =
                    manager.rollRangedDamage(this, target, attack, accuracyMultiplier = 10.0 / 7.0)
                total += damage
                manager.queueRangedHit(this, target, arrow, damage, clientDelay, serverDelay)
            }
            manager.giveCombatXp(this, target, attack, total)
            manager.continueCombat(this, target)
            return true
        }
    }

    /**
     * Toxic blowpipe, Toxic Siphon: double accuracy and 50% more damage, healing the wielder for
     * half the damage dealt (rounded down). Keeps the blowpipe's 1 in 4 venom chance.
     */
    private class ToxicSiphon(private val manager: SpecialAttackManager) : Special() {
        override fun ProtectedAccess.fire(target: PathingEntity, attack: CombatAttack.Ranged): Boolean {
            val dart = BlowpipeDarts.dart(attack.weapon)
            if (dart == null) {
                manager.stopCombat(this)
                mes("Your blowpipe has no darts in it.")
                return false
            }
            anim(weaponAnim(attack))
            val proj =
                manager.spawnProjectile(this, target, "spotanim.toxic_blowpipe_specialattack", "projanim.thrown")
            val (serverDelay, clientDelay) = proj.durations
            val damage =
                manager.rollRangedDamage(
                    source = this,
                    target = target,
                    attack = attack,
                    accuracyMultiplier = 2.0,
                    maxHitMultiplier = 1.5,
                )
            manager.giveCombatXp(this, target, attack, damage)
            manager.queueRangedHit(this, target, dart, damage, clientDelay, serverDelay)
            if (damage > 0) {
                statHeal("stat.hitpoints", constant = damage / 2, percent = 0)
                if (random.of(4) == 0) {
                    when (target) {
                        is Npc -> NpcVenom.tryVenom(target)
                        is Player -> PlayerVenom.tryVenom(target)
                    }
                }
            }
            manager.continueCombat(this, target)
            return true
        }
    }

    /**
     * Webweaver bow, Swarm: four hits with double accuracy, each up to 40% (rounded up) of the max
     * hit.
     */
    private class Swarm(private val manager: SpecialAttackManager) : Special() {
        override fun ProtectedAccess.fire(target: PathingEntity, attack: CombatAttack.Ranged): Boolean {
            anim("seq.human_special01_webweaver")
            spotanim(
                "spotanim.fx_webweaver01_launch_spotanim",
                height = 0,
                slot = constants.spotanim_slot_combat,
            )
            val proj =
                manager.spawnProjectile(this, target, "spotanim.wild_cave_bow_arrow_travel02", "projanim.arrow")
            val (serverDelay, clientDelay) = proj.durations
            target.spotanim("spotanim.fx_webweaver01_impact_spotanim", height = 0, delay = clientDelay)
            val max =
                manager.calculateRangedMaxHit(this, target, attack.type, attack.style, 1.0, 0)
            val hitMax = ceil(max * 0.4).toInt()
            var total = 0
            repeat(HITS) {
                val accurate =
                    manager.rollRangedAccuracy(
                        source = this,
                        target = target,
                        attackType = attack.type,
                        attackStyle = attack.style,
                        blockType = attack.type,
                        multiplier = 2.0,
                    )
                val damage = if (accurate && hitMax > 0) random.of(0, hitMax) else 0
                total += damage
                manager.queueRangedHit(this, target, null, damage, clientDelay, serverDelay)
            }
            manager.giveCombatXp(this, target, attack, total)
            manager.continueCombat(this, target)
            return true
        }

        private companion object {
            const val HITS = 4
        }
    }

    /**
     * Tonalztics of Ralos, Division: 50% more accuracy; each glaive that hits (two when charged,
     * one uncharged) lowers the target's Defence by 1/8 of its Magic level. Against npcs the first
     * reduction already applies to the second glaive's roll. Each throw deals 0-75% of the max hit
     * against light ranged defence.
     */
    private class Division(private val manager: SpecialAttackManager, private val charged: Boolean) :
        Special() {
        override fun ProtectedAccess.fire(target: PathingEntity, attack: CombatAttack.Ranged): Boolean {
            anim(if (charged) CHARGED_SPECIAL else UNCHARGED_SPECIAL)
            val launch = if (charged) CHARGED_LAUNCH else UNCHARGED_LAUNCH
            spotanim(launch, height = 0, slot = constants.spotanim_slot_combat)
            val glaives = if (charged) GLAIVES else GLAIVES.take(1)
            var total = 0
            for (travel in glaives) {
                val proj = manager.spawnProjectile(this, target, travel, "projanim.thrown")
                val (serverDelay, clientDelay) = proj.durations
                val damage =
                    manager.rollRangedDamage(
                        source = this,
                        target = target,
                        attack = attack,
                        accuracyMultiplier = 1.5,
                        maxHitMultiplier = 0.75,
                        blockType = RangedAttackType.Light,
                    )
                total += damage
                manager.queueRangedHit(this, target, null, damage, clientDelay, serverDelay)
                if (damage > 0 && target is Npc) {
                    val reduction = target.magicLvl / 8
                    if (reduction > 0) {
                        target.statSub("stat.defence", constant = reduction, percent = 0)
                    }
                }
            }
            manager.giveCombatXp(this, target, attack, total)
            manager.continueCombat(this, target)
            return true
        }

        private companion object {
            const val CHARGED_SPECIAL = "seq.human_glaive_ralos01_charged_special"
            const val UNCHARGED_SPECIAL = "seq.human_glaive_ralos01_uncharged_special"
            const val CHARGED_LAUNCH = "spotanim.vfx_glaive_charged_special"
            const val UNCHARGED_LAUNCH = "spotanim.vfx_glaive_uncharged_special"
            val GLAIVES =
                listOf("spotanim.projanim_glaive_01_special", "spotanim.projanim_glaive_02_special")
        }
    }
}

/**
 * The ammunition in the quiver slot, after checking it suits the weapon; `null` (with combat
 * stopped) when it doesn't.
 */
private fun ProtectedAccess.loadedAmmo(
    manager: SpecialAttackManager,
    ammunition: RangedAmmoManager,
    attack: CombatAttack.Ranged,
): ItemServerType? {
    val weapon = getInvObj(attack.weapon)
    val ammo = getOrNull(player.quiver)
    if (!ammunition.attemptAmmoUsage(player, weapon, ammo) || ammo == null) {
        manager.stopCombat(this)
        return null
    }
    if (ammo.paramOrNull(params.proj_travel) == null) {
        manager.stopCombat(this)
        mes("You are unable to fire your ammunition.")
        return null
    }
    return ammo
}

private fun travelOf(ammo: ItemServerType): String {
    val travel = checkNotNull(ammo.paramOrNull(params.proj_travel)) { "No proj_travel: $ammo" }
    return RSCM.getReverseMapping(RSCMType.SPOTANIM, travel.id)
}

private fun weaponAnim(attack: CombatAttack.Ranged): String {
    val anim = getInvObj(attack.weapon).paramOrNull(params.attack_anim_stance1)
    return anim?.let { RSCM.getReverseMapping(RSCMType.SEQ, it.id) } ?: "seq.xbows_human_fire_and_reload"
}
