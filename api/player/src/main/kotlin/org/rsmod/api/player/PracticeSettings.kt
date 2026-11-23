package org.rsmod.api.player

/**
 * Practice switches (the rotten potato's right-click), server-wide and flipped any time mid-fight:
 * - [noDeaths]: players still take every hit but never drop below 1 hitpoint.
 * - [infiniteHp]: hits on NPCs still roll and show their hitsplats, but take nothing off - hold a boss in its phase.
 * - [infiniteSupplies]: food, potions, phoenix necklaces, runes and ammo aren't used up.
 */
public object PracticeSettings {
    @Volatile public var noDeaths: Boolean = false
    @Volatile public var infiniteHp: Boolean = false
    @Volatile public var infiniteSupplies: Boolean = false
    @Volatile public var infinitePrayer: Boolean = false
    @Volatile public var alwaysMaxHit: Boolean = false
}
