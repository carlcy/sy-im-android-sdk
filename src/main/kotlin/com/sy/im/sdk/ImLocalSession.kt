package com.sy.im.sdk

/** mock 模式下的本地会话，不依赖 Android / OpenIM。供单测和 Demo。 */
internal class ImLocalSession(
    private val onUnread: (UnreadSnapshot) -> Unit,
) {
    private val tracker = UnreadTracker()
    private val messages = mutableListOf<LocalMessage>()
    private val revoked = linkedSetOf<String>()
    private val blacklist = linkedSetOf<String>()
    private val pinned = mutableMapOf<String, Boolean>()
    private val drafts = mutableMapOf<String, String>()
    private val dnd = mutableMapOf<String, Boolean>()
    private val groupEx = mutableMapOf<String, String>()
    var selfEx: String? = null
        private set
    var typingUser: String? = null
        private set

    fun conversationId(userId: String?, groupId: String?): String {
        require(!userId.isNullOrBlank() || !groupId.isNullOrBlank()) { "provide userId or groupId" }
        return if (!groupId.isNullOrBlank()) "sg_$groupId" else "si_$userId"
    }

    fun sendText(userId: String?, groupId: String?, text: String): String {
        val id = nextId("stub_msg")
        val cid = conversationId(userId, groupId)
        messages += LocalMessage(id, cid, text)
        emit(tracker.incoming(cid, userId, groupId))
        return id
    }

    fun sendAt(groupId: String, text: String, atUserIds: List<String>, atAll: Boolean): String {
        require(groupId.isNotBlank()) { "groupId blank" }
        require(atUserIds.isNotEmpty() || atAll) { "atUserIds empty" }
        val id = nextId("stub_at")
        val cid = conversationId(null, groupId)
        messages += LocalMessage(id, cid, text)
        emit(tracker.incoming(cid, null, groupId))
        return id
    }

    fun sendCustom(userId: String?, groupId: String?, data: String, description: String): String {
        require(data.isNotBlank()) { "data blank" }
        val id = nextId("stub_custom")
        val cid = conversationId(userId, groupId)
        messages += LocalMessage(id, cid, description.ifBlank { data })
        emit(tracker.incoming(cid, userId, groupId))
        return id
    }

    fun revoke(conversationId: String, clientMsgId: String): Boolean {
        if (conversationId.isBlank() || clientMsgId.isBlank()) return false
        revoked += clientMsgId
        return true
    }

    fun isRevoked(clientMsgId: String): Boolean = clientMsgId in revoked

    fun markRead(conversationId: String) {
        emit(tracker.markRead(conversationId))
    }

    fun search(keyword: String, conversationId: String?): List<ImSearchHit> {
        val hits = messages.filter { msg ->
            (conversationId.isNullOrBlank() || msg.conversationId == conversationId) &&
                msg.text.contains(keyword, ignoreCase = true) &&
                msg.clientMsgId !in revoked
        }
        return hits.groupBy { it.conversationId }.map { (cid, list) ->
            ImSearchHit(cid, null, list.size, list.last().text)
        }
    }

    fun pin(conversationId: String, value: Boolean) {
        pinned[conversationId] = value
    }

    fun isPinned(conversationId: String): Boolean = pinned[conversationId] == true

    fun draft(conversationId: String, text: String) {
        drafts[conversationId] = text
    }

    fun draftOf(conversationId: String): String? = drafts[conversationId]

    fun setDoNotDisturb(conversationId: String, enabled: Boolean) {
        dnd[conversationId] = enabled
    }

    fun isDoNotDisturb(conversationId: String): Boolean = dnd[conversationId] == true

    fun typing(userId: String, typing: Boolean) {
        typingUser = if (typing) userId else null
    }

    fun setSelfEx(ex: String) {
        selfEx = ex
    }

    fun setGroupEx(groupId: String, ex: String) {
        groupEx[groupId] = ex
    }

    fun groupEx(groupId: String): String? = groupEx[groupId]

    fun addBlack(userId: String) {
        blacklist += userId
    }

    fun removeBlack(userId: String) {
        blacklist -= userId
    }

    fun blacklist(): List<String> = blacklist.toList()

    fun snapshot(): UnreadSnapshot = tracker.snapshot()

    private fun emit(snapshot: UnreadSnapshot) {
        onUnread(snapshot)
    }

    private fun nextId(prefix: String) = "${prefix}_${messages.size + revoked.size + 1}_${textClock()}"

    private fun textClock(): Long = System.currentTimeMillis()

    private data class LocalMessage(
        val clientMsgId: String,
        val conversationId: String,
        val text: String,
    )
}
