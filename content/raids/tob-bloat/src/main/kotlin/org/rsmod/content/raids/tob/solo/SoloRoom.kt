package org.rsmod.content.raids.tob.solo

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.MoveRestrict
import dev.openrune.types.aconverted.SpotanimType
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.npc.heal
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.baseHitpointsLvl
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.game.proj.ProjAnim
import org.rsmod.map.CoordGrid
import kotlin.math.abs
import kotlin.math.max

/**
 * One room of a solo raid: a private copy of the room's map, the NPCs it spawns and the locs it puts down, and its clock.
 * [t] is -1 until the player passes the entry barrier (the game's room timer starts then) and counts up a tick at a time.
 *
 * Coordinates are the real game's ([at] turns a real tile into this copy's tile, [real] the other way), so the room's
 * code reads like the map.
 */
abstract class SoloRoom(val id: TobRoom, val owner: Player, val session: InstanceSession, val d: SoloDeps, private val origin: CoordGrid, private val copyBase: CoordGrid) {
    var t = -1
        protected set

    protected val logger = com.github.michaelbull.logging.InlineLogger()

    /** The map clock when the room was built (the arrival teleport lands a couple of ticks later). */
    val builtAt: Int = d.mapClock.cycle

    /** The boss is dead: the way on is open. */
    var cleared = false
        protected set

    private val npcs = ArrayList<Npc>()
    private val locs = ArrayList<LocInfo>()

    fun at(x: Int, y: Int, level: Int = id.level): CoordGrid = CoordGrid(copyBase.x + x - origin.x, copyBase.z + y - origin.z, level)

    fun at(c: CoordGrid): CoordGrid = at(c.x, c.z, c.level)

    fun real(c: CoordGrid): CoordGrid = CoordGrid(c.x - copyBase.x + origin.x, c.z - copyBase.z + origin.z, c.level)

    fun contains(c: CoordGrid): Boolean {
        val r = real(c)
        return r.x in id.minX..id.maxX && r.z in id.minY..id.maxY
    }

    /** The player is in this room's copy and alive. */
    fun player(): Player? = owner.takeIf { contains(it.coords) && it.hitpoints > 0 && it.isSlotAssigned }

    /** The player, if inside the arena (past the entry barrier). */
    fun inside(): Player? = player()?.takeIf { inArena(real(it.coords)) }

    abstract fun inArena(c: CoordGrid): Boolean

    /** Where the player stands to start: in front of the entry barrier (real tile). */
    abstract val entry: CoordGrid

    /** The supply chest's tile and facing, outside the barrier (real tile). */
    abstract val chest: Pair<CoordGrid, LocAngle>

    /** Puts the room's NPCs and locs down (the room is idle until [start]). */
    abstract fun build()

    /** The player came through the entry barrier: the room's timer starts. */
    open fun start() {
        if (t >= 0) return
        t = 0
        onStart()
    }

    protected open fun onStart() {}

    fun tick() {
        if (t < 0) {
            idle()
            return
        }
        snapshot()
        step()
        t++
        bossBar()
    }

    /* ---- the recording: every tick of the room in Blert's shape (stage events), to set beside real raids */

    private val recording = StringBuilder()

    /** Where the room is in Blert's stage numbering (10 Maiden ... 15 Verzik). */
    private val stage: Int
        get() = 10 + id.ordinal

    /** Everyone's tile and hitpoints at the start of this tick (where the last tick left them). */
    private fun snapshot() {
        if (recording.length > MAX_RECORDING) return
        val p = player()
        if (p != null) {
            val r = real(p.coords)
            recording.append("{\"type\":4,\"stage\":$stage,\"tick\":$t,\"xCoord\":${r.x},\"yCoord\":${r.z},\"plane\":${r.level},")
                .append("\"player\":{\"name\":\"${p.username}\",\"hitpoints\":${(p.hitpoints shl 16) or p.baseHitpointsLvl}}}\n")
        }
        for (n in npcs) {
            if (!n.isSlotAssigned) continue
            val r = real(n.coords)
            recording.append("{\"type\":8,\"stage\":$stage,\"tick\":$t,\"xCoord\":${r.x},\"yCoord\":${r.z},\"plane\":${r.level},")
                .append("\"npc\":{\"id\":${n.id},\"roomId\":${n.slotId},\"hitpoints\":${(n.hitpoints.coerceAtLeast(0) shl 16) or n.baseHitpointsLvl}}}\n")
        }
    }

