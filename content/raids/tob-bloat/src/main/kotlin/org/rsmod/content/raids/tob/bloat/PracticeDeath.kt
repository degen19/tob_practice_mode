package org.rsmod.content.raids.tob.bloat

import org.rsmod.api.player.PracticeSettings
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.death.PlayerDeathOverrideHook
import org.rsmod.api.player.deathResetTimers
import org.rsmod.api.player.disablePrayers
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.statRestore
import org.rsmod.api.player.stat.statRestoreAll
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.config.constants
import org.rsmod.game.entity.Player

/**
 * Dying in a practice room: the death animation, then you're back outside the east barrier (by the
 * supply chest) with the setup you came in with (worn and inventory), stats, special and run restored. Bots are respawned the same way. When nobody is
 * left alive inside, the room resets (Bloat back to full and patrolling, the bots back outside).
 */
class PracticeDeath @Inject constructor(private val rooms: BloatRooms) : PlayerDeathOverrideHook {
    private var Player.specialEnergy by intVarp("varp.sa_energy")

    override suspend fun ProtectedAccess.override(): Boolean {
        if (PracticeSettings.noDeaths) {
            // deaths off: the hit still lands, they just stay on 1 hitpoint
            player.statMap.setCurrentLevel("stat.hitpoints", 1.toByte())
            return true
        }
        val room = rooms.forPlayer(player) ?: return false
        stopAction()
        combatClearQueue()
        player.clearQueue("queue.hit")
        delay(2)
        anim("seq.human_death")
        delay(4)
        combatClearQueue()
        player.clearQueue("queue.hit")
        resetAnim()
        player.disablePrayers()
        player.deathResetTimers()
        player.statRestoreAll(ServerCacheManager.getStats().values.map { RSCM.getReverseMapping(RSCMType.STAT, it.id) })
        val restored = rooms.restoreSetup(player)
        player.specialEnergy = MAX_SPECIAL
        player.runEnergy = constants.run_max_energy
        player.rebuildAppearance()
        telejump(room.tile(OUT_X, OUT_Y), TeleportType.Exempt)
        mes(if (restored) "You died. You're back outside with the setup you went in with." else "You died. You're back outside the room with your items.")
        rooms.afterDeath(room)
        return true
    }

    private companion object {
        const val OUT_X = 25
        const val OUT_Y = 15
        const val MAX_SPECIAL = 1000
    }
}
