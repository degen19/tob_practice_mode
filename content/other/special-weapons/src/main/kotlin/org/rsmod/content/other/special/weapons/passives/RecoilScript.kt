package org.rsmod.content.other.special.weapons.passives

import jakarta.inject.Inject
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.ring
import org.rsmod.api.script.onPlayerHit
import org.rsmod.game.entity.NpcList
import org.rsmod.game.hit.HitType
import org.rsmod.game.inv.isType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Ring of recoil and the recoil-charged Rings of suffering ((r), (ri) and their Soul Wars and
 * PvP Arena versions): when an npc damages the wearer, 10% of the damage (rounded down) plus 1 is
 * dealt back to that npc as a typeless hit. A plain Ring of suffering or Ring of suffering (i) has no
 * recoil.
 *
 * The 40-damage limit of the Ring of recoil and the recoil charges of the Ring of suffering are not
 * tracked on this server: the rings never run out.
 */
class RecoilScript
@Inject
constructor(private val npcList: NpcList, private val npcHitModifier: NpcHitModifier) :
    PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerHit { recoil() }
    }

    private fun PlayerHitEvents.Impact.recoil() {
        if (!hit.isFromNpc || hit.damage <= 0) {
            return
        }
        val ring = player.ring
        if (RECOIL_RINGS.none { ring.isType(it) }) {
            return
        }
        val attacker = hit.resolveNpcSource(npcList) ?: return
        if (attacker.hitpoints <= 0) {
            return
        }
        val damage = hit.damage / 10 + 1
        attacker.queueHit(player, 1, HitType.Typeless, damage, npcHitModifier)
    }

    private companion object {
        val RECOIL_RINGS =
            listOf(
                "obj.ring_of_recoil",
                "obj.zenyte_ring_enchanted_recoil",
                "obj.nzone_zenyte_ring_enchanted_recoil",
                "obj.sw_zenyte_ring_enchanted_recoil",
                "obj.pvpa_zenyte_ring_enchanted_recoil",
            )
    }
}
