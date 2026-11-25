package org.rsmod.content.skills.magic.lunar

import org.rsmod.game.entity.Player

/**
 * A Lunar stall: the cast tick's own action still goes off, then [locked] ticks are locked - no attacks, hits on the
 * caster held till it ends, and in combat the path held - and the tick after is actionable again, the held attack
 * going off on the same target. Spellbook Swap and Humidify lock 3 (a 4-tick cycle), NPC Contact 7.
 */
internal fun Player.stall(locked: Int) {
    afterInteraction += {
        delay(locked + 1)
        pathHeldUntil = currentMapClock + locked + 1
    }
}

internal const val SHORT_STALL = 3
internal const val CONTACT_STALL = 7
