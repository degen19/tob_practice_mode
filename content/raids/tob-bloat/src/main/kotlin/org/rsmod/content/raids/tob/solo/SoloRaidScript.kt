package org.rsmod.content.raids.tob.solo

import org.rsmod.api.player.righthand
import org.rsmod.api.player.syncPrayerOverrides
import com.github.michaelbull.logging.InlineLogger
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.instances.DEFAULT_EMPTY_INSTANCE_RECLAIM_TICKS
import org.rsmod.api.instances.DEFAULT_INSTANCE_GRACE_TICKS
import org.rsmod.api.instances.InstanceAccess
import org.rsmod.api.instances.InstanceArea
import org.rsmod.api.instances.InstanceSpec
import org.rsmod.api.instances.RegionLocal
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statAdd
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.player.stat.statBoost
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.forcedWalk
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Player
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.api.player.vars.varMoveSpeed
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid
import org.rsmod.map.util.Bounds
import org.rsmod.plugin.module.PluginModule
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class SoloRaidModule : PluginModule() {
    override fun bind() {
        addSetBinding<org.rsmod.api.npc.hit.NpcHitLandHook>(SoloLand::class.java)
        addSetBinding<org.rsmod.api.npc.hit.NpcHitpointsFloorHook>(SoloFloor::class.java)
        addSetBinding<org.rsmod.api.death.PlayerDeathOverrideHook>(SoloDeath::class.java)
        addSetBinding<org.rsmod.api.death.NpcAttackValidateHook>(SoloAttackable::class.java)
    }
}

/**
 * `::tob <room>` - the Theatre of Blood played solo, room by room, as in the game: you come in at the room's barrier
 * (with a supply chest beside you), Pass starts the room, and once its boss is dead you walk on out to the next room.
 * The supply chest also takes you to any room. `::tob stop` leaves.
 */
private var Player.pvpTutorial by org.rsmod.api.player.vars.intVarp("varp.pvptut_active_tutorial")

