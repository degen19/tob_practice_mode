package org.rsmod.content.interfaces.prayer.tab

import dev.openrune.definition.type.VarBitType
import dev.openrune.types.aconverted.SynthType
import org.rsmod.api.player.stat.baseDefenceLvl
import org.rsmod.api.player.stat.basePrayerLvl
import org.rsmod.game.entity.Player

data class Prayer(
    val id: Int,
    val obj: Int,
    val name: String,
    val level: Int,
    val sound: SynthType,
    val enabled: String,
    val drainEffect: Int,
    val overhead: Int?,
    val unlocked: VarBitType?,
    val unlockState: Int,
    val defenceReq: Int?,
    val lockedMessage: String?,
) {
    val plainLockedMessage: String? =
        lockedMessage?.replace("<col=000080>", "")?.replace("</col>", "")?.replace("<br>", " ")

    fun hasAllRequirements(player: Player): Boolean {
        if (unlocked != null && player.vars[unlocked] < unlockState) {
            return false
        } else if (defenceReq != null && player.baseDefenceLvl < defenceReq) {
            return false
        }
        return hasBaseRequirement(player)
    }

    fun hasBaseRequirement(player: Player): Boolean = player.basePrayerLvl >= levelFor(player)

    /** Deadeye, Mystic Vigour and Zeal take over this prayer's slot once unlocked. */
    fun override(player: Player): Override? =
        Override.entries.firstOrNull { it.base == enabled && player.vars[it.unlock] == 1 }

    fun levelFor(player: Player): Int = override(player)?.level ?: level

    fun nameFor(player: Player): String = override(player)?.label ?: name

    enum class Override(val base: String, val unlock: String, val label: String, val level: Int) {
        Deadeye("varbit.prayer_eagleeye", "varbit.prayer_deadeye_unlocked", "Deadeye", 62),
        MysticVigour("varbit.prayer_mysticmight", "varbit.prayer_mystic_vigour_unlocked", "Mystic Vigour", 63),
        Zeal("varbit.prayer_piety", "varbit.prayer_zeal_unlocked", "Zeal", 84),
    }
}
