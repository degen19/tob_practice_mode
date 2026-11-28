package org.rsmod.content.raids.tob.bloat

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import dev.openrune.types.aconverted.SpotanimType
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.params
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.content.other.special.weapons.magic.PoweredStaffTypes
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.map.CardinalDirection
import org.rsmod.game.map.Direction
import org.rsmod.game.map.translate

/**
 * A recorded attack (Blert's attack name, animation and weapon) shown the way the server's own combat shows it: the
 * weapon's animation at combat priority and its stance's sound, a powered staff's cast, projectile, impact and sounds
 * ([PoweredStaffTypes]), the scythe's swing and the specials' own animations, graphics and sounds, each as their
 * attack scripts play them. Sounds are area sounds, so everyone watching hears them.
 */
class BlertLooks(private val worldRepo: WorldRepository) {
    private val staffs by lazy {
        PoweredStaffTypes.ALL.flatMap { t -> t.allObjs.map { it.asRSCM(RSCMType.OBJ) to t } }.toMap()
    }

    fun attack(bot: Player, name: String, anim: Int, weaponId: Int, target: Npc) {
        bot.faceSquare(target.coords, target.size, target.size)
        bot.faceNpc(target)
        val weapon = ServerCacheManager.getItem(weaponId)
        when (name) {
            "DAWN_SPEC" -> dawnSpec(bot, target)
            "CLAW_SPEC" -> clawSpec(bot)
            "CHALLY_SPEC" -> halberdSpec(bot, target, CRYSTAL_HALBERD_COLOUR)
            "ZCB_SPEC" -> {
                play(bot, anim, weapon)
                worldRepo.projAnim(bot, target, SpotanimType(ZCB_SPEC_PROJ.asRSCM(RSCMType.SPOTANIM)), BOLT_PROJANIM)
            }
            else -> {
                val staff = staffs[weaponId]
                if (staff != null) {
                    staffCast(bot, anim, staff.spell, target)
                    return
                }
                play(bot, anim, weapon)
                if (weapon != null) projectile(bot, weapon, bot.worn[AMMO_WEARPOS]?.let { ServerCacheManager.getItem(it.id) }, target)
                if (name == "SCYTHE") sweep(bot, target, SCYTHE_COLOUR, SCYTHE_SWING_DELAY)
            }
        }
    }

    /** The animation at combat priority, and the sound of the weapon's stance that has it (its first stance's if none). */
    private fun play(bot: Player, anim: Int, weapon: ItemServerType?) {
        if (anim >= 0) RSCM.getReverseMapping(RSCMType.SEQ, anim)?.let { bot.anim(it, priority = ATTACK_PRIORITY) }
        weapon ?: return
        val stances = listOf(params.attack_anim_stance1, params.attack_anim_stance2, params.attack_anim_stance3, params.attack_anim_stance4)
        val sounds = listOf(params.attack_sound_stance1, params.attack_sound_stance2, params.attack_sound_stance3, params.attack_sound_stance4)
        val k = stances.indexOfFirst { weapon.paramOrNull(it)?.id == anim }.takeIf { it >= 0 } ?: 0
        val sound = weapon.paramOrNull(sounds[k]) ?: sounds.firstNotNullOfOrNull { weapon.paramOrNull(it) } ?: return
        worldRepo.soundArea(bot, RSCM.getReverseMapping(RSCMType.SYNTH, sound.id) ?: return, radius = SOUND_RADIUS)
    }

    private fun staffCast(bot: Player, anim: Int, spell: org.rsmod.content.other.special.weapons.magic.StaffSpell, target: Npc) {
        if (anim >= 0) RSCM.getReverseMapping(RSCMType.SEQ, anim)?.let { bot.anim(it, priority = ATTACK_PRIORITY) } ?: bot.anim(spell.anim, priority = ATTACK_PRIORITY)
        bot.spotanim(spell.castSpot, height = spell.castHeight)
        val proj = worldRepo.projAnim(bot, target, SpotanimType(spell.projectile.asRSCM(RSCMType.SPOTANIM)), spell.projanim)
        val clientDelay = proj.durations.clientDelay
        worldRepo.soundArea(bot, spell.castSound, radius = SOUND_RADIUS)
        target.spotanim(spell.impact, delay = clientDelay, height = spell.impactHeight)
        worldRepo.soundArea(target.coords, spell.hitSound, delay = clientDelay, radius = SOUND_RADIUS)
    }

