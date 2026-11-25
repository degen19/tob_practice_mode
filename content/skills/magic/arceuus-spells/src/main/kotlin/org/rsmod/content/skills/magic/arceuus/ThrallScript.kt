package org.rsmod.content.skills.magic.arceuus

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.MoveRestrict
import dev.openrune.types.NpcMode
import jakarta.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.combat.manager.MagicRuneManager.Companion.isFailure
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.magicLvl
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitType
import org.rsmod.game.interact.InteractionNpc
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.game.proj.ProjAnim
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag
import org.rsmod.game.inv.isType

private var Player.thrallActive by boolVarBit("varbit.arceuus_resurrection_active")

private var Player.thrallCooldown by boolVarBit("varbit.arceuus_resurrection_cooldown")

private val NoopNpcHitModifier = NpcHitModifier {}

/**
 * Thralls (Arceuus, the nine Resurrect spells; needs the Book of the dead held or worn). A thrall
 * rises beside you, follows you and attacks whatever you're attacking every 4 ticks. It always
 * hits, for 0 up to 1 (lesser), 2 (superior) or 3 (greater): ghosts with magic, skeletons with
 * ranged, zombies in melee. It stays for 0.6 seconds per Magic level (a tick per level), one
 * thrall at a time (casting again replaces it), raising one costs 2, 4 or 6 prayer points, and
 * the spell can't be cast again for 10 seconds. Ghosts and skeletons attack from 6 tiles.
 */