    /** Notes an attack (Blert's NPC_ATTACKED, type 10; [attack] is Blert's NpcAttack number) at this tick. */
    fun recordAttack(npc: Npc, attack: Int) {
        if (recording.length > MAX_RECORDING) return
        val r = real(npc.coords)
        val target = player()?.username ?: ""
        recording.append("{\"type\":10,\"stage\":$stage,\"tick\":$t,\"xCoord\":${r.x},\"yCoord\":${r.z},")
            .append("\"npc\":{\"id\":${npc.id},\"roomId\":${npc.slotId}},\"npcAttack\":{\"attack\":$attack,\"target\":\"$target\"}}\n")
    }

    /** Notes a room event of Blert's [type] with its fields ([json]: the inside of an object) at this tick. */
    fun recordEvent(type: Int, json: String) {
        if (recording.length > MAX_RECORDING) return
        recording.append("{\"type\":$type,\"stage\":$stage,\"tick\":$t,\"xCoord\":0,\"yCoord\":0,$json}\n")
    }

    /** Writes the recording to .data/recordings (one file per attempt at the room). */
    private fun saveRecording() {
        if (recording.isEmpty()) return
        try {
            val dir = java.io.File(".data/recordings")
            dir.mkdirs()
            val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            java.io.File(dir, "${id.name.lowercase()}-${if (entryMode) "entry" else "normal"}-$stamp.jsonl").writeText(recording.toString())
        } catch (_: Exception) {
        }
        recording.setLength(0)
    }

    /**
     * Where the instance is in its 4-tick cycle: 0 on the cycle's first tick (when Nylocas waves come, Sotetseg
     * wakes from a maze and Xarpus screeches), then 1, 2, 3. It runs on the world clock, so it carries on from room
     * to room as the raid's one instance does.
     */
    val cycle: Int
        get() = Math.floorMod(d.mapClock.cycle, INSTANCE_CYCLE)

    /** The instance timer over the player's head, 1 to 4 (1 on the cycle's first tick), in the rooms that use it. */
    protected fun showCycle() {
        inside()?.say("${cycle + 1}")
    }

    /** The room's boss for the big health bar at the top of the screen (null: no bar). */
    protected open fun barBoss(): Npc? = null

    private var barNpc: Npc? = null
    private var barHp = -1
    private var barMax = -1

    /** The big boss health bar: opened once the room has started, kept in step with the boss's hitpoints. */
    private fun bossBar() {
        val p = player() ?: return
        val boss = barBoss()?.takeIf { it.isSlotAssigned && !cleared }
        if (boss == null) {
            barNpc?.let { d.bossBar.onClose(p, it) }
            barNpc = null
            return
        }
        if (boss !== barNpc) {
            d.bossBar.onOpen(p, boss)
            barNpc = boss
            barHp = -1
        }
        if (boss.hitpoints != barHp || boss.baseHitpointsLvl != barMax) {
            barHp = boss.hitpoints
            barMax = boss.baseHitpointsLvl
            d.bossBar.onUpdate(p, boss, boss.hitpoints.coerceAtLeast(0), boss.baseHitpointsLvl)
        }
    }

    /** A tick before the room has started. */
    protected open fun idle() {}

    /** A tick of the room ([t] is this tick). */
    protected abstract fun step()

    /** After the tick's npc and player processing (hits have landed). */
    open fun late() {}

    /** A hit landing on one of this room's NPCs: the damage it does (null: not ours, leave it). */
    open fun land(npc: Npc, hit: Hit): Int? = null

    /** The lowest an NPC's hitpoints can be taken by a hit (null: not ours). */
    open fun floor(npc: Npc): Int? = null

    open fun owns(npc: Npc): Boolean = npc in npcs

    /** A click on one of the room's locs (by real tile): true when handled. */
    open suspend fun opLoc(name: String, at: CoordGrid, op: Int, access: ProtectedAccess): Boolean = false

    /** Autopilot (tests): what to attack, where to stand to start, and a state line for the log. */
    open fun autoTarget(): Npc? = null

    open val autoStart: CoordGrid
        get() = entry

    /** Autopilot: anything the room needs doing that isn't an attack (e.g. the maze portal). */
    open fun autoAct(p: Player) {}

    open fun debug(): String = ""

    /** The player walked out of the last room after clearing it. */
    open fun leftAfterClear() {}

