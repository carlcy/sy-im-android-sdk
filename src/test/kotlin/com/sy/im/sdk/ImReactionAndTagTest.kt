package com.sy.im.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImReactionAndTagTest {
    @Test
    fun parsesServerReactionPayload() {
        val data = """{"sy":"reaction_lite","action":"add","emoji":"👍","target":{"seq":12,"clientMsgId":"c1","senderId":"u2"}}"""
        val r = ImReaction.parse(data)!!
        assertEquals("👍", r.emoji)
        assertTrue(r.added)
        assertEquals("c1", r.targetClientMsgId)
        assertEquals(12L, r.targetSeq)
        assertEquals("u2", r.targetSenderId)
        assertFalse(ImReaction.parse(data.replace("\"add\"", "\"remove\""))!!.added)
    }

    @Test
    fun ignoresOtherCustomMessages() {
        assertNull(ImReaction.parse(null))
        assertNull(ImReaction.parse("not json"))
        assertNull(ImReaction.parse("""{"sy":"call_invite"}"""))
        assertNull(ImReaction.parse("""{"sy":"reaction_lite","emoji":""}"""))
    }

    @Test
    fun reactionBodyMatchesBackendReactReq() {
        val group = ImControlPlane.reactionBody("app", "u1", "👍", null, "g1", "c1", 0, "u2", add = false)
        assertEquals("g1", group.getString("groupId"))
        assertFalse(group.has("toUserId"))
        assertEquals("remove", group.getString("action"))
        assertEquals("c1", group.getString("targetClientMsgId"))
        assertFalse(group.has("targetSeq"))
        val single = ImControlPlane.reactionBody("app", "u1", "❤️", "u3", null, null, 7, null, add = true)
        assertEquals("u3", single.getString("toUserId"))
        assertEquals(7L, single.getLong("targetSeq"))
        assertEquals("add", single.getString("action"))
    }

    @Test
    fun tagMembersBodyMatchesBackend() {
        val b = ImControlPlane.tagMembersBody("app", "u1", 9, "add", listOf("si_u1_u2", "sg_g1"))
        assertEquals(9L, b.getLong("tagId"))
        assertEquals("add", b.getString("action"))
        assertEquals(2, b.getJSONArray("conversationIds").length())
        assertEquals("u1", b.getString("ownerUserId"))
    }
}
