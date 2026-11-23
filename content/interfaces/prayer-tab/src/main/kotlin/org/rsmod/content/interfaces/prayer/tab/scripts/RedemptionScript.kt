package org.rsmod.content.interfaces.prayer.tab.scripts

import org.rsmod.api.player.disablePrayers
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.output.soundSynth
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.basePrayerLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.prayerLvl
import org.rsmod.api.player.stat.statHeal
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.script.onEvent
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Redemption: when a hit leaves you alive below 10% of your Hitpoints, you're healed a quarter of
 * your Prayer level, your Prayer points drop to 0 and your prayers switch off.
 */
class RedemptionScript : PluginScript() {
    override fun ScriptContext.startup() {
        onEvent<PlayerHitEvents.Impact> { redeem(player) }
    }

    private fun redeem(player: Player) {
        if (player.vars["varbit.prayer_redemption"] == 0) return
        val hp = player.hitpoints
        if (hp <= 0 || hp * 10 >= player.baseHitpointsLvl) return
        player.statHeal("stat.hitpoints", constant = player.basePrayerLvl / 4, percent = 0)
        player.statSub("stat.prayer", constant = player.prayerLvl, percent = 0)
        player.disablePrayers()
        player.spotanim("spotanim.redemption_spotanim")
        player.soundSynth("synth.redemption_heal")
    }
}