    /** Whether stepping out of the arena once it's clear takes you on (false: the room has its own way out). */
    open val walkOn: Boolean = true

    /** The player died in here: the room goes back to how it was. */
    open fun reset() {
        cleanup()
        cleared = false
        t = -1
        build()
    }

    fun cleanup() {
        saveRecording()
        val p = player()
        barNpc?.let { if (p != null) d.bossBar.onClose(p, it, instant = true) }
        barNpc = null
        for (n in npcs) if (n.isSlotAssigned) d.npcRepo.del(n, Int.MAX_VALUE)
        npcs.clear()
        for (l in locs) d.locRepo.del(l, Int.MAX_VALUE)
        locs.clear()
        onCleanup()
    }

    protected open fun onCleanup() {}

    /* ---- helpers */

    fun spawn(type: String, real: CoordGrid, hp: Int? = null, walk: Boolean = true): Npc {
        val npc = Npc(type, at(real))
        d.npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        npc.ignoreCombatInteractions = true
        npc.moveRestrict = MoveRestrict.PassThru
        npc.defaultMoveSpeed = MoveSpeed.Walk
        if (hp != null) {
            npc.baseHitpointsLvl = hp
            npc.hitpoints = hp
        }
        d.instances.attachNpc(session.id, npc)
        npcs += npc
        return npc
    }

    fun despawn(npc: Npc) {
        npcs.remove(npc)
        if (npc.isSlotAssigned) d.npcRepo.del(npc, Int.MAX_VALUE)
    }

    fun transmog(npc: Npc, type: String) {
        ServerCacheManager.getNpc(type.asRSCM(RSCMType.NPC))?.let { npc.transmog(it, Int.MAX_VALUE) }
    }

    fun loc(type: String, real: CoordGrid, angle: LocAngle = LocAngle.West, shape: LocShape = LocShape.GroundDecor): LocInfo {
        val l = d.locRepo.add(at(real), type, Int.MAX_VALUE, angle, shape)
        locs += l
        return l
    }

    fun delLoc(l: LocInfo) {
        if (locs.remove(l)) d.locRepo.del(l, Int.MAX_VALUE)
    }

    /** A graphic on a tile (real coords). */
    fun gfx(spot: String, real: CoordGrid, height: Int = 0, delay: Int = 0) {
        d.worldRepo.spotanimMap(SpotanimType(spot.asRSCM(RSCMType.SPOTANIM)), at(real), height, delay)
    }

    /** A projectile from [from] to a player ([homing]) or a tile; returns the ticks until it lands. */
    fun proj(spot: String, from: Npc, to: Player?, toTile: CoordGrid? = null, delay: Int = 30, speed: Int = 10, startHeight: Int = 85, endHeight: Int = 31, angle: Int = 16, offset: Int = 0): Int {
        val target = to?.coords ?: at(toTile!!)
        val src = from.coords.translate(from.size / 2, from.size / 2)
        val dist = max(abs(src.x - target.x), abs(src.z - target.z))
        val end = delay + offset + speed * dist
        val p =
            ProjAnim(
                spotanim = spot.asRSCM(RSCMType.SPOTANIM),
                startHeight = startHeight,
                endHeight = endHeight,
                startTime = delay,
                endTime = end,
                angle = angle,
                progress = from.size * 64,
                sourceIndex = from.slotId + 1,
                targetIndex = to?.let { -(it.slotId + 1) } ?: 0,
                startCoord = src,
                endCoord = target,
            )
        d.worldRepo.projAnim(p)
        return (end + 29) / 30
    }

    /** A projectile between two tiles (real coords), e.g. a split off a player; returns ticks to land. */
    fun projTiles(spot: String, fromReal: CoordGrid, toReal: CoordGrid, delay: Int = 0, speed: Int = 10, startHeight: Int = 40, endHeight: Int = 0, angle: Int = 16, target: Player? = null, start: Int? = null): Int {
        val src = at(fromReal)
        val dst = target?.coords ?: at(toReal)
        val dist = max(abs(src.x - dst.x), abs(src.z - dst.z))
        val end = delay + speed * max(1, dist)
        // [start]: when it leaves, if not at [delay] (the landing still counts from [delay])
        val p = ProjAnim(spot.asRSCM(RSCMType.SPOTANIM), startHeight, endHeight, start ?: delay, end, angle, 0, 0, target?.let { -(it.slotId + 1) } ?: 0, src, dst)
        d.worldRepo.projAnim(p)
        return (end + 29) / 30
    }

