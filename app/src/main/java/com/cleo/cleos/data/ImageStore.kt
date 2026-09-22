package com.cleo.cleos.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class StoredImage(val file: String, val width: Int, val height: Int)

/**
 * Pictures copied into the app's own storage.
 *
 * Keeping a content:// URI from the picker is not enough: the grant can lapse, and the
 * photo can be deleted from the gallery, and a diary page should not lose its picture
 * because of either. So every picked image is decoded, downscaled and saved here.
 */
class ImageStore(context: Context) {
    private val resolver = context.contentResolver
    val dir: File = File(context.filesDir, "images").apply { mkdirs() }

    fun file(name: String): File = File(dir, name)

    /**
     * Copies [uri] into the store. ImageDecoder applies the EXIF rotation, so portrait
     * phone photos come out upright. Images with transparency stay PNG; everything else
     * becomes JPEG, whose missing alpha would otherwise turn transparent pixels black.
     */
    suspend fun import(uri: Uri, maxEdge: Int = 2048, prefix: String = ""): StoredImage = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(resolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = min(1f, maxEdge / max(w, h).toFloat())
            if (scale < 1f) {
                decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val png = bitmap.hasAlpha()
        val name = prefix + UUID.randomUUID().toString() + if (png) ".png" else ".jpg"
        val out = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.outputStream().use {
            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it)
        }
        tmp.renameTo(out)
        StoredImage(name, bitmap.width, bitmap.height).also { bitmap.recycle() }
    }

    fun delete(names: Collection<String>) {
        names.forEach { runCatching { File(dir, it).delete() } }
    }

    /** A small, cheap decode for colour analysis. */
    fun thumbnail(name: String, targetEdge: Int = 48): Bitmap? {
        val f = File(dir, name)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetEdge) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
