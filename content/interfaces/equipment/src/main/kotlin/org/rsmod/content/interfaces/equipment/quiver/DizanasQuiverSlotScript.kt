package org.rsmod.content.interfaces.equipment.quiver

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.types.aconverted.interf.IfButtonOp
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.player.worn.DizanasQuiver
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerLogin
import dev.openrune.definition.type.widget.IfEvent
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Dizana's quiver's extra ammunition slot: Fill, Quiver-Remove and Swap. The slot is on the worn equipment side tab
 * (`wornitems`) - the one players click - and on the equipment stats screen (`equipment`).
 */
class DizanasQuiverSlotScript : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        for (slot in SLOTS) onIfOverlayButton(slot) { click(it.op) }
        onPlayerLogin {
            if (DizanasQuiver.storedCount(player) <= 0) DizanasQuiver.store(player, -1, 0)
            player.ifSetEvents("component.wornitems:extra_quiver_slot", 0..0, IfEvent.Op1, IfEvent.Op2, IfEvent.Op3, IfEvent.Op10)
        }
    }

    /** Op 1: Fill when the slot is empty, else Quiver-Remove. Op 2 (or 3): Swap with the worn ammunition. */
    private fun ProtectedAccess.click(op: IfButtonOp) {
        logger.debug { "quiver slot ${player.username} op $op" }
        when {
            op == IfButtonOp.Op2 || op == IfButtonOp.Op3 -> DizanasQuiver.swap(player)
            DizanasQuiver.storedCount(player) <= 0 -> DizanasQuiver.fill(player)?.let { mes(it) }
            !DizanasQuiver.empty(player) -> mes("You don't have enough inventory space to do that.")
        }
    }

    private companion object {
        val SLOTS = listOf("component.wornitems:extra_quiver_slot", "component.equipment:extra_quiver_slot")
    }
}
