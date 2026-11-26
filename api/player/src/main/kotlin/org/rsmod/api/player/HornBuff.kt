package org.rsmod.api.player

import java.util.WeakHashMap
import org.rsmod.game.entity.Player

/**
 * The Soulflame horn's Entice: a player's next melee attack before it runs out never misses its first accuracy roll.
 * Claw specials take it themselves and make their second roll the guaranteed one.
 */
public object HornBuff {
    private val until = WeakHashMap<Player, Int>()

    public fun give(player: Player, toCycle: Int) {
        until[player] = toCycle
    }

    public fun holds(player: Player): Boolean = (until[player] ?: -1) >= player.currentMapClock

    /** True, and spent, when [player] still holds the buff. */
    public fun consume(player: Player): Boolean {
        val to = until.remove(player) ?: return false
        return to >= player.currentMapClock
    }

    public fun clear(player: Player) {
        until.remove(player)
    }
}
