package com.cleo.cleos.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.StickerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/**
 * A picture picked to be a sticker, before it has a name: copied into the cache, so one whose
 * dialog never closed (the app stopped meanwhile) is cleared away with the rest of the cache.
 */
data class PickedSticker(val file: File, val width: Int, val height: Int, val animated: Boolean)

class StickerException(message: String) : Exception(message)

/**
 * The person's stickers: the pictures in ImageStore (named sticker-…, so backups carry them with
 * the rest), the names in the database. One that moves (a GIF, an animated WebP) is kept byte for
 * byte: decoding it to store it again would keep its first frame only. A still one is downscaled
 * like any other picture; it is never shown large.
 */
class Stickers(context: Context, private val db: AppDatabase, private val images: ImageStore) {
    private val resolver = context.contentResolver
    private val picking = File(context.cacheDir, "stickers-picked")

    val all: Flow<List<StickerEntity>> = db.stickers().observeAll()

    suspend fun import(uri: Uri): PickedSticker = withContext(Dispatchers.IO) {
        picking.mkdirs()
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: throw StickerException("读不到这张图")
        if (bytes.size > MAX_BYTES) throw StickerException("这张图太大了，超过 8 MB")
        var animated = false
        var width = 0
        var height = 0
        try {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                animated = info.isAnimated
                width = info.size.width
                height = info.size.height
                // Only what the header says is wanted: the picture itself comes out tiny.
                decoder.setTargetSampleSize(maxOf(1, maxOf(width, height) / 32))
            }
        } catch (e: Exception) {
            throw StickerException("这张图打不开")
        }
        val ext = movingExtension(bytes)
        if (animated && ext != null) {
            val file = File(picking, "$PREFIX${UUID.randomUUID()}.$ext")
            file.writeBytes(bytes)
            PickedSticker(file, width, height, animated = true)
        } else {
            val bitmap = runCatching { images.decode(uri, STILL_EDGE) }.getOrElse { throw StickerException("这张图打不开") }
            // Kept clear where it is clear; else a JPEG, whose missing alpha would turn clear pixels black.
            val png = bitmap.hasAlpha()
            val file = File(picking, "$PREFIX${UUID.randomUUID()}" + if (png) ".png" else ".jpg")
            file.outputStream().use { bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it) }
            PickedSticker(file, bitmap.width, bitmap.height, animated = false).also { bitmap.recycle() }
        }
    }

    /** [picked] in the collection as [name]; null when it went in, else what is wrong with the name. */
    suspend fun add(picked: PickedSticker, name: String, description: String): String? {
        val n = oneLine(name)
        val about = oneLine(description)
        problem(n, about, except = null)?.let { return it }
        val kept = withContext(Dispatchers.IO) {
            val dest = images.file(picked.file.name)
            if (!picked.file.renameTo(dest)) {
                picked.file.copyTo(dest, overwrite = true)
                picked.file.delete()
            }
            dest.name
        }
        db.stickers().insert(
            StickerEntity(
                name = n,
                description = about,
                file = kept,
                width = picked.width,
                height = picked.height,
                animated = picked.animated,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return null
    }

    /**
     * A new name or description; null when that went through. The old name is kept among its
     * [StickerEntity.aliases]: messages that sent it by that name still show the picture.
     */
    suspend fun edit(id: Long, name: String, description: String): String? {
        val s = db.stickers().get(id) ?: return "这张表情包已经不在了"
        val n = oneLine(name)
        val about = oneLine(description)
        problem(n, about, except = id)?.let { return it }
        val before = StickerBook.aliases(s)
        val aliases = if (n == s.name) before else (before - n + s.name).distinct()
        db.stickers().update(s.copy(name = n, description = about, aliases = StickerBook.encodeAliases(aliases)))
        return null
    }

    /** Messages that sent it keep its name, shown in words: [表情包：名字]. */
    suspend fun delete(id: Long) {
        val s = db.stickers().get(id) ?: return
        db.stickers().delete(id)
        images.delete(listOf(s.file))
    }

    /** A picked picture that never got a name: nothing will point at its file. */
    fun discard(picked: PickedSticker) {
        runCatching { picked.file.delete() }
    }

    private suspend fun problem(name: String, description: String, except: Long?): String? = when {
        name.isEmpty() -> "起个名字：TA 看不到图，是按名字认的"
        name.length > MAX_NAME -> "名字最多 $MAX_NAME 个字"
        name.any { it in NOT_IN_NAMES } -> "名字里不能有方括号"
        description.length > MAX_DESCRIPTION -> "说明最多 $MAX_DESCRIPTION 个字"
        StickerBook(db.stickers().all()).taken(name, except) -> "已经有叫「$name」的表情包了"
        else -> null
    }

    private fun oneLine(s: String) = s.replace('\n', ' ').trim()

    /** What a picture that moves is kept as, from its first bytes; null for the kinds kept still. */
    private fun movingExtension(b: ByteArray): String? = when {
        b.size >= 6 && String(b, 0, 4, Charsets.US_ASCII) == "GIF8" -> "gif"
        b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> null
    }

    companion object {
        const val PREFIX = "sticker-"
        const val MAX_NAME = 20
        const val MAX_DESCRIPTION = 40
        private const val MAX_BYTES = 8 * 1024 * 1024

        /** A still one's longer side: it is drawn at most a little over 100dp. */
        private const val STILL_EDGE = 512

        /** They would close the token early, or look like one ([[sticker:…]]). */
        private val NOT_IN_NAMES = setOf('[', ']', '【', '】')
    }
}
