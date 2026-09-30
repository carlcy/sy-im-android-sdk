package com.sy.im.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingStatusTest {
    @Test
    fun parsesOpenImInputStatusJson() {
        val s = ImTypingStatus.parse("""{"conversationID":"si_u1_u2","userID":"u2","platformIDs":[2,5]}""")!!
        assertEquals("si_u1_u2", s.conversationId)
        assertEquals("u2", s.userId)
        assertEquals(listOf(2, 5), s.platformIds)
        assertTrue(s.typing)
    }

    @Test
    fun emptyOrMissingPlatformsMeansStopped() {
        assertFalse(ImTypingStatus.parse("""{"conversationID":"c","userID":"u2","platformIDs":[]}""")!!.typing)
        assertFalse(ImTypingStatus.parse("""{"conversationID":"c","userID":"u2"}""")!!.typing)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(ImTypingStatus.parse(null))
        assertNull(ImTypingStatus.parse(""))
        assertNull(ImTypingStatus.parse("yes"))
        assertNull(ImTypingStatus.parse("""{"conversationID":"c"}"""))
    }

    @Test
    fun listenerDefaultsAreNoOps() {
        val listener = object : ImEventListener {}
        listener.onTypingStatus(ImTypingStatus("c", "u", emptyList()))
        listener.onTypingStatusChanged("")
    }
}
