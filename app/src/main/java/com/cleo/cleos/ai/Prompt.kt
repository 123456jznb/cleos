package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.MessageEntity
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the model is told, and in which order.
 *
 * The system prompt carries only what the user wrote: the two names and the persona.
 * Names are given bare, with no adjectives attached. A label like "gentle" or "a friend"
 * gets performed rather than lived in, and deciding what the relationship is, is not the
 * app's job.
 *
 * The one line the app adds itself is about format, because this is a chat screen that
 * shows plain text: markdown headings and bullet lists would arrive as raw symbols.
 *
 * The current time is not in the system prompt. Providers cache the longest unchanged
 * prefix of a request; a clock ticking in the first message would change the prefix on
 * every turn and throw the whole history out of the cache. So the time rides on the last
 * user message, after everything that stays the same.
 */
object Prompt {
    private const val FORMAT_RULE = "这是手机上的聊天。像平常发消息那样回复，不用 Markdown 标题、列表和加粗。"

    fun system(settings: AppSettings): String = buildList {
        if (settings.aiName.isNotBlank()) add("你叫${settings.aiName.trim()}。")
        if (settings.userName.isNotBlank()) add("和你说话的人叫${settings.userName.trim()}。")
        if (settings.persona.isNotBlank()) add(settings.persona.trim())
        add(FORMAT_RULE)
    }.joinToString("\n\n")

    /**
     * [history] oldest first, already trimmed to the window. Failed assistant turns are
     * left out: a half reply ending mid-sentence invites the model to continue it.
     */
    fun messages(settings: AppSettings, history: List<MessageEntity>, now: ZonedDateTime): List<ApiMessage> {
        val usable = history.filter { !(it.role == "assistant" && it.error != null) && it.content.isNotBlank() }
        // Two user messages in a row happen whenever a reply failed in between. Some
        // endpoints reject consecutive same-role turns, so they are joined into one.
        val merged = mutableListOf<ApiMessage>()
        for (m in usable) {
            val last = merged.lastOrNull()
            if (last != null && last.role == m.role) {
                merged[merged.lastIndex] = last.copy(content = last.content + "\n\n" + m.content)
            } else {
                merged += ApiMessage(m.role, m.content)
            }
        }
        val lastUser = merged.indexOfLast { it.role == "user" }
        if (lastUser >= 0) {
            merged[lastUser] = merged[lastUser].let { it.copy(content = "（${timeLine(now)}）\n${it.content}") }
        }
        return listOf(ApiMessage("system", system(settings))) + merged
    }

    private val timeFormat = DateTimeFormatter.ofPattern("M月d日 EEEE HH:mm", Locale.CHINA)

    fun timeLine(now: ZonedDateTime): String = "现在是" + now.format(timeFormat)
}
