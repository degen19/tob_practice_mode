package org.rsmod.content.raids.tob.lobby

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.rsmod.content.raids.tob.solo.NyloRole
import org.rsmod.content.raids.tob.solo.TobSeat

class TobPartiesTest {
    @Test
    fun `a new party is a trio with its leader on Player 1 and bots in the rest`() {
        val party = TobParties().own("ann")
        assertEquals(listOf("ann", null, null), party.group.seats.map { it.person })
        assertEquals(NyloRole.TRIO, party.group.roles)
    }

    @Test
    fun `joiners take the first bot seats and keep their seat as others come and go`() {
        val parties = TobParties()
        val party = parties.own("ann")
        assertTrue(parties.join(party, "bob"))
        assertTrue(parties.join(party, "cat"))
        assertEquals(listOf("ann", "bob", "cat"), party.group.seats.map { it.person })
        parties.leave("bob")
        assertEquals(listOf("ann", null, "cat"), party.group.seats.map { it.person })
        assertNull(parties["bob"])
    }

    @Test
    fun `a chosen seat survives a newcomer joining`() {
        val parties = TobParties()
        val party = parties.own("ann")
        party.group = party.group.copy(seats = listOf(TobSeat(NyloRole.Mage), TobSeat(NyloRole.Ranger), TobSeat(NyloRole.Melee, "ann")))
        parties.join(party, "bob")
        assertEquals(listOf("bob", null, "ann"), party.group.seats.map { it.person })
    }

    @Test
    fun `a fourth person grows a full trio into a four, and a sixth can't join a five`() {
        val parties = TobParties()
        val party = parties.own("p1")
        for (name in listOf("p2", "p3", "p4")) parties.join(party, name)
        assertEquals(NyloRole.QUAD, party.group.roles)
        assertEquals(listOf("p1", "p2", "p3", "p4"), party.group.seats.map { it.person })
        assertTrue(parties.join(party, "p5"))
        assertFalse(parties.join(party, "p6"))
        assertEquals(5, party.group.size)
    }

    @Test
    fun `the leader leaving hands the party to whoever joined next`() {
        val parties = TobParties()
        val party = parties.own("ann")
        parties.join(party, "bob")
        parties.leave("ann")
        assertEquals("bob", party.leader)
        assertEquals("bob", party.group.leader)
        assertEquals(party, parties.partyLedBy("bob"))
    }

    @Test
    fun `joining another party leaves the old one`() {
        val parties = TobParties()
        val first = parties.own("ann")
        parties.join(first, "bob")
        val second = parties.own("cat")
        parties.join(second, "bob")
        assertEquals(listOf("ann"), first.members)
        assertEquals(listOf("cat", "bob"), second.members)
    }
}
