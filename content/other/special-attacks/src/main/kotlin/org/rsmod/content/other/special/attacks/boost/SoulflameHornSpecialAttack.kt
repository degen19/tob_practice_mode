package org.rsmod.content.other.special.attacks.boost

import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.player.HornBuff
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.specials.SpecialAttackManager
import org.rsmod.api.specials.SpecialAttackMap
import org.rsmod.api.specials.SpecialAttackRepository
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList

/**
 * The Soulflame horn's Entice: up to [MAX_PLAYERS] other players within [RADIUS] tiles of the user (nearest first, not
 * one already holding it) and the user get [HornBuff] for [BUFF_CYCLES]; 25% special energy for each of the others.
 * No one in range: no energy, and the horn blows into the wind. (The radius and player cap aren't configurable yet; as
 * inside a raid, everyone can receive it.)
 */
class SoulflameHornSpecialAttack @Inject constructor(private val players: PlayerList) : SpecialAttackMap {
    override fun SpecialAttackRepository.register(manager: SpecialAttackManager) {
        registerInstant("obj.soulflame_horn", ::entice)
    }

    private fun entice(access: ProtectedAccess): Boolean = access.blow()

    private fun ProtectedAccess.blow(): Boolean {
        val energy = player.vars[ENERGY]
        val affordable = energy / COST_EACH
        val near =
            players.filter { it !== player && it.isSlotAssigned && it.coords.level == player.coords.level && !HornBuff.holds(it) && inRadius(player, it) }
                .sortedBy { dist2(player, it) }
                .take(minOf(MAX_PLAYERS, affordable))
        if (near.isEmpty()) {
            mes("You blow your horn into the wind. No-one nearby is able to listen.")
            return false
        }
        VarPlayerIntMapSetter.set(player, ENERGY, energy - COST_EACH * near.size)
        anim("seq.soulflame_horn_blow_01")
        spotanim("spotanim.vfx_soulflame_horn_spotanim01", slot = constants.spotanim_slot_combat)
        soundSynth("synth.yama_soulflame_horn_fire_01")
        val until = player.currentMapClock + BUFF_CYCLES
        HornBuff.give(player, until)
        for (p in near) {
            HornBuff.give(p, until)
            p.spotanim("spotanim.vfx_soulflame_horn_impact_spotanim01", slot = constants.spotanim_slot_combat)
        }
        return false
    }

    private fun inRadius(a: Player, b: Player): Boolean = dist2(a, b) <= RADIUS * RADIUS

    private fun dist2(a: Player, b: Player): Int {
        val dx = a.coords.x - b.coords.x
        val dz = a.coords.z - b.coords.z
        return dx * dx + dz * dz
    }

    private companion object {
        const val ENERGY = "varp.sa_energy"
        const val COST_EACH = 250
        const val RADIUS = 3
        const val MAX_PLAYERS = 3
        const val BUFF_CYCLES = 10
    }
}
