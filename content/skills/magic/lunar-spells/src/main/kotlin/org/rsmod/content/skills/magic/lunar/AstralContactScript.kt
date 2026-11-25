package org.rsmod.content.skills.magic.lunar

import dev.openrune.types.aconverted.interf.IfButtonOp
import jakarta.inject.Inject
import org.rsmod.api.player.dialogue.mesanims
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.ui.IfScriptArgs
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onIfScriptTrigger
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private var Player.lastContact by intVarBit("varbit.lunar_contact_lastid")

/**
 * Astral Contact (Lunar, level 67): opens the "Choose a character" window. On this world the
 * window (drawn by the client from the lunar-spells pack's `lunar_contact_npc_draw` override)
 * lists two contacts, Washer and TM 19. Picking one pays the runes, gives the cast experience,
 * plays the cast and opens a chat with that contact. The right-click option contacts the last
 * one picked straight away.
 */
class AstralContactScript @Inject constructor(private val caster: LunarCaster) : PluginScript() {
    override fun ScriptContext.startup() {
        onIfOverlayButton("component.magic_spellbook:npc_contact") { open(it.op) }
        onIfScriptTrigger<ContactChoice>("component.lunar_contact_npc:scrolllayer") {
            ifClose()
            contact(it.contact)
        }
    }

    private suspend fun ProtectedAccess.open(op: IfButtonOp) {
        val spell = caster.spell(SPELL_OBJ) ?: return
        if (!caster.canCast(player, spell)) {
            return
        }
        if (op != IfButtonOp.Op1 && Contact.of(player.lastContact) != null) {
            contact(player.lastContact)
            return
        }
        ifOpenMainModal(INTERFACE)
    }

    private suspend fun ProtectedAccess.contact(id: Int) {
        val contact = Contact.of(id) ?: return
        val spell = caster.spell(SPELL_OBJ) ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        player.lastContact = contact.id
        anim(CAST_ANIM)
        spotanim(CAST_SPOTANIM, height = 92)
        player.stall(CONTACT_STALL)
        delay(CONTACT_STALL + 1)
        resetAnim()
        startDialogue {
            chatNpcSpecific(contact.title, contact.head, mesanims.neutral, contact.line)
        }
    }

    internal data class ContactChoice(val contact: Int, val option: Int) : IfScriptArgs

    /** The ids match the contact ids the client override sends back. */
    private enum class Contact(val id: Int, val title: String, val head: String, val line: String) {
        Washer(1, "Washer", "npc.honest_jimmy", "Fuck off"),
        Tm19(2, "TM 19", "npc.rcu_zammy_mage2", "Report bugs to Caps lock13 on discord");

        companion object {
            fun of(id: Int): Contact? = entries.firstOrNull { it.id == id }
        }
    }

    private companion object {
        const val SPELL_OBJ = "obj.67_npc_contact"
        const val INTERFACE = "interface.lunar_contact_npc"
        const val CAST_ANIM = "seq.lunar_human_magic_summon2"
        const val CAST_SPOTANIM = "spotanim.quest_lunar_spellbook_string_jewelery_spot_anim"
    }
}
