package org.rsmod.content.skills.magic.lunar

import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.config.refs.params
import org.rsmod.api.player.hook.PlayerTeleportValidator
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.output.ChatType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Tele Group spells (Moonclan, Waterbirth, Barbarian, Khazard, Fishing Guild, Catherby and
 * Ice Plateau). The caster teleports like the single-player version, and every player in the 3x3
 * area around them with Accept Aid on is asked, in the teleport window, whether they want to go
 * too; accepting sends them to the same place.
 */
class TeleGroupScript
@Inject
constructor(
    private val caster: LunarCaster,
    private val teleportValidator: PlayerTeleportValidator,
    private val areaChecker: AreaChecker,
    private val clock: MapClock,
    private val launcher: ProtectedAccessLauncher,
) : PluginScript() {
    private class Offer(val destination: CoordGrid, val expires: Int)

    private val offers = WeakHashMap<Player, Offer>()

    override fun ScriptContext.startup() {
        for (group in TeleGroup.entries) {
            onIfOverlayButton(group.component) { cast(group) }
        }
        onIfModalButton("component.teleport_other:teleport_accept") { accept() }
        onIfModalButton("component.teleport_other:teleport_decline") {
            offers.remove(player)
            ifClose()
        }
        onPlayerQueueWithArgs<Int>(QUEUE) { land(CoordGrid(it.args)) }
    }

    private suspend fun ProtectedAccess.cast(group: TeleGroup) {
        if (actionDelay > mapClock) {
            return
        }
        val spell = caster.spell(group.obj) ?: return
        val destination = spell.obj.paramOrNull(params.spell_telecoord)
        if (destination == null) {
            mes("That teleport is not implemented yet.")
            return
        }
        if (!caster.canCast(player, spell) || !canTeleport(player)) {
            return
        }
        if (group.deepWilderness && !confirmDeepWilderness()) {
            return
        }
        if (!caster.cast(player, spell)) {
            return
        }
        depart()
        for (other in caster.playersAround(player, GROUP_RADIUS)) {
            if (!other.acceptAid || other.isAccessProtected) {
                continue
            }
            offer(other, destination, group.location)
        }
        queue(QUEUE, TELEPORT_DELAY, destination.packed)
    }

    private fun ProtectedAccess.offer(other: Player, destination: CoordGrid, location: String) {
        val name = player.displayName
        val opened =
            launcher.launch(other) {
                ifOpenMainModal(OFFER_INTERFACE)
                ifSetText("component.teleport_other:casters_name", name)
                ifSetText("component.teleport_other:teleport_location", location)
            }
        if (opened) {
            offers[other] = Offer(destination, clock.cycle + OFFER_TICKS)
        }
    }

    private fun ProtectedAccess.accept() {
        val offer = offers.remove(player)
        ifClose()
        if (offer == null || offer.expires < clock.cycle) {
            return
        }
        if (!canTeleport(player)) {
            return
        }
        depart()
        queue(QUEUE, TELEPORT_DELAY, offer.destination.packed)
    }

    private fun ProtectedAccess.depart() {
        actionDelay = mapClock + TELEPORT_DELAY + 1
        anim(TELEPORT_ANIM)
        spotanim(TELEPORT_SPOTANIM, height = 120)
        soundSynth(TELEPORT_SOUND)
    }

    private fun ProtectedAccess.land(destination: CoordGrid) {
        if (!canTeleport(player)) {
            return
        }
        val square = mapFindSquareLineOfWalk(destination, minRadius = 0, maxRadius = 1) ?: destination
        telejump(square)
        resetAnim()
    }

    private suspend fun ProtectedAccess.confirmDeepWilderness(): Boolean =
        menu(
            "Teleport deep into the Wilderness?",
            "Yes, teleport us to the Ice Plateau (level 53 Wilderness).",
            "No, stay here.",
        ) == 0

    private fun canTeleport(player: Player): Boolean {
        val denial = teleportValidator.validate(player, TeleportType.Standard, areaChecker) ?: return true
        player.mes(denial, ChatType.Engine)
        return false
    }

    private enum class TeleGroup(
        val obj: String,
        val component: String,
        val location: String,
        val deepWilderness: Boolean = false,
    ) {
        Moonclan("obj.70_tele_moonclan_group", "component.magic_spellbook:tele_group_moonclan", "Moonclan"),
        Waterbirth("obj.73_tele_waterbirth_group", "component.magic_spellbook:tele_group_waterbirth", "Waterbirth Island"),
        Barbarian("obj.76_tele_barb_outpost_group", "component.magic_spellbook:tele_group_barbarian", "Barbarian Outpost"),
        Khazard("obj.79_tele_port_khazard_group", "component.magic_spellbook:tele_group_khazard", "Port Khazard"),
        FishingGuild("obj.86_tele_fish_guild_group", "component.magic_spellbook:tele_group_fishing_guild", "Fishing Guild"),
        Catherby("obj.88_tele_catherby_group", "component.magic_spellbook:tele_group_catherby", "Catherby"),
        IcePlateau("obj.90_tele_ghorrock_group", "component.magic_spellbook:tele_group_ghorrock", "Ice Plateau", deepWilderness = true),
    }

    private companion object {
        const val QUEUE = "queue.lunar_tele_group"
        const val GROUP_RADIUS = 1
        const val OFFER_TICKS = 100
        const val TELEPORT_DELAY = 4
        const val OFFER_INTERFACE = "interface.teleport_other"
        const val TELEPORT_ANIM = "seq.human_teleport_other_impact"
        const val TELEPORT_SPOTANIM = "spotanim.lunar_teleport_spotanim"
        const val TELEPORT_SOUND = "synth.teleport_all"
    }
}
