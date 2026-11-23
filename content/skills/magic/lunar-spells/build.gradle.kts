plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.combat.combatCommons)
    implementation(projects.api.combat.combatManager)
    implementation(projects.api.areaChecker)
    implementation(projects.api.config)
    implementation(projects.api.generated)
    implementation(projects.api.mechanics.toxins)
    implementation(projects.api.npc)
    implementation(projects.api.player)
    implementation(projects.api.playerOutput)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.random)
    implementation(projects.api.repo)
    implementation(projects.api.script)
    implementation(projects.api.spells)
    implementation(projects.api.spellsAutocast)
    implementation(projects.content.other.consumables)
    implementation(projects.content.skills.farming)
}
