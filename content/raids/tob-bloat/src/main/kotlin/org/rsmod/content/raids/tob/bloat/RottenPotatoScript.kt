package org.rsmod.content.raids.tob.bloat

import jakarta.inject.Inject
import org.rsmod.api.player.PracticeSettings
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeld5
import org.rsmod.content.raids.tob.solo.SoloRaids
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The rotten potato: the practice switches ([PracticeSettings]) on its right-click. Settings lists them all (deaths,
 * NPC hitpoints, supplies, infinite prayer, always max hit, infinite special attack, Nylo wave numbers and death
 * indicators) and stays open while you flip
 * them; Toggle deaths and Toggle NPC HP flip theirs straight away (any time, mid-fight too). Gear opens the supplies (what
 * the lobby's chest has). Restart room puts you back outside the barrier with the room as it was (Verzik: in the middle
 * before P1, the Dawnbringer on the floor).
 */
class RottenPotatoScript @Inject constructor(private val solo: SoloRaids) : PluginScript() {
    override fun ScriptContext.startup() {
        onOpHeld1(POTATO) { settings() }
        onOpHeld2(POTATO) { toggleDeaths() }
        onOpHeld3(POTATO) { toggleNpcHp() }
        onOpHeld4(POTATO) { solo.supplies?.invoke(this) ?: mes("The supplies aren't ready yet.") }
        onOpHeld5(POTATO) {
            if (solo.rewatch?.invoke(player) == true) return@onOpHeld5 mes("A fresh trio heads in from outside.")
            val room = solo.forPlayer(player) ?: return@onOpHeld5 mes("You can only restart a room from inside the Theatre.")
            room.restart(this)
            mes("${room.id.label} restarted.")
        }
    }

    /** The settings list stays open as you flip things, showing each one's new state; Done closes it. */
    private suspend fun ProtectedAccess.settings() {
        val s = PracticeSettings
        while (true) {
            val choice =
                menu(
                    "Practice settings",
                    "Player deaths: ${onOff(!s.noDeaths)}",
                    "NPC hitpoints: ${if (s.infiniteHp) "<col=00ff00>infinite</col>" else "normal"}",
                    "Supplies: ${if (s.infiniteSupplies) "<col=00ff00>infinite</col>" else "normal"}",
                    "Infinite prayer: ${onOff(s.infinitePrayer)}",
                    "Always max hit: ${onOff(s.alwaysMaxHit)}",
                    "Infinite special attack: ${onOff(s.infiniteSpec)}",
                    "Nylo wave numbers: ${onOff(s.nyloWaveLabels)}",
                    "Nylo death indicators: ${onOff(s.nyloDeathIndicators)}",
                    "Done",
                )
            when (choice) {
                0 -> s.noDeaths = !s.noDeaths
                1 -> s.infiniteHp = !s.infiniteHp
                2 -> s.infiniteSupplies = !s.infiniteSupplies
                3 -> s.infinitePrayer = !s.infinitePrayer
                4 -> s.alwaysMaxHit = !s.alwaysMaxHit
                5 -> s.infiniteSpec = !s.infiniteSpec
                6 -> s.nyloWaveLabels = !s.nyloWaveLabels
                7 -> s.nyloDeathIndicators = !s.nyloDeathIndicators
                else -> {
                    ifClose()
                    return
                }
            }
        }
    }

    private fun onOff(on: Boolean): String = if (on) "<col=00ff00>on</col>" else "<col=ff0000>off</col>"

    private fun ProtectedAccess.toggleDeaths() {
        PracticeSettings.noDeaths = !PracticeSettings.noDeaths
        mes("Player deaths: ${if (PracticeSettings.noDeaths) "off - you take the hits but stay on at least 1 HP" else "on"}.")
    }

    private fun ProtectedAccess.toggleNpcHp() {
        PracticeSettings.infiniteHp = !PracticeSettings.infiniteHp
        mes("NPC hitpoints: ${if (PracticeSettings.infiniteHp) "infinite - hits show but take nothing off" else "normal"}.")
    }

    private companion object {
        const val POTATO = "obj.rotten_potato"
    }
}
