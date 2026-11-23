package org.rsmod.content.other.special.weapons

import org.rsmod.api.weapons.WeaponMap
import org.rsmod.content.other.special.weapons.ranged.BlowpipeWeapons
import org.rsmod.content.other.special.weapons.ranged.ChargeBowWeapons
import org.rsmod.content.other.special.weapons.ranged.ChinchompaWeapons
import org.rsmod.content.other.special.weapons.ranged.DarkBowWeapons
import org.rsmod.content.other.special.weapons.ranged.TonalzticsOfRalosWeapons
import org.rsmod.content.other.special.weapons.ranged.VenatorBowWeapons
import org.rsmod.plugin.module.PluginModule

class RangedWeaponsModule : PluginModule() {
    override fun bind() {
        addSetBinding<WeaponMap>(DarkBowWeapons::class.java)
        addSetBinding<WeaponMap>(VenatorBowWeapons::class.java)
        addSetBinding<WeaponMap>(BlowpipeWeapons::class.java)
        addSetBinding<WeaponMap>(ChargeBowWeapons::class.java)
        addSetBinding<WeaponMap>(TonalzticsOfRalosWeapons::class.java)
        addSetBinding<WeaponMap>(ChinchompaWeapons::class.java)
    }
}
