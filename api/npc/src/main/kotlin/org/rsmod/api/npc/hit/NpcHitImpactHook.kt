package org.rsmod.api.npc.hit

import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit

/**
 * Runs when a hit from [source] has come off the npc's hitpoints, with the damage actually dealt.
 * For passive effects of the attacker's gear that act on the damage dealt (e.g. the Amulet of blood
 * fury healing part of a melee hit).
 */
public fun interface NpcHitImpactHook {
    public fun impact(npc: Npc, source: Player, hit: Hit)
}
