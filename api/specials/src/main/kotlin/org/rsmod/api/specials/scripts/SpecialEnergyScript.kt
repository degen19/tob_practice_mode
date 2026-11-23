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
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
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

    /** The regen timer's own interval says whether a Lightbearer was on (half the usual). */
    @OptIn(org.rsmod.annotations.InternalApi::class)
    private fun Player.ringChanged() {
        val key = "timer.spec_regen".asRSCM(RSCMType.TIMER).toShort()
        val was = softTimerMap[key]?.let { softTimerMap.extractInterval(it) } == LIGHTBEARER_REGEN_INTERVAL
        val now = ring.isLightbearer()
        if (now && !was) lightbearerOn() else if (!now && was) lightbearerOff()
    }

    /**
     * Putting a Lightbearer on starts the regen over only if it was less than halfway to its next 10%; past halfway the
     * restore comes at once. Taking it off always starts it over (the wiki).
     */
    private fun Player.lightbearerOn() {
        val elapsed = constants.spec_regen_interval - softTimerMap.remainingCycles("timer.spec_regen", currentMapClock)
        softTimer("timer.spec_regen", if (elapsed < LIGHTBEARER_REGEN_INTERVAL) LIGHTBEARER_REGEN_INTERVAL else 1)
    }

    private fun Player.lightbearerOff() {
        softTimer("timer.spec_regen", constants.spec_regen_interval)
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
