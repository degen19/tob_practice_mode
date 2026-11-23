package org.rsmod.api.combat.manager

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import jakarta.inject.Inject
import kotlin.math.min
import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.config.refs.params
import org.rsmod.api.player.hit.modifier.NoopPlayerHitModifier
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.rangedLvl
import org.rsmod.api.player.stat.statHeal
import org.rsmod.api.random.GameRandom
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.isType

/**
 * The special effects of enchanted Ruby, Diamond, Dragonstone and Onyx bolts (and their dragon
 * bolt versions) fired from a crossbow.
 *
 * Each shot rolls the bolt's activation chance (higher with the Kandarin hard diary). Ruby and
 * Diamond bolts activate even when the accuracy roll fails; Dragonstone and Onyx bolts need a
 * successful hit:
 * - Ruby (Blood forfeit): hits for 20% of the target's current hitpoints (max 100) and the shooter
 *   loses 10% of their own current hitpoints.
 * - Diamond (Armour piercing): a guaranteed hit with a 15% higher max hit.
 * - Dragonstone (Dragon's breath): adds 20% of the shooter's visible Ranged level to the max hit.
 *   Draconic targets are immune.
 * - Onyx (Life leech): a 20% higher max hit, healing the shooter for 25% of the damage. Undead
 *   targets are immune.
 *
 * The Zaryte crossbow raises those numbers (22% / 110 cap, 126%, 22%, 132%). Its Evoke special
 * guarantees the effect on a successful hit, and the Armadyl crossbow special doubles the chance.
 */
