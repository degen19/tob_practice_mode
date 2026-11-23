package org.rsmod.content.other.special.attacks

import org.rsmod.api.specials.SpecialAttackMap
import org.rsmod.content.other.special.attacks.boost.StatBoostSpecialAttacks
import org.rsmod.content.other.special.attacks.magic.NightmareStaffSpecialAttacks
import org.rsmod.content.other.special.attacks.magic.PoweredStaffSpecialAttacks
import org.rsmod.content.other.special.attacks.melee.ClawSpecialAttacks
import org.rsmod.content.other.special.attacks.melee.DefenceDrainSpecialAttacks
import org.rsmod.content.other.special.attacks.melee.DragonLongswordSpecialAttack
import org.rsmod.content.other.special.attacks.melee.GodswordSpecialAttacks
import org.rsmod.content.other.special.attacks.melee.HalberdSpecialAttack
import org.rsmod.content.other.special.attacks.melee.MeleeWeaponSpecialAttacks
import org.rsmod.content.other.special.attacks.ranged.DarkBowSpecialAttack
import org.rsmod.content.other.special.attacks.ranged.RangedWeaponSpecialAttacks
import org.rsmod.plugin.module.PluginModule

class SpecialAttackModule : PluginModule() {
    override fun bind() {
        addSetBinding<SpecialAttackMap>(StatBoostSpecialAttacks::class.java)
        addSetBinding<SpecialAttackMap>(DarkBowSpecialAttack::class.java)
        addSetBinding<SpecialAttackMap>(DragonLongswordSpecialAttack::class.java)
        addSetBinding<SpecialAttackMap>(HalberdSpecialAttack::class.java)
        addSetBinding<SpecialAttackMap>(PoweredStaffSpecialAttacks::class.java)
        addSetBinding<SpecialAttackMap>(ClawSpecialAttacks::class.java)
        addSetBinding<SpecialAttackMap>(GodswordSpecialAttacks::class.java)
        addSetBinding<SpecialAttackMap>(DefenceDrainSpecialAttacks::class.java)
        addSetBinding<SpecialAttackMap>(MeleeWeaponSpecialAttacks::class.java)
        addSetBinding<SpecialAttackMap>(RangedWeaponSpecialAttacks::class.java)
        addSetBinding<SpecialAttackMap>(NightmareStaffSpecialAttacks::class.java)
    }
}
