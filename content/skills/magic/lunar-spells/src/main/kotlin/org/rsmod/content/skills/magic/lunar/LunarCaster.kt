package org.rsmod.content.skills.magic.lunar

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import org.rsmod.api.combat.commons.magic.MagicSpell
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.combat.manager.MagicRuneManager.Companion.isFailure
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.stat.baseDefenceLvl
import org.rsmod.api.player.stat.statAdvance
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList

internal var Player.acceptAid by boolVarBit("varbit.option_acceptaid")

/**
 * Shared casting rules for Lunar spells: the spell is looked up by its spell obj, the caster also
 * needs 40 Defence (the book's base requirement), and a successful cast pays the runes and gives
 * the spell's cast experience.
 */
class LunarCaster
@Inject
constructor(
    private val spells: MagicSpellRegistry,
    private val runes: MagicRuneManager,
    private val players: PlayerList,
) {
    fun spell(obj: String): MagicSpell? {
        val type = ServerCacheManager.getItem(obj.asRSCM(RSCMType.OBJ)) ?: return null
        return spells.getObjSpell(type)
    }

    /** Checks every requirement without using anything up. */
    fun canCast(player: Player, spell: MagicSpell): Boolean {
        if (!meetsDefence(player, spell)) {
            return false
        }
        return runes.canCastSpell(player, spell)
    }

    /** Uses up the runes and gives the cast experience; false when the cast is refused. */
    fun cast(player: Player, spell: MagicSpell): Boolean {
        if (!meetsDefence(player, spell)) {
            return false
        }
        if (runes.attemptCast(player, spell).isFailure()) {
            return false
        }
        player.statAdvance("stat.magic", spell.castXp)
        return true
    }

    /** Other players standing within [radius] tiles of [centre] (a radius of 1 is a 3x3 area). */
    fun playersAround(centre: Player, radius: Int): List<Player> =
        players.filter { other ->
            other !== centre &&
                other.coords.level == centre.coords.level &&
                max(abs(other.coords.x - centre.coords.x), abs(other.coords.z - centre.coords.z)) <=
                    radius
        }

    private fun meetsDefence(player: Player, spell: MagicSpell): Boolean {
        if (spell.spellbook != Spellbook.Lunars || player.baseDefenceLvl >= DEFENCE_REQ) {
            return true
        }
        player.mes("You need a Defence level of $DEFENCE_REQ to cast Lunar spells.")
        return false
    }

    private companion object {
        const val DEFENCE_REQ = 40
    }
}
