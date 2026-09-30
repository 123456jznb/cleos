package com.cleo.cleos.ai

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.cleo.cleos.NowPlayingListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What is playing on the phone, as its player tells the system (the same thing the lock screen
 * shows). [positionMs] was where it was at [at], an elapsedRealtime; [position] works out now.
 * [art] is for the screen only.
 */
data class NowPlaying(
    val player: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val at: Long,
    val playing: Boolean,
    val speed: Float = 1f,
    val art: Bitmap? = null,
) {
    fun position(now: Long): Long {
        val p = if (playing) positionMs + ((now - at) * speed).toLong() else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }

    /** Which song: the same title and artist in another length is another recording. */
    val song: String get() = "$title|$artist|${durationMs / 1000}"
}

enum class MusicAction { Pause, Play, Next, Previous }

/**
 * The phone's music, whichever app plays it. "一起听" was asked for without the fuss other
 * companions' versions come with: they log into one music service (a cookie pasted in, a phone
 * number and password, a developer key). Every player already tells the system what it plays,
 * for the lock screen and the car; reading that needs one switch (notification access) and
 * works for all of them.
 */
interface MusicSource {
    fun allowed(): Boolean

    /** What is playing; else what was playing most recently, paused. Null with nothing, or no access. */
    fun now(): NowPlaying?

    /** Throws [ToolFailure] when there is nothing to control. */
    fun control(action: MusicAction)
}

/** What the TA reads about the music, and what music_control says back. */
object MusicText {
    fun clock(ms: Long): String {
        val s = ms / 1000
        return "${s / 60}:%02d".format(s % 60)
    }

    fun song(np: NowPlaying): String = "《${np.title}》" + (if (np.artist.isNotBlank()) "—${np.artist}" else "")

    /**
     * Beside the person's message while a song plays (Prompt.messages): which, how far in, and
     * the line being sung when the words are known. Only while it plays; a song paused an hour
     * ago isn't being listened to.
     */
    fun listening(np: NowPlaying, words: List<LyricLine>?, positionMs: Long): String = buildString {
        append("（你们正在一起听").append(song(np)).append("，放到 ").append(clock(positionMs))
        if (np.durationMs > 0) append(" / ").append(clock(np.durationMs))
        if (!words.isNullOrEmpty()) {
            val i = Lrc.at(words, positionMs)
            when {
                i < 0 -> append("，还在前奏")
                words[i].text.isBlank() -> append("，这会儿没在唱")
                else -> append("，这会儿唱到：「").append(words[i].text).append("」")
            }
        }
        append("）")
    }

    /** What the model asked for, in English or not. */
    fun action(text: String?): MusicAction? = when (text?.trim()?.lowercase()) {
        "pause", "stop", "暂停", "停" -> MusicAction.Pause
        "play", "resume", "继续", "接着放", "播放" -> MusicAction.Play
        "next", "skip", "下一首" -> MusicAction.Next
        "previous", "prev", "back", "上一首" -> MusicAction.Previous
        else -> null
    }

    /** What the model is told once it is done, [after] being what plays then. */
    fun done(action: MusicAction, before: NowPlaying, after: NowPlaying?): String = when (action) {
        MusicAction.Pause -> "暂停了${song(before)}。"
        MusicAction.Play -> "接着放了${song(after ?: before)}。"
        MusicAction.Next, MusicAction.Previous -> {
            val which = if (action == MusicAction.Next) "下一首" else "上一首"
            if (after == null || after.song == before.song) "让播放器切了$which，还没看到换成了哪首。"
            else "切到${which}了，现在放的是${song(after)}。"
        }
    }

    /** The chat's line. */
    fun note(action: MusicAction, before: NowPlaying, after: NowPlaying?): String = when (action) {
        MusicAction.Pause -> "暂停了音乐"
        MusicAction.Play -> "接着放了${song(after ?: before)}"
        MusicAction.Next, MusicAction.Previous -> {
            val which = if (action == MusicAction.Next) "下一首" else "上一首"
            if (after == null || after.song == before.song) "切了$which" else "切到$which：${song(after)}"
        }
    }
}

/**
 * [MusicSource] from the phone's media sessions. Reading them takes an enabled notification
 * listener ([NowPlayingListener]), which is all it is for: no notification is ever looked at.
 */
class PhoneMusic(private val context: Context) : MusicSource {
    private val listener = ComponentName(context, NowPlayingListener::class.java)
    private val manager get() = context.getSystemService(MediaSessionManager::class.java)

    override fun allowed(): Boolean = allowed(context)

    private fun controllers(): List<MediaController> {
        if (!allowed()) return emptyList()
        return runCatching { manager.getActiveSessions(listener) }.getOrDefault(emptyList())
            .filter { it.packageName != context.packageName }
    }

