package org.rsmod.content.raids.tob.bloat.verzik

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import kotlin.math.abs
import kotlin.math.max
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.proj.ProjAnim
import org.rsmod.map.CoordGrid

/**
 * How Verzik's room looks and sounds, shared by every room that plays her out (the Blert replays and the sim's raids):
 * her attacks' animations, projectiles (each one's own flight, height and arc), impacts and the sounds the server
 * sends, her deaths and rises, the reds' summon and pop, the purple's throw, the tornado's vanish. The values are
 * the solo room's (VerzikSolo), timed from Blert. Tiles are the room's real coordinates; [at] puts them in the
 * instance and [later] runs something that many ticks on.
 */
class VerzikLooks(
    private val worldRepo: WorldRepository,
    private val at: (CoordGrid) -> CoordGrid,
    private val later: (Int, () -> Unit) -> Unit,
) {
    fun p1Auto(v: Npc, vCentre: CoordGrid, targets: List<Pair<Player, CoordGrid?>>) {
        v.anim(P1_ATTACK)
        for ((p, pillar) in targets) {
            if (pillar != null) {
                val lands = projTiles(P1_PROJ, vCentre, pillar, delay = P1_LAND_CYCLES, speed = 0, startHeight = P1_START_HEIGHT, endHeight = 60, angle = P1_ANGLE, start = P1_LAUNCH)
                later(lands) {
                    gfx(P1_IMPACT_PILLAR, pillar, height = 60)
                    sound(PILLAR_HIT_SOUND, pillar)
                }
                continue
            }
            val lands = flight(P1_PROJ, v, p, start = P1_LAUNCH, end = P1_LAND_CYCLES, startHeight = P1_START_HEIGHT, angle = P1_ANGLE)
            later(lands) {
                p.spotanim(P1_IMPACT)
                worldRepo.soundArea(p, P1_IMPACT_SOUND, radius = SOUND_RADIUS)
            }
        }
    }

    fun urn(v: Npc, vCentre: CoordGrid, p: Player, tile: CoordGrid) {
        v.facePlayer(p)
        v.anim(P2_MAGIC)
        val lands = projTiles(URN_PROJ, vCentre, tile, delay = URN_TICKS * 30, speed = 0, startHeight = 100, endHeight = 0, angle = 40, start = 30)
        later(lands) { gfx(URN_IMPACT, tile) }
    }

    fun zap(v: Npc, p: Player) {
        v.facePlayer(p)
        v.anim(P2_MAGIC)
        flight(ZAP_PROJ, v, p, start = 30, end = ZAP_LANDS * 30, startHeight = 100)
    }

    fun bounce(v: Npc, p: Player) {
        v.facePlayer(p)
        v.anim(P2_MELEE)
        p.spotanim(STUNNED_GFX)
        p.anim(STUNNED)
    }

    fun purple(v: Npc, vCentre: CoordGrid, tile: CoordGrid) {
        v.anim(P2_MAGIC)
        val dist = max(1, max(abs(vCentre.x - tile.x), abs(vCentre.z - tile.z)))
        projTiles(PURPLE_PROJ, vCentre, tile, delay = 30, speed = (ATHANATOS_FLIGHT * 30 - 31) / dist, startHeight = 100, endHeight = 0, angle = 40)
    }

    fun purpleHeal(purpleCentre: CoordGrid, vCentre: CoordGrid) {
        projTiles(PURPLE_HEAL_PROJ, purpleCentre, vCentre, speed = 6, startHeight = 20, endHeight = 80)
    }

    fun blood(v: Npc, p: Player) {
        v.facePlayer(p)
        v.anim(P2_MAGIC)
        val lands = proj(BLOOD_PROJ, v, p, delay = 20, speed = 8, startHeight = 100)
        later(lands) { p.spotanim(BLOOD_IMPACT) }
    }

    fun summonReds(v: Npc) = v.anim(P2_HEAL)

    /** The shield's dome round her (a real raid: it holds for the shield's ticks); resent each tick, it lasts one. */
    fun shield(v: Npc) = v.spotanim(SHIELD_GFX)

    fun spawning(tile: CoordGrid) = gfx(SPAWNING_GFX, tile)

    fun redArrives(r: Npc) = r.anim(RED_SPAWN_SEQ)

    fun purpleArrives(p: Npc) = p.anim(PURPLE_SPAWN_SEQ)

    fun redPops(r: Npc, rCentre: CoordGrid, vCentre: CoordGrid) {
        projTiles(RED_HEAL_PROJ, rCentre, vCentre, speed = RED_HEAL_SPEED, startHeight = 40, endHeight = 100)
        r.anim(RED_POP_SEQ)
    }

    fun p3Melee(v: Npc, p: Player?) {
        p?.let { v.facePlayer(it) }
        v.anim(P3_MELEE)
    }

    fun p3Ranged(v: Npc, p: Player) {
        v.facePlayer(p)
        v.anim(P3_RANGED)
        proj(P3_RANGE_PROJ, v, p, delay = 30, speed = 6, startHeight = 120)
    }

    fun p3Magic(v: Npc, p: Player) {
        v.facePlayer(p)
        v.anim(P3_MAGIC)
        proj(P3_MAGE_PROJ, v, p, delay = 30, speed = 6, startHeight = 120)
    }

    fun webs(v: Npc) = v.anim(P3_WEBS)

    /** A web shot at [tile] (the solo room's): ticks until it lands. */
    fun web(vCentre: CoordGrid, tile: CoordGrid): Int = projTiles(WEB_PROJ, vCentre, tile, delay = 10, speed = 8, startHeight = 100, endHeight = 0, angle = 30)

    fun yellows(v: Npc) = v.anim(P3_YELLOWS)

    fun summonCrawler(v: Npc) = v.anim(P3_SUMMON)

    fun greenBall(v: Npc, p: Player) {
        v.facePlayer(p)
        v.anim(P3_MAGIC)
        val lands = proj(BALL_PROJ, v, p, delay = 30, speed = 10, startHeight = 120)
        later(lands) { p.spotanim(BALL_IMPACT) }
    }

    fun tornadoGone(tor: Npc) {
        tor.routeDestination.clear()
        tor.anim(TORNADO_DESPAWN_SEQ)
    }

    fun tornadoHit(v: Npc) = v.spotanim(TORNADO_HIT_GFX)

    fun p1Dies(v: Npc) = v.anim(P1_DEATH)

    fun p2Arrives(v: Npc) = later(1) { if (v.isSlotAssigned) v.anim(P2_SPAWN) }

    fun p2Dies(v: Npc) = v.anim(P2_DEATH)

    fun p3Rises(v: Npc) = v.anim(P3_SPAWN)

    fun p3Dies(v: Npc, vCentre: CoordGrid) {
        v.routeDestination.clear()
        v.anim(P3_DEATH)
        sound(P3_DEATH_SOUND, vCentre)
    }

    private fun gfx(spot: String, tile: CoordGrid, height: Int = 0) =
        worldRepo.spotanimMap(dev.openrune.types.aconverted.SpotanimType(spot.asRSCM(RSCMType.SPOTANIM)), at(tile), height = height)

    private fun sound(synth: String, tile: CoordGrid) = worldRepo.soundArea(at(tile), synth, radius = SOUND_RADIUS)

    /** Her projectile at [to] (SoloRoom.proj): leaves at [delay] client cycles, [speed] per tile; ticks until it lands. */
    private fun proj(spot: String, from: Npc, to: Player, delay: Int = 30, speed: Int = 10, startHeight: Int = 85, endHeight: Int = 31, angle: Int = 16, offset: Int = 0): Int {
        val src = from.coords.translate(from.size / 2, from.size / 2)
        val dist = max(abs(src.x - to.coords.x), abs(src.z - to.coords.z))
        val end = delay + offset + speed * dist
        worldRepo.projAnim(ProjAnim(spot.asRSCM(RSCMType.SPOTANIM), startHeight, endHeight, delay, end, angle, from.size * 64, from.slotId + 1, -(to.slotId + 1), src, to.coords))
        return (end + 29) / 30
    }

    /** Her projectile at [to] that leaves at client cycle [start] and lands at [end] (Blert's landing tick); ticks until it lands. */
    private fun flight(spot: String, from: Npc, to: Player, start: Int, end: Int, startHeight: Int, endHeight: Int = 31, angle: Int = 16): Int {
        val src = from.coords.translate(from.size / 2, from.size / 2)
        worldRepo.projAnim(ProjAnim(spot.asRSCM(RSCMType.SPOTANIM), startHeight, endHeight, start, end, angle, from.size * 64, from.slotId + 1, -(to.slotId + 1), src, to.coords))
        return (end + 29) / 30
    }

    /** A projectile between tiles (SoloRoom.projTiles); [start] when it leaves, if not at [delay]. */
    private fun projTiles(spot: String, fromReal: CoordGrid, toReal: CoordGrid, delay: Int = 0, speed: Int = 10, startHeight: Int = 40, endHeight: Int = 0, angle: Int = 16, start: Int? = null): Int {
        val src = at(fromReal)
        val dst = at(toReal)
        val dist = max(abs(src.x - dst.x), abs(src.z - dst.z))
        val end = delay + speed * max(1, dist)
        worldRepo.projAnim(ProjAnim(spot.asRSCM(RSCMType.SPOTANIM), startHeight, endHeight, start ?: delay, end, angle, 0, 0, 0, src, dst))
        return (end + 29) / 30
    }

    private companion object {
        const val SOUND_RADIUS = 15

        /** Blert: her P1 blast hurts 2 ticks after her attack (537 of 566), the P2 spark too (320 of 345). */
        const val ZAP_LANDS = 2
        /** A real raid frame by frame: the orb leaves her hands ~1.5 ticks into her attack, high, and lands ~2.3 ticks in. */
        const val P1_LAUNCH = 45
        const val P1_LAND_CYCLES = 70
        const val P1_START_HEIGHT = 140
        const val P1_ANGLE = 16
        const val URN_TICKS = 6
        const val ATHANATOS_FLIGHT = 6
        const val RED_HEAL_SPEED = 4
        const val P1_ATTACK = "seq.verzik_phase1_attack_magic"
        const val P1_DEATH = "seq.verzik_phase1_death"
        const val P1_PROJ = "spotanim.verzik_phase1_projectile"
        const val P1_IMPACT = "spotanim.verzik_phase1_impact"
        const val P1_IMPACT_PILLAR = "spotanim.verzik_phase1_impactpillar"
        const val P1_IMPACT_SOUND = "synth.tob_verzik_spark_2"
        const val PILLAR_HIT_SOUND = "synth.tob_pillar_hit"
        const val P2_SPAWN = "seq.verzik_phase2_spawn"
        const val P2_MAGIC = "seq.verzik_phase2_attack_magic"
        const val P2_MELEE = "seq.verzik_phase2_attack_melee"
        const val P2_HEAL = "seq.verzik_phase2_heal"
        const val P2_DEATH = "seq.verzik_phase2_death"
        const val URN_PROJ = "spotanim.verzik_phase2_ranged"
        const val URN_IMPACT = "spotanim.verzik_ranged_impact"
        const val ZAP_PROJ = "spotanim.verzik_phase2_lightning"
        const val BLOOD_PROJ = "spotanim.verzik_phase2_bloodproj"
        const val BLOOD_IMPACT = "spotanim.verzik_phase2_bloodimpact"
        const val PURPLE_PROJ = "spotanim.verzik_phase2_spawn_armouredtank_proj"
        const val PURPLE_HEAL_PROJ = "spotanim.verzik_phase2_poisonglobule"
        const val SPAWNING_GFX = "spotanim.verzik_phase2_spawning"
        const val RED_SPAWN_SEQ = "seq.elemental_spawn"
        const val SHIELD_GFX = "spotanim.verzik_phase2_poisonaura"
        const val PURPLE_SPAWN_SEQ = "seq.tob_spider_tank_spawn"
        const val RED_POP_SEQ = "seq.elemental_death"
        const val RED_HEAL_PROJ = "spotanim.verzik_phase2_bloodproj"
        const val STUNNED = "seq.human_stunned"
        const val STUNNED_GFX = "spotanim.stunned_thieving"
        const val P3_SPAWN = "seq.verzik_phase3_spawn"
        const val P3_MELEE = "seq.verzik_phase3_attack_melee"
        const val P3_RANGED = "seq.verzik_phase3_attack_ranged"
        const val P3_MAGIC = "seq.verzik_phase3_attack_magic"
        const val P3_WEBS = "seq.verzik_phase3_attack_webspin"
        const val P3_YELLOWS = "seq.verzik_phase3_attack_powerblast"
        const val P3_SUMMON = "seq.verzik_phase3_attack_summon"
        const val P3_DEATH = "seq.verzik_phase3_death_a"
        const val P3_DEATH_SOUND = "synth.tob_verzik_spider_death_1"
        const val P3_RANGE_PROJ = "spotanim.verzik_phase3_rangeproj"
        const val P3_MAGE_PROJ = "spotanim.verzik_phase3_mageproj"
        const val WEB_PROJ = "spotanim.verzik_p3_web_proj"
        const val BALL_PROJ = "spotanim.verzik_acidbomb_projanim"
        const val BALL_IMPACT = "spotanim.verzik_acidbomb_impact"
        const val TORNADO_DESPAWN_SEQ = "seq.tob_shadow_projectile_despawn"
        const val TORNADO_HIT_GFX = "spotanim.verzik_p3_creeper_spot"
    }
}
