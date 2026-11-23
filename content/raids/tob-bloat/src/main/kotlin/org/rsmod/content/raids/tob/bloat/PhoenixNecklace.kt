package org.rsmod.content.raids.tob.bloat

import dev.openrune.util.Wearpos
import org.rsmod.api.player.PracticeSettings
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.statHeal
import org.rsmod.api.script.onEvent
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.isType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Phoenix necklace: a hit that leaves you alive below 20% of your Hitpoints heals you 30% of them and
 * destroys the necklace. The hits still queued on you are cleared with it (verzikSim's rule, as in
 * every room: one proc clears the lot - why stalls bunch the flies up for one necklace).
 */
class PhoenixNecklace : PluginScript() {
    override fun ScriptContext.startup() {
        onEvent<PlayerHitEvents.Impact> { proc(player) }
    }

    private fun proc(player: Player) {
        val hp = player.hitpoints
        val max = player.baseHitpointsLvl
        if (hp <= 0 || hp * 5 >= max) return
        val neck = Wearpos.Front.slot
        if (!player.worn[neck].isType(NECKLACE)) return
        // infinite supplies: it procs, but stays on
        if (!PracticeSettings.infiniteSupplies) {
            player.worn[neck] = null
            player.rebuildAppearance()
        }
        player.statHeal("stat.hitpoints", constant = max * 3 / 10, percent = 0)
        player.clearQueue("queue.hit")
        player.mes("Your phoenix necklace heals you, but is destroyed in the process.")
    }

    private companion object {
        const val NECKLACE = "obj.jewl_necklace_of_phoenix"
    }
}
