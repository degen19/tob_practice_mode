package org.rsmod.content.skills.magic.lunar

import dev.openrune.types.aconverted.interf.IfButtonOp
import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.spells.autocast.MagicSpellbookManager
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Player
import org.rsmod.game.queue.WorldQueueList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private var Player.swapBook by intVarBit("varbit.spellbook_swap_book")

/**
 * Spellbook Swap (Lunar, level 96): changes to the Standard, Ancient or Arceuus spellbook for one
 * cast; the cast (see `MagicRuneManager.attemptCast`) or two minutes puts you back on the Lunars.
 * Left-click asks which book; the right-click options pick one straight away. The cast stalls the
 * caster (see [stall]).
 */
class SpellbookSwapScript
@Inject
constructor(
    private val caster: LunarCaster,
    private val books: MagicSpellbookManager,
    private val queues: WorldQueueList,
    private val clock: MapClock,
) : PluginScript() {
    private val swappedAt = WeakHashMap<Player, Int>()

    override fun ScriptContext.startup() {
        onIfOverlayButton("component.magic_spellbook:spellbook_swap") { cast(it.op) }
        onPlayerLogin { if (player.swapBook != 0) revert(player) }
    }

    private suspend fun ProtectedAccess.cast(op: IfButtonOp) {
        if (books.activeSpellbook(player) != Spellbook.Lunars) {
            return
        }
        val book =
            when (op) {
                IfButtonOp.Op2 -> Spellbook.Standard
                IfButtonOp.Op3 -> Spellbook.Ancients
                IfButtonOp.Op4 -> Spellbook.Arceuus
                else ->
                    when (menu("Select a spellbook", "Standard", "Ancient", "Arceuus", "Cancel")) {
                        0 -> Spellbook.Standard
                        1 -> Spellbook.Ancients
                        2 -> Spellbook.Arceuus
                        else -> return
                    }
            }
        val spell = caster.spell(SPELL_OBJ) ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim(CAST_ANIM)
        spotanim(CAST_SPOTANIM, height = 92)
        books.setSpellbook(player, book)
        player.swapBook = 1
        val stamp = clock.cycle
        swappedAt[player] = stamp
        val caster = player
        queues.add(DURATION_TICKS) {
            if (swappedAt[caster] == stamp && caster.swapBook != 0) revert(caster)
        }
        player.stall(SHORT_STALL)
    }

    private fun revert(player: Player) {
        player.swapBook = 0
        swappedAt.remove(player)
        books.setSpellbook(player, Spellbook.Lunars)
    }

    private companion object {
        const val SPELL_OBJ = "obj.96_spellbook_swap"
        const val CAST_ANIM = "seq.dream_player_spellbook_swap"
        const val CAST_SPOTANIM = "spotanim.dream_spellbook_spotanim"
        const val DURATION_TICKS = 200
    }
}
