package org.rsmod.content.other.consumables.potion

import dev.openrune.types.ItemServerType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.game.inv.Inventory

/** A potion's Empty: its empty vial takes the same slot. False if [slot] no longer holds [potion]. */
internal fun ProtectedAccess.emptyPotion(inv: Inventory, slot: Int, potion: ItemServerType, vial: ItemServerType): Boolean {
    if (inv[slot]?.id != potion.id) return false
    if (invReplaceSlot(inv = inv, slot = slot, count = 1, replacement = vial).failure) return false
    player.mes(EMPTY_MESSAGE)
    return true
}

internal const val EMPTY_MESSAGE = "You empty the vial."
