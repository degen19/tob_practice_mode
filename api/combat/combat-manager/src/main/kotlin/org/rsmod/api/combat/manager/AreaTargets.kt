package org.rsmod.api.combat.manager

import jakarta.inject.Inject
import org.rsmod.api.death.NpcAttackValidateHook
import org.rsmod.api.death.NpcAttackValidateResult
import org.rsmod.api.npc.isValidTarget
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.interact.InteractionOp
import org.rsmod.map.util.Bounds
import org.rsmod.map.zone.ZoneKey

/**
 * Finds the extra npcs an area attack also lands on: chinchompas, the Scythe of vitur sweep, the
 * Dragon crossbow and Dinh's bulwark specials, and so on.
 *
 * An npc counts when any of its tiles lies in the area, it can still be attacked (alive, has an
 * attack option) and no attack validation hook denies the player. Npcs are returned nearest to the
 * centre of the area first, so a target limit drops the farthest ones.
 */
public class AreaTargets
@Inject
constructor(
    private val npcRepo: NpcRepository,
    private val attackValidateHooks: Set<NpcAttackValidateHook>,
) {
    public fun npcsIn(
        player: Player,
        area: Bounds,
        exclude: Collection<Npc> = emptyList(),
        limit: Int = Int.MAX_VALUE,
    ): List<Npc> {
        if (limit <= 0) {
            return emptyList()
        }
        val centre = area.coords.translate(area.width / 2, area.length / 2)
        val zoneRadius = 1 + maxOf(area.width, area.length) / ZONE_SIZE
        return npcRepo
            .findAll(ZoneKey.from(centre), zoneRadius)
            .filter { it !in exclude }
            .filter { it.coords.level == area.level }
            .filter { it.bounds().isWithinDistance(area, 0) }
            .filter { it.isValidTarget() }
            .filter { it.visType.hasOp(InteractionOp.Op2.slot) }
            .filter { canAttack(player, it) }
            .sortedBy { it.bounds().distanceTo(Bounds(centre)) }
            .take(limit)
            .toList()
    }

    private fun canAttack(player: Player, npc: Npc): Boolean =
        attackValidateHooks.none { it.validate(player, npc) is NpcAttackValidateResult.Deny }

    private companion object {
        const val ZONE_SIZE = 8
    }
}
