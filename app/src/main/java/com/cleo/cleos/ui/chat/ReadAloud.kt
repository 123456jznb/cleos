package com.cleo.cleos.ui.chat

import android.media.MediaPlayer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What 朗读 puts in the chat's "playing" mark for a message, beside the file names a voice message plays under. */
internal fun readingMark(messageId: Long) = "read:$messageId"

/**
 * Plays [file] to its end. The player is handed to [hold] while it plays (and null after), so the
 * screen can stop it; cancelling lets it go. Throws when the file can't be played.
 */
internal suspend fun playToEnd(file: File, hold: (MediaPlayer?) -> Unit) = suspendCancellableCoroutine<Unit> { cont ->
    val p = MediaPlayer()
    hold(p)
    cont.invokeOnCancellation {
        runCatching { p.stop() }
        p.release()
        hold(null)
    }
    p.setOnCompletionListener {
        p.release()
        hold(null)
        if (cont.isActive) cont.resume(Unit)
    }
    p.setOnErrorListener { _, _, _ ->
        p.release()
        hold(null)
        if (cont.isActive) cont.resumeWithException(IllegalStateException("can't play ${file.name}"))
        true
    }
    try {
        p.setDataSource(file.path)
        p.prepare()
        p.start()
    } catch (e: Exception) {
        p.release()
        hold(null)
        if (cont.isActive) cont.resumeWithException(e)
    }
}
