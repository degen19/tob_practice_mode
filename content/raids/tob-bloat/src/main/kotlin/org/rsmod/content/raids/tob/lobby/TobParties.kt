package org.rsmod.content.raids.tob.lobby

import jakarta.inject.Singleton
import org.rsmod.content.raids.tob.solo.NyloRole
import org.rsmod.content.raids.tob.solo.SeatBook
import org.rsmod.content.raids.tob.solo.TobGroup
import org.rsmod.content.raids.tob.solo.TobSeat

/** A lobby party: its leader, who else has joined (in join order), who's been invited and who's asked to join. */
class TobParty(var leader: String) {
    val members = arrayListOf(leader)
    val invited = HashSet<String>()
    val asked = LinkedHashSet<String>()
    var group: TobGroup = defaultGroup(leader, listOf(leader))

    /** Re-seats everyone after the party changes: people keep their seat where they can, newcomers take the first bot seats. */
    fun reseat() {
        val roles = group.roles.takeIf { it.size >= members.size } ?: TobGroup.standardRoles(members.size)
        val seats =
            roles.mapIndexed { i, role ->
                val old = group.seats.getOrNull(i)
                TobSeat(role, old?.person?.takeIf { it in members }, old?.book ?: SeatBook.Standard)
            }.toMutableList()
        val waiting = ArrayDeque(members.filter { name -> seats.none { it.person == name } })
        for (i in seats.indices) if (seats[i].person == null && waiting.isNotEmpty()) seats[i] = seats[i].copy(person = waiting.removeFirst())
        group = group.copy(leader = leader, seats = seats)
    }

    companion object {
        fun defaultGroup(leader: String, people: List<String>): TobGroup {
            val roles = TobGroup.standardRoles(maxOf(NyloRole.TRIO.size, people.size))
            return TobGroup(leader, roles.mapIndexed { i, role -> TobSeat(role, people.getOrNull(i)) })
        }
    }
}

/** Every party in the lobby, by member. */
@Singleton
class TobParties {
    private val byMember = HashMap<String, TobParty>()

    operator fun get(username: String): TobParty? = byMember[username]

    fun own(username: String): TobParty = byMember.getOrPut(username) { TobParty(username) }

    fun join(party: TobParty, username: String): Boolean {
        if (party.members.size >= TobGroup.MAX_SIZE) return false
        leave(username)
        party.members += username
        party.invited -= username
        party.asked -= username
        byMember[username] = party
        party.reseat()
        return true
    }

    /** Takes [username] out of their party; a leader leaving hands it to the next to have joined. */
    fun leave(username: String): TobParty? {
        val party = byMember.remove(username) ?: return null
        party.members -= username
        if (party.members.isEmpty()) return party
        if (party.leader == username) party.leader = party.members.first()
        party.reseat()
        return party
    }

    fun partyLedBy(leader: String): TobParty? = byMember[leader]?.takeIf { it.leader == leader }
}
