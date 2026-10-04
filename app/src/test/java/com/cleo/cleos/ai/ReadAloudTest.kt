package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudTest {
    @Test
    fun aShortMessageIsOnePiece() {
        val parts = Speech.readParts("你好呀。今天怎么样？")
        assertEquals(listOf("你好呀。今天怎么样？"), parts.pieces)
        assertFalse(parts.cut)
        assertTrue(Speech.readParts("  \n ").pieces.isEmpty())
    }

    @Test
    fun marksOnlyForTheEyeAreNotSaid() {
        assertEquals(listOf("摸摸头\n你好"), Speech.readParts("*摸摸头*\n> 你好").pieces)
        assertEquals(listOf("标题\n正文"), Speech.readParts("## 标题\n\n正文").pieces)
    }

    @Test
    fun aLongMessageIsCutAtTheEndsOfSentencesIntoWhatAVoiceCanSay() {
        val sentence = "甲".repeat(199) + "。"
        val three = Speech.readParts(sentence.repeat(3))
        assertEquals(listOf(200, 200, 200), three.pieces.map { it.length })
        assertFalse(three.cut)
        // More than a few pieces: the first ones are read, and it says the rest was left out.
        val six = Speech.readParts(sentence.repeat(6))
        assertEquals(Speech.READ_PARTS_MAX, six.pieces.size)
        assertTrue(six.cut)
        // No ends to cut at: cut where it must be.
        assertEquals(listOf(300, 300, 100), Speech.readParts("乙".repeat(700)).pieces.map { it.length })
        assertTrue(Speech.readParts("乙".repeat(5000)).pieces.all { it.length <= Speech.MAX_CHARS })
    }

    @Test
    fun aPieceIsKeptUnderWhatChangesHowItSounds() {
        val s = AppSettings(speechEngine = "api", speechBaseUrl = "https://relay.example.com/v1", speechModel = "tts-1", speechVoice = "alloy")
        val key = Speech.readKey(s, VoiceService.Other, "你好")
        assertEquals(24, key.length)
        assertEquals(key, Speech.readKey(s, VoiceService.Other, "你好"))
        assertNotEquals(key, Speech.readKey(s, VoiceService.Other, "你好呀"))
        assertNotEquals(key, Speech.readKey(s.copy(speechVoice = "nova"), VoiceService.Other, "你好"))
        assertNotEquals(key, Speech.readKey(s.copy(speechModel = "tts-1-hd"), VoiceService.Other, "你好"))
    }
}