    private fun dawnSpec(bot: Player, target: Npc) {
        bot.anim(DAWN_SPEC_SEQ, priority = ATTACK_PRIORITY)
        bot.spotanim(DAWN_SPEC_CAST, height = DAWN_CAST_HEIGHT)
        val proj = worldRepo.projAnim(bot, target, SpotanimType(DAWN_SPEC_PROJ.asRSCM(RSCMType.SPOTANIM)), STAFF_PROJANIM)
        target.spotanim(DAWN_SPEC_IMPACT, delay = proj.durations.clientDelay)
    }

    private fun clawSpec(bot: Player) {
        bot.anim(CLAW_SPEC_SEQ, priority = ATTACK_PRIORITY)
        bot.spotanim(CLAW_SPEC_SPOT, height = 0, slot = constants.spotanim_slot_combat)
        for ((sound, delay) in CLAW_SPEC_SOUNDS) worldRepo.soundArea(bot, sound, delay = delay, radius = SOUND_RADIUS)
    }

    private fun halberdSpec(bot: Player, target: Npc, colour: String) {
        bot.anim(HALBERD_SPEC_SEQ, priority = ATTACK_PRIORITY)
        sweep(bot, target, colour, 0)
    }

    /** The swing graphic on the tile in front of the bot, facing the target as combat works it out. */
    private fun sweep(bot: Player, target: Npc, colour: String, delay: Int) {
        val direction = Direction.cardinalBetween(bot.bounds(), target.bounds())
        val anchor = bot.coords.translate(bot.size / 2, bot.size / 2).translate(direction.toDirection())
        worldRepo.spotanimMap(SpotanimType("spotanim.dragon_halberd_special_${direction.suffix()}_$colour".asRSCM(RSCMType.SPOTANIM)), anchor, height = SWING_HEIGHT, delay = delay)
    }

    private fun projectile(bot: Player, weapon: ItemServerType, ammo: ItemServerType?, target: Npc) {
        val projanim = weapon.paramOrNull(params.proj_type)?.let { RSCM.getReverseMapping(RSCMType.PROJANIM, it.id) } ?: return
        val travel = ammo?.paramOrNull(params.proj_travel) ?: weapon.paramOrNull(params.proj_travel) ?: return
        worldRepo.projAnim(bot, target, travel, projanim)
    }

    private fun CardinalDirection.toDirection(): Direction =
        when (this) {
            CardinalDirection.North -> Direction.North
            CardinalDirection.South -> Direction.South
            CardinalDirection.East -> Direction.East
            CardinalDirection.West -> Direction.West
        }

    private fun CardinalDirection.suffix(): String =
        when (this) {
            CardinalDirection.North -> "north"
            CardinalDirection.South -> "south"
            CardinalDirection.East -> "east"
            CardinalDirection.West -> "west"
        }

    private companion object {
        const val ATTACK_PRIORITY = 6
        const val SOUND_RADIUS = 10
        const val AMMO_WEARPOS = 13
        const val SWING_HEIGHT = 96
        const val SCYTHE_SWING_DELAY = 20
        const val SCYTHE_COLOUR = "red"
        const val CRYSTAL_HALBERD_COLOUR = "white"
        const val STAFF_PROJANIM = "projanim.powered_staff"
        const val BOLT_PROJANIM = "projanim.bolt"
        const val DAWN_SPEC_SEQ = "seq.human_castwave_staff"
        const val DAWN_SPEC_CAST = "spotanim.dawnbringer_casting_spec"
        const val DAWN_SPEC_PROJ = "spotanim.dawnbringer_projectile_spec"
        const val DAWN_SPEC_IMPACT = "spotanim.dawnbringer_impact_spec"
        const val DAWN_CAST_HEIGHT = 92
        const val CLAW_SPEC_SEQ = "seq.human_dragon_claws_spec"
        const val CLAW_SPEC_SPOT = "spotanim.dragon_claws_spot"
        val CLAW_SPEC_SOUNDS = listOf("synth.dragonclaws_special_1" to 0, "synth.dragonclaws_special_2" to 20, "synth.dragonclaws_special_3" to 40)
        const val HALBERD_SPEC_SEQ = "seq.dragon_halberd_special_attack"
        const val ZCB_SPEC_PROJ = "spotanim.zcb_specialattack"
    }
}
