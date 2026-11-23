package org.rsmod.content.other.consumables.potion

import dev.openrune.rscm.RSCM.asRSCM
import org.rsmod.api.player.PracticeSettings
import org.rsmod.api.player.SurgeCooldown
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.output.soundSynth
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onPlayerTimer
import org.rsmod.content.other.consumables.ConsumableDelayState
import org.rsmod.content.other.consumables.ConsumableType
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.Inventory
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The surge potion: each dose restores 25% special attack energy, once every five minutes ([SurgeCooldown]).
 */
class SurgePotionScript : PluginScript() {
    override fun ScriptContext.startup() {
        for ((i, dose) in DOSES.withIndex()) {
            onOpHeld1(dose) { drink(it.inventory, it.slot, DOSES.getOrNull(i + 1) ?: VIAL, DOSES.size - 1 - i) }
        }
        onPlayerTimer(SurgeCooldown.TIMER) {
            player.clearTimer(SurgeCooldown.TIMER)
            mes("You now feel capable of drinking another dose of surge potion.")
        }
    }

    private fun ProtectedAccess.drink(inventory: Inventory, slot: Int, next: String, left: Int) {
        if (!ConsumableDelayState.canConsume(this, ConsumableType.POTION)) return
        val wait = SurgeCooldown.remaining(player)
        if (wait > 0) return mes("You are still full of adrenaline. You'll be able to drink in ${time(wait)}.")
        if (player.specialEnergy >= MAX_ENERGY) return mes("Drinking this would have no effect.")
        if (!PracticeSettings.infiniteSupplies && invReplaceSlot(inventory, slot, 1, dev.openrune.ServerCacheManager.getItem(next.asRSCM(dev.openrune.rscm.RSCMType.OBJ))!!).failure) return
        anim(DRINK_ANIMATION)
        soundSynth(DRINK_SOUND)
        player.specialEnergy = (player.specialEnergy + RESTORE).coerceAtMost(MAX_ENERGY)
        SurgeCooldown.start(player)
        ConsumableDelayState.recordConsumption(this, ConsumableType.POTION, consumeDelay = DRINK_DELAY, combatDelay = 0)
        player.resetFaceEntity()
        mes("You drink some of your surge potion.")
        mes(
            when (left) {
                0 -> "You have finished your potion."
                1 -> "You have 1 dose of potion left."
                else -> "You have $left doses of potion left."
            },
        )
    }

    private fun time(ticks: Int): String {
        val seconds = (ticks * 3 + 4) / 5
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "$m minute${if (m == 1) "" else "s"}${if (s > 0) " and $s second${if (s == 1) "" else "s"}" else ""}" else "$s second${if (s == 1) "" else "s"}"
    }

    private companion object {
        val DOSES = listOf("obj.4dosesurge", "obj.3dosesurge", "obj.2dosesurge", "obj.1dosesurge")
        const val VIAL = "obj.vial_empty"
        const val RESTORE = 250
        const val MAX_ENERGY = 1000
        const val DRINK_DELAY = 3
        const val DRINK_ANIMATION = "seq.human_eat"
        const val DRINK_SOUND = "synth.liquid"
    }
}

private var Player.specialEnergy by intVarp("varp.sa_energy")
