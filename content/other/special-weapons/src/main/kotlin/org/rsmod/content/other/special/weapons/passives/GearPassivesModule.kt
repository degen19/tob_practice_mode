package org.rsmod.content.other.special.weapons.passives

import org.rsmod.api.npc.hit.NpcHitImpactHook
import org.rsmod.plugin.module.PluginModule

class GearPassivesModule : PluginModule() {
    override fun bind() {
        addSetBinding<NpcHitImpactHook>(BloodFuryPassive::class.java)
    }
}
