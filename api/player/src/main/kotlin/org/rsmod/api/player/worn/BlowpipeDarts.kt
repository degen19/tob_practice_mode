package org.rsmod.api.player.worn

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.isType
import org.rsmod.utils.bits.getBits

/**
 * The darts loaded in a blowpipe. A blowpipe fires its loaded darts: the dart's ranged strength
 * adds to the blowpipe's, and the dart decides the projectile.
 *
 * The dart type is kept on the blowpipe itself (`varobj.snakeboss_blowpipe_darttype`, 1 = bronze up
 * to 9 = dragon). This server does not load darts into blowpipes yet, so a blowpipe without a
 * stored dart type counts as loaded with dragon darts, the darts used in raids.
 */
public object BlowpipeDarts {
    private val BLOWPIPES =
        arrayOf(
            "obj.toxic_blowpipe_loaded",
            "obj.toxic_blowpipe_loaded_ornament",
            "obj.drygore_blowpipe_loaded",
        )

    private val DARTS =
        listOf(
            "obj.bronze_dart",
            "obj.iron_dart",
            "obj.steel_dart",
            "obj.black_dart",
            "obj.mithril_dart",
            "obj.adamant_dart",
            "obj.rune_dart",
            "obj.amethyst_dart",
            "obj.dragon_dart",
        )

    private const val DEFAULT_DART = "obj.dragon_dart"
    private const val DART_TYPE_VAROBJ = "varobj.snakeboss_blowpipe_darttype"

    public fun isLoadedBlowpipe(obj: InvObj?): Boolean = BLOWPIPES.any { obj.isType(it) }

    /** The dart [blowpipe] fires, or `null` when [blowpipe] is not a loaded blowpipe. */
    public fun dart(blowpipe: InvObj?): ItemServerType? {
        if (blowpipe == null || !isLoadedBlowpipe(blowpipe)) {
            return null
        }
        val varobj = ServerCacheManager.getVarObj(DART_TYPE_VAROBJ.asRSCM(RSCMType.VAROBJ))
        val stored = varobj?.let { blowpipe.vars.getBits(it.bits) } ?: 0
        val internal = DARTS.getOrNull(stored - 1) ?: DEFAULT_DART
        return ServerCacheManager.getItem(internal.asRSCM(RSCMType.OBJ))
    }
}
