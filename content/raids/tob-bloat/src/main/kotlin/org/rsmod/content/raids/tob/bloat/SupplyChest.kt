package org.rsmod.content.raids.tob.bloat

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.combat.commons.magic.Spellbook
import org.rsmod.api.config.constants
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.XpSource
import org.rsmod.api.player.stat.statAdvance
import org.rsmod.api.player.stat.statRestoreAll
import org.rsmod.api.player.vars.intVarp
import dev.openrune.definition.type.widget.IfEvent
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.aconverted.interf.IfButtonOp
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.stopInvTransmit
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.script.onIfClose
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onOpLoc1
import org.rsmod.content.raids.tob.bloat.SupplyCatalog.Tab
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.utils.bits.getBits
import org.rsmod.utils.bits.withBits
import org.rsmod.api.spells.autocast.MagicSpellbookManager
import org.rsmod.game.entity.Player
import org.rsmod.game.stat.PlayerSkillXPTable
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The supply chest outside each practice room: search every item in the game and take it, switch
 * spellbook, restore, or max your stats - set yourself up without commands.
 */
class SupplyChest @Inject constructor(private val spellbooks: MagicSpellbookManager, private val solo: org.rsmod.content.raids.tob.solo.SoloRaids) : PluginScript() {
    private var Player.specialEnergy by intVarp("varp.sa_energy")

    private val tabs = java.util.WeakHashMap<Player, Tab>()

    private val Player.supplyInv: Inventory
        get() = invMap.getOrPut(SUPPLY_INV)

    override fun ScriptContext.startup() {
        onOpLoc1(CHEST) { open() }
        for (tab in Tab.entries) onIfModalButton("component.tob_supplies:tab${tab.ordinal}") { showTab(tab) }
        onIfModalButton("component.tob_supplies:grid") { take(it.comsub, it.op) }
        onIfClose(SUPPLY_IF) {
            tabs.remove(player)
            player.supplyInv.fillNulls()
            player.stopInvTransmit(player.supplyInv)
        }
    }

    private suspend fun ProtectedAccess.open() {
        val choice = menu("Supplies", *OPTIONS.toTypedArray())
        // close the list first: the item search below must not be tied to it (its closing would end the search)
        ifClose()
        when (choice) {
            13 -> goToRoom()
            14 -> solo.switchMode(player)
            0 -> openSupplies(Tab.Runes)
            1 -> openSupplies(Tab.Gear)
            2 -> openSupplies(Tab.Ammo)
            3 -> openSupplies(Tab.Supplies)
            4 -> takeItems(tradeables = true)
            5 -> takeItems(tradeables = false)
            6 -> book(Spellbook.Lunars)
            7 -> book(Spellbook.Standard)
            8 -> book(Spellbook.Ancients)
            9 -> book(Spellbook.Arceuus)
            10 -> restore()
            11 -> maxStats()
            12 -> clearInventory()
        }
    }

    /** The supplies window: a tab of items, each with Take-1 / Take-5 / Take-10 / Take-X. */
    private fun ProtectedAccess.openSupplies(tab: Tab) {
        player.supplyInv.fillNulls()
        ifOpenMainModal(SUPPLY_IF)
        ifSetEvents("component.tob_supplies:grid", 0 until SUPPLY_SLOTS, IfEvent.Op1, IfEvent.Op2, IfEvent.Op3, IfEvent.Op4)
        invTransmit(player.supplyInv)
        showTab(tab)
    }

    private fun ProtectedAccess.showTab(tab: Tab) {
        tabs[player] = tab
        val inv = player.supplyInv
        inv.fillNulls()
        for ((slot, item) in SupplyCatalog.items(tab).take(SUPPLY_SLOTS).withIndex()) inv[slot] = InvObj(item)
        player.runClientScript("clientscript.tob_supplies_tabs".asRSCM(RSCMType.CLIENTSCRIPT), tab.ordinal)
    }

    private suspend fun ProtectedAccess.take(slot: Int, op: IfButtonOp) {
        val tab = tabs[player] ?: return
        val item = SupplyCatalog.items(tab).getOrNull(slot) ?: return
        val count =
            when (op) {
                IfButtonOp.Op1 -> 1
                IfButtonOp.Op2 -> 5
                IfButtonOp.Op3 -> 10
                IfButtonOp.Op4 -> countDialog("How many?")
                else -> return
            }
        if (count <= 0) return
        val added = player.invAdd(player.inv, item.id, count, strict = false).completed()
        if (added <= 0) player.mes("You don't have enough inventory space.")
        fillCharges(player.inv)
    }

