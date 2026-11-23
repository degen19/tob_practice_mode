@file:OptIn(UncheckedType::class)

package org.rsmod.api.player.worn

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.ItemServerType
import dev.openrune.types.util.UncheckedType
import dev.openrune.util.Wearpos
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.isType

/**
 * Dizana's quiver (charged or infinite, and the max cape made with it): a second ammunition slot,
 * kept in two varps the equipment tab draws from.
 *
 * - Fill (the extra slot's option when it's empty) moves the worn arrows or bolts into it; Swap
 *   exchanges it with the worn ammunition; Quiver-Remove takes it out.
 * - A ranged attack whose worn ammunition can't be fired uses the quiver's instead
 *   ([swapIn]/[swapOut] around the attack).
 */
public object DizanasQuiver {
    private const val AMMO = "varp.dizanas_quiver_temp_ammo"
    private const val AMOUNT = "varp.dizanas_quiver_temp_ammo_amount"
    private const val EMPTY = -1

    private val STORABLE = listOf("category.arrows", "category.crossbow_bolt", "category.ascension_bolt")

    private val QUIVERS =
        listOf(
            "obj.dizanas_quiver_charged",
            "obj.dizanas_quiver_charged_trouver",
            "obj.dizanas_quiver_infinite",
            "obj.dizanas_quiver_infinite_trouver",
            "obj.skillcape_max_dizanas",
            "obj.skillcape_max_dizanas_trouver",
        )

    public fun isWorn(player: Player): Boolean {
        val back = player.worn[Wearpos.Back.slot] ?: return false
        return QUIVERS.any { back.isType(it) }
    }

    public fun storedObj(player: Player): Int = if (storedCount(player) > 0) player.vars[AMMO] else EMPTY

    public fun storedCount(player: Player): Int = player.vars[AMOUNT]

    public fun store(player: Player, obj: Int, count: Int) {
        if (count <= 0) {
            VarPlayerIntMapSetter.set(player, AMMO, EMPTY)
            VarPlayerIntMapSetter.set(player, AMOUNT, 0)
        } else {
            VarPlayerIntMapSetter.set(player, AMMO, obj)
            VarPlayerIntMapSetter.set(player, AMOUNT, count)
        }
    }

    /** Puts what the quiver holds back in the inventory; false when there's no room. */
    public fun empty(player: Player): Boolean {
        val count = storedCount(player)
        if (count <= 0) {
            store(player, EMPTY, 0)
            return true
        }
        val added = player.invAdd(player.inv, storedObj(player), count, strict = true)
        if (!added.success) return false
        store(player, EMPTY, 0)
        return true
    }

    /**
     * Fill: the worn ammunition goes into the quiver (arrows and bolts only, and only onto the same
     * kind already there). Returns the message to show, or null when it worked.
     */
    public fun fill(player: Player): String? {
        if (!isWorn(player)) return null
        val worn = player.worn[Wearpos.Quiver.slot] ?: return "You have nothing in your worn quiver to fill your Dizana's Quiver with."
        val type = dev.openrune.ServerCacheManager.getItem(worn.id) ?: return null
        val storable = STORABLE.any { type.isCategory(dev.openrune.types.aconverted.CategoryType(it.asRSCM(dev.openrune.rscm.RSCMType.CATEGORY))) }
        if (!storable) return "Only arrows and bolts can be stored in Dizana's quiver."
        val held = storedObj(player)
        if (held != EMPTY && held != worn.id) return "Your quiver already holds different ammunition."
        val total = worn.count.toLong() + (if (held == worn.id) storedCount(player) else 0)
        store(player, worn.id, total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        player.worn[Wearpos.Quiver.slot] = null
        return null
    }

    /** Swap: the quiver's ammunition and the worn ammunition change places. */
    public fun swap(player: Player) {
        if (!isWorn(player)) return
        val worn = player.worn[Wearpos.Quiver.slot]
        val held = storedObj(player)
        val stored = if (held == EMPTY) null else InvObj(held, storedCount(player))
        exchange(player, worn, stored)
    }

    /**
     * Before a ranged attack: if the worn ammunition can't be fired by the weapon ([usable]) but
     * the quiver's can, the two swap places for the attack. Returns true when swapped - call
     * [swapOut] once the attack is done.
     */
    public fun swapIn(player: Player, usable: (InvObj?) -> Boolean): Boolean {
        if (!isWorn(player)) return false
        val held = storedObj(player)
        if (held == EMPTY) return false
        val worn = player.worn[Wearpos.Quiver.slot]
        if (usable(worn)) return false
        val quiver = InvObj(held, storedCount(player))
        if (!usable(quiver)) return false
        exchange(player, worn, quiver)
        return true
    }

    /** Undoes [swapIn]: what's left of the fired stack goes back into the quiver. */
    public fun swapOut(player: Player) {
        val fired = player.worn[Wearpos.Quiver.slot]
        val held = storedObj(player)
        val back = if (held == EMPTY) null else InvObj(held, storedCount(player))
        exchange(player, fired, back)
    }

    private fun exchange(player: Player, toQuiver: InvObj?, toWorn: InvObj?) {
        if (toQuiver == null) store(player, EMPTY, 0) else store(player, toQuiver.id, toQuiver.count)
        player.worn[Wearpos.Quiver.slot] = toWorn
    }
}
