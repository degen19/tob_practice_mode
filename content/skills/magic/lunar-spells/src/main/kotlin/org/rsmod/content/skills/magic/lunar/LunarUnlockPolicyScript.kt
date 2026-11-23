package org.rsmod.content.skills.magic.lunar

import jakarta.inject.Inject
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.server.config.ServerConfig
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * With quest requirements set to `assume-completed`, the Lunar spells' other unlocks are treated as
 * done too: the Hard Fremennik Diary (Tan Leather, Recharge Dragonstone) and Baba Yaga's Ourania
 * Teleport.
 */
class LunarUnlockPolicyScript @Inject constructor(private val config: ServerConfig) : PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerLogin {
            if (config.gameplay.questRequirements.mode != "assume-completed") return@onPlayerLogin
            for (name in UNLOCKS) VarPlayerIntMapSetter.set(player, name, 1)
        }
    }

    private companion object {
        val UNLOCKS = listOf("varbit.fremennik_diary_hard_complete", "varbit.rc_zmi_lunar_spell")
    }
}