    override fun now(): NowPlaying? = pick(controllers().map { Seen(it) })?.let(::describe)

    override fun control(action: MusicAction) {
        val c = pick(controllers().map { Seen(it) })?.controller ?: throw ToolFailure("对方手机上现在没有在放的音乐。", "没在放歌")
        val t = c.transportControls
        when (action) {
            MusicAction.Pause -> t.pause()
            MusicAction.Play -> t.play()
            MusicAction.Next -> t.skipToNext()
            MusicAction.Previous -> t.skipToPrevious()
        }
    }

    /**
     * The player's own screen, the way tapping its notification opens it. The intent is the
     * player's; since Android 14 an app sending another's has to lend it its own leave to open
     * a screen (Cleos is in front then), or nothing opens.
     */
    fun open(): Boolean {
        val intent = pick(controllers().map { Seen(it) })?.controller?.sessionActivity ?: return false
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            } else {
                @Suppress("DEPRECATION")
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
        } else {
            null
        }
        return runCatching { intent.send(context, 0, null, null, null, null, options) }.isSuccess
    }

    /**
     * What is playing, for as long as it is collected: follows players starting and stopping, and
     * each one's songs and pauses. What each last said is kept from its callbacks, so a player that
     * reports its position every second doesn't send its cover over again each time.
     */
    fun watch(): Flow<NowPlaying?> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        var watched = emptyList<Seen>()
        fun update() {
            trySend(pick(watched)?.let(::describe))
        }
        fun follow(list: List<MediaController>) {
            watched.forEach { it.controller.unregisterCallback(it.callback) }
            watched = list.filter { it.packageName != context.packageName }.map { c ->
                Seen(c).also { seen ->
                    seen.callback = object : MediaController.Callback() {
                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            seen.metadata = metadata
                            update()
                        }

                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            seen.state = state
                            update()
                        }

                        override fun onSessionDestroyed() {
                            seen.metadata = null
                            seen.state = null
                            update()
                        }
                    }
                    c.registerCallback(seen.callback, handler)
                }
            }
            update()
        }
        val sessions = MediaSessionManager.OnActiveSessionsChangedListener { follow(it.orEmpty()) }
        val m = manager
        try {
            m.addOnActiveSessionsChangedListener(sessions, listener, handler)
            follow(m.getActiveSessions(listener))
        } catch (_: SecurityException) {
            // Notification access not given (or taken back): nothing to show.
            trySend(null)
        }
        awaitClose {
            runCatching { m.removeOnActiveSessionsChangedListener(sessions) }
            watched.forEach { it.controller.unregisterCallback(it.callback) }
        }
    }.distinctUntilChanged()

    /** A player and what it last said. */
    private class Seen(val controller: MediaController) {
        var metadata: MediaMetadata? = controller.metadata
        var state: PlaybackState? = controller.playbackState
        lateinit var callback: MediaController.Callback
    }

    /** The one playing; else the most recent with a song (sessions come most recent first). */
    private fun pick(list: List<Seen>): Seen? =
        list.firstOrNull { it.state?.state == PlaybackState.STATE_PLAYING && it.metadata != null } ?: list.firstOrNull { it.metadata != null }

    private fun describe(seen: Seen): NowPlaying? {
        val m = seen.metadata ?: return null
        val title = (m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m.description.title?.toString()).orEmpty().trim()
        if (title.isEmpty()) return null
        val artist = (m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m.description.subtitle?.toString()).orEmpty().trim()
        val state = seen.state
        return NowPlaying(
            player = seen.controller.packageName,
            title = title,
            artist = artist,
            album = m.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty().trim(),
            durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            positionMs = state?.position?.coerceAtLeast(0) ?: 0,
            at = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
        )
    }

    companion object {
        fun allowed(context: Context): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /**
         * Straight to Cleos's own switch where the phone has that page (Android 11 on), else the
         * list of apps with notification access.
         */
        fun accessIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, NowPlayingListener::class.java).flattenToString())
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
    }
}

/** The line a reply gets about the music (Prompt.messages), when a song is playing. */
class Listening(private val music: MusicSource, private val lyrics: Lyrics) {
    suspend fun line(): String? {
        if (!music.allowed()) return null
        val np = music.now()?.takeIf { it.playing } ?: return null
        // Usually known already (the chat's bar asked when the song began); not worth holding a reply up long for.
        val words = withTimeoutOrNull(WORDS_WAIT_MS) { lyrics.of(np.title, np.artist, np.durationMs) }
        return MusicText.listening(np, words, np.position(SystemClock.elapsedRealtime()))
    }

    private companion object {
        const val WORDS_WAIT_MS = 2_500L
    }
}
