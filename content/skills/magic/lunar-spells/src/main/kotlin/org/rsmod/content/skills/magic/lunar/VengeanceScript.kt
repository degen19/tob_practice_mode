package org.rsmod.content.skills.magic.lunar

import jakarta.inject.Inject
import org.rsmod.api.npc.hit.modifier.NpcHitModifier
import org.rsmod.api.npc.hit.queueHit
import org.rsmod.api.player.events.PlayerHitEvents
import org.rsmod.api.player.hit.modifier.PlayerHitModifier
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hitpoints
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerQueue
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.hit.Hit
import org.rsmod.game.hit.HitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

internal var Player.vengeanceActive by boolVarBit("varbit.vengeance_rebound")

internal var Player.vengeanceCooldown by boolVarBit("varbit.vengeance_timelimit")

private val NoopNpcHitModifier = NpcHitModifier {}

private val NoopPlayerHitModifier = PlayerHitModifier {}

/** Starts the 30-second cooldown shared by Vengeance and Vengeance Other. */
internal fun Player.startVengeanceCooldown() {
    vengeanceCooldown = true
    clearQueue(VengeanceScript.COOLDOWN_QUEUE)
    queue(VengeanceScript.COOLDOWN_QUEUE, VengeanceScript.COOLDOWN_TICKS)
}

/**
 * Vengeance (Lunar, level 94): the next hit that damages you also deals 75% of that damage back to
 * whoever hit you, and you say "Taste vengeance!". Vengeance and Vengeance Other share a
 * 30-second cooldown counted from the cast; once it's up you can cast again whether or not the last
 * one has gone off (it doesn't stack).
 */
class VengeanceScript
@Inject
constructor(
    private val caster: LunarCaster,
    private val npcList: NpcList,
    private val playerList: PlayerList,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onIfOverlayButton("component.magic_spellbook:vengeance") { cast() }
        onEvent<PlayerHitEvents.Impact> { rebound(player, hit) }
        onPlayerQueue(COOLDOWN_QUEUE) { player.vengeanceCooldown = false }
        onPlayerLogin {
            player.vengeanceActive = false
            player.vengeanceCooldown = false
        }
    }

    private fun ProtectedAccess.cast() {
        if (player.vengeanceCooldown) {
            mes("You can only cast vengeance spells every 30 seconds.")
            return
        }
        val spell = caster.spell(SPELL_OBJ) ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim(CAST_ANIM)
        spotanim(CAST_SPOTANIM, height = 92)
        soundSynth(CAST_SOUND)
        player.vengeanceActive = true
        player.startVengeanceCooldown()
    }

    private fun rebound(player: Player, hit: Hit) {
        if (!player.vengeanceActive || hit.damage <= 0) {
            return
        }
        val damage = hit.damage * 3 / 4
        if (hit.isFromNpc) {
            val npc = hit.resolveNpcSource(npcList) ?: return
            player.vengeanceActive = false
            player.say("Taste vengeance!")
            if (damage > 0 && npc.isSlotAssigned && npc.hitpoints > 0) {
                npc.queueHit(player, 1, HitType.Typeless, damage, NoopNpcHitModifier)
            }
            return
        }
        if (hit.isFromPlayer) {
            val source = hit.resolvePlayerSource(playerList) ?: return
            player.vengeanceActive = false
            player.say("Taste vengeance!")
            if (damage > 0 && source.isSlotAssigned && source.hitpoints > 0) {
                source.queueHit(player, 1, HitType.Typeless, damage, NoopPlayerHitModifier)
            }
        }
    }

    internal companion object {
        const val SPELL_OBJ = "obj.94_vengeance"
        const val CAST_ANIM = "seq.vengeance_spell_anim_nostalling"
        const val CAST_SPOTANIM = "spotanim.quest_lunar_spellbook_vengeance_other_spot_anim"
        const val CAST_SOUND = "synth.lunar_vengence"
        const val COOLDOWN_QUEUE = "queue.vengeance_cooldown"
        const val COOLDOWN_TICKS = 50
    }
}
