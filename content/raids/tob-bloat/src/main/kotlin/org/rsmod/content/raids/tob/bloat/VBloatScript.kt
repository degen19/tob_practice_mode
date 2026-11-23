package org.rsmod.content.raids.tob.bloat

import org.rsmod.api.player.PracticeSettings
import com.github.michaelbull.logging.InlineLogger
import dev.openrune.types.MoveRestrict
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.instances.DEFAULT_EMPTY_INSTANCE_RECLAIM_TICKS
import org.rsmod.api.instances.DEFAULT_INSTANCE_GRACE_TICKS
import org.rsmod.api.instances.InstanceAccess
import org.rsmod.api.instances.InstanceArea
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.instances.InstanceSpec
import org.rsmod.api.death.PlayerDeathOverrideHook
import org.rsmod.api.npc.hit.NpcHitLandHook
import org.rsmod.api.player.hit.modifier.PlayerHitModifier
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.config.constants
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLoc1
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.util.Wearpos
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.entity.PlayerList
import org.rsmod.content.raids.tob.bloat.maiden.MaidenRooms
import org.rsmod.game.hit.Hit
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.module.PluginModule
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/** A bot in a room and what it has clicked so far. */
class RoomBot(val player: Player) {
    var clickedBarrier = false
}

/** Every live Bloat room, by owner. */
@Singleton
class BloatRooms {
    val byOwner = HashMap<String, BloatRoom>()

    fun forNpc(npc: Npc): BloatRoom? = byOwner.values.firstOrNull { it.npc === npc }

    /** The room whose instance the player is in (inside or out in its corridor). */
    fun forPlayer(player: Player): BloatRoom? = byOwner.values.firstOrNull { it.contains(player.coords) }

    /** What each player had on them as they last came in, given back when they die (or the room resets). */
    private val setups = java.util.WeakHashMap<Player, Pair<List<InvObj?>, List<InvObj?>>>()

    fun saveSetup(player: Player) {
        setups[player] = player.worn.objs.toList() to player.inv.objs.toList()
    }

    fun restoreSetup(player: Player): Boolean {
        val (worn, inv) = setups[player] ?: return false
        for (slot in worn.indices) player.worn[slot] = worn[slot]
        for (slot in inv.indices) player.inv[slot] = inv[slot]
        player.rebuildAppearance()
        return true
    }

    /** After a death: when nobody is left alive inside, the room resets. */
    fun afterDeath(room: BloatRoom) {
        if (room.aliveInside() == 0) room.reset()
    }
}

class VBloatModule : PluginModule() {
    override fun bind() {
        addSetBinding<NpcHitLandHook>(BloatHitLand::class.java)
        addSetBinding<PlayerDeathOverrideHook>(PracticeDeath::class.java)
    }
}

/** Hits on Bloat are halved as they land unless he's down (see [BloatRoom.land]). */
class BloatHitLand
@Inject
constructor(private val rooms: BloatRooms, private val maidens: MaidenRooms, private val players: PlayerList) : NpcHitLandHook {
    override fun land(npc: Npc, hit: Hit): Int {
        // a live Maiden room: the engine keeps her HP (a real player's hit goes into it)
        maidens.forNpc(npc)?.let { room -> return room.land(npc, hit, if (hit.isFromPlayer) hit.resolvePlayerSource(players) else null) }
        val damage = rooms.forNpc(npc)?.land(hit.damage) ?: hit.damage
        // infinite NPC HP (the rotten potato) - the hitsplat shows the full hit; what it takes off is put back first
        if (PracticeSettings.infiniteHp && damage > 0) npc.hitpoints += damage
        return damage
    }
}

/**
 * `::bloat play [team size]` - a private copy of the ToB Bloat room with Bloat running his real
 * mechanics; you come in through the east barrier and play it with your own inputs.
 * `::bloat stop` - leave and close the room.
 */
