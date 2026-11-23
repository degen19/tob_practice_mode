package org.rsmod.content.raids.tob.bloat.maiden

import jakarta.inject.Singleton
import org.rsmod.game.entity.Npc

/** The live Maiden rooms open now (for the hit hook: which room an NPC is in). */
@Singleton
class MaidenRooms {
    val rooms = ArrayList<MaidenLiveRoom>()

    fun forNpc(npc: Npc): MaidenLiveRoom? = rooms.firstOrNull { it.has(npc) }
}
