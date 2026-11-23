package org.rsmod.api.specials.scripts

import kotlin.math.min
import org.rsmod.api.config.constants
import org.rsmod.api.player.ring
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.isAnyType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Special attack energy regenerates 10% every 50 ticks, or every 25 ticks while a Lightbearer is
 * worn.
 */
public class SpecialEnergyScript() : PluginScript() {
    private val Player.newAccount by boolVarBit("varbit.new_player_account")
    private var Player.specialAttackEnergy by intVarp("varp.sa_energy")

    override fun ScriptContext.startup() {
        onPlayerLogin { player.initRegenTimer() }
        onPlayerSoftTimer("timer.spec_regen") { player.specRegen() }
    }

    private fun Player.initRegenTimer() {
        if (newAccount) {
            specialAttackEnergy = constants.sa_max_energy
        }
        softTimer("timer.spec_regen", interval())
    }

    private fun Player.specRegen() {
        softTimer("timer.spec_regen", interval())
        val increased = min(constants.sa_max_energy, specialAttackEnergy + 100)
        if (increased > specialAttackEnergy) {
            specialAttackEnergy = increased
        }
    }

    /** Each player's regen counts from their own timer, so setting it again (a room done) starts it over. */
    private fun Player.interval(): Int =
        if (ring.isAnyType("obj.lightbearer", "obj.br_lightbearer")) LIGHTBEARER_REGEN_INTERVAL else constants.spec_regen_interval

    private companion object {
        const val LIGHTBEARER_REGEN_INTERVAL = constants.spec_regen_interval / 2
    }
}
