package org.rsmod.content.raids.tob.lobby

import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.content.raids.tob.solo.SeatBook
import org.rsmod.content.raids.tob.solo.SoloRaids
import org.rsmod.content.raids.tob.solo.TobGroup
import org.rsmod.content.raids.tob.solo.TobRoom
import org.rsmod.content.raids.tob.solo.TobSeat
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Grouping in the lobby, a mock until the interfaces exist: `::party` invites and join requests, and Mr. Mordaut's Build
 * Team, which sets the party's [TobGroup] (size, who sits where, each freezer's spellbook) and starts it at Maiden.
 */
class TobPartyScript
@Inject
constructor(private val parties: TobParties, private val players: PlayerList, private val raids: SoloRaids) : PluginScript() {
    override fun ScriptContext.startup() {
        onCommand("party") {
            desc = "Lobby party: ::party | invite <name> | accept <name> | request <name> | approve <name> | leave"
            cheat { party() }
        }
        onOpNpc1(TobLobby.TEACHER) { buildTeam() }
        onPlayerLogout { parties.leave(player.username)?.let { tell(it, "${player.displayName} has left the party.") } }
    }

    private fun Cheat.party() {
        val name = args.drop(1).joinToString(" ").lowercase().replace(' ', '_').ifEmpty { null }
        when (args.getOrNull(0)?.lowercase()) {
            null -> show(player)
            "invite" -> invite(player, name ?: return player.mes("::party invite <name>"))
            "accept" -> accept(player, name ?: return player.mes("::party accept <name>"))
            "request" -> request(player, name ?: return player.mes("::party request <name>"))
            "approve" -> approve(player, name ?: return player.mes("::party approve <name>"))
            "leave" -> parties.leave(player.username)?.let { tell(it, "${player.displayName} has left the party."); player.mes("You leave the party.") } ?: player.mes("You're not in a party.")
            else -> player.mes("::party | invite <name> | accept <name> | request <name> | approve <name> | leave")
        }
    }

    private fun online(name: String): Player? = players.firstOrNull { it.username.equals(name, ignoreCase = true) || it.displayName.equals(name.replace('_', ' '), ignoreCase = true) }

    private fun invite(leader: Player, name: String) {
        val target = online(name) ?: return leader.mes("Nobody called $name is online.")
        if (target === leader) return leader.mes("You're already in your own party.")
        val party = parties.own(leader.username)
        if (party.leader != leader.username) return leader.mes("Only ${party.leader} can invite.")
        party.invited += target.username
        leader.mes("You invite ${target.displayName}.")
        target.mes("${leader.displayName} invites you to their party: ::party accept ${leader.displayName}")
    }

    private fun accept(player: Player, name: String) {
        val leader = online(name) ?: return player.mes("Nobody called $name is online.")
        val party = parties.partyLedBy(leader.username)?.takeIf { player.username in it.invited } ?: return player.mes("${leader.displayName} hasn't invited you.")
        if (!parties.join(party, player.username)) return player.mes("That party is full.")
        tell(party, "${player.displayName} has joined the party.")
    }

    private fun request(player: Player, name: String) {
        val leader = online(name) ?: return player.mes("Nobody called $name is online.")
        val party = parties.partyLedBy(leader.username) ?: return player.mes("${leader.displayName} isn't leading a party.")
        party.asked += player.username
        player.mes("You ask to join ${leader.displayName}'s party.")
        leader.mes("${player.displayName} asks to join your party: ::party approve ${player.displayName}")
    }

    private fun approve(leader: Player, name: String) {
        val party = parties.partyLedBy(leader.username) ?: return leader.mes("You're not leading a party.")
        val target = online(name)?.takeIf { it.username in party.asked } ?: return leader.mes("$name hasn't asked to join.")
        if (!parties.join(party, target.username)) return leader.mes("Your party is full.")
        tell(party, "${target.displayName} has joined the party.")
    }

    private fun show(player: Player) {
        val party = parties[player.username] ?: return player.mes("You're not in a party: ::party invite <name> or ::party request <name>.")
        player.mes("Party led by ${party.leader}: ${party.members.joinToString()}.")
        for (line in describe(party.group)) player.mes(line)
    }

    private fun tell(party: TobParty, text: String) {
        for (name in party.members) online(name)?.mes(text)
    }

    /** Build Team: the leader sets the team up; anyone in the party can pick their own seat. */
    private suspend fun ProtectedAccess.buildTeam() {
        val party = parties[player.username] ?: parties.own(player.username)
        val leads = party.leader == player.username
        while (true) {
            val options = buildList {
                add("Show the team")
                add("Choose my seat")
                if (leads) add("Team size: ${party.group.size}")
                if (leads) add("Freezers' spellbook")
                if (leads) add("Start at Maiden")
                add("Done")
            }
            val picked = options[menu("Build Team", *options.toTypedArray())]
            when {
                picked == "Show the team" -> describe(party.group).forEach { mes(it) }
                picked == "Choose my seat" -> chooseSeat(party)
                picked.startsWith("Team size") -> chooseSize(party)
                picked == "Freezers' spellbook" -> chooseBook(party)
                picked == "Start at Maiden" -> return start(party, TobRoom.MAIDEN)
                else -> return
            }
        }
    }

    private suspend fun ProtectedAccess.chooseSize(party: TobParty) {
        val sizes = (maxOf(party.members.size, TobGroup.MIN_SIZE)..TobGroup.MAX_SIZE).toList()
        val choice = menu("Team size", *sizes.map { SIZE_NAMES.getValue(it) }.toTypedArray())
        val size = sizes[choice]
        val old = party.group.seats
        party.group = party.group.copy(seats = TobGroup.standardRoles(size).mapIndexed { i, role -> TobSeat(role, old.getOrNull(i)?.person) })
        party.reseat()
        mes("The team is a ${SIZE_NAMES.getValue(size)}.")
    }

    private suspend fun ProtectedAccess.chooseSeat(party: TobParty) {
        val seats = party.group.seats
        val choice = menu("Your seat", *seats.mapIndexed { i, s -> "Player ${i + 1}: ${s.role} (${s.person ?: "bot"})" }.toTypedArray())
        val target = seats[choice]
        if (target.person != null && target.person != player.username) return mes("${target.person} sits there.")
        val mine = seats.indexOfFirst { it.person == player.username }
        val updated = seats.toMutableList()
        if (mine >= 0) updated[mine] = updated[mine].copy(person = null)
        updated[choice] = target.copy(person = player.username)
        party.group = party.group.copy(seats = updated)
        mes("You'll play Player ${choice + 1}, the ${target.role}.")
    }

    private suspend fun ProtectedAccess.chooseBook(party: TobParty) {
        val choice = menu("Freezers' spellbook", "Ancients (no Death Charge)", "Lunars with Spellbook Swap (Death Charge too)")
        val book = if (choice == 0) SeatBook.Standard else SeatBook.LunarSwap
        party.group = party.group.copy(seats = party.group.seats.map { if (it.role.freezes) it.copy(book = book) else it })
        mes("Freezers on ${if (book == SeatBook.Standard) "the Ancients" else "the Lunars, swapping for barrages"}.")
    }

    private fun ProtectedAccess.start(party: TobParty, room: TobRoom) {
        val go = raids.startGroup ?: return mes("The Theatre isn't ready.")
        go(player, party.group, room)?.let { mes(it) }
    }

    companion object {
        val SIZE_NAMES = mapOf(1 to "solo", 2 to "duo", 3 to "trio", 4 to "four", 5 to "five")

        fun describe(group: TobGroup): List<String> =
            group.seats.mapIndexed { i, s ->
                val book = if (s.role.freezes && s.book == SeatBook.LunarSwap) ", Lunars" else ""
                "Player ${i + 1}: ${s.role}$book - ${s.person ?: "bot"}"
            }
    }
}
