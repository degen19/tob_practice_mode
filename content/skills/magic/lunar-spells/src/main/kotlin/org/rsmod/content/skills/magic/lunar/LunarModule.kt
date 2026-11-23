package org.rsmod.content.skills.magic.lunar

import org.rsmod.api.spells.attack.SpellAttackMap
import org.rsmod.plugin.module.PluginModule

class LunarModule : PluginModule() {
    override fun bind() {
        addSetBinding<SpellAttackMap>(LunarTargetSpells::class.java)
    }
}
