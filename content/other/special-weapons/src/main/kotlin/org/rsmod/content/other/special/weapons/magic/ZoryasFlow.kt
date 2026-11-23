package org.rsmod.content.other.special.weapons.magic

import kotlin.math.min
import org.rsmod.api.attr.AttributeKey
import org.rsmod.api.player.vars.intVarp
import org.rsmod.game.entity.Player

/**
 * Zorya's flow, the tome's special attack: a hit stores three buffed tome attacks (+150% accuracy, +60% damage, every
 * 2 ticks), up to five. They're kept for 30 seconds from the last one used and through weapon switches; the count shows
 * on the buff bar through the tome's stacks varp.
 */
object ZoryasFlow {
    const val ACCURACY_PER_BOOST = 1.5
    const val DAMAGE_PER_BOOST = 0.6
    const val BUFFED_DELAY = 2

    fun stacks(player: Player): Int {
        val stacks = player.flowStacks
        if (stacks > 0 && player.currentMapClock >= player.attr.getOrDefault(EXPIRES, 0)) {
            player.flowStacks = 0
            return 0
        }
        return stacks
    }

    /** Uses one stored buffed attack; false if none are left. */
    fun take(player: Player): Boolean {
        val stacks = stacks(player)
        if (stacks <= 0) return false
        player.flowStacks = stacks - 1
        player.attr[EXPIRES] = player.currentMapClock + EXPIRY_TICKS
        return true
    }

    /** A Zorya's flow that hit: three more buffed attacks, up to five stored. */
    fun add(player: Player) {
        player.flowStacks = min(MAX_STACKS, stacks(player) + PER_SPEC)
        player.attr[EXPIRES] = player.currentMapClock + EXPIRY_TICKS
    }

    private const val PER_SPEC = 3
    private const val MAX_STACKS = 5
    private const val EXPIRY_TICKS = 50

    private var Player.flowStacks by intVarp("varp.zoryas_tome_stacks")

    private val EXPIRES: AttributeKey<Int> = AttributeKey(resetOnDeath = true, temp = true)
}
