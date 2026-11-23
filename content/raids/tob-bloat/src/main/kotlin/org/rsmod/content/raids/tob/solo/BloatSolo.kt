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
    private lateinit var room: BloatRoom
    private var dead = -1

    override fun build() {
        dead = -1
        val hp = hp(BloatRules.hitpoints(1), entryHp(1600))
        bloat = spawn(NPC, CoordGrid(BASE_X + 8, BASE_Y + 8, 0), hp)
        val region = checkNotNull(d.instances.regionOf(session))
        room = BloatRoom(session, bloat, at(BASE_X, BASE_Y), d.random, d.worldRepo, d.playerHits, d.collision, hp, region.southWest, region.northEast)
        room.worldQueues = d.queues
        room.entry = entryMode
        val (sx, sy) = room.sw
        bloat.telejump(d.collision, at(BASE_X + sx, BASE_Y + sy))
    }

    override fun floor(npc: Npc): Int? = if (npc === bloat && dead < 0) 1 else null

    override fun land(npc: Npc, hit: Hit): Int? {
        if (npc !== bloat) return null
        if (dead >= 0) return 0
        return room.land(hit.damage)
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
            later(DEATH_SEQ_TICKS) { despawn(bloat) }
            mes("The Pestilent Bloat is dead.")
            return
        }
        val was = room.mode
        room.tick(listOfNotNull(player()))
        if (room.mode != was) recordEvent(if (room.mode == BloatRoom.Mode.Down) 110 else 111, "\"bloatDown\":{}")
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
        const val DEATH_TICKS = 6
        const val DEATH_SEQ_TICKS = 3
    }
}