class VBloatScript
@Inject
constructor(
    private val rooms: BloatRooms,
    private val instances: InstanceManager,
    private val npcRepo: NpcRepository,
    private val worldRepo: WorldRepository,
    private val playerList: PlayerList,
    private val mapClock: MapClock,
    private val random: GameRandom,
    private val hitModifier: PlayerHitModifier,
    private val collision: CollisionFlagMap,
    private val protectedAccess: ProtectedAccessLauncher,
    private val bots: RaidBots,
    private val locRepo: LocRepository,
    private val solo: org.rsmod.content.raids.tob.solo.SoloRaids,
) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        onEvent<GameLifecycle.StartCycle> { tickRooms() }
        onOpLoc1(BARRIER) { barrier(it.loc) }
        if (System.getenv("TOB_BARRIERS") != null) onEvent<GameLifecycle.StartCycle> { if (mapClock.cycle == 5) scanBarriers() }
        onCommand("bloat") {
            desc = "ToB Bloat practice room: ::bloat [team size] | enter | bots [n] | los | stop"
            invalidArgs = "Usage: ::bloat [team size] | enter | bots [n] | los | stop"
            cheat { bloat() }
        }
        System.getenv("BLOAT_SELFTEST")?.toIntOrNull()?.let { n ->
            onEvent<GameLifecycle.StartCycle> {
                if (mapClock.cycle == SELF_TEST_AT) selfTest(n)
            }
        }
    }

    /** Debug: logs every barrier loc in the ToB rooms' regions (TOB_BARRIERS=1). */
    private fun scanBarriers() {
        val skip = Regex("walkway|platform|floor|_wall|throne_(floor|side|left|right|window)|rug|steps|window_glass|diag_wall|corpse|dead_merc|skeleton|bookcase|drain|grate|chain|vial|table|shroud|pile_of|fence|twisty|statue|justiciar|weapon_rack|ladder")
        for (region in intArrayOf(12613, 13125, 13122, 13123, 13379, 12612, 12611, 12867, 12869)) {
            val bx = (region shr 8) * 64
            val by = (region and 0xFF) * 64
            for (level in 0..3) for (zx in 0 until 8) for (zy in 0 until 8) {
                val zone = org.rsmod.map.zone.ZoneKey.from(CoordGrid(bx + zx * 8, by + zy * 8, level))
                for (loc in locRepo.findAll(zone)) {
                    val name = dev.openrune.rscm.RSCM.getReverseMapping(RSCMType.LOC, loc.id)
                    if (!name.contains("tob") || skip.containsMatchIn(name)) continue
                    logger.info { "tobscan region $region: $name ${loc.coords} shape ${loc.shape} angle ${loc.angle}" }
                }
            }
        }
    }

    /** Clicks Pass on the barrier in front of you (the same click the client sends). */
    private fun enter(player: Player) {
        val room = rooms.byOwner[player.username] ?: return player.mes("::bloat play first.")
        val (x, y) = room.local(player.coords)
        val bx = if (x >= 24) 24 else if (x <= 7) 7 else return player.mes("You're already inside.")
        bots.opLoc(player, BARRIER_ID, room.tile(bx, y.coerceIn(14, 17)))
    }

    /** Teammate bots for your room: they wait outside the east barrier and come in when you do. */
    private fun addBots(player: Player, n: Int) {
        val room = rooms.byOwner[player.username] ?: return player.mes("::bloat play first.")
        repeat(n.coerceIn(1, 4)) { i ->
            val at = room.tile(ENTER_X, 14 + (room.bots.size % 4))
            val bot = bots.spawn("Bot ${room.bots.size + 1}", at, BOT_GEAR, emptyList()) ?: return@repeat
            room.bots += RoomBot(bot)
            bots.pray(bot, "varbit.prayer_protectfrommissiles")
        }
        player.mes("${room.bots.size} bots in your room.")
    }

    /** No client needed: a room with [n] bots that come in and attack, logged every tick. */
    private fun selfTest(n: Int) {
        val room = openRoom(SELF_TEST, 4) ?: return logger.warn { "vbloat selftest: no room" }
        room.log = true
        for (i in 0 until n) {
            val at = room.tile(ENTER_X, 14 + i)
            val bot = bots.spawn("Bot ${i + 1}", at, BOT_GEAR, emptyList()) ?: continue
            room.bots += RoomBot(bot)
            bots.pray(bot, "varbit.prayer_protectfrommissiles")
        }
        logger.info { "vbloat selftest: ${room.bots.size} bots" }
        for (tab in SupplyCatalog.Tab.entries) logger.info { "supply chest ${tab.label}: " + SupplyCatalog.items(tab).joinToString { it.name } }
    }

    private fun Cheat.bloat() {
        val first = args.getOrNull(0)?.lowercase()
        when (first) {
            null -> play(player, 1)
            "los" -> rooms.forPlayer(player)?.let { it.toggleLos(player) } ?: player.mes("You're not in a Bloat room.")
            "stop" -> rooms.byOwner[player.username]?.let { stop(it, player) } ?: player.mes("You have no Bloat room open.")
            "bots" -> addBots(player, args.getOrNull(1)?.toIntOrNull() ?: 3)
            "enter" -> enter(player)
            "log" -> rooms.byOwner[player.username]?.let { it.log = !it.log; player.mes("Tick log ${if (it.log) "on" else "off"}.") }
            else -> first.toIntOrNull()?.let { play(player, it) } ?: player.mes("::bloat [team size] | enter | bots [n] | los | stop")
        }
    }

    private fun play(player: Player, team: Int) {
        val room = openRoom(player.username, team) ?: return player.mes("No instance space free; try again shortly.")
        val enter = room.tile(ENTER_X, ENTER_Y)
        protectedAccess.launch(player) { telejump(enter, TeleportType.Exempt) }
        player.mes("Bloat room ready ($team-man, ${room.max} hitpoints). He starts walking when you pass the barrier. ::bloat stop to leave.")
    }

    private fun openRoom(owner: String, team: Int): BloatRoom? {
        rooms.byOwner[owner]?.let { close(it) }
        val session =
            instances.createServerOwned(
                key = INSTANCE_KEY,
                spec =
                    InstanceSpec(
                        fee = 0,
                        maxPlayers = 5,
                        reclaimTicks = DEFAULT_EMPTY_INSTANCE_RECLAIM_TICKS,
                        graceTicks = DEFAULT_INSTANCE_GRACE_TICKS,
                        area = InstanceArea.copyRegions(centerRegionId = BloatRules.REGION),
                        settingsRowId = -1,
                    ),
                access = InstanceAccess.Private,
                currentTick = mapClock.cycle,
            ) ?: return null
        val base = checkNotNull(instances.resolveCoord(session, CoordGrid(BloatRules.ROOM_X, BloatRules.ROOM_Y, 0)))
        val max = BloatRules.hitpoints(team.coerceIn(1, 5))
        val npc = Npc(BLOAT_NPC, base.translate(8, 8))
        npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        // he walks through players (they stand under him all the time); players block NPCs otherwise
        npc.moveRestrict = MoveRestrict.PassThru
        // he never fights back: his walk is his own (BloatRoom), not a chase after whoever hit him
        npc.ignoreCombatInteractions = true
        instances.attachNpc(session.id, npc)
        npc.baseHitpointsLvl = max
        npc.hitpoints = max
        val region = checkNotNull(instances.regionOf(session))
        val room = BloatRoom(session, npc, base, random, worldRepo, hitModifier, collision, max, region.southWest, region.northEast)
        val (sx, sy) = room.sw
        npc.telejump(collision, base.translate(sx, sy))
        rooms.byOwner[owner] = room
        if (room.log || owner == SELF_TEST) {
            for (y in 24 downTo 7) {
                val row = (5..26).joinToString("") { x -> val c = base.translate(x, y); if (collision[c.x, c.z, c.level] != 0) "#" else "." }
                logger.info { "vbloat map y=${"%2d".format(y)} $row" }
            }
        }
        // the supply chest, just outside the east barrier on the first free tile
        CHEST_SPOTS.map { (x, y) -> base.translate(x, y) }
            .firstOrNull { collision[it.x, it.z, it.level] == 0 }
            ?.let { locRepo.add(it, "loc.tob_midway_chest_closed", Int.MAX_VALUE, LocAngle.West, LocShape.CentrepieceStraight) }
        logger.info { "vbloat: room for $owner in instance ${session.id} ($team-man)" }
        return room
    }

    /**
     * Pass on a barrier. Before the fight has started, only the player who opened the room can go in,
     * and only after answering the game's "ready to fight?" question - you leave it open and click Yes
     * on the tick you want, as in the game. Once someone is inside, Pass just takes you through.
     */
    private suspend fun ProtectedAccess.barrier(loc: org.rsmod.game.loc.BoundLocInfo) {
        solo.forPlayer(player)?.let { return org.rsmod.content.raids.tob.solo.SoloBarrier.pass(this, it, loc.bounds()) }
        val barrier = loc.coords
        val room = rooms.forPlayer(player)
        if (room != null && room.t < 0 && room.local(player.coords).first >= 24) {
            val leader = rooms.byOwner[player.username] === room
            if (!leader && !room.log) return player.mes("The party leader has to start the fight first.")
            if (leader) {
                val choice = menu("Is your party ready to fight?", "Yes, let's go.", "No, don't start yet.")
                ifClose()
                if (choice != 0) return
            }
        }
        // coming in from the east side: remember this setup to hand back on a death or reset
        if (room != null && room.local(player.coords).first >= 24) rooms.saveSetup(player)
        passBarrier(barrier)
    }

    /**
     * The room's barriers (east x 24, west x 7; the other rooms' run east-west): Pass carries you straight through - from the tile in
     * front of it to the tile past it - walking through, as in the game. Your true tile is on the other
     * side the tick after your click goes in; the walk itself is drawn over two ticks.
     */
    private fun ProtectedAccess.passBarrier(barrier: CoordGrid) {
        val dx = barrier.x - player.coords.x
        val dz = barrier.z - player.coords.z
        if (dx == 0 && dz == 0) return
        // the other rooms' barriers run east-west (you pass them going north or south)
        val (dest, dir) =
            if (dx != 0) {
                val step = if (dx > 0) 1 else -1
                CoordGrid(barrier.x + step, player.coords.z, player.coords.level) to (if (step > 0) constants.em_face_east else constants.em_face_west)
            } else {
                val step = if (dz > 0) 1 else -1
                CoordGrid(player.coords.x, barrier.z + step, player.coords.level) to (if (step > 0) constants.em_face_north else constants.em_face_south)
            }
        anim(WALK_SEQ)
        exactMove(player.coords, dest, delay1 = 0, delay2 = BARRIER_CYCLES, dir = dir, teleportType = TeleportType.Exempt)
    }

    private fun tickRooms() {
        if (rooms.byOwner.isEmpty()) return
        for (room in rooms.byOwner.values.toList()) {
            try {
                val region = instances.regionOf(room.session) ?: continue
                val players = playerList.filter { p ->
                    p.coords.level == region.southWest.level &&
                        p.coords.x in region.southWest.x..region.northEast.x &&
                        p.coords.z in region.southWest.z..region.northEast.z
                }
                // he must stay drawn across the whole room, as in the game (the default NPC range is 15 tiles)
                for (p in players) if (p.npcViewDistance == null) p.npcViewDistance = NPC_VIEW_DISTANCE
                room.tick(players)
                for (rb in room.bots) think(room, rb)
                if (room.log) logTick(room)
            } catch (e: Exception) {
                logger.error(e) { "vbloat: room tick failed" }
            }
        }
    }

    private fun stop(room: BloatRoom, player: Player) {
        close(room)
        protectedAccess.launch(player) { telejump(EXIT, TeleportType.Exempt) }
        player.npcViewDistance = null
        player.mes("Bloat room closed.")
    }

    private fun close(room: BloatRoom) {
        rooms.byOwner.values.remove(room)
        for (rb in room.bots) bots.remove(rb.player)
        room.bots.clear()
        if (room.npc.isSlotAssigned) npcRepo.del(room.npc, Int.MAX_VALUE)
        instances.end(room.session)
    }

    /**
     * A bot's turn (before the players move, as a client's click would arrive): outside, click Pass on
     * the barrier in front of it; inside, click Attack on Bloat whenever it isn't already on him.
     */
    private fun think(room: BloatRoom, rb: RoomBot) {
        val p = rb.player
        if (p.isDelayed || !room.npc.isSlotAssigned) return
        val (x, y) = room.local(p.coords)
        if (x >= 25 && !rb.clickedBarrier && (room.t >= 0 || room.log)) {
            bots.opLoc(p, BARRIER_ID, room.tile(24, y))
            rb.clickedBarrier = true
            return
        }
        if (BloatRules.inRoom(x, y) && p.interaction == null) bots.attack(p, room.npc)
    }

    private fun logTick(room: BloatRoom) {
        val (bx, by) = room.sw
        val bs = room.bots.joinToString(" ") { rb ->
            val (x, y) = room.local(rb.player.coords)
            val pl = rb.player
            val flags = buildString {
                if (pl.interaction != null) append('*')
                if (pl.isDelayed) append('D')
                if (pl.isAccessProtected) append('P')
                if (pl.frozen) append('F')
                if (pl.forcedRoute) append('R')
                if (pl.vars["varbit.prayer_protectfrommissiles"] == 1) append('M')
                if (pl.overheadIcon == 1) append('I')
                append("r").append(pl.routeDestination.size)
            }
            "$x,$y hp${pl.hitpoints} $flags"
        }
        val (ax, ay) = room.local(room.npc.coords)
        logger.info { "vbloat t${room.t} ${room.mode} d${room.down ?: "-"} B$bx,$by at$ax,$ay hp${room.npc.hitpoints} | $bs" }
    }

    private companion object {
        const val INSTANCE_KEY = "tob_bloat_practice"
        const val BLOAT_NPC = "npc.tob_bloat"
        const val BARRIER = "loc.tob_arena_barrier"
        const val WALK_SEQ = "seq.human_walk_f"
        const val BARRIER_CYCLES = 60
        val BARRIER_ID by lazy { BARRIER.asRSCM(RSCMType.LOC) }
        const val SELF_TEST = "selftest"
        const val NPC_VIEW_DISTANCE = 32
        val CHEST_SPOTS = listOf(27 to 13, 27 to 18, 26 to 13, 26 to 18, 28 to 13, 28 to 18, 29 to 14)
        const val SELF_TEST_AT = 20
        val BOT_GEAR =
            listOf(
                Wearpos.Hat.slot to "obj.torva_helm",
                Wearpos.Back.slot to "obj.infernal_cape",
                Wearpos.Front.slot to "obj.nzone_salve_amulet_e",
                Wearpos.RightHand.slot to "obj.scythe_of_vitur",
                Wearpos.Torso.slot to "obj.torva_chest",
                Wearpos.Legs.slot to "obj.torva_legs",
                Wearpos.Hands.slot to "obj.ferocious_gloves",
                Wearpos.Feet.slot to "obj.primordial_boots",
                Wearpos.Ring.slot to "obj.ultor_ring",
            )
        const val ENTER_X = 25
        const val ENTER_Y = 15
        val EXIT = CoordGrid(3222, 3218, 0)
    }
}
