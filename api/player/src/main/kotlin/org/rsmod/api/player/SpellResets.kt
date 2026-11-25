package org.rsmod.api.player

import org.rsmod.game.entity.Player

/**
 * Puts a player's running spell effects and cooldowns (Vengeance, Death Charge, a thrall, ...) back to none, as when a
 * practice room starts over. Each spell's script registers what it keeps.
 */
public object SpellResets {
    private val hooks = ArrayList<(Player) -> Unit>()

    public fun register(hook: (Player) -> Unit) {
        hooks += hook
    }

    public fun reset(player: Player) {
        for (hook in hooks) hook(player)
    }
}
