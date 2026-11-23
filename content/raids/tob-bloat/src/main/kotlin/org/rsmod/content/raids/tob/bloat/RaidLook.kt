package org.rsmod.content.raids.tob.bloat

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import dev.openrune.types.aconverted.SpotanimType
import org.rsmod.api.config.refs.params
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import kotlin.math.abs

/**
 * How the raid bots look doing what the sims say: a render kit's armour and weapon on ([equip]), its attack animation,
 * the weapon's projectile ([projectile]) and the scythe's swing ([sweep]). Shared by the live rooms.
 */
class RaidLook(private val worldRepo: WorldRepository) {
    private val worn = HashMap<Player, String>()

    fun wearing(bot: Player): Boolean = bot in worn

    /** Puts the kit's armour set and weapon on (only when it changes). */
    fun equip(bot: Player, kit: MaidenRules.Kit) {
        val key = "${kit.style}/${kit.weapon}/${kit.ammo}"
        if (worn[bot] == key) return
        worn[bot] = key
        for (slot in 0 until bot.worn.size) bot.worn[slot] = null
        for (id in MaidenRules.STYLE_SETS.getValue(kit.style) + kit.weapon + listOfNotNull(kit.ammo)) {
            val item = ServerCacheManager.getItem(id) ?: continue
            if (item.wearpos1 < 0) continue
            bot.worn[item.wearpos1] = InvObj(item)
        }
        bot.rebuildAppearance()
    }

    /** The kit on, facing the target, its animation - and for ranged its projectile, for the scythe its swing. */
    fun attack(bot: Player, kit: MaidenRules.Kit, target: Npc, scythe: Boolean = false) {
        equip(bot, kit)
        bot.faceSquare(target.coords, target.size, target.size)
        bot.faceNpc(target)
        bot.anim(RSCM.getReverseMapping(RSCMType.SEQ, kit.seq))
        if (kit.style == "range") ServerCacheManager.getItem(kit.weapon)?.let { projectile(bot, it, kit.ammo?.let { a -> ServerCacheManager.getItem(a) }, target) }
        if (scythe) sweep(bot, target)
    }

    fun projectile(bot: Player, weapon: ItemServerType, ammo: ItemServerType?, target: Npc) {
        val projanim = weapon.paramOrNull(params.proj_type)?.let { RSCM.getReverseMapping(RSCMType.PROJANIM, it.id) } ?: return
        val travel = ammo?.paramOrNull(params.proj_travel) ?: weapon.paramOrNull(params.proj_travel) ?: return
        worldRepo.projAnim(bot, target, travel, projanim)
    }

    /** The scythe's swing graphic, on the side of the bot facing the target. */
    fun sweep(bot: Player, target: Npc) {
        val dx = target.coords.x + target.size / 2 - bot.coords.x
        val dz = target.coords.z + target.size / 2 - bot.coords.z
        val (dir, step) =
            if (abs(dx) >= abs(dz)) (if (dx > 0) "east" to (1 to 0) else "west" to (-1 to 0))
            else (if (dz > 0) "north" to (0 to 1) else "south" to (0 to -1))
        worldRepo.spotanimMap(SpotanimType("spotanim.dragon_halberd_special_${dir}_red".asRSCM(RSCMType.SPOTANIM)), bot.coords.translate(step.first, step.second), height = 96, delay = 20)
    }

    fun forget(bot: Player) {
        worn.remove(bot)
    }
}
