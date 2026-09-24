package com.cleo.cleos.ai

import android.util.Log
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.DiaryBlocks
import com.cleo.cleos.data.SecretStore
import com.cleo.cleos.data.SettingsRepository
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.data.db.MemoryEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.random.Random

/**
 * When a TA's letter arrives. Letters are slow unless the person sets them otherwise: an
 * answer a moment after the person sends theirs is a chat, not a letter. The wait is random
 * within the range they set, so it is not read as a timer ("half an hour after I send,
 * every time"), and it is seeded by the letter, so a reply written late (the phone was
 * offline) still arrives when it was due.
 */
object LetterTiming {
    private val NIGHT_START: LocalTime = LocalTime.of(23, 0)
    private val MORNING: LocalTime = LocalTime.of(8, 0)

    /** The choices for how long a reply takes, in minutes: from as soon as it is written to a day. */
    val REPLY_STEPS = listOf(0, 5, 15, 30, 60, 120, 180, 360, 720, 1440)

    /**
     * A reply: [minMinutes] to [maxMinutes] after the person's letter was sent (1 to 6 hours
     * unless they changed it). With [quietNight], not in the night.
     */
    fun replyAt(
        sentAt: Long,
        seed: Long,
        zone: ZoneId,
        minMinutes: Int = 60,
        maxMinutes: Int = 360,
        quietNight: Boolean = true,
    ): Long {
        val r = Random(seed)
        val lo = minMinutes.coerceAtLeast(0)
        val hi = maxMinutes.coerceAtLeast(lo)
        val at = sentAt + r.nextLong(Duration.ofMinutes(lo.toLong()).toMillis(), Duration.ofMinutes(hi.toLong()).toMillis() + 1)
        return if (quietNight) awake(at, r, zone) else at
    }

    /** The step nearest a number of minutes, for a slider over [REPLY_STEPS]. */
    fun stepOf(minutes: Int): Int = REPLY_STEPS.indices.minBy { abs(REPLY_STEPS[it] - minutes) }

    /** The wait as the person reads it: "回信过 1～6 小时到". */
    fun describeReply(minMinutes: Int, maxMinutes: Int): String {
        fun say(m: Int) = when {
            m == 1440 -> "一天"
            m > 1440 && m % 1440 == 0 -> "${m / 1440} 天"
            m < 60 -> "$m 分钟"
            else -> "${m / 60} 小时"
        }
        // A space between Chinese and a number, none before "一天".
        fun after(s: String) = if (s.first().isDigit()) " $s" else s
        val sameUnit = maxMinutes < 1440 && (minMinutes < 60) == (maxMinutes < 60)
        return when {
            maxMinutes <= 0 -> "回信一写好就到"
            minMinutes <= 0 -> "回信${after(say(maxMinutes))}以内到"
            minMinutes >= maxMinutes -> "回信过${after(say(minMinutes))}到"
            sameUnit -> "回信过 ${say(minMinutes).substringBefore(' ')}～${say(maxMinutes)}到"
            else -> "回信过${after(say(minMinutes))}～${say(maxMinutes)}到"
        }
    }

    /**
     * A letter the TA wrote of their own accord: 25 to 100 minutes after it was written,
     * after the person has put the phone down, not as the answer to something they did.
     */
    fun ownAt(writtenAt: Long, seed: Long, zone: ZoneId, quietNight: Boolean = true): Long {
        val r = Random(seed)
        val at = writtenAt + r.nextLong(Duration.ofMinutes(25).toMillis(), Duration.ofMinutes(100).toMillis() + 1)
        return if (quietNight) awake(at, r, zone) else at
    }

    /** Nothing arrives in the night: what would, comes the next morning, some time after 8. */
    internal fun awake(at: Long, r: Random, zone: ZoneId): Long {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        val morning = when {
            t.toLocalTime() >= NIGHT_START -> t.toLocalDate().plusDays(1)
            t.toLocalTime() < MORNING -> t.toLocalDate()
            else -> return at
        }
        return ZonedDateTime.of(morning, MORNING, zone).toInstant().toEpochMilli() + r.nextLong(0, Duration.ofHours(1).toMillis())
    }
}