class SoloRaidScript
@Inject
constructor(
    private val raids: SoloRaids,
    private val d: SoloDeps,
    private val protectedAccess: ProtectedAccessLauncher,
    private val bots: org.rsmod.content.raids.tob.bloat.RaidBots,
    private val spells: org.rsmod.api.spells.MagicSpellRegistry,
) : PluginScript() {
    private val auto = HashSet<Player>()
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        raids.travel = { p, r, a -> travel(p, r, a) }
        raids.rewatch = { p -> rewatch(p) }
        if (System.getenv("TOB_NODEATH") != null) org.rsmod.api.player.PracticeSettings.noDeaths = true
        onEvent<GameLifecycle.StartCycle> { tickRooms() }
        onEvent<GameLifecycle.LateCycle> {
            for (room in raids.byOwner.values.toList()) runCatching { room.late() }.onFailure { e -> logger.error(e) { "tob room late tick" } }
            SoloBarrier.settle()
        }
        SoloBarrier.collision = d.collision
        // ToB practice is at the game's own experience rates: no boosted XP
        onPlayerLogin {
            player.xpRate = 1.0
            player.globalXpRate = 1.0
            // the client's var reset leaves this at 0, a real dbrow, and inside any instance its spellbook then counts
            // only the PvP tutorial's fake runes: no spell lights up in the Theatre
            player.pvpTutorial = -1
        }
        System.getenv("TOB_TRIO")?.toIntOrNull()?.let { teams ->
            org.rsmod.api.player.PracticeSettings.noDeaths = true
            onEvent<GameLifecycle.StartCycle> { if (d.mapClock.cycle == SELF_TEST_AT) startTrios(teams) }
        }
        System.getenv("TOB_SELFTEST")?.let { list ->
            val rooms = ArrayDeque(list.split(',').mapNotNull { TobRoom.parse(it.trim()) })
            org.rsmod.api.player.PracticeSettings.noDeaths = true
            onEvent<GameLifecycle.StartCycle> {
                if (d.mapClock.cycle != SELF_TEST_AT || rooms.isEmpty()) return@onEvent
                val bot = bots.spawn("Solo", EXIT, SELF_TEST_GEAR, emptyList()) ?: return@onEvent
                auto += bot
                travel(bot, rooms.removeFirst())
                selfTestQueue = rooms
            }
        }
        onCommand("tob") {
            desc = "Theatre of Blood, solo: ::tob maiden | bloat | nylo | sote | xarpus | verzik | stop | watch [maiden] (a bot trio plays the Nylocas or Maiden)"
            invalidArgs = "Usage: ::tob <room> | stop"
            cheat { tob() }
        }
        onCommand("watch") {
            desc = "Watch a bot trio play a Theatre room live: ::watch [maiden | nylo] (Nylocas by default)"
            cheat { watch(player, watched(args.getOrNull(0))) }
        }
        for (type in QUEUE_WATCHED) {
            onEvent<org.rsmod.api.npc.events.NpcHitEvents.Modify>(type.asRSCM(RSCMType.NPC).toLong()) { raids.forNpc(npc)?.queued(npc, hit) }
        }
        for (loc in ROOM_LOCS) onOpLoc1(loc) { roomLoc(loc, it.loc.coords, 1) }
        onOpLoc1(PASSAGE) { passage(it.loc.coords) }
        onOpLoc2(PASSAGE) { passage(it.loc.coords) }
        onOpNpc1("npc.verzik_initial") { (raids.forPlayer(player) as? VerzikSolo)?.begin(this) }
        onOpNpc2("npc.verzik_initial") { (raids.forPlayer(player) as? VerzikSolo)?.begin(this) }
        onOpNpc3("npc.verzik_initial") { (raids.forPlayer(player) as? VerzikSolo)?.begin(this) }
    }

    private fun Cheat.tob() {
        val arg = args.getOrNull(0)?.lowercase() ?: return player.mes("::tob maiden | bloat | nylo | sote | xarpus | verzik | stop")
        when (arg) {
            "stop" -> {
                if (stopWatching(player)) return
                val room = raids.byOwner[player.username] ?: return player.mes("You're not in the Theatre.")
                close(room)
                org.rsmod.api.player.SurgeCooldown.clear(player)
                TobHud.close(player, d.eventBus)
                protectedAccess.launch(player) { telejump(EXIT, TeleportType.Exempt) }
                player.npcViewDistance = null
                player.playerViewDistance = null
                player.mes("You leave the Theatre.")
            }
            "where" -> {
                val room = raids.byOwner[player.username] ?: return player.mes("You're not in the Theatre.")
                val r = room.real(player.coords)
                player.mes("${room.id.label}: you're on ${r.x}, ${r.z} (level ${r.level}); room tick ${room.t}.")
            }
            "map" -> raids.byOwner[player.username]?.let { dumpMap(it) }
            "mode" -> raids.switchMode(player)
            "start" -> raids.byOwner[player.username]?.start()
            "watch" -> watch(player, watched(args.getOrNull(1)))
            "auto" -> if (!auto.remove(player)) { auto += player; player.mes("Autopilot on.") } else player.mes("Autopilot off.")
            "hp" -> raids.byOwner[player.username]?.autoTarget()?.let { it.hitpoints = args.getOrNull(1)?.toIntOrNull() ?: it.hitpoints }
            else -> {
                val room = TobRoom.parse(arg) ?: return player.mes("::tob maiden | bloat | nylo | sote | xarpus | verzik | stop")
                travel(player, room)
            }
        }
    }

    /**
     * Takes [player] to the corridor outside [room]'s barrier (a fresh copy of the room). From inside a script
     * ([access]) the jump is made there; otherwise it's launched.
     */
    fun travel(player: Player, room: TobRoom, access: ProtectedAccess? = null) {
        // into the raid at its start: no surge cooldown carried in
        if (room == TobRoom.MAIDEN) org.rsmod.api.player.SurgeCooldown.clear(player)
        raids.byOwner[player.username]?.let { close(it) }
        val area =
            if (room.allLevels) {
                val zx = (room.regions.minOf { it ushr 8 }) shl 3
                val zz = (room.regions.minOf { it and 0xFF }) shl 3
                val w = (room.regions.maxOf { it ushr 8 } - room.regions.minOf { it ushr 8 } + 1) * 8
                val h = (room.regions.maxOf { it and 0xFF } - room.regions.minOf { it and 0xFF } + 1) * 8
                val template =
                    RegionTemplate.createLarge {
                        copyAllLevels(zx, zz) {
                            zoneWidth = w
                            zoneLength = h
                            regionZoneX = 0
                            regionZoneZ = 0
                        }
                    }
                InstanceArea.template(template, RegionLocal(0, 0, 0, 0, 0), EXIT)
            } else {
                InstanceArea.copyRegions(regionIds = room.regions)
            }
        val session =
            d.instances.createServerOwned(
                key = "tob_solo_${room.name.lowercase()}",
                spec =
                    InstanceSpec(
                        fee = 0,
                        maxPlayers = 5,
                        reclaimTicks = DEFAULT_EMPTY_INSTANCE_RECLAIM_TICKS,
                        graceTicks = DEFAULT_INSTANCE_GRACE_TICKS,
                        area = area,
                        settingsRowId = -1,
                    ),
                access = InstanceAccess.Private,
                currentTick = d.mapClock.cycle,
            ) ?: return player.mes("No instance space free; try again shortly.")
        val region = checkNotNull(d.instances.regionOf(session))
        val origin = CoordGrid((room.regions.minOf { it ushr 8 }) * 64, (room.regions.minOf { it and 0xFF }) * 64, 0)
        val copyBase = CoordGrid(region.southWest.x, region.southWest.z, 0)
        val solo =
            when (room) {
                TobRoom.MAIDEN -> MaidenSolo(player, session, d, origin, copyBase)
                TobRoom.BLOAT -> BloatSolo(player, session, d, origin, copyBase)
                TobRoom.NYLOCAS -> NyloSolo(player, session, d, origin, copyBase)
                TobRoom.SOTETSEG -> SoteSolo(player, session, d, origin, copyBase)
                TobRoom.XARPUS -> XarpusSolo(player, session, d, origin, copyBase)
                TobRoom.VERZIK -> VerzikSolo(player, session, d, origin, copyBase)
            }
        raids.byOwner[player.username] = solo
        solo.anchor = raids.lineTicks.remove(player.username) ?: solo.builtAt
        logger.info { "tob $room: instance cycle from clock ${solo.anchor} (built ${solo.builtAt})" }
        solo.build()
        if (System.getenv("TOB_MAPDUMP") != null) dumpMap(solo)
        val (chestAt, angle) = solo.chest
        d.locRepo.add(solo.at(chestAt), CHEST, Int.MAX_VALUE, angle, LocShape.CentrepieceStraight)
        player.npcViewDistance = VIEW_DISTANCE
        player.playerViewDistance = VIEW_DISTANCE
        TobHud.open(player, d.eventBus)
        val dest = solo.at(solo.entry)
        if (access != null) access.telejump(dest, TeleportType.Exempt) else protectedAccess.launch(player) { telejump(dest, TeleportType.Exempt) }
        player.mes("${room.label}. Pass the barrier when you're ready; the supply chest is beside you.")
        logger.info { "tob solo: ${player.username} -> $room (instance ${session.id})" }
    }

    private fun close(room: SoloRoom) {
        raids.byOwner.values.remove(room)
        room.resets++
        room.cleanup()
        d.instances.end(room.session)
    }

    private fun tickRooms() {
        if (raids.byOwner.isEmpty()) return
        for (room in raids.byOwner.values.toList()) {
            try {
                if (!room.owner.isSlotAssigned) {
                    close(room)
                    continue
                }
                room.tick()
                val trio = trios[room]
                if (trio != null) {
                    trioTick(room, trio)
                    continue
                }
                if (room.owner in auto) autopilot(room)
                val limit = System.getenv("TOB_ROOM_TICKS")?.toIntOrNull()
                if (room.owner in auto && limit != null && room.t >= limit && selfTestQueue.isNotEmpty()) {
                    travel(room.owner, selfTestQueue.removeFirst())
                    continue
                }
                if (room.owner in auto && room.t % 5 == 0) logger.info { "tobtest ${room.id} t${room.t} ${room.debug()} | player ${room.real(room.owner.coords)} hp ${room.owner.hitpoints}" }
                val p = room.player() ?: continue
                if (room.cleared && room.id.onLine(room.real(p.coords)) && raids.lineTicks.putIfAbsent(p.username, d.mapClock.cycle - 1) == null) {
                    logger.info { "tob ${room.id}: ${p.username} on the exit line at ${room.real(p.coords)}, clock ${d.mapClock.cycle - 1}" }
                }
                val out = room.cleared && room.walkOn && !room.inArena(room.real(p.coords)) && !p.isDelayed
                if (!out) room.outSince = -1 else if (room.outSince < 0) room.outSince = room.t
                // players walk to the Formidable passage; if they haven't found it a while after leaving, take them on
                val viaPassage = room.owner !in auto && room.hasPassage() && room.t - room.outSince < PASSAGE_GRACE
                if (out && !viaPassage) {
                    val next = room.id.next
                    if (next == null) {
                        room.leftAfterClear()
                    } else if (room.owner in auto && selfTestQueue.isNotEmpty()) {
                        travel(p, selfTestQueue.removeFirst())
                    } else {
                        p.mes("You head on to ${next.label}.")
                        travel(p, next)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "tob solo: room tick failed (${room.id})" }
            }
        }
    }

    private var selfTestQueue = ArrayDeque<TobRoom>()

    /** Tests (`TOB_TRIO=n`): n bot trios, each in its own room (`TOB_TRIO_ROOM`: nylo, the default, or maiden), play it out. */
    private val trios = HashMap<SoloRoom, BotCrew>()

    /** `TOB_TRIO_PARAMS`: a file with one line of crew params per trio (a search's candidates). */
    private val trioParams: List<String> by lazy { System.getenv("TOB_TRIO_PARAMS")?.let { java.io.File(it).readLines() } ?: emptyList() }

    private val barrageButton: Int by lazy {
        val obj = dev.openrune.ServerCacheManager.getItem(NyloLoadout.ICE_BARRAGE.asRSCM(dev.openrune.rscm.RSCMType.OBJ))!!
        spells.getObjSpell(obj)!!.component.packed
    }

    private val rushButton: Int by lazy {
        val obj = dev.openrune.ServerCacheManager.getItem(NyloLoadout.ICE_RUSH.asRSCM(dev.openrune.rscm.RSCMType.OBJ))!!
        spells.getObjSpell(obj)!!.component.packed
    }

    private fun startTrios(teams: Int) {
        val where = TobRoom.parse(System.getenv("TOB_TRIO_ROOM") ?: "nylo") ?: TobRoom.NYLOCAS
        for (i in 1..teams) startTrio("$i", where, trioParams.getOrNull(i - 1) ?: "") ?: return
    }

    /** A bot trio in a fresh room (bots named Mage[tag], Ranger[tag], Melee[tag]); null if they can't all log in. */
    private fun startTrio(tag: String, where: TobRoom, params: String): SoloRoom? {
        val maiden = where == TobRoom.MAIDEN
        val crew = NyloRole.entries.map { role -> role to spawnCrewBot("${role.name}$tag", role, if (maiden) NyloLoadout.maidenSets(role) else NyloLoadout.sets(role), maiden) }
        if (crew.any { it.second == null }) {
            for ((_, bot) in crew) bot?.let(bots::remove)
            logger.warn { "${where.name.lowercase()} trio $tag: no room for its bots" }
            return null
        }
        val owner = crew[0].second!!
        auto += owner
        for ((_, bot) in crew) raids.undying += bot!!
        travel(owner, where)
        val room = raids.byOwner[owner.username] ?: return null
        for ((_, bot) in crew.drop(1)) {
            room.helpers += bot!!
            org.rsmod.game.entity.util.PathingEntityCommon.telejump(bot, d.collision, room.at(room.entry))
        }
        val members = crew.map { it.second!! to it.first }
        trios[room] =
            when (room) {
                is MaidenSolo -> MaidenCrew(room, members, bots, d.mapClock, barrageButton, rushButton, MaidenCrewParams.parse(params))
                is NyloSolo -> NyloCrew(room, members, bots, d.mapClock, barrageButton, NyloCrewParams.parse(params))
                else -> return null
            }
        return room
    }

    /** `::tob watch [maiden]`: a bot trio plays the room (Nylocas unless named) and you watch from the doorway. */
    private val spectators = HashMap<SoloRoom, MutableList<Player>>()
    private var watchRuns = 0

    private fun watched(arg: String?): TobRoom = arg?.let { TobRoom.parse(it) }?.takeIf { it == TobRoom.MAIDEN } ?: TobRoom.NYLOCAS

    /** Spectators waiting outside until their bots pass the barrier (so their room timer starts with the bots'). */
    private val arriving = HashSet<Player>()

    /** Trios whose run is over, kept up while someone is still watching in the room. */
    private val finished = HashSet<SoloRoom>()

    /** Watching from your own room (or another trio) leaves it first, as ::tob stop does. */
    private fun watch(player: Player, where: TobRoom) {
        watching(player)?.let { endTrio(it) }
        raids.byOwner[player.username]?.let { close(it) }
        val room = startTrio("w${++watchRuns}", where, "") ?: return player.mes("No room for a bot trio right now.")
        spectators.getOrPut(room) { ArrayList() } += player
        arriving += player
        player.npcViewDistance = VIEW_DISTANCE
        player.playerViewDistance = VIEW_DISTANCE
        org.rsmod.game.entity.util.PathingEntityCommon.telejump(player, d.collision, EXIT)
        player.mes("A bot trio takes on ${where.label}; you'll watch from the doorway as they go in (nothing targets you).")
    }

    private fun watching(player: Player): SoloRoom? = spectators.entries.firstOrNull { player in it.value }?.key

    /** The potato's Restart room while watching: a fresh trio walks in from outside. */
    private fun rewatch(player: Player): Boolean {
        val room = watching(player) ?: return false
        watch(player, room.id)
        return true
    }

    private fun stopWatching(player: Player): Boolean {
        val room = watching(player) ?: return false
        endTrio(room)
        protectedAccess.launch(player) { telejump(EXIT, TeleportType.Exempt) }
        player.mes("You stop watching.")
        return true
    }

    private fun endTrio(room: SoloRoom) {
        val crew = listOf(room.owner) + room.helpers
        trios.remove(room)
        readied.remove(room)
        finished.remove(room)
        auto.remove(room.owner)
        close(room)
        for (p in crew) {
            raids.undying -= p
            SoloBarrier.noAsk -= p
            bots.remove(p)
        }
        for (p in spectators.remove(room) ?: emptyList()) {
            arriving -= p
            p.npcViewDistance = null
            p.playerViewDistance = null
        }
    }

    private fun spawnCrewBot(name: String, role: NyloRole, sets: List<GearSet>, maiden: Boolean): Player? {
        val extra = if (!maiden) emptyList() else if (role == NyloRole.Mage) NyloLoadout.ANCIENT_RUNES + NyloLoadout.SURGE else NyloLoadout.LUNAR_RUNES + NyloLoadout.SURGE + NyloLoadout.BOOK
        val pack = (NyloLoadout.pack(role, sets) + extra).distinct()
        val bot = bots.spawn(name, EXIT, NyloLoadout.worn(sets), pack) ?: return null
        val stacks = NyloLoadout.SPELL_RUNES + NyloLoadout.CHINCHOMPAS
        for (slot in 0 until bot.inv.size) {
            val obj = bot.inv[slot] ?: continue
            val rune = stacks.firstOrNull { it.asRSCM(dev.openrune.rscm.RSCMType.OBJ) == obj.id } ?: continue
            bot.inv[slot] = org.rsmod.game.inv.InvObj(rune, RUNES)
        }
        val ammo = if (maiden) NyloLoadout.RUBY_BOLTS else NyloLoadout.BOLTS
        bot.worn[dev.openrune.util.Wearpos.Quiver.slot] = org.rsmod.game.inv.InvObj(ammo, RUNES)
        org.rsmod.content.raids.tob.bloat.fillCharges(bot.inv)
        val arrows = if (maiden) NyloLoadout.SEEKING_ARROWS else NyloLoadout.ARROWS
        org.rsmod.api.player.worn.DizanasQuiver.store(bot, arrows.asRSCM(dev.openrune.rscm.RSCMType.OBJ), RUNES)
        val book = if (role == NyloRole.Mage) ANCIENTS else if (maiden) LUNARS else null
        book?.let { org.rsmod.api.player.vars.VarPlayerIntMapSetter.set(bot, "varbit.spellbook", it) }
        bot.varMoveSpeed = org.rsmod.game.movement.MoveSpeed.Run
        return bot
    }

    /** A bot walking in as a team does: fully potted (super combat, ranging, saturated heart), full spec, prayers up. */
    private fun potUp(bot: Player) {
        for (stat in MELEE_STATS) bot.statBoost(stat, SUPER_COMBAT_BOOST, SUPER_COMBAT_PERCENT)
        bot.statBoost("stat.ranged", RANGING_BOOST, RANGING_PERCENT)
        bot.statBoost("stat.magic", HEART_BOOST, HEART_PERCENT)
        org.rsmod.api.player.vars.VarPlayerIntMapSetter.set(bot, "varp.sa_energy", FULL_SPEC)
        org.rsmod.api.player.vars.VarPlayerIntMapSetter.set(bot, "varbit.dizanas_quiver_ammo_save", QUIVER_ASSEMBLER)
        for (unlock in PRAYER_UNLOCKS) org.rsmod.api.player.vars.VarPlayerIntMapSetter.set(bot, unlock, 1)
        for (prayer in OFFENSIVE_PRAYERS) org.rsmod.api.player.vars.VarPlayerIntMapSetter.set(bot, prayer, 1)
        bot.syncPrayerOverrides()
    }

    /** A trio's tick: start on a 4 like a team does, then play until the crew's run is over. */
    private fun trioTick(room: SoloRoom, trio: BotCrew) {
        val crew = listOf(room.owner) + room.helpers
        if (room.t < 0) {
            if (d.mapClock.cycle - room.builtAt < 4) return
            if (trio.walksIn) return walkIn(room, trio, crew)
            // entering on the right tick (Blert: wave 1 on room tick 4, Vasilias on a multiple of 4)
            if (room.instanceTick != TRIO_START_TICK) return
            for ((p, at) in crew.zip(trio.starts())) org.rsmod.game.entity.util.PathingEntityCommon.telejump(p, d.collision, room.at(at))
            for (p in crew) {
                if (p !in bots.all) continue
                potUp(p)
                trio.prepare(p)
            }
            room.start()
            return
        }
        for (p in crew) {
            if (p !in bots.all) continue
            p.runEnergy = org.rsmod.api.config.constants.run_max_energy
            val missing = p.statBase("stat.prayer") - p.stat("stat.prayer")
            if (missing > 0) p.statAdd("stat.prayer", missing, 0)
            val hurt = p.statBase("stat.hitpoints") - p.stat("stat.hitpoints")
            if (hurt > 0) p.statAdd("stat.hitpoints", hurt, 0)
        }
        val watchers = spectators[room].orEmpty()
        for (p in watchers.filter { it in arriving }) {
            arriving -= p
            if (p.isSlotAssigned) org.rsmod.game.entity.util.PathingEntityCommon.telejump(p, d.collision, room.at(room.entry))
        }
        if (room in finished) {
            if (watchers.none { it.isSlotAssigned && room.contains(it.coords) }) endTrio(room)
            return
        }
        trio.tick()
        val linger = if (watchers.isNotEmpty()) WATCH_LINGER else 0
        val done = trio.doneAt()
        if (done < 0 || room.t < done + linger) return
        logger.info { "${room.id.name.lowercase()} trio ${room.owner.username}: ${trio.summary()}" }
        val staying = watchers.filter { it.isSlotAssigned }
        if (staying.isEmpty()) return endTrio(room)
        for (p in staying) p.mes("The bots are done: ${trio.summary()}. Restart room on the potato for another run; ::tob stop to leave.")
        finished += room
    }

    /** Crews whose bots have potted up outside the barrier. */
    private val readied = HashSet<SoloRoom>()

    /** Potted up outside, the bots click Pass on the barrier together on the tick a team goes in (wave 1 on room tick 4). */
    private fun walkIn(room: SoloRoom, trio: BotCrew, crew: List<Player>) {
        val team = crew.filter { it in bots.all }
        if (readied.add(room)) {
            for (p in team) {
                if (room.real(p.coords) != room.entry) org.rsmod.game.entity.util.PathingEntityCommon.telejump(p, d.collision, room.at(room.entry))
                potUp(p)
                trio.prepare(p)
                SoloBarrier.noAsk += p
            }
            return
        }
        if (room.instanceTick != Math.floorMod(TRIO_START_TICK - CLICK_LEAD, INSTANCE_CYCLE)) return
        val (tile, barrier) = room.findLocs(BARRIER).minByOrNull { (at, _) -> maxOf(kotlin.math.abs(at.x - room.entry.x), kotlin.math.abs(at.z - room.entry.z)) } ?: return
        for (p in team) bots.opLoc(p, barrier.id, room.at(tile))
    }

    /** Tests: the server plays the room's owner - starts the room, attacks what the room says, walks out when clear. */
    private fun autopilot(room: SoloRoom) {
        val p = room.owner
        if (p.isDelayed || !p.isSlotAssigned || d.mapClock.cycle - room.builtAt < 4) return
        if (room.t < 0) {
            org.rsmod.game.entity.util.PathingEntityCommon.telejump(p, d.collision, room.at(room.autoStart))
            SoloRespawn.save(p)
            room.start()
            return
        }
        if (room.cleared) {
            val approach = if (System.getenv("TOB_PASSAGE") != null) room.passageApproach() else null
            if (approach != null) {
                if (p.isAccessProtected) return
                org.rsmod.game.entity.util.PathingEntityCommon.telejump(p, d.collision, room.at(approach.first))
                protectedAccess.launch(p) { passage(room.at(approach.second)) }
                return
            }
            org.rsmod.game.entity.util.PathingEntityCommon.telejump(p, d.collision, room.at(room.entry))
            return
        }
        room.autoAct(p)
        val target = room.autoTarget() ?: return
        // tests: bleed the target so a room's phases come round quickly
        val drain = System.getenv("TOB_DRAIN")?.toIntOrNull() ?: 0
        if (drain > 0 && room.t % 5 == 0 && target.hitpoints > 2) target.hitpoints = (target.hitpoints - target.baseHitpointsLvl * drain / 100).coerceAtLeast(1)
        if (p.interaction == null && target.isSlotAssigned && target.hitpoints > 0) bots.attack(p, target)
    }

    /**
     * The Formidable passage past a cleared room: you walk through it onto the room's exit line, the screen goes dark
     * red around the turning portal, and you come out at the next room under its name.
     */
    private suspend fun ProtectedAccess.passage(at: CoordGrid) {
        val room = raids.forPlayer(player) ?: return
        if (!room.cleared) return
        val next = room.id.next ?: return room.leftAfterClear()
        val dx = Integer.signum(at.x - player.coords.x)
        val dz = Integer.signum(at.z - player.coords.z)
        val through = CoordGrid(at.x + dx, at.z + dz, at.level)
        if ((dx == 0) != (dz == 0) && room.id.onLine(room.real(through))) forcedWalk(through, crossTiles = PASSAGE_CROSS)
        TobHud.portal(player)
        delay(PASSAGE_FADE_TICKS)
        travel(player, next, this)
        delay(1)
        TobHud.arrive(player, next.label)
    }

    private suspend fun ProtectedAccess.roomLoc(loc: String, at: CoordGrid, op: Int) {
        val room = raids.forPlayer(player) ?: return
        room.opLoc(loc, room.real(at), op, this)
    }

    private fun dumpMap(room: SoloRoom) {
        val id = room.id
        for (level in if (id.allLevels) listOf(0, 3) else listOf(id.level)) {
            for (y in id.maxY downTo id.minY) {
                val row = (id.minX..id.maxX).joinToString("") { x -> if (room.blocked(CoordGrid(x, y, level))) "#" else "." }
                if (row.contains('.')) logger.info { "tobmap ${id.name} L$level y=$y x${id.minX} $row" }
            }
        }
    }

    companion object {
        const val CHEST = "loc.tob_midway_chest_closed"
        /** NPCs and players in a room drawn from this far (3x the game's 15): the whole room, end to end. */
        const val VIEW_DISTANCE = 45
        const val SELF_TEST_AT = 30
        /** NPCs whose hits are judged when thrown (Vasilias's colour, Xarpus's gaze, a nylocas' damage on its way). */
        val QUEUE_WATCHED =
            listOf(
                "npc.tob_nylocas_incoming_melee", "npc.tob_nylocas_incoming_ranged", "npc.tob_nylocas_incoming_magic",
                "npc.tob_nylocas_big_incoming_melee", "npc.tob_nylocas_big_incoming_ranged", "npc.tob_nylocas_big_incoming_magic",
                "npc.tob_nylocas_fighting_melee", "npc.tob_nylocas_fighting_ranged", "npc.tob_nylocas_fighting_magic",
                "npc.tob_nylocas_big_fighting_melee", "npc.tob_nylocas_big_fighting_ranged", "npc.tob_nylocas_big_fighting_magic",
                "npc.nylocas_boss_spawning", "npc.nylocas_boss_melee", "npc.nylocas_boss_ranged", "npc.nylocas_boss_magic", "npc.tob_xarpus_combat",
                "npc.tob_xarpus_static", "npc.tob_xarpus_feeding")
        const val RUNES = 100_000
        val MELEE_STATS = listOf("stat.attack", "stat.strength", "stat.defence")
        const val SUPER_COMBAT_BOOST = 5
        const val SUPER_COMBAT_PERCENT = 15
        const val RANGING_BOOST = 4
        const val RANGING_PERCENT = 10
        const val HEART_BOOST = 4
        const val HEART_PERCENT = 10
        const val FULL_SPEC = 1000
        const val ANCIENTS = 1
        const val LUNARS = 2
        /** Bots keep each style's prayer on at once: the same as flicking the right one with every switch. */
        val TRIO_START_TICK = System.getenv("TOB_TRIO_START")?.toIntOrNull() ?: 0
        val OFFENSIVE_PRAYERS = listOf("varbit.prayer_piety", "varbit.prayer_rigour", "varbit.prayer_augury")

        /** Maxed raiders have the scroll prayers: Zeal over Piety (as the sim's melee). */
        val PRAYER_UNLOCKS = listOf("varbit.prayer_zeal_unlocked", "varbit.prayer_deadeye_unlocked", "varbit.prayer_mystic_vigour_unlocked")
        const val WATCH_LINGER = 8

        /** Dizana's quiver set to save ammo as an assembler does: none lands on the floor. */
        const val QUIVER_ASSEMBLER = 2
        const val BARRIER = "loc.tob_arena_barrier"

        /** A Pass click is handled the tick after it's sent: the bots click a tick before the room should start. */
        const val CLICK_LEAD = 1
        const val INSTANCE_CYCLE = 4
        val SELF_TEST_GEAR =
            listOf(
                dev.openrune.util.Wearpos.Hat.slot to "obj.torva_helm",
                dev.openrune.util.Wearpos.RightHand.slot to "obj.scythe_of_vitur",
                dev.openrune.util.Wearpos.Torso.slot to "obj.torva_chest",
                dev.openrune.util.Wearpos.Legs.slot to "obj.torva_legs",
            )
        val EXIT = CoordGrid(3676, 3219, 0)
        const val PASSAGE = "loc.tob_dungeon_walkway_exit_clickbox"
        const val PASSAGE_CROSS = 2
        const val PASSAGE_FADE_TICKS = 2
        const val PASSAGE_GRACE = 100
        val ROOM_LOCS =
            listOf(
                "loc.tob_dungeon_xarpus_arena_door_exit",
                "loc.tob_skeleton_with_weapon",
                "loc.tob_sotetseg_darkrealm_exit",
                "loc.tob_dungeon_verzik_entrance_door",
                "loc.tob_dungeon_verzik_throne_empty",
            )
    }
}

