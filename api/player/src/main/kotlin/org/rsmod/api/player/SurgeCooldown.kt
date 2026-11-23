package org.rsmod.api.player

import org.rsmod.api.attr.AttributeKey
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.game.entity.Player

/**
 * The surge potion's five-minute cooldown: a player timer while it runs, shown on the buff bar through
 * `varbit.surge_potion_timer` (tens of ticks). Raids pause it between rooms ([pause] / [resume]) and clear it
 * on the way in and out ([clear]).
 */
public object SurgeCooldown {
    public const val TIMER: String = "timer.surge_potion_cooldown"
    public const val TICKS: Int = 500

    private const val VARBIT = "varbit.surge_potion_timer"
    private const val BUFF_BAR_START = 5931
    private const val BUFF_STRUCT = 4735
    private const val UNIT = 10

    private val PAUSED: AttributeKey<Int> = AttributeKey(resetOnDeath = false, temp = true)

    public fun remaining(player: Player): Int =
        player.attr[PAUSED] ?: player.timerMap.remainingCycles(TIMER, player.currentMapClock)

    public fun start(player: Player): Unit = run(player, TICKS)

    /** Stops the clock where it is (a raid room done). */
    public fun pause(player: Player) {
        if (player.attr[PAUSED] != null) return
        val left = player.timerMap.remainingCycles(TIMER, player.currentMapClock)
        if (left <= 0) return
        player.clearTimer(TIMER)
        player.attr[PAUSED] = left
    }

    /** Starts the clock again from where [pause] left it (the next room begins). */
    public fun resume(player: Player) {
        val left = player.attr[PAUSED] ?: return
        player.attr.remove(PAUSED)
        run(player, left)
    }

    /** No cooldown at all (going into or out of a raid). */
    public fun clear(player: Player) {
        player.attr.remove(PAUSED)
        player.clearTimer(TIMER)
        VarPlayerIntMapSetter.set(player, VARBIT, 0)
    }

    private fun run(player: Player, ticks: Int) {
        player.clearTimer(TIMER)
        player.timer(TIMER, ticks)
        VarPlayerIntMapSetter.set(player, VARBIT, 0)
        VarPlayerIntMapSetter.set(player, VARBIT, (ticks + UNIT - 1) / UNIT)
        player.runClientScript(BUFF_BAR_START, BUFF_STRUCT, player.currentMapClock.coerceAtLeast(1))
    }
}