public class EnchantedBolts
@Inject
constructor(private val manager: PlayerAttackManager, private val random: GameRandom) {
    public enum class Effect(
        public val pvmChance: Int,
        public val pvpChance: Int,
        public val bypassesAccuracy: Boolean,
        public val spotanim: String,
        public val synth: String,
    ) {
        Ruby(60, 110, true, "spotanim.xbows_blood_sacrifice_spot_anim", "synth.blood_sacrifice"),
        Diamond(100, 50, true, "spotanim.xbows_diamond_tips_spotanim", "synth.armour_piercing"),
        Dragonstone(60, 60, false, "spotanim.xbows_dragons_breath_spot_anim", "synth.dragons_breath"),
        Onyx(110, 100, false, "spotanim.xbows_life_leach_spot_anim", "synth.life_leech"),
    }

    /** How a special attack changes the activation roll of the bolt effect. */
    public enum class Proc {
        Normal,
        DoubleChance,
        GuaranteedOnHit,
    }

    /** The damage of one shot and the bolt effect that activated on it, if any. */
    public data class Shot(val damage: Int, val effect: Effect?)

    private val effectsByObj: Map<Int, Effect> by lazy {
        buildMap {
            for ((obj, effect) in BOLTS) {
                put(obj.asRSCM(RSCMType.OBJ), effect)
            }
        }
    }

    public fun effectOf(ammo: ItemServerType?): Effect? = ammo?.let { effectsByObj[it.id] }

    /**
     * Rolls one crossbow shot from [player] at [target] with [ammo], applying the bolt effect when
     * it activates. The effect's graphic and sound play on [target] after [clientDelay].
     */
    public fun shoot(
        player: Player,
        target: PathingEntity,
        attack: CombatAttack.Ranged,
        ammo: ItemServerType?,
        clientDelay: Int,
        accuracyMultiplier: Double = 1.0,
        maxHitMultiplier: Double = 1.0,
        proc: Proc = Proc.Normal,
    ): Shot {
        val accurate =
            manager.rollRangedAccuracy(
                source = player,
                target = target,
                attackType = attack.type,
                attackStyle = attack.style,
                blockType = attack.type,
                multiplier = accuracyMultiplier,
            )

        val effect = effectOf(ammo)?.takeIf { it.canAffect(target) }
        val activated = effect != null && activates(player, target, effect, accurate, proc)
        if (effect == null || !activated) {
            val damage = if (accurate) rollMaxHit(player, target, attack, maxHitMultiplier) else 0
            return Shot(damage, null)
        }

        val zaryte = player.righthand.isType("obj.zaryte_xbow")
        val damage =
            when (effect) {
                Effect.Ruby -> bloodForfeit(player, target, zaryte)
                Effect.Diamond -> {
                    val multiplier = if (zaryte) 1.26 else 1.15
                    rollMaxHit(player, target, attack, maxHitMultiplier * multiplier)
                }
                Effect.Dragonstone -> {
                    val percent = if (zaryte) 22 else 20
                    val extra = player.rangedLvl * percent / 100
                    rollMaxHit(player, target, attack, maxHitMultiplier, boltSpecDamage = extra)
                }
                Effect.Onyx -> {
                    val multiplier = if (zaryte) 1.32 else 1.2
                    val damage = rollMaxHit(player, target, attack, maxHitMultiplier * multiplier)
                    player.statHeal("stat.hitpoints", constant = damage / 4, percent = 0)
                    damage
                }
            }

        target.spotanim(effect.spotanim, delay = clientDelay)
        manager.soundArea(target.coords, effect.synth, delay = clientDelay, loops = 1, radius = 5, size = 0)
        return Shot(damage, effect)
    }

    private fun activates(
        player: Player,
        target: PathingEntity,
        effect: Effect,
        accurate: Boolean,
        proc: Proc,
    ): Boolean {
        if (!accurate && !effect.bypassesAccuracy) {
            return false
        }
        if (proc == Proc.GuaranteedOnHit && accurate) {
            return true
        }
        var chance = if (target is Player) effect.pvpChance else effect.pvmChance
        if (player.vars["varbit.kandarin_diary_hard_complete"] != 0) {
            chance = chance * 11 / 10
        }
        if (proc == Proc.DoubleChance) {
            chance *= 2
        }
        // the Ascension crossbows trigger enchanted bolts half as often
        if (player.righthand.isType("obj.ascension_crossbows")) {
            chance /= 2
        }
        return random.of(CHANCE_SCALE) < chance
    }

    private fun bloodForfeit(player: Player, target: PathingEntity, zaryte: Boolean): Int {
        val targetHp =
            when (target) {
                is Npc -> target.hitpoints
                is Player -> target.hitpoints
            }
        val percent = if (zaryte) 22 else 20
        val cap = if (zaryte) 110 else 100
        val damage = min(cap, targetHp * percent / 100)
        val selfDamage = player.hitpoints / 10
        if (selfDamage > 0) {
            player.queueHit(1, HitType.Typeless, selfDamage, NoopPlayerHitModifier)
        }
        return damage
    }

    private fun rollMaxHit(
        player: Player,
        target: PathingEntity,
        attack: CombatAttack.Ranged,
        multiplier: Double,
        boltSpecDamage: Int = 0,
    ): Int =
        manager.rollRangedMaxHit(
            source = player,
            target = target,
            attackType = attack.type,
            attackStyle = attack.style,
            multiplier = multiplier,
            boltSpecDamage = boltSpecDamage,
        )

    private fun Effect.canAffect(target: PathingEntity): Boolean {
        if (target !is Npc) {
            return true
        }
        return when (this) {
            Effect.Dragonstone -> target.visType.param(params.draconic) == 0
            Effect.Onyx -> target.visType.param(params.undead) == 0
            else -> true
        }
    }

    private companion object {
        /** Activation chances are stored in tenths of a percent. */
        const val CHANCE_SCALE = 1000

        val BOLTS =
            listOf(
                "obj.xbows_crossbow_bolts_adamantite_tipped_ruby_enchanted" to Effect.Ruby,
                "obj.dragon_bolts_enchanted_ruby" to Effect.Ruby,
                "obj.xbows_crossbow_bolts_adamantite_tipped_diamond_enchanted" to Effect.Diamond,
                "obj.dragon_bolts_enchanted_diamond" to Effect.Diamond,
                "obj.xbows_crossbow_bolts_runite_tipped_dragonstone_enchanted" to
                    Effect.Dragonstone,
                "obj.dragon_bolts_enchanted_dragonstone" to Effect.Dragonstone,
                "obj.xbows_crossbow_bolts_runite_tipped_onyx_enchanted" to Effect.Onyx,
                "obj.dragon_bolts_enchanted_onyx" to Effect.Onyx,
                "obj.ascension_bolts_diamond_enchanted" to Effect.Diamond,
                "obj.ascension_bolts_onyx_enchanted" to Effect.Onyx,
            )
    }
}
