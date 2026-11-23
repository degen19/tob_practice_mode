package org.rsmod.content.other.devrestart

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.io.File
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.script.onEvent
import org.rsmod.api.utils.system.SafeServiceExit
import org.rsmod.game.MapClock
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Development restarts without anyone at the console: when `.data/restart.flag` appears the server
 * deletes it and shuts down cleanly; `server.bat` (or any loop around `gradlew run`) then rebuilds
 * and starts it again, picking up new code.
 */
class DevRestartScript @Inject constructor(private val mapClock: MapClock) : PluginScript() {
    private val logger = InlineLogger()
    private val flag = File(".data/restart.flag")
    private var requested = false

    override fun ScriptContext.startup() {
        if (flag.exists()) flag.delete()
        onEvent<GameLifecycle.StartCycle> {
            if (requested || mapClock.cycle % CHECK_TICKS != 0 || !flag.exists()) return@onEvent
            requested = true
            flag.delete()
            logger.info { "Restart requested (.data/restart.flag): shutting down to rebuild" }
            SafeServiceExit.terminate(0)
        }
    }

    private companion object {
        const val CHECK_TICKS = 5
    }
}