/**
 * When a TA writes a letter of their own. At least the gap the person set ([COOLDOWN] unless
 * changed) after their last try, whether it became a letter or not, so a TA with nothing to say doesn't try every time the app
 * opens. And only with something to write about since their last letter: talk, or a diary
 * entry. The model still decides; it can answer SKIP.
 *
 * Material counts from the last letter actually written, not the last try: counting from
 * the try would throw away what piled up every time the model skipped, and someone who
 * doesn't talk much would never collect enough.
 */
object LetterRules {
    val COOLDOWN: Duration = Duration.ofDays(5)

    /** Things the person said since the last letter that make a letter worth trying. */
    const val ENOUGH_SAID = 20

    fun ready(
        now: Long,
        ta: CompanionEntity,
        letters: List<LetterEntity>,
        saidSince: Int,
        diarySince: Int,
        cooldown: Duration = COOLDOWN,
    ): Boolean {
        val own = letters.filter { it.author == LetterEntity.AUTHOR_AI }
        // One waiting to be read (or still on its way) is enough; a second would pile up.
        if (own.any { it.readAt == null }) return false
        val last = maxOf(ta.lastLetterTry ?: 0L, own.maxOfOrNull { it.createdAt } ?: 0L, ta.createdAt)
        if (now - last < cooldown.toMillis()) return false
        return saidSince >= ENOUGH_SAID || diarySince > 0
    }

    /** Where material counts from: the TA's last letter, or when they came to be. */
    fun materialSince(ta: CompanionEntity, letters: List<LetterEntity>): Long =
        letters.filter { it.author == LetterEntity.AUTHOR_AI }.maxOfOrNull { it.createdAt } ?: ta.createdAt
}

/**
 * What a TA is told when writing a letter. The rules are the ones the person's earlier app
 * settled on: one or two real things instead of a summary, no padding, no letter
 * templates, no lists; the relationship is not named. The TA's persona comes along, since
 * the letter is theirs, but its chat habits (short messages, one thing per message) don't
 * apply to a letter, and it is told so.
 */
object LetterPrompt {
    const val SKIP = "SKIP"

