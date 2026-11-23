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
 * The rotten potato: the practice switches ([PracticeSettings]) on its right-click. Settings shows them; Toggle deaths,
 * Toggle NPC HP and Toggle supplies flip them (any time, mid-fight too). Restart room puts you back outside the
 * barrier with the room as it was (Verzik: in the middle before P1, the Dawnbringer on the floor).
 */
class RottenPotatoScript @Inject constructor(private val solo: SoloRaids) : PluginScript() {
    override fun ScriptContext.startup() {
        onOpHeld1(POTATO) { settings() }
        onOpHeld2(POTATO) {
            PracticeSettings.noDeaths = !PracticeSettings.noDeaths
            mes("Player deaths: ${if (PracticeSettings.noDeaths) "off - you take the hits but stay on at least 1 HP" else "on"}.")
        }
        onOpHeld3(POTATO) {
            PracticeSettings.infiniteHp = !PracticeSettings.infiniteHp
            mes("NPC hitpoints: ${if (PracticeSettings.infiniteHp) "infinite - hits show but take nothing off" else "normal"}.")
        }
        onOpHeld4(POTATO) {
            PracticeSettings.infiniteSupplies = !PracticeSettings.infiniteSupplies
            mes("Supplies: ${if (PracticeSettings.infiniteSupplies) "infinite - food, potions, phoenix necklaces, runes and ammo aren't used up" else "used up as normal"}.")
        }
        onOpHeld5(POTATO) {
            val room = solo.forPlayer(player) ?: return@onOpHeld5 mes("You can only restart a room from inside the Theatre.")
            room.restart(this)
            mes("${room.id.label} restarted.")
        }
    }

    private fun ProtectedAccess.settings() {
        val s = PracticeSettings
        mes("Player deaths: ${if (s.noDeaths) "off (1 HP floor)" else "on"}. NPC hitpoints: ${if (s.infiniteHp) "infinite" else "normal"}. Supplies: ${if (s.infiniteSupplies) "infinite" else "normal"}.")
    }

    private companion object {
        const val POTATO = "obj.rotten_potato"
    }
}
