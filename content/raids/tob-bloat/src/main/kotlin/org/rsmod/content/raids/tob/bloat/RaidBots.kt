package org.rsmod.content.raids.tob.bloat

import jakarta.inject.Inject
import jakarta.inject.Singleton
import net.rsprot.protocol.game.incoming.buttons.If3Button
import net.rsprot.protocol.game.incoming.locs.OpLocV2
import net.rsprot.protocol.util.CombinedId
import org.rsmod.api.net.rsprot.handlers.If3ButtonHandler
import org.rsmod.content.interfaces.prayer.tab.PrayerRepository
import net.rsprot.protocol.game.incoming.misc.user.MoveGameClick
import net.rsprot.protocol.game.incoming.npcs.OpNpcT
import net.rsprot.protocol.game.incoming.npcs.OpNpcV2
import org.rsmod.api.net.rsprot.handlers.OpNpcTHandler
import org.rsmod.annotations.InternalApi
import org.rsmod.api.net.rsprot.HeadlessAvatars
import org.rsmod.api.net.rsprot.handlers.MoveGameClickHandler
import org.rsmod.api.net.rsprot.handlers.OpLocHandler
import org.rsmod.api.net.rsprot.handlers.OpNpcHandler
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.player.PlayerRegistry
import org.rsmod.api.registry.player.isSuccess
import org.rsmod.content.other.bots.BotLooks
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.inv.InvObj
import org.rsmod.game.world.WorldType
import org.rsmod.map.CoordGrid

/**
 * Raid teammates: real players in the world (registered as a login is, processed by the game's
 * player loop, seen by NPCs and combat) with no client attached. They act only by clicking - each
 * click goes through the same handler a client's packet would (walk here, Pass, Attack, ...), so
 * the server's own pathing, interactions and combat decide what happens.
 */
