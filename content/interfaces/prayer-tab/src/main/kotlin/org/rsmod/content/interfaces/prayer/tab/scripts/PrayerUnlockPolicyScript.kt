package org.rsmod.content.interfaces.prayer.tab.scripts

import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.server.config.ServerConfig
import org.rsmod.content.interfaces.prayer.tab.PrayerRepository
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * With quest requirements set to `assume-completed`, prayers locked behind a quest or an unlock
 * (Piety, Chivalry, Rigour, Augury, Zeal, ...) are unlocked on login, so the prayer book matches a
 * completed account.
 */
class PrayerUnlockPolicyScript
@Inject
constructor(private val config: ServerConfig, private val repo: PrayerRepository) : PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerLogin { if (config.gameplay.questRequirements.mode == ASSUME_COMPLETED) unlockAll(player) }
    }

    private fun unlockAll(player: Player) {
        for (prayer in repo.prayerList) {
            val varbit = prayer.unlocked ?: continue
            val name = RSCM.getReverseMapping(RSCMType.VARBIT, varbit.id)
            if (player.vars[varbit] < prayer.unlockState) VarPlayerIntMapSetter.set(player, name, prayer.unlockState)
        }
        for (name in EXTRA_UNLOCKS) {
            if (RSCM.getRSCMOrNull(name, RSCMType.VARBIT) != null) VarPlayerIntMapSetter.set(player, name, 1)
        }
    }

    private companion object {
        const val ASSUME_COMPLETED = "assume-completed"
        val EXTRA_UNLOCKS = listOf("varbit.prayer_zeal_unlocked")
    }
}
