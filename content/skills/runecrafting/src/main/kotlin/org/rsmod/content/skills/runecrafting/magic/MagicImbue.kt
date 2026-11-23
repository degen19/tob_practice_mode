package org.rsmod.content.skills.runecrafting.magic

import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.content.skills.runecrafting.magicImbueActive
import org.rsmod.game.entity.Player

/**
 * Magic Imbue (Lunar, level 82): for 12.6 seconds (21 ticks) combination runes can be crafted
 * without the opposing talisman. A normal queue, so clicking the altar does not end it.
 */
object MagicImbue {
    const val DURATION_CYCLES = 21
    const val EXPIRE_QUEUE = "queue.runecraft_magic_imbue_expire"

    fun Player.isActive(): Boolean = magicImbueActive == 1

    fun ProtectedAccess.activate() {
        player.magicImbueActive = 1
        clearQueue(EXPIRE_QUEUE)
        queue(EXPIRE_QUEUE, DURATION_CYCLES)
        mes("You are imbued with the ability to bind combination runes without a talisman.")
        anim("seq.human_casting")
        spotanim("spotanim.quest_lunar_spellbook_magic_embue_spot_anim", height = 92)
    }

    fun ProtectedAccess.deactivate() {
        player.magicImbueActive = 0
        clearQueue(EXPIRE_QUEUE)
    }
}