@Singleton
class RaidBots
@Inject
constructor(
    private val registry: PlayerRegistry,
    private val playerList: PlayerList,
    private val avatars: HeadlessAvatars,
    private val eventBus: EventBus,
    private val mapClock: MapClock,
    private val random: GameRandom,
    private val moveClick: MoveGameClickHandler,
    private val opNpc: OpNpcHandler,
    private val opNpcT: OpNpcTHandler,
    private val opLoc: OpLocHandler,
    private val button: If3ButtonHandler,
    private val prayers: PrayerRepository,
) {
    private val bots = ArrayList<Player>()
    private var nextId = BOT_ID_BASE

    val all: List<Player>
        get() = bots

    @OptIn(InternalApi::class)
    fun spawn(name: String, at: CoordGrid, gear: List<Pair<Int, String>>, inventory: List<String>): Player? {
        val slot = playerList.nextFreeSlot() ?: return null
        val id = nextId++
        val player = Player()
        player.username = name.lowercase()
        player.displayName = name
        player.coords = at
        player.slotId = slot
        player.uuid = id
        // not positive: the account system never saves them (no account rows exist)
        player.accountId = -(id - BOT_ID_BASE + 1).toInt()
        player.characterId = player.accountId
        player.accountHash = id
        player.userId = id
        // who sees a kill's drops (an NPC a bot kills drops for it)
        player.observerUUID = id
        player.worldType = WorldType.DEFAULT
        player.currentMapClock = mapClock.cycle
        BotLooks.randomise(player.appearance, random)
        val add = registry.add(player)
        if (!add.isSuccess()) return null
        for (stat in STATS) {
            player.statMap.setBaseLevel(stat, 99.toByte())
            player.statMap.setCurrentLevel(stat, 99.toByte())
            player.statMap.setFineXP(stat, XP_99)
        }
        for ((slotIndex, obj) in gear) player.worn[slotIndex] = InvObj(obj)
        for ((i, obj) in inventory.withIndex()) player.inv[i] = InvObj(obj)
        eventBus.publish(SessionStateEvent.Login(player))
        eventBus.publish(SessionStateEvent.EngineLogin(player))
        avatars.attach(player)
        player.rebuildAppearance()
        bots += player
        return player
    }

    fun remove(player: Player) {
        if (!bots.remove(player)) return
        avatars.detach(player)
        registry.del(player)
    }

    fun removeAll() {
        for (bot in bots.toList()) remove(bot)
    }

    /* Clicks: what a client would send, through the same handlers. */

    fun walk(player: Player, dest: CoordGrid) {
        moveClick.handle(player, MoveGameClick(dest.x, dest.z, 0))
    }

    fun attack(player: Player, npc: Npc) {
        val op = (1..5).firstOrNull { npc.visType.actions.getOpOrNull(it - 1).equals("Attack", ignoreCase = true) } ?: return
        opNpc.handle(player, OpNpcV2(npc.slotId, false, op, -1))
    }

    /** Cast the spell on [component] (a spellbook button, packed) on [npc]. */
    fun cast(player: Player, npc: Npc, component: Int) {
        opNpcT.handle(player, OpNpcT(npc.slotId, false, CombinedId(component ushr 16, component and 0xFFFF), -1, -1))
    }

    /**
     * Puts [obj] on in [slot] at once, swapping what was there into its inventory slot (and a shield off for a
     * two-handed weapon). Switches in the game cost no ticks, but a Wield click queues behind the bot's attack and
     * cancels it; this keeps a bot's switch and its attack on the same tick, as a player's are.
     */
    fun equip(player: Player, slot: Int, obj: Int): Boolean {
        val from = (0 until player.inv.size).firstOrNull { player.inv[it]?.id == obj } ?: return false
        val type = dev.openrune.ServerCacheManager.getItem(obj) ?: return false
        val left = dev.openrune.util.Wearpos.LeftHand.slot
        val right = dev.openrune.util.Wearpos.RightHand.slot
        val twoHanded = type.wearpos2 == left || type.wearpos3 == left
        val offhandTwoHanded = slot == left && player.worn[right]?.let { w -> dev.openrune.ServerCacheManager.getItem(w.id)?.let { it.wearpos2 == left || it.wearpos3 == left } } == true
        val bump = when {
            slot == right && twoHanded -> left
            offhandTwoHanded -> right
            else -> -1
        }
        if (bump >= 0 && player.worn[bump] != null) {
            val free = (0 until player.inv.size).firstOrNull { player.inv[it] == null && it != from } ?: return false
            player.inv[free] = player.worn[bump]
            player.worn[bump] = null
        }
        val off = player.worn[slot]
        player.worn[slot] = player.inv[from]
        player.inv[from] = off
        player.rebuildAppearance()
        return true
    }

    /** Wield or wear the first [obj] in the inventory (its second option, as a click on the inventory would). */
    fun wield(player: Player, obj: Int): Boolean {
        val slot = (0 until player.inv.size).firstOrNull { player.inv[it]?.id == obj } ?: return false
        click(player, INVENTORY_ITEMS ushr 16, INVENTORY_ITEMS and 0xFFFF, slot, obj, WIELD_OP)
        return true
    }

    fun opLoc(player: Player, loc: Int, at: CoordGrid, op: Int = 1) {
        opLoc.handle(player, OpLocV2(loc, at.x, at.z, false, op, -1))
    }

    /** Click a prayer on the prayer tab, e.g. "varbit.prayer_protectfrommissiles". */
    fun pray(player: Player, prayerVarbit: String) {
        val component = prayers.prayerComponents.entries.firstOrNull { it.value.enabled == prayerVarbit }?.key ?: return
        click(player, component.packed ushr 16, component.packed and 0xFFFF)
    }

    fun click(player: Player, iface: Int, comp: Int, sub: Int = -1, obj: Int = -1, op: Int = 1) {
        button.handle(player, If3Button(CombinedId(iface, comp), sub, obj, op))
    }

    private companion object {
        const val BOT_ID_BASE = 9_000_000_000L
        const val XP_99 = 130_344_310
        const val WIELD_OP = 3
        val INVENTORY_ITEMS: Int by lazy { dev.openrune.rscm.RSCM.run { "component.inventory:items".asRSCM(dev.openrune.rscm.RSCMType.COMPONENT) } }
        val STATS =
            listOf(
                "stat.attack", "stat.strength", "stat.defence", "stat.hitpoints",
                "stat.ranged", "stat.magic", "stat.prayer",
            )
    }
}
