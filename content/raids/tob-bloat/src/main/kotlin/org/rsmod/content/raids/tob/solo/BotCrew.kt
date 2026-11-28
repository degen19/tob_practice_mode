package org.rsmod.content.raids.tob.solo

import org.rsmod.map.CoordGrid

/** A bot team playing one room: the script ticks it, and it says where its bots start and when the run is over. */
interface BotCrew {
    val room: SoloRoom

    /** Where each bot stands (in order) on the room's first tick; for a crew that [walksIn], outside the barrier. */
    fun starts(): List<CoordGrid>

    /** The bots wait outside and pass the barrier as players do (else they're put on [starts] as the room begins). */
    val walksIn: Boolean
        get() = false

    fun tick()

    /** Whether its bots keep the damage they take (else they're topped back up every tick). */
    val keepsHp: Boolean
        get() = false

    /** Anything a bot does before the room starts (outside it, as a team would), after it's potted up. */
    fun prepare(bot: org.rsmod.game.entity.Player) {}

    /** Before the room starts, outside: true when the crew picks its own moment to go in (it clicks the gates itself). */
    fun enter(): Boolean = false

    /** The room tick the run ended on, -1 while it's still going. */
    fun doneAt(): Int

    /** One line on how it went, for the log and anyone watching. */
    fun summary(): String
}

/** A room tick count as the in-game timer shows it (m:ss.d). */
fun raidTime(ticks: Int): String {
    val cs = ticks * 60
    return "%d:%02d.%d".format(cs / 6000, cs / 100 % 60, cs / 10 % 10)
}