/** Pass on a barrier in a solo room (the barrier handler hands over here). */
object SoloBarrier {
    /** Bots, who go in without the "ready to fight?" question. */
    val noAsk = HashSet<Player>()

    suspend fun pass(access: ProtectedAccess, room: SoloRoom, barrier: Bounds) {
        with(access) {
            val here = room.real(player.coords)
            val inside = room.inArena(here)
            if (!inside) {
                if (room.cleared) return mes("The room is clear: head on.")
                if (room.t < 0 && player !in noAsk) {
                    val choice = chatMenu("Is your party ready to fight?", "Yes, let's begin.", "No, don't start yet.")
                    if (choice != 0) return
                    SoloRespawn.save(player)
                }
            } else if (!room.cleared) {
                return mes("You can't leave while the fight goes on.")
            }
            val p = player.coords
            val xs = barrier.x until barrier.x + barrier.width
            val zs = barrier.z until barrier.z + barrier.length
            val dest =
                when {
                    p.x in xs -> CoordGrid(p.x, if (p.z < zs.first) zs.last + 1 else zs.first - 1, p.level)
                    p.z in zs -> CoordGrid(if (p.x < xs.first) xs.last + 1 else xs.first - 1, p.z, p.level)
                    else -> return
                }
            if (!inside && room.t < 0) room.start()
            val dir =
                when {
                    dest.x > p.x -> constants.em_face_east
                    dest.x < p.x -> constants.em_face_west
                    dest.z > p.z -> constants.em_face_north
                    else -> constants.em_face_south
                }
            // as the game's barrier: you're on the far side from the next tick, the walk through drawn over two
            anim(PASS_SEQ)
            exactMove(p, dest, delay1 = 0, delay2 = PASS_CYCLES, dir = dir, teleportType = TeleportType.Exempt)
        }
    }

