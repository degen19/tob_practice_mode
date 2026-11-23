package org.rsmod.content.interfaces.equipment.quiver

import dev.openrune.definition.type.widget.IfEvent
import dev.openrune.types.aconverted.interf.IfButtonOp
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.player.worn.DizanasQuiver
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onIfOpen
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Dizana's quiver's extra ammunition slot. The client (`dizanas_quiver_draw_worn_ammo`) puts the ops on the
 * `extra_quiver_ammo` component: Quiver-Remove (op 1), Swap (op 2) and Examine (op 10) when it holds ammunition,
 * Fill (op 2) when it's empty.
 */
class DizanasQuiverSlotScript : PluginScript() {
    override fun ScriptContext.startup() {
        onIfOverlayButton(WORN) { click(it.op) }
        onIfModalButton(STATS) { click(it.op) }
        onPlayerLogin {
            if (DizanasQuiver.storedCount(player) <= 0) DizanasQuiver.store(player, -1, 0)
            player.enable(WORN)
        }
        onIfOpen("interface.equipment") { player.enable(STATS) }
    }

    private fun Player.enable(component: String) {
        ifSetEvents(component, -1..-1, IfEvent.Op1, IfEvent.Op2, IfEvent.Op10)
        ifSetEvents(component, 0..0, IfEvent.Op1, IfEvent.Op2, IfEvent.Op10)
    }

    private fun ProtectedAccess.click(op: IfButtonOp) {
        val held = DizanasQuiver.storedCount(player) > 0
        when (op) {
            IfButtonOp.Op1 -> if (held && !DizanasQuiver.empty(player)) mes("You don't have enough inventory space to do that.")
            IfButtonOp.Op2 -> if (held) DizanasQuiver.swap(player) else DizanasQuiver.fill(player)?.let { mes(it) }
            IfButtonOp.Op10 -> if (held) examine()
            else -> Unit
        }
    }

    private fun ProtectedAccess.examine() {
        val obj = dev.openrune.ServerCacheManager.getItem(DizanasQuiver.storedObj(player)) ?: return
        mes(obj.examine.ifBlank { obj.name })
    }

    private companion object {
        const val WORN = "component.wornitems:extra_quiver_ammo"
        const val STATS = "component.equipment:extra_quiver_ammo"
    }
}
