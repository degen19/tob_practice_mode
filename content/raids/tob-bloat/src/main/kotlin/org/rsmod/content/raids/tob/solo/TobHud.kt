package org.rsmod.content.raids.tob.solo

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.ui.ifCloseOverlay
import org.rsmod.api.player.ui.ifOpenFullOverlay
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player

/**
 * The Theatre's own HUD (`tob_hud`), as the game drives it: the party's orbs top left (each one's first letter, "Me"
 * on your own, filled by hitpoints; none of them yours while you only watch), the room's progress bar top
 * centre, and the screen-wide fades (the red passage between rooms with the next room's name).
 *
 * The bar's type is also what client plugins read as the room's state: 0 while no fight is on, 1 for a fight, 2 while
 * the boss can't be hurt (Sotetseg's maze, Xarpus feeding).
 */
object TobHud {
    data class Bar(val type: Int, val value: Int, val max: Int)

    private var Player.partyStatus by intVarBit("varbit.tob_client_partystatus")
    private var Player.partySlot by intVarBit("varbit.tob_client_partyslot")
    private val ORBS = listOf("varbit.tob_client_p0", "varbit.tob_client_p1", "varbit.tob_client_p2", "varbit.tob_client_p3", "varbit.tob_client_p4")
    private val named = java.util.WeakHashMap<Player, List<String>>()
    private var Player.barType by intVarBit("varbit.tob_client_waveprogress_type")
    private var Player.barValue by intVarBit("varbit.tob_client_waveprogress_val")
    private var Player.barMax by intVarBit("varbit.tob_client_waveprogress_max")

    fun open(player: Player, eventBus: EventBus) {
        if (!player.ui.containsOverlay(HUD)) player.ifOpenFullOverlay(HUD, eventBus)
        if (player.partyStatus != IN_RAID) player.partyStatus = IN_RAID
        named.remove(player)
        update(player, null, listOf(player))
    }

    fun close(player: Player, eventBus: EventBus) {
        update(player, null, emptyList())
        player.partyStatus = 0
        player.partySlot = 0
        named.remove(player)
        if (player.ui.containsOverlay(HUD)) player.ifCloseOverlay(HUD, eventBus)
    }

    /** The orbs of [party] (in its order) and the bar, written only when they change. */
    fun update(player: Player, bar: Bar?, party: List<Player>) {
        val members = party.take(ORBS.size)
        val slot = members.indexOf(player) + 1
        if (player.partySlot != slot) player.partySlot = slot
        val names = members.map { it.displayName }
        if (named[player] != names) {
            named[player] = names
            val padded = names + List(ORBS.size - names.size) { "" }
            player.runClientScript(NAMES.asRSCM(RSCMType.CLIENTSCRIPT), padded[0], padded[1], padded[2], padded[3], padded[4])
        }
        for ((i, varbit) in ORBS.withIndex()) {
            val orb = members.getOrNull(i)?.let(::orb) ?: 0
            if (player.vars[varbit] != orb) org.rsmod.api.player.vars.VarPlayerIntMapSetter.set(player, varbit, orb)
        }
        val b = bar ?: NONE
        if (player.barMax != b.max) player.barMax = b.max
        if (player.barValue != b.value) player.barValue = b.value
        if (player.barType != b.type) player.barType = b.type
    }

    /** An orb: empty to full red with hitpoints (an overheal shows full), red when dead, dark when not here. */
    private fun orb(p: Player): Int = when {
        !p.isSlotAssigned -> ORB_GONE
        p.hitpoints <= 0 -> ORB_DEAD
        else -> 1 + minOf(ORB_FULL, (p.hitpoints * ORB_FULL + p.baseHitpointsLvl - 1) / p.baseHitpointsLvl.coerceAtLeast(1))
    }

    /** A bar of [hp] out of [max], kept to the HUD's ten bits as thousandths. */
    fun bar(type: Int, hp: Int, max: Int): Bar {
        val m = max.coerceAtLeast(1)
        val v = (hp.coerceIn(0, m).toLong() * SCALE / m).toInt()
        return Bar(type, if (hp > 0) v.coerceAtLeast(1) else 0, SCALE)
    }

    /** The passage: the screen goes to dark red with the portal turning in it. */
    fun portal(player: Player) {
        player.runClientScript(PORTAL.asRSCM(RSCMType.CLIENTSCRIPT), "")
    }

    /** Arriving: the red lifts and the room's name comes up over the screen. */
    fun arrive(player: Player, title: String) {
        player.runClientScript(FADE.asRSCM(RSCMType.CLIENTSCRIPT), CLEAR, 0, title)
    }

    const val FIGHT = 1
    const val IMMUNE = 2

    private val NONE = Bar(0, 0, 0)
    private const val HUD = "interface.tob_hud"
    private const val NAMES = "clientscript.[clientscript,tob_hud_statusnames]"
    private const val PORTAL = "clientscript.[clientscript,tob_hud_portal]"
    private const val FADE = "clientscript.[clientscript,tob_hud_fade]"
    private const val IN_RAID = 2
    private const val ORB_FULL = 26
    private const val ORB_DEAD = 30
    private const val ORB_GONE = 31
    private const val SCALE = 1000
    private const val CLEAR = 255
}