    /**
     * A hit on the player now (a tick's delay, as hits land): [prayer] is the protection prayer that applies and what it
     * keeps off (1.0 blocks it all, 0.5 halves it). The damage is worked out here, so the hit goes in typeless.
     */
    /** Queues a hit on [p] ([delay] ticks); returns the damage it will deal (after Entry mode and prayer). */
    fun hurt(p: Player, damage: Int, prayer: String? = null, blocks: Double = 1.0, source: Npc? = null, delay: Int = 1): Int {
        var dmg = if (entryMode) damage / 2 else damage
        if (prayer != null && p.vars["varbit.prayer_protectfrom$prayer"] == 1) dmg = (dmg * (1.0 - blocks)).toInt()
        dmg = dmg.coerceAtLeast(0)
        val delay = delay.coerceAtLeast(1)
        if (source != null && source.isSlotAssigned) p.queueHit(source, delay, HitType.Typeless, dmg, d.playerHits) else p.queueHit(delay, HitType.Typeless, dmg, d.playerHits)
        return dmg
    }

    /**
     * Telejumps [p] to [dest] (an instance coord) and moves the build area with it on the same tick, so the
     * scene rebuild goes out with the teleport. Without that, a jump made after this cycle's build-area pass
     * (Sote's realm is ~77 tiles off the arena) can leave the client drawing the model in the old scene.
     */
    fun jump(p: Player, dest: CoordGrid) {
        org.rsmod.game.entity.util.PathingEntityCommon.telejump(p, d.collision, dest)
        // an animation that stalls walking (Sote's teleport impact) would keep the model on the arrival tile while
        // the player walks off: drop it with the jump
        p.resetAnim()
        val zone = org.rsmod.map.zone.ZoneKey.from(dest)
        p.buildArea = zone.translate(-BUILD_RADIUS, -BUILD_RADIUS).toCoords()
        p.clientCycle.forceSceneRebuild()
    }

    /** Every loc of [type] in this room's copy, as (real tile, loc). */
    fun findLocs(type: String): List<Pair<CoordGrid, LocInfo>> {
        val id = type.asRSCM(RSCMType.LOC)
        val out = ArrayList<Pair<CoordGrid, LocInfo>>()
        val levels = if (this.id.allLevels) 0..3 else 0..3
        for (level in levels) {
            var zx = this.id.minX
            while (zx <= this.id.maxX) {
                var zz = this.id.minY
                while (zz <= this.id.maxY) {
                    for (l in d.locRepo.findAll(org.rsmod.map.zone.ZoneKey.from(at(zx, zz, level)))) if (l.id == id) out += real(l.coords) to l
                    zz += 8
                }
                zx += 8
            }
        }
        return out
    }

    /** Marks [size]x[size] tiles from [realSw] as solid to players (a boss you can't walk under), or clears them. */
    fun blockPlayers(realSw: CoordGrid, size: Int, block: Boolean) {
        val sw = at(realSw)
        for (x in 0 until size) for (z in 0 until size) {
            if (block) d.collision.add(sw.x + x, sw.z + z, sw.level, org.rsmod.routefinder.flag.CollisionFlag.BLOCK_PLAYERS)
            else d.collision.remove(sw.x + x, sw.z + z, sw.level, org.rsmod.routefinder.flag.CollisionFlag.BLOCK_PLAYERS)
        }
    }

    /** A damage hitsplat on [npc] without touching its hitpoints ([hp] of [max]: what it stands at after). */
    fun showDamage(npc: Npc, damage: Int, hp: Int, max: Int) {
        if (!npc.isSlotAssigned) return
        val group = if (damage > 0) org.rsmod.api.config.refs.done.hitmark_groups.regular_damage else org.rsmod.api.config.refs.done.hitmark_groups.zero_damage
        val id = group.lit.asRSCM(RSCMType.HITMARK)
        val mark = org.rsmod.game.hit.Hitmark.fromNoSource(self = id, source = id, public = id, damage = damage, delay = 0)
        npc.showHitmark(mark)
    }

    /** Heals [npc] by [amount] (capped at its max), with the heal hitsplat, and updates the boss bar. */
    fun healNpc(npc: Npc, amount: Int) {
        if (amount <= 0 || !npc.isSlotAssigned) return
        npc.heal(amount, showHitsplat = true)
        player()?.let { d.bossBar.onUpdate(it, npc) }
    }