    /** Powered staves come out of the chest fully charged, as the sim plays them. */
    private fun fillCharges(inv: Inventory) {
        for (slot in inv.indices) {
            val obj = inv[slot] ?: continue
            val (internal, full) = CHARGES[RSCM.getReverseMapping(RSCMType.OBJ, obj.id)] ?: continue
            val varobj = ServerCacheManager.getVarObj(internal.asRSCM(RSCMType.VAROBJ)) ?: continue
            if (obj.vars.getBits(varobj.bits) == 0) inv[slot] = obj.copy(vars = obj.vars.withBits(varobj.bits, full))
        }
    }

    /**
     * Search by name, then how many. [tradeables]: the Grand Exchange list (one entry per item);
     * otherwise every item in the game, untradeables and the game's alternate copies included.
     */
    private suspend fun ProtectedAccess.takeItems(tradeables: Boolean) {
        while (true) {
            val item = objDialog("Search for an item to take:", stockMarketRestriction = tradeables, showLastSearched = true)
            val count = countDialog("How many?").coerceAtLeast(1)
            val added = player.invAdd(player.inv, item.id, count, strict = false).completed()
            if (added <= 0) player.mes("You don't have enough inventory space.") else player.mes("Took ${item.name} x $added.")
        }
    }

    /** The Theatre's rooms: go straight to any of them (a fresh room, you outside its barrier). */
    private suspend fun ProtectedAccess.goToRoom() {
        val rooms = org.rsmod.content.raids.tob.solo.TobRoom.entries
        val pick = menu("Go to which room?", *rooms.map { it.label }.toTypedArray())
        ifClose()
        val room = rooms.getOrNull(pick) ?: return
        val travel = solo.travel ?: return
        travel(player, room, this)
    }

    private fun ProtectedAccess.book(book: Spellbook) {
        spellbooks.setSpellbook(player, book)
        player.mes("Your spellbook is now ${book.name}.")
    }

    private fun ProtectedAccess.restore() {
        player.statRestoreAll(allStats())
        player.specialEnergy = MAX_SPECIAL
        player.runEnergy = constants.run_max_energy
        player.mes("Stats, prayer, special attack and run energy restored.")
    }

    private fun ProtectedAccess.maxStats() {
        val xp = PlayerSkillXPTable.getXPFromLevel(99)
        for (stat in allStats()) {
            val delta = xp - player.statMap.getXP(stat)
            if (delta > 0) player.statAdvance(stat, delta.toDouble(), rate = 1.0, source = XpSource.Manual)
        }
        player.statRestoreAll(allStats())
        player.mes("All stats set to 99.")
    }

    private fun ProtectedAccess.clearInventory() {
        for (slot in 0 until player.inv.size) player.inv[slot] = null
        player.mes("Inventory cleared.")
    }

    private fun allStats(): List<String> =
        ServerCacheManager.getStats().values.map { RSCM.getReverseMapping(RSCMType.STAT, it.id) }

    private companion object {
        const val CHEST = "loc.tob_midway_chest_closed"
        const val MAX_SPECIAL = 1000
        const val SUPPLY_IF = "interface.tob_supplies"
        const val SUPPLY_INV = "inv.tob_supplies"
        const val SUPPLY_SLOTS = 200
        val OPTIONS =
            listOf(
                "Runes",
                "Gear",
                "Ammo",
                "Supplies",
                "Search items",
                "Search all items (untradeables too)",
                "Lunar spellbook",
                "Standard spellbook",
                "Ancient spellbook",
                "Arceuus spellbook",
                "Restore stats, prayer, special and run",
                "Set all stats to 99",
                "Clear inventory",
                "Go to another room",
                "Switch Entry / Normal mode",
            )
    }
}

private val CHARGES =
    mapOf(
        "obj.sanguinesti_staff" to ("varobj.tumeken_charges" to 20_000),
        "obj.tumekens_shadow" to ("varobj.tumeken_charges" to 20_000),
        "obj.eye_of_ayak" to ("varobj.charges_65535" to 50_000),
        "obj.zoryas_tome" to ("varobj.charges_65535" to 20_000),
    )