    fun system(
        settings: AppSettings,
        ta: CompanionEntity,
        own: Boolean,
        memories: List<MemoryEntity> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildList {
        if (ta.name.isNotBlank()) add("你叫${ta.name.trim()}。")
        if (settings.userName.isNotBlank()) add("对方叫${settings.userName.trim()}。")
        if (ta.persona.isNotBlank()) add(ta.persona.trim())
        // No tools in a letter, so the details come along with each topic.
        MemoryDigest.forLetter(memories, zone)?.let(::add)
        add(
            "你正在给对方写一封信。信不是聊天：它慢、有距离，可以说些平时聊天里不会说的话。" +
                "不要预设你们是什么关系，也别给这段关系起名字，那由你们相处的方式决定。",
        )
        add(
            buildString {
                append("写法：\n")
                append("- 挑一两件具体的事写，不要流水账式地总结这段时间。\n")
                append("- 长度你自己定。有话就多写，没什么可写就三五行，不要凑。\n")
                append("- 怎么开头、怎么称呼对方都随你，但用你平时叫对方的方式，不要套「亲爱的某某」这类信件模板话。\n")
                append("- 不要写标题、日期和落款，界面会显示。\n")
                append("- 不要用列表和分点，也不用 Markdown。信就该像信。\n")
                append("- 上面如果有聊天时的说话习惯（比如说话简短、一条只说一件事），写信时不用照着，按这里的写法。")
                // Only a letter of its own may be skipped: someone who wrote gets an answer.
                if (own) append("\n- 如果这段时间实在没什么值得写的，就只回复 $SKIP 四个字母，不要勉强写一封。")
            },
        )
    }.joinToString("\n\n")

    /**
     * The material, in the user message: the earlier letters, the one being answered, what
     * was said lately, and diary entries since the last letter (the person's only when the
     * TA may read their diary; secrets never).
     */
    fun material(
        now: ZonedDateTime,
        letters: List<LetterEntity>,
        replyTo: LetterEntity?,
        said: List<MessageEntity>,
        diary: List<DiaryEntryEntity>,
        zone: ZoneId,
    ): String = buildString {
        append(Prompt.timeLine(now)).append("。\n\n")
        val earlier = letters.filter { !it.draft && it.id != replyTo?.id && (it.author == LetterEntity.AUTHOR_ME || (it.deliverAt ?: 0) <= now.toInstant().toEpochMilli()) }
            .sortedBy { it.createdAt }
            .takeLast(EARLIER_LETTERS)
        if (earlier.isNotEmpty()) {
            append("你们之前的信（从早到晚）：\n")
            for (l in earlier) {
                val who = if (l.author == LetterEntity.AUTHOR_AI) "你写的" else "对方写的"
                append("【").append(who).append(" · ").append(day(l.createdAt, zone)).append("】\n")
                append(l.content.trim().take(LETTER_MAX)).append("\n\n")
            }
        }
        if (replyTo != null) {
            append("对方刚寄来这封信（").append(day(replyTo.deliverAt ?: replyTo.createdAt, zone)).append("）：\n")
            append(replyTo.content.trim()).append("\n\n")
        }
        if (said.isNotEmpty()) {
            append("最近你们聊的（从早到晚）：\n")
            for (m in said.sortedBy { it.createdAt }) {
                append(if (m.role == "user") "对方：" else "你：").append(m.content.trim().replace('\n', ' ').take(SAID_MAX)).append('\n')
            }
            append('\n')
        }
        val own = diary.filter { it.author == DiaryEntryEntity.AUTHOR_AI }
        val theirs = diary.filter { it.author == DiaryEntryEntity.AUTHOR_ME && !it.secret }
        if (own.isNotEmpty()) {
            append("你上一封信之后写的日记：\n")
            own.forEach { append(entry(it)) }
            append('\n')
        }
        if (theirs.isNotEmpty()) {
            append("对方上一封信之后写的日记：\n")
            theirs.forEach { append(entry(it)) }
            append('\n')
        }
        append(if (replyTo != null) "给对方写一封回信。" else "给对方写一封信。")
    }

    /** A short answer with the word in it: the model doesn't always send only the four letters ("SKIP（没什么可写）"). */
    fun isSkip(text: String): Boolean = text.trim().length < 30 && SKIP in text.uppercase()

    private fun entry(e: DiaryEntryEntity): String {
        val text = DiaryBlocks.plainText(DiaryBlocks.decode(e.blocks)).trim().replace('\n', ' ').take(DIARY_MAX)
        val title = e.title.trim().takeIf { it.isNotEmpty() }?.let { "「$it」" }.orEmpty()
        return "- ${LocalDate.ofEpochDay(e.day).let { "${it.monthValue}月${it.dayOfMonth}日" }}$title $text\n"
    }

    private fun day(at: Long, zone: ZoneId): String = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().let { "${it.monthValue}月${it.dayOfMonth}日" }

    private const val EARLIER_LETTERS = 4
    private const val LETTER_MAX = 1200
    private const val SAID_MAX = 150
    private const val DIARY_MAX = 300
    const val SAID_LATELY = 30
    const val DIARY_LATELY = 5
}

/**
 * Letters: drafts, sending, and the TA's side. A TA's letter is written ahead of time and
 * stored with the time it arrives; the mailbox shows it from then on. Nothing waits for a
 * background job: phones kill those. Whatever is due gets written the next time the app
 * comes to the front ([tick]).
 */
class Letters(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val client: ChatClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val writing = Mutex()

    /** A draft saved: a new one gets an id, an existing one is updated. Sent letters stay as they are. */
    suspend fun saveDraft(companionId: Long, id: Long, content: String): Long {
        val now = clock()
        if (id == 0L) {
            return db.letters().insert(LetterEntity(companionId = companionId, author = LetterEntity.AUTHOR_ME, content = content, createdAt = now))
        }
        val draft = db.letters().get(id)?.takeIf { it.draft } ?: return id
        db.letters().update(draft.copy(content = content, createdAt = now))
        return id
    }

    /**
     * Sends a draft; the reply is written right after, to arrive hours later. Waits for a
     * tick already running rather than skipping: that one has already listed what to answer.
     */
    suspend fun send(id: Long) {
        val draft = db.letters().get(id)?.takeIf { it.draft && it.content.isNotBlank() } ?: return
        val now = clock()
        db.letters().update(draft.copy(content = draft.content.trim(), createdAt = now, deliverAt = now))
        scope.launch { writing.withLock { due() } }
    }

    suspend fun markRead(id: Long) {
        val l = db.letters().get(id) ?: return
        if (l.author == LetterEntity.AUTHOR_AI && l.readAt == null) db.letters().update(l.copy(readAt = clock()))
    }

    suspend fun delete(id: Long) = db.letters().delete(id)

    /**
     * Writes what is due: replies to letters still unanswered, then, where the time has
     * come, a letter of a TA's own. One at a time; a tick while one runs does nothing.
     */
    fun tick() {
        if (!writing.tryLock()) return
        scope.launch {
            try {
                due()
            } finally {
                writing.unlock()
            }
        }
    }

    private suspend fun due() {
        try {
            answer()
            writeOwn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, a model that failed: what is due stays due, for the next tick.
            Log.w(TAG, "letters: ${e.message}")
        }
    }

    private suspend fun answer() {
        for (sent in db.letters().unanswered()) {
            val ta = db.companions().get(sent.companionId) ?: continue
            val text = write(ta, sent) ?: continue
            // The pace as it is now: a reply already written keeps the time it was given.
            val s = settings.current()
            val at = LetterTiming.replyAt(
                sent.deliverAt ?: sent.createdAt,
                seed = sent.id,
                zone = zone(),
                minMinutes = s.letterReplyMin,
                maxMinutes = s.letterReplyMax,
                quietNight = s.letterQuietNight,
            )
            db.letters().insert(
                LetterEntity(companionId = ta.id, author = LetterEntity.AUTHOR_AI, content = text, createdAt = clock(), deliverAt = at, replyTo = sent.id),
            )
        }
    }

    private suspend fun writeOwn() {
        val s = settings.current()
        if (ToolGroup.Letters !in s.tools) return
        val cooldown = Duration.ofDays(s.letterEveryDays.coerceIn(1, 30).toLong())
        for (ta in db.companions().all()) {
            val letters = db.letters().allFor(ta.id)
            val since = LetterRules.materialSince(ta, letters)
            val own = if (ToolGroup.AiDiary in s.tools) ta.id else -1L
            val diary = db.diary().since(since, mine = ToolGroup.Diary in s.tools, own = own, limit = LetterPrompt.DIARY_LATELY)
            val now = clock()
            if (!LetterRules.ready(now, ta, letters, db.messages().saidSince(ta.id, since), diary.size, cooldown)) continue
            val text = write(ta, null) ?: continue
            db.companions().get(ta.id)?.let { db.companions().update(it.copy(lastLetterTry = now)) }
            if (LetterPrompt.isSkip(text)) continue
            db.letters().insert(
                LetterEntity(companionId = ta.id, author = LetterEntity.AUTHOR_AI, content = text, createdAt = now, deliverAt = LetterTiming.ownAt(now, seed = now, zone = zone(), quietNight = s.letterQuietNight)),
            )
        }
    }

    /** The letter's text, or null when it can't be written now (no key yet, nothing came back). */
    private suspend fun write(ta: CompanionEntity, replyTo: LetterEntity?): String? {
        val key = secrets.key(ta.apiBaseUrl)?.takeIf { it.isNotBlank() } ?: return null
        val s = settings.current()
        val z = zone()
        val letters = db.letters().allFor(ta.id)
        val since = LetterRules.materialSince(ta, letters)
        val own = if (ToolGroup.AiDiary in s.tools) ta.id else -1L
        val diary = db.diary().since(since, mine = ToolGroup.Diary in s.tools, own = own, limit = LetterPrompt.DIARY_LATELY)
        val messages = listOf(
            ApiMessage(
                "system",
                LetterPrompt.system(
                    s,
                    ta,
                    own = replyTo == null,
                    memories = if (ToolGroup.Memory in s.tools) db.memories().allFor(ta.id) else emptyList(),
                    zone = z,
                ),
            ),
            ApiMessage(
                "user",
                LetterPrompt.material(
                    now = Instant.ofEpochMilli(clock()).atZone(z),
                    letters = letters,
                    replyTo = replyTo,
                    said = db.messages().saidLately(ta.id, LetterPrompt.SAID_LATELY),
                    diary = diary,
                    zone = z,
                ),
            ),
        )
        val text = StringBuilder()
        client.stream(ApiEndpoint(ta.apiBaseUrl, key, ta.apiModel), messages).collect { if (it is ChatEvent.Delta) text.append(it.text) }
        return text.toString().trim().ifEmpty { null }
    }

    private companion object {
        const val TAG = "Letters"
    }
}
