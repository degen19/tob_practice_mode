package org.rsmod.api.specials.scripts

import kotlin.math.min
import org.rsmod.api.config.constants
import org.rsmod.api.player.ring
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftTimer
import org.rsmod.api.script.onEvent
import org.rsmod.api.player.events.interact.HeldEquipEvents
import dev.openrune.util.Wearpos
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
        onEvent<HeldEquipEvents.WearposChange> { if (wearpos == Wearpos.Ring) player.ringChanged() }
    }

    /**
     * Ring swaps leave the regen alone while 25 ticks or fewer are left on it. With more left (only possible without a
     * Lightbearer), putting one on brings it down to the Lightbearer's 25. Taking one off changes nothing until its
     * restore comes; the timer then runs at the usual 50.
     */
    private fun Player.ringChanged() {
        if (!ring.isLightbearer()) return
        if (softTimerMap.remainingCycles("timer.spec_regen", currentMapClock) > LIGHTBEARER_REGEN_INTERVAL) {
            softTimer("timer.spec_regen", LIGHTBEARER_REGEN_INTERVAL)
        }
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
    private fun Player.interval(): Int = if (ring.isLightbearer()) LIGHTBEARER_REGEN_INTERVAL else constants.spec_regen_interval

    private fun org.rsmod.game.inv.InvObj?.isLightbearer(): Boolean = isAnyType(LIGHTBEARER, BR_LIGHTBEARER)

    private companion object {
        const val LIGHTBEARER_REGEN_INTERVAL = constants.spec_regen_interval / 2
        const val LIGHTBEARER = "obj.lightbearer"
        const val BR_LIGHTBEARER = "obj.br_lightbearer"
    }
}
