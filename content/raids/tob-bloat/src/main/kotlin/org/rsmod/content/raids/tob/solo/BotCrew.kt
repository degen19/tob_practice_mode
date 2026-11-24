package org.rsmod.content.raids.tob.solo

import org.rsmod.map.CoordGrid

/** A bot team playing one room: the script ticks it, and it says where its bots start and when the run is over. */
interface BotCrew {
    val room: SoloRoom

    /** Where each bot stands (in order) on the room's first tick. */
    fun starts(): List<CoordGrid>

    fun tick()

    /** Anything a bot does before the room starts (outside it, as a team would), after it's potted up. */
    fun prepare(bot: org.rsmod.game.entity.Player) {}

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