    /** Players walking through a barrier, and the tile they're walking to. */
    private val crossing = HashMap<Player, Pair<CoordGrid, Int>>()

    /**
     * Walks [player] through the barrier to [dest] (unchecked, the client's own walk). The script lets go as the last
     * step starts, so you're free to click on the tick you arrive; [settle] ends the walk once you're there.
     */
    private suspend fun ProtectedAccess.cross(player: Player, dest: CoordGrid, tiles: Int) {
        player.forcedRoute = true
        player.resetPendingFaceSquare()
        player.routeRequest = null
        player.routeDestination.clear()
        player.routeDestination.add(dest)
        player.moveSpeed = MoveSpeed.Walk
        player.tempMoveSpeed = MoveSpeed.Walk
        crossing[player] = dest to tiles + CROSS_GRACE
        if (tiles > 1) delay(tiles - 1)
    }

    /** After the tick's movement: a crossing that's arrived (or run out of time) hands movement back. */
    fun settle() {
        val it = crossing.iterator()
        while (it.hasNext()) {
            val (player, entry) = it.next()
            val (dest, left) = entry
            if (player.isSlotAssigned && player.coords != dest && left > 1) {
                crossing[player] = dest to left - 1
                continue
            }
            player.forcedRoute = false
            player.routeDestination.clear()
            player.tempMoveSpeed = null
            player.resetPendingFaceSquare()
            if (player.isSlotAssigned && player.coords != dest) org.rsmod.game.entity.util.PathingEntityCommon.telejump(player, collision ?: return, dest)
            it.remove()
        }
    }

    /** Set by [SoloRaidScript] at startup. */
    var collision: org.rsmod.routefinder.collision.CollisionFlagMap? = null

    private const val CROSS_GRACE = 2
    private const val PASS_SEQ = "seq.human_walk_f"
    private const val PASS_CYCLES = 60
}

/** Names of locs by id, for the room's handlers. */
fun locName(id: Int): String = RSCM.getReverseMapping(RSCMType.LOC, id)
