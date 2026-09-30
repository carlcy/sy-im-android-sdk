package com.sy.im.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UnreadAndApiTest {
    @Test
    fun versionIsUnifiedAt050() {
        val fileVersion = File("VERSION").readText().trim().removePrefix("v")
        assertEquals("0.5.0", fileVersion)
        assertEquals(fileVersion, SyImSdk.VERSION)
    }

    @Test
    fun markReadClearsTotalAndThatConversationImmediately() {
        val seen = mutableListOf<UnreadSnapshot>()
        val session = ImLocalSession { seen += it }
        session.sendText("u2", null, "hello")
        session.sendText(null, "g1", "group")
        val before = seen.last()
        assertEquals(2, before.total)
        assertEquals(1, before.conversations.first { it.conversationId == "si_u2" }.unreadCount)
        assertEquals(1, before.conversations.first { it.conversationId == "sg_g1" }.unreadCount)

        session.markRead("si_u2")
        val after = seen.last()
        assertEquals(1, after.total)
        assertEquals(0, after.conversations.first { it.conversationId == "si_u2" }.unreadCount)
        assertEquals(1, after.conversations.first { it.conversationId == "sg_g1" }.unreadCount)
        assertTrue(seen.size >= 3)
    }

    @Test
    fun serverTotalAndConversationUpsertStayIndependent() {
        val tracker = UnreadTracker()
        tracker.applyServerTotal(5)
        val snap = tracker.upsert(
            listOf(
                ImConversation("c1", "u2", null, "peer", 2, false, null, false),
            ),
        )
        assertEquals(5, snap.total)
        assertEquals(2, snap.conversations.single().unreadCount)
        val cleared = tracker.markRead("c1")
        assertEquals(0, cleared.conversations.single().unreadCount)
        assertEquals(3, cleared.total)
    }

    @Test
    fun newApisCoverRecallMentionSearchPinDraftDndTypingCustomExBlacklist() {
        val session = ImLocalSession { }
        val atId = session.sendAt("g1", "@bob hi", listOf("bob"), atAll = false)
        val customId = session.sendCustom("u2", null, """{"k":1}""", "card")
        assertTrue(atId.startsWith("stub_at"))
        assertTrue(customId.startsWith("stub_custom"))

        assertTrue(session.revoke("sg_g1", atId))
        assertTrue(session.isRevoked(atId))
        assertTrue(session.search("@bob", "sg_g1").isEmpty())
        val hits = session.search("card", null)
        assertEquals(1, hits.single().messageCount)
        assertEquals("card", hits.single().preview)

        session.pin("si_u2", true)
        session.draft("si_u2", "draft")
        session.setDoNotDisturb("si_u2", true)
        session.typing("u2", true)
        session.setSelfEx("""{"vip":1}""")
        session.setGroupEx("g1", """{"topic":"t"}""")
        session.addBlack("u9")
        session.addBlack("u8")
        session.removeBlack("u8")

        assertTrue(session.isPinned("si_u2"))
        assertEquals("draft", session.draftOf("si_u2"))
        assertTrue(session.isDoNotDisturb("si_u2"))
        assertEquals("u2", session.typingUser)
        assertEquals("""{"vip":1}""", session.selfEx)
        assertEquals("""{"topic":"t"}""", session.groupEx("g1"))
        assertEquals(listOf("u9"), session.blacklist())
        session.typing("u2", false)
        assertEquals(null, session.typingUser)
        assertFalse(session.revoke("", "x"))
    }

    @Test
    fun publishedPomKeepsTransitiveOpenIm() {
        val pom = File("build/publications/release/pom-default.xml")
        assertTrue(
            "先执行 generatePomFileForReleasePublication，POM 不存在: ${pom.absolutePath}",
            pom.isFile,
        )
        val text = pom.readText()
        assertTrue(text.contains("<version>v0.5.0</version>") || text.contains("<version>0.5.0</version>"))
        assertTrue(text.contains("io.openim"))
        assertTrue(text.contains("android-sdk"))
        assertTrue(text.contains("core-sdk"))
        assertTrue(text.contains("3.8.3.5"))
        assertTrue(text.contains("3.8.3-patch15"))
        assertFalse(text.contains("<groupId>*</groupId>"))
        assertFalse(text.contains("<artifactId>*</artifactId>"))
    }
}
