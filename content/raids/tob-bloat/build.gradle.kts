plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.instances)
    implementation(projects.api.registry)
    implementation(projects.api.utils.utilsMap)
}

dependencies {
    implementation(projects.api.net)
    implementation(projects.content.other.bots)
    implementation(libs.rsprot.api)
}

dependencies {
    implementation(projects.content.interfaces.prayerTab)
    implementation(libs.rsprot.shared)
}

dependencies {
    implementation(projects.api.spellsAutocast)
    implementation(projects.api.spells)
    implementation(projects.api.bossHpBarPlugin)
    implementation(projects.api.specials)
    implementation(projects.api.weapons)
    implementation(projects.api.combat.combatCommons)
    implementation(projects.api.combat.combatManager)
    implementation(projects.api.combat.combatWeapon)
    implementation(projects.api.spellsRunes)
    implementation(projects.engine.utilsBits)
}

dependencies {
    implementation(libs.jackson.databind)
}
