package org.rsmod.content.other.special.weapons.melee

import org.rsmod.api.combat.commons.CombatAttack
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.weapons.MeleeWeapon
import org.rsmod.api.weapons.WeaponAttackManager
import org.rsmod.api.weapons.WeaponMap
import org.rsmod.api.weapons.WeaponRepository
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Soulreaper axe soul stacks: every attack with the axe, hit or miss, adds a stack after its damage
 * is rolled, up to five. Each stack gives +6% Strength to the axe (see the melee max hit formula).
 * Stacks no longer cost hitpoints (June 2026 change). When the player stops attacking with the
 * axe, one stack decays every 50 ticks. The Behead special consumes them.
 */
class SoulreaperAxeWeapons : WeaponMap {
    override fun WeaponRepository.register(manager: WeaponAttackManager) {
        val axe = SoulreaperAxe(manager)
        register("obj.soulreaper", axe)
        register("obj.soulreaper_axe_orn", axe)
    }

    private class SoulreaperAxe(private val manager: WeaponAttackManager) : MeleeWeapon {
        override suspend fun ProtectedAccess.attack(
            target: Npc,
            attack: CombatAttack.Melee,
        ): Boolean {
            swing(target, attack)
            return true
        }

        override suspend fun ProtectedAccess.attack(
            target: Player,
            attack: CombatAttack.Melee,
        ): Boolean {
            swing(target, attack)
            return true
        }

        private fun ProtectedAccess.swing(target: PathingEntity, attack: CombatAttack.Melee) {
            val damage =
                manager.rollMeleeDamage(
                    source = this,
                    target = target,
                    attack = attack,
                    accuracyMultiplier = 1.0,
                    maxHitMultiplier = 1.0,
                )
            manager.giveCombatXp(this, target, attack, damage)
            manager.playWeaponFx(this, attack)
            manager.queueMeleeHit(this, target, damage)
            SoulStacks.add(player)
            manager.continueCombat(this, target)
        }
    }
}

internal object SoulStacks {
    const val MAX_STACKS = 5
    const val DECAY_TICKS = 50
    const val DECAY_TIMER = "timer.soulreaper_stack_decay"

    private var Player.soulStacks by intVarp("varp.soulreaper_stacks")

    fun add(player: Player) {
        player.soulStacks = minOf(MAX_STACKS, player.soulStacks + 1)
        player.softTimer(DECAY_TIMER, DECAY_TICKS)
    }

    fun decay(player: Player) {
        val remaining = (player.soulStacks - 1).coerceAtLeast(0)
        player.soulStacks = remaining
        if (remaining == 0) {
            player.clearSoftTimer(DECAY_TIMER)
        }
    }
}

class SoulStackDecayScript : PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerSoftTimer(SoulStacks.DECAY_TIMER) { SoulStacks.decay(player) }
    }
}
