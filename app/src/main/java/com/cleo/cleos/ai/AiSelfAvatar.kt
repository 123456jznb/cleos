package com.cleo.cleos.ai

import com.cleo.cleos.data.Companions
import com.cleo.cleos.data.ImageStore
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.db.AppDatabase

/**
 * The app's side of set_my_avatar. A TA's avatar lives in their row, like the one the
 * person picks for them on the home page, so either can replace the other.
 */
class AiSelfAvatar(
    private val db: AppDatabase,
    private val images: ImageStore,
    private val companions: Companions,
) : SelfAvatar {
    /** Only pictures the person sent in this conversation: the model can't reach into another one. */
    override suspend fun picture(conversationId: Long, ref: String): String? {
        val r = PictureRef.parse(ref) ?: return null
        val message = when (r) {
            PictureRef.Latest -> db.messages().latestWithImages(conversationId)
            is PictureRef.Id -> db.messages().get(r.messageId)?.takeIf { it.conversationId == conversationId && it.role == "user" }
        } ?: return null
        val pictures = MessageImages.decode(message.images)
        return when (r) {
            PictureRef.Latest -> pictures.lastOrNull()
            is PictureRef.Id -> pictures.getOrNull(r.index - 1)
        }?.file
    }

    override suspend fun usePicture(companionId: Long, file: String): Boolean {
        val square = images.centreSquare(file) ?: return false
        companions.setAvatar(companionId, file = images.save(square, prefix = "avatar-"), emoji = null)
        return true
    }

    override suspend fun useEmoji(companionId: Long, emoji: String) = companions.setAvatar(companionId, file = null, emoji = emoji)
}

/** How the model names a picture: "latest", or "#45-2" (message 45, its second picture). */
internal sealed interface PictureRef {
    data object Latest : PictureRef

    data class Id(val messageId: Long, val index: Int) : PictureRef

    companion object {
        private val ID = Regex("""#?\s*(\d+)\s*-\s*(\d+)""")

        fun parse(ref: String): PictureRef? {
            val t = ref.trim()
            if (t.isEmpty() || t.equals("latest", ignoreCase = true) || t == "最近" || t == "最新") return Latest
            val m = ID.matchEntire(t) ?: return null
            val id = m.groupValues[1].toLongOrNull() ?: return null
            val index = m.groupValues[2].toIntOrNull() ?: return null
            return if (index >= 1) Id(id, index) else null
        }
    }
}
