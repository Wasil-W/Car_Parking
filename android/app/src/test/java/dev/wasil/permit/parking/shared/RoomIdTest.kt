package dev.wasil.permit.parking.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomIdTest {
    @Test
    fun `room id is 32 lowercase hex chars`() {
        val id = roomIdFor("wasil@example.com")
        assertEquals(32, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
    }

    @Test
    fun `same username gives same room`() {
        assertEquals(roomIdFor("wasil@example.com"), roomIdFor("wasil@example.com"))
    }

    @Test
    fun `case and whitespace are normalized`() {
        assertEquals(roomIdFor("wasil@example.com"), roomIdFor("  Wasil@Example.COM "))
    }

    @Test
    fun `different usernames give different rooms`() {
        assertNotEquals(roomIdFor("wasil@example.com"), roomIdFor("walid@example.com"))
    }
}
