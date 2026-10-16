package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The morning's proposal and its swap, as the client computes it before « C'est parti » (MAG-498). */
class DayProposalTest {

    private fun slot(position: Int, id: String, minutes: Int, reason: String = "postponed") =
        ProposalSlot(position = position, reason = reason, minutes = minutes, task = DayTask(id, id))

    private fun entry(id: String, minutes: Int, reason: String = "whenever") =
        ProposalEntry(reason = reason, minutes = minutes, task = DayTask(id, id))

    private val proposal = DayProposal(
        date = "2026-10-16",
        slots = listOf(slot(1, "A", 10), slot(2, "B", 20, "dueToday")),
        reserveRanked = listOf(entry("R1", 30, "quick"), entry("R2", 5)),
        totalMinutes = 30,
        freeMinutes = 180,
    )

    @Test
    fun `the first of the reserve takes the slot, its position stays, the old task goes to the end`() {
        val swapped = proposal.swap(1)

        assertEquals(listOf("A", "R1"), swapped.taskIds)
        assertEquals(listOf(1, 2), swapped.slots.map { it.position })
        assertEquals("quick", swapped.slots[1].reason)
        assertEquals(listOf("R2", "B"), swapped.reserveRanked.map { it.task.id })
        assertEquals("dueToday", swapped.reserveRanked.last().reason)
        assertEquals(40, swapped.totalMinutes)
    }

    @Test
    fun `swapping again walks the reserve without losing a task`() {
        val swapped = proposal.swap(1).swap(1)

        assertEquals(listOf("A", "R2"), swapped.taskIds)
        assertEquals(listOf("B", "R1"), swapped.reserveRanked.map { it.task.id })
    }

    @Test
    fun `without a reserve, or for a slot that does not exist, nothing changes`() {
        val alone = proposal.copy(reserveRanked = emptyList())

        assertSame(alone, alone.swap(0))
        assertSame(proposal, proposal.swap(7))
    }

    @Test
    fun `the ids to write are those of the slots, in order`() {
        assertEquals(listOf("A", "B"), proposal.taskIds)
    }

    @Test
    fun `each reason has its word`() {
        assertEquals(
            listOf("reportée", "à faire aujourd'hui", "urgent", "pour demain", "rapide", "quand tu veux"),
            listOf("postponed", "dueToday", "urgent", "dueTomorrow", "quick", "whenever").map(ProposalReason::label),
        )
    }
}
