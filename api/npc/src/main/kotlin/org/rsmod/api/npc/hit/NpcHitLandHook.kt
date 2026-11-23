package org.rsmod.api.npc.hit

import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.Hit

/**
 * Changes a hit's damage on the tick it lands, before it comes off the npc's hitpoints. For rules that
 * depend on the npc's state when the hit lands rather than when it was queued (e.g. Pestilent Bloat
 * halving damage except while he is down). Returns the damage to deal.
 */
public fun interface NpcHitLandHook {
    public fun land(npc: Npc, hit: Hit): Int

    /** Whether [hit] heals the npc instead of hurting it (it shows as a heal hitsplat). */
    public fun heals(npc: Npc, hit: Hit): Boolean = false
}