    /**
     * One step of the game's plain NPC movement toward a tile: diagonally if it can, else along x, else along z
     * (real coords). Returns false when every way is blocked.
     */
    fun stepToward(npc: Npc, tx: Int, tz: Int): Boolean {
        val r = real(npc.coords)
        val sx = sign(tx - r.x)
        val sz = sign(tz - r.z)
        if (sx == 0 && sz == 0) return false
        val options = listOf(sx to sz, sx to 0, 0 to sz).filter { (x, z) -> x != 0 || z != 0 }.distinct()
        for ((x, z) in options) {
            if (canStep(npc, r, x, z)) {
                npc.walk(at(r.x + x, r.z + z, r.level))
                return true
            }
        }
        return false
    }

    /** Whether an npc of its size can step from [r] by (dx, dz) - every tile of its new edge must be open. */
    private fun canStep(npc: Npc, r: CoordGrid, dx: Int, dz: Int): Boolean {
        val size = npc.size
        for (i in 0 until size) {
            for (j in 0 until size) {
                val c = CoordGrid(r.x + dx + i, r.z + dz + j, r.level)
                if (blocked(c)) return false
            }
        }
        return true
    }

    /** Entry mode (fixed when the room is built): bosses have far fewer hitpoints and hit about half as hard. */
    val entryMode: Boolean = d.raids.isEntry(owner)

    /**
     * Hitpoints for this mode: [normal] on Normal mode; on Entry mode [entryValue] if known, else
     * [ENTRY_HP_PERCENT]% of the five-player Normal hitpoints ([normal] is the solo value, 75% of that).
     */
    fun hp(normal: Int, entryValue: Int? = null): Int =
        if (!entryMode) normal else entryValue ?: maxOf(1, normal * 4 / 3 * ENTRY_HP_PERCENT / 100)

    /**
     * A boss's Entry mode hitpoints from its five-player base value. Blert's Entry raids: a solo's bosses have 20%
     * (Xarpus 680, Verzik 240 / 320 / 320), a four's 80% (Xarpus 2720, Verzik 960 / 1280 / 1280). Two and three
     * aren't recorded; they're taken as 40% and 60%. The four's Nylocas Vasilias (1440) and Sotetseg (2240) put
     * their five-player bases at 1800 and 2800.
     */
    fun entryHp(base: Int, partySize: Int = 1): Int = base * partySize.coerceIn(1, 5) / 5

    fun prays(p: Player, prayer: String): Boolean = p.vars["varbit.prayer_protectfrom$prayer"] == 1

    /** Runs [block] [ticks] ticks from now (on the world queue), if the room still stands. */
    fun later(ticks: Int, block: () -> Unit) {
        val epoch = resets
        d.queues.add(ticks.coerceAtLeast(1)) {
            try {
                if (epoch == resets && d.raids.isLive(this)) block()
            } catch (e: Exception) {
                com.github.michaelbull.logging.InlineLogger().error(e) { "tob solo: queued room action failed (${id})" }
            }
        }
    }

    /** Counts resets, so anything queued before one is dropped. */
    var resets = 0
        internal set

    fun mes(text: String) = owner.mes(text)

    fun rand(n: Int): Int = d.random.of(0, n - 1)

    fun rand(lo: Int, hi: Int): Int = d.random.of(lo, hi)

    fun chance(p: Double): Boolean = d.random.randomDouble() < p

    /** Chebyshev distance from a tile to an NPC's footprint (0 under it). */
    fun dist(c: CoordGrid, n: Npc): Int {
        val r = real(n.coords)
        return boxDist(c.x, c.z, r.x, r.z, r.x + n.size - 1, r.z + n.size - 1)
    }

    companion object {
        /** About 20,000 ticks of a busy room; a stuck room stops recording rather than filling memory. */
        const val MAX_RECORDING = 40_000_000

        const val INSTANCE_CYCLE = 4

        /** Zones from the player's zone to the build area's south-west corner (a 104-tile scene). */
        const val BUILD_RADIUS = 6

        /** Entry mode bosses: about this share of their five-player Normal hitpoints (solo; an estimate). */
        const val ENTRY_HP_PERCENT = 20

        fun boxDist(x: Int, y: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int = max(max(x0 - x, x - x1).coerceAtLeast(0), max(y0 - y, y - y1).coerceAtLeast(0))

        fun sign(v: Int): Int = if (v > 0) 1 else if (v < 0) -1 else 0
    }
}
