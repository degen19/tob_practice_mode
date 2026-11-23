package org.rsmod.content.other.special.weapons

import org.rsmod.api.weapons.WeaponMap
import org.rsmod.content.other.special.weapons.melee.MultiHitMeleeWeapons
import org.rsmod.content.other.special.weapons.melee.OsmumtensFangWeapons
import org.rsmod.content.other.special.weapons.melee.ObligatorWeapons
import org.rsmod.content.other.special.weapons.melee.ScytheOfViturWeapons
import org.rsmod.content.other.special.weapons.melee.SoulreaperAxeWeapons
import org.rsmod.plugin.module.PluginModule

class MeleeWeaponsModule : PluginModule() {
    override fun bind() {
        addSetBinding<WeaponMap>(ScytheOfViturWeapons::class.java)
        addSetBinding<WeaponMap>(ObligatorWeapons::class.java)
        addSetBinding<WeaponMap>(MultiHitMeleeWeapons::class.java)
        addSetBinding<WeaponMap>(OsmumtensFangWeapons::class.java)
        addSetBinding<WeaponMap>(SoulreaperAxeWeapons::class.java)
    }
}
