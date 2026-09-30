package com.sy.im.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 与 iOS SyImReadReceiptsTests、Flutter read_receipts_test 相同的期望。 */
class ReadReceiptsTest {
    @Test
    fun conversationIds() {
        assertEquals("si_a_b", ImReadReceipts.singleConversationId("b", "a"))
        assertEquals("", ImReadReceipts.singleConversationId("", "a"))
        assertEquals("sg_g1", ImReadReceipts.groupConversationId("g1"))
        assertEquals("g1", ImReadReceipts.groupIdOf("sg_g1"))
        assertNull(ImReadReceipts.groupIdOf("si_a_b"))
    }

    @Test
    fun perMessageGroupReceiptBecomesPerReader() {
        val out = ImReadReceipts.perReader("sg_g1", listOf("m1" to listOf("u2", "u3"), "m2" to listOf("u2"), "" to listOf("u9")))
        assertEquals(2, out.size)
        assertEquals(ImReadReceipt("sg_g1", "u2", "g1", listOf("m1", "m2")), out[0])
        assertEquals(listOf("m1"), out[1].msgIds)
        assertTrue(out[0].isGroup)
        assertFalse(ImReadReceipt("si_a_b", "a", null, listOf("m")).isGroup)
    }

    @Test
    fun mergePrefersOpenImThenRoster() {
        val a = ImReadReceipts.merge("m1", 2, 3, listOf("u2", "u3"), listOf("u9"))
        assertEquals(ImGroupReadInfo.SOURCE_OPENIM, a.source)
        assertEquals(listOf("u2", "u3"), a.readUserIds)

        val b = ImReadReceipts.merge("m1", 1, 3, emptyList(), listOf("u2", "u3"))
        assertEquals(ImGroupReadInfo.SOURCE_CONTROL_PLANE, b.source)
        assertEquals(2, b.hasReadCount)
        assertEquals(2, b.unreadCount)

        val c = ImReadReceipts.merge("m1", 4, 0, emptyList(), null)
        assertEquals(ImGroupReadInfo.SOURCE_NONE, c.source)
        assertEquals(4, c.hasReadCount)
        assertTrue(c.readUserIds.isEmpty())
    }

    @Test
    fun whoReadRosterDedupes() {
        val list = listOf(mapOf("readerUid" to "u3", "seq" to 7), mapOf("readerUid" to "u2"), mapOf("readerUid" to "u3"), mapOf("x" to 1))
        assertEquals(listOf("u3", "u2"), ImReadReceipts.readersFromWhoRead(list))
    }
}
