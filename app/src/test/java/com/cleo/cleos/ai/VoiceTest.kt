package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.MessageAudio
import com.cleo.cleos.data.MessageAudios
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId

class VoiceTest {
    @Test
    fun theEndpointIsFoundFromTheAddress() {
        assertEquals("https://api.example.com/v1/audio/transcriptions", Voice.url("https://api.example.com/v1"))
        assertEquals("https://api.example.com/v1/audio/transcriptions", Voice.url(" https://api.example.com/v1/ "))
        assertEquals("https://api.example.com/v1/audio/transcriptions", Voice.url("https://api.example.com/v1/chat/completions"))
        assertEquals("https://api.example.com/v1/audio/transcriptions", Voice.url("https://api.example.com/v1/audio/transcriptions"))
    }

    @Test
    fun theTextIsReadFromTheAnswer() {
        assertEquals("今天好累", Voice.text("""{"text":"今天好累","usage":{"seconds":3}}"""))
        assertEquals("", Voice.text("""{"text":""}"""))
        assertNull(Voice.text("""{"error":{"message":"x"}}"""))
        assertNull(Voice.text("<html>busy</html>"))
    }

    @Test
    fun aWavHeaderDescribesTheRecording() {
        val h = Voice.wavHeader(32_000)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals(36 + 32_000, b.getInt(4))
        assertEquals("WAVEfmt ", String(h, 8, 8))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(h, 36, 4))
        assertEquals(32_000, b.getInt(40))
        assertEquals(44 + 32_000, Voice.silence().size)
    }

    @Test
    fun lengthsReadLikeAChatApp() {
        assertEquals("1″", Voice.duration(300))
        assertEquals("7″", Voice.duration(7_200))
        assertEquals("1′05″", Voice.duration(65_000))
    }

    @Test
    fun aVoiceMessageGoesAsWhatItSaid() {
        val ta = CompanionEntity(id = 1, name = "星", apiBaseUrl = "", apiModel = "", createdAt = 0)
        val now = LocalDateTime.of(2026, 9, 25, 15, 0).atZone(ZoneId.of("Asia/Shanghai"))
        val audio = MessageAudios.encode(MessageAudio("voice_1.wav", 3000))
        // One not turned into text yet says nothing; one that has been goes as a voice message.
        val pending = MessageEntity(id = 1, conversationId = 1, role = "user", content = "", createdAt = 0, audio = audio)
        val said = MessageEntity(id = 2, conversationId = 1, role = "user", content = "今天好累", createdAt = 1, audio = audio)
        val out = Prompt.messages(AppSettings(), ta, listOf(pending, said), now)
        assertEquals(listOf("system", "user"), out.map { it.role })
        assertTrue(out[1].content.endsWith("\n（语音）今天好累"))
        assertEquals(MessageAudio("voice_1.wav", 3000), MessageAudios.decode(audio))
        assertNull(MessageAudios.decode("not json"))
    }
}