class ThrallScript
@Inject
constructor(
    private val spells: MagicSpellRegistry,
    private val runes: MagicRuneManager,
    private val npcRepo: NpcRepository,
    private val worldRepo: WorldRepository,
    private val collision: CollisionFlagMap,
    private val random: GameRandom,
    private val clock: MapClock,
) : PluginScript() {
    private class Live(val owner: Player, val npc: Npc, val kind: Thrall, val until: Int) {
        var nextAttack = 0
        var target: Npc? = null
    }

    private val live = HashMap<Player, Live>()
    private val cooldownUntil = HashMap<Player, Int>()

    override fun ScriptContext.startup() {
        for (kind in Thrall.entries) {
            onIfOverlayButton("component.magic_spellbook:${kind.component}") { cast(kind) }
        }
        onEvent<GameLifecycle.StartCycle> { tick() }
        org.rsmod.api.player.SpellResets.register { player ->
            live[player]?.let { dismiss(it, expired = false) }
            cooldownUntil.remove(player)
            player.thrallCooldown = false
        }
        onPlayerLogin {
            player.thrallActive = false
            player.thrallCooldown = false
        }
    }

    private fun ProtectedAccess.cast(kind: Thrall) {
        if (BOOK !in player.inv && player.worn.objs.none { it.isType(BOOK) }) {
            mes("You need the Book of the Dead to cast this spell.")
            return
        }
        if ((cooldownUntil[player] ?: 0) > clock.cycle) {
            mes("You can't resurrect a thrall again so soon.")
            return
        }
        if (player.stat("stat.prayer") < kind.prayerCost) {
            mes("You don't have enough prayer points to cast this spell.")
            return
        }
        val spellObj = ServerCacheManager.getItem(kind.spellObj.asRSCM(RSCMType.OBJ)) ?: return
        val spell = spells.getObjSpell(spellObj) ?: return
        if (runes.attemptCast(player, spell).isFailure()) {
            return
        }
        statAdvance("stat.magic", spell.castXp)
        player.statSub("stat.prayer", constant = kind.prayerCost, percent = 0)
        live[player]?.let { dismiss(it, expired = false) }
        anim(CAST_ANIM)
        spotanim(kind.castSpotanim)
        summon(player, kind)
    }

    private fun summon(owner: Player, kind: Thrall) {
        val tile = spawnTile(owner)
        val npc = Npc(kind.npc, tile)
        npcRepo.add(npc, Int.MAX_VALUE)
        npc.respawns = false
        npc.mode = NpcMode.None
        npc.ignoreCombatInteractions = true
        npc.moveRestrict = MoveRestrict.PassThru
        npc.defaultMoveSpeed = MoveSpeed.Walk
        kind.spawnAnim?.let { npc.anim(it) }
        val now = clock.cycle
        val thrall = Live(owner, npc, kind, now + max(1, owner.magicLvl))
        thrall.nextAttack = now + FIRST_ATTACK
        live[owner] = thrall
        cooldownUntil[owner] = now + COOLDOWN_TICKS
        owner.thrallActive = true
        owner.thrallCooldown = true
    }

    private fun spawnTile(owner: Player): CoordGrid {
        val c = owner.coords
        for ((dx, dz) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, 1 to 1, -1 to 1, 1 to -1)) {
            val t = c.translate(dx, dz)
            if (collision[t.x, t.z, t.level] and (CollisionFlag.BLOCK_WALK or CollisionFlag.LOC) == 0) return t
        }
        return c
    }

    private fun tick() {
        val now = clock.cycle
        for ((player, until) in cooldownUntil.entries.toList()) {
            if (until <= now) {
                cooldownUntil.remove(player)
                player.thrallCooldown = false
            }
        }
        for (thrall in live.values.toList()) {
            val owner = thrall.owner
            val npc = thrall.npc
            val gone = !owner.isSlotAssigned || !npc.isSlotAssigned
            if (gone || now >= thrall.until) {
                dismiss(thrall, expired = !gone)
                continue
            }
            follow(thrall)
            attack(thrall, now)
        }
    }

    private fun follow(thrall: Live) {
        val npc = thrall.npc
        val owner = thrall.owner
        val dist = max(abs(npc.coords.x - owner.coords.x), abs(npc.coords.z - owner.coords.z))
        if (npc.coords.level != owner.coords.level || dist > TELEPORT_DISTANCE) {
            npc.telejump(collision, spawnTile(owner))
            return
        }
        val target = thrall.target
        if (target != null && valid(target, owner)) {
            return
        }
        if (dist > 1) npc.walk(owner.coords)
    }

    private fun attack(thrall: Live, now: Int) {
        val owner = thrall.owner
        val fighting = (owner.interaction as? InteractionNpc)?.target
        if (fighting != null && valid(fighting, owner)) thrall.target = fighting
        val target = thrall.target?.takeIf { valid(it, owner) }
        if (target == null) {
            thrall.target = null
            return
        }
        if (now < thrall.nextAttack) return
        val npc = thrall.npc
        val dist = edgeDistance(npc, target)
        val reach = if (thrall.kind.style == Style.Melee) 1 else RANGED_REACH
        if (dist > reach || dist == 0) {
            npc.walk(target.coords)
            return
        }
        thrall.nextAttack = now + ATTACK_SPEED
        npc.faceNpc(target)
        npc.anim(thrall.kind.attackAnim)
        val damage = random.of(0, thrall.kind.maxHit)
        val delay =
            when (thrall.kind.style) {
                Style.Melee -> 1
                Style.Ranged -> projectile(npc, target, "spotanim.thrall_ranged_travel")
                Style.Magic -> projectile(npc, target, "spotanim.thrall_magic_travel")
            }
        if (thrall.kind.style == Style.Magic) {
            target.spotanim("spotanim.thrall_magic_impact", delay = delay * 30, height = 80)
        }
        target.queueHit(npc, delay, HitType.Typeless, damage, NoopNpcHitModifier)
    }

    private fun projectile(from: Npc, to: Npc, spot: String): Int {
        val dist = edgeDistance(from, to)
        val end = PROJ_DELAY + PROJ_SPEED * max(1, dist)
        val p =
            ProjAnim(
                spot.asRSCM(RSCMType.SPOTANIM),
                40,
                36,
                PROJ_DELAY,
                end,
                16,
                64,
                from.slotId + 1,
                to.slotId + 1,
                from.coords,
                to.coords.translate(to.size / 2, to.size / 2),
            )
        worldRepo.projAnim(p)
        return max(1, (end + 29) / 30)
    }

    private fun valid(target: Npc, owner: Player): Boolean =
        target.isSlotAssigned &&
            target.hitpoints > 0 &&
            target.coords.level == owner.coords.level &&
            max(abs(target.coords.x - owner.coords.x), abs(target.coords.z - owner.coords.z)) <= TARGET_RANGE

    private fun edgeDistance(a: Npc, b: Npc): Int {
        val dx = max(0, max(b.coords.x - (a.coords.x + a.size - 1), a.coords.x - (b.coords.x + b.size - 1)))
        val dz = max(0, max(b.coords.z - (a.coords.z + a.size - 1), a.coords.z - (b.coords.z + b.size - 1)))
        return if (dx == 0 && dz == 0) 0 else max(dx, dz)
    }

    private fun dismiss(thrall: Live, expired: Boolean) {
        live.remove(thrall.owner)
        if (thrall.npc.isSlotAssigned) npcRepo.del(thrall.npc, Int.MAX_VALUE)
        thrall.owner.thrallActive = false
        if (expired) thrall.owner.mes("Your thrall returns to the grave.")
    }

    internal enum class Style {
        Melee,
        Ranged,
        Magic,
    }

    internal enum class Thrall(
        val component: String,
        val spellObj: String,
        val npc: String,
        val style: Style,
        val maxHit: Int,
        val castSpotanim: String,
    ) {
        LesserGhost("resurrect_lesser_ghost", "obj.osb8_monkey_cow", "npc.arceuus_thrall_ghost_lesser", Style.Magic, 1, GHOST_CAST),
        LesserSkeleton("resurrect_lesser_skeleton", "obj.osb8_monkey_bob", "npc.arceuus_thrall_skeleton_lesser", Style.Ranged, 1, SKELETON_CAST),
        LesserZombie("resurrect_lesser_zombie", "obj.osb8_monkey_donie", "npc.arceuus_thrall_zombie_lesser", Style.Melee, 1, ZOMBIE_CAST),
        SuperiorGhost("resurrect_superior_ghost", "obj.osb8_bamboo", "npc.arceuus_thrall_ghost_superior", Style.Magic, 2, GHOST_CAST),
        SuperiorSkeleton("resurrect_superior_skeleton", "obj.osb8_monkey_unicorn", "npc.arceuus_thrall_skeleton_superior", Style.Ranged, 2, SKELETON_CAST),
        SuperiorZombie("resurrect_superior_zombie", "obj.osb8_monkey_sheep", "npc.arceuus_thrall_zombie_superior", Style.Melee, 2, ZOMBIE_CAST),
        GreaterGhost("resurrect_greater_ghost", "obj.osb8_monkey_trap", "npc.arceuus_thrall_ghost_greater", Style.Magic, 3, GHOST_CAST),
        GreaterSkeleton("resurrect_greater_skeleton", "obj.osb8_monkey_aereck", "npc.arceuus_thrall_skeleton_greater", Style.Ranged, 3, SKELETON_CAST),
        GreaterZombie("resurrect_greater_zombie", "obj.osb8_unicorn_horn", "npc.arceuus_thrall_zombie_greater", Style.Melee, 3, ZOMBIE_CAST);

        /** Lesser, superior and greater thralls cost 2, 4 and 6 prayer points to raise. */
        val prayerCost: Int
            get() = maxHit * 2

        val attackAnim: String
            get() =
                when (style) {
                    Style.Magic -> "seq.ghost_update_tendrill_attack_thrall"
                    Style.Ranged -> "seq.skeleton_update_champion_attack_thrall"
                    Style.Melee -> "seq.zombie_update_attack_normal_thrall"
                }

        val spawnAnim: String?
            get() =
                when (style) {
                    Style.Magic -> "seq.ghost_update_thrall_spawn"
                    Style.Ranged -> "seq.skeleton_update_thrall_spawn"
                    Style.Melee -> null
                }
    }

    private companion object {
        const val BOOK = "obj.book_of_the_dead"
        const val CAST_ANIM = "seq.human_spellcast_resurrect"
        const val GHOST_CAST = "spotanim.resurrect_ghost_cast_spotanim"
        const val SKELETON_CAST = "spotanim.resurrect_skeleton_cast_spotanim"
        const val ZOMBIE_CAST = "spotanim.resurrect_zombie_cast_spotanim"
        const val COOLDOWN_TICKS = 17
        const val ATTACK_SPEED = 4
        const val FIRST_ATTACK = 2
        const val RANGED_REACH = 6
        const val TARGET_RANGE = 15
        const val TELEPORT_DISTANCE = 12
        const val PROJ_DELAY = 20
        const val PROJ_SPEED = 5
    }
}
