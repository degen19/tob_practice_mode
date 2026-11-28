package org.rsmod.content.raids.tob.solo

import org.rsmod.api.instances.InstanceSession
import org.rsmod.content.raids.tob.bloat.BloatRoom
import org.rsmod.content.raids.tob.bloat.BloatRules
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit
import org.rsmod.game.loc.LocAngle
import org.rsmod.map.CoordGrid

/**
 * The Pestilent Bloat, solo (1500 hitpoints): the practice room's Bloat ([BloatRoom]) - his walk round the tank, turns,
 * speed by hitpoints, downs, stomp, falling hands and flies - in the raid. You come in at the east barrier and leave by
 * the west one once he's dead.
 */
class BloatSolo(owner: Player, session: InstanceSession, d: SoloDeps, origin: CoordGrid, copyBase: CoordGrid) :
    SoloRoom(TobRoom.BLOAT, owner, session, d, origin, copyBase) {
    override val entry = CoordGrid(3305, 4447, 0)
    override val chest = CoordGrid(3308, 4449, 0) to LocAngle.West

    override fun inArena(c: CoordGrid): Boolean = BloatRules.inRoom(c.x - BASE_X, c.z - BASE_Y) && !BloatRules.inTank(c.x - BASE_X, c.z - BASE_Y)

    private lateinit var bloat: Npc
    lateinit var room: BloatRoom
        private set
    private var dead = -1

    override fun build() {
        dead = -1
        val hp = hp(BloatRules.hitpoints(1), entryHp(ENTRY_HP))
        bloat = spawn(NPC, CoordGrid(BASE_X + 8, BASE_Y + 8, 0), hp)
        val region = checkNotNull(d.instances.regionOf(session))
        room = BloatRoom(session, bloat, at(BASE_X, BASE_Y), d.random, d.worldRepo, d.playerHits, d.collision, hp, region.southWest, region.northEast)
        room.worldQueues = d.queues
        room.entry = entryMode
        val (sx, sy) = room.sw
        bloat.telejump(d.collision, at(BASE_X + sx, BASE_Y + sy))
    }

    /** He's sized for the party that comes in (Normal: 1500 up to a trio, 1750 in a four, 2000 in a five). */
    override fun onStart() {
        val team = 1 + helpers.size
        room.resize(hp(BloatRules.hitpoints(team), entryHp(ENTRY_HP, team)))
    }

    /** The bloat, for a crew. */
    val boss: Npc
        get() = bloat

    override fun floor(npc: Npc): Int? = if (npc === bloat && dead < 0) 1 else null

    override fun land(npc: Npc, hit: Hit): Int? {
        if (npc !== bloat) return null
        if (dead >= 0) return 0
        val dealt = room.land(hit.damage)
        if (HIT_TRACE) println("bloathit t${room.t} down ${room.down} dmg ${hit.damage} dealt $dealt by ${attacker(hit)?.username}")
        return dealt
    }

    /** He patrols his loop before the room starts, as in the game. */
    override fun idle() {
        if (dead < 0) room.tick(emptyList())
    }

    override fun step() {
        if (dead >= 0) {
            if (t - dead == DEATH_TICKS) {
                cleared = true
                mes("The way on is open: leave by the west barrier for the Nylocas.")
            }
            return
        }
        if (bloat.hitpoints <= 1) {
            dead = t
            bloat.hitpoints = 0
            bloat.routeDestination.clear()
            bloat.anim(DEATH_SEQ)
            sound(DEATH_SOUND, real(bloat.coords), DEATH_SOUND_RADIUS)
            later(DEATH_SEQ_TICKS) { despawn(bloat) }
            mes("The Pestilent Bloat is dead.")
            return
        }
        val was = room.mode
        room.tick((listOf(owner) + helpers).filter { it.isSlotAssigned })
        if (room.mode != was) recordEvent(if (room.mode == BloatRoom.Mode.Down) 110 else 111, "\"bloatDown\":{}")
        recordHands()
    }

    private val handsFalling = HashMap<Int, String>()

    /** Hands into the room's recording as Blert has them: the drop (112) and the splat (113), with their tiles. */
    private fun recordHands() {
        for ((lands, tiles) in room.falling) {
            if (lands in handsFalling) continue
            val json = "\"bloatHands\":[" + tiles.joinToString(",") { (x, y) -> "{\"x\":${BASE_X + x},\"y\":${BASE_Y + y}}" } + "]"
            handsFalling[lands] = json
            recordEvent(HANDS_DROP, json)
        }
        val landed = handsFalling.keys.filter { it <= room.t }
        for (lands in landed) recordEvent(HANDS_SPLAT, handsFalling.remove(lands)!!)
    }

    override fun autoTarget(): Npc? = bloat

    override val autoStart: CoordGrid
        get() = CoordGrid(3301, 4447, 0)

    override fun barBoss(): Npc? = bloat

    override fun debug(): String = "bloat ${bloat.hitpoints} ${room.mode} down ${room.down} at ${real(bloat.coords)} dead $dead"

    private companion object {
        const val BASE_X = 3280
        const val BASE_Y = 4432
        const val NPC = "npc.tob_bloat"
        const val DEATH_SEQ = "seq.tob_bloat_death"
        const val DEATH_SOUND = "synth.tob_bloat_death"
        const val DEATH_SOUND_RADIUS = 15
        const val DEATH_TICKS = 6
        const val DEATH_SEQ_TICKS = 3
        const val ENTRY_HP = 1600
        const val HANDS_DROP = 112
        val HIT_TRACE = System.getenv("TOB_HIT_TRACE") != null
        const val HANDS_SPLAT = 113
    }
}
