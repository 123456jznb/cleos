package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.MessageAudio
import com.cleo.cleos.data.MessageAudios
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64

class SpeechTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun audioIsFoundWhereverATtsToolPutsIt() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val part = Speech.audioIn(obj("""{"content":[{"type":"text","text":"好了"},{"type":"audio","data":"$b64","mimeType":"audio/wav"}]}"""))
        assertTrue(part is Speech.Found.Bytes)
        assertArrayEquals(bytes, (part as Speech.Found.Bytes).data)
        assertEquals("audio/wav", part.mime)
        // A MiniMax-style answer: the file is a link in the text.
        assertEquals(
            Speech.Found.Link("https://cdn.example.com/a/b.mp3?sig=1"),
            Speech.audioIn(obj("""{"content":[{"type":"text","text":"Success. Audio URL: https://cdn.example.com/a/b.mp3?sig=1"}]}""")),
        )
        assertEquals(
            Speech.Found.Link("https://x.example/voice"),
            Speech.audioIn(obj("""{"content":[{"type":"resource_link","uri":"https://x.example/voice","name":"v"}]}""")),
        )
        // The only link there is, even without an audio file's ending; none, and nothing is found.
        assertEquals(Speech.Found.Link("https://x.example/get?id=7"), Speech.audioIn(obj("""{"content":[{"type":"text","text":"链接：https://x.example/get?id=7。"}]}""")))
        assertNull(Speech.audioIn(obj("""{"content":[{"type":"text","text":"余额不足"}]}""")))
        assertNull(Speech.audioIn(obj("""{"content":[{"type":"text","text":"see https://a.example/x and https://b.example/y"}]}""")))
    }

    @Test
    fun theRequestsAreTheServicesShapes() {
        val api = obj(Speech.apiBody("FunAudioLLM/CosyVoice2-0.5B", "FunAudioLLM/CosyVoice2-0.5B:anna", "晚安"))
        assertEquals("晚安", api["input"]!!.jsonPrimitive.content)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:anna", api["voice"]!!.jsonPrimitive.content)
        assertEquals("mp3", api["response_format"]!!.jsonPrimitive.content)
        assertFalse("voice" in obj(Speech.apiBody("m", "", "x")))
        assertEquals(Speech.ELEVENLABS_MODEL, obj(Speech.elevenLabsBody("", "晚安"))["model_id"]!!.jsonPrimitive.content)
        assertEquals("https://api.example.com/v1/audio/speech", Speech.url("https://api.example.com/v1/"))
        assertEquals("https://api.example.com/v1/audio/speech", Speech.url("https://api.example.com/v1/chat/completions"))
        // An MCP tool gets the person's fixed arguments and the words.
        val args = Speech.mcpArguments("""{"voice_id":"female-shaonv","speed":1}""", "text", "晚安")
        assertEquals("female-shaonv", args["voice_id"]!!.jsonPrimitive.content)
        assertEquals("晚安", args["text"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(mapOf("input" to kotlinx.serialization.json.JsonPrimitive("晚安"))), Speech.mcpArguments(" ", "input", "晚安"))
        assertThrows(SpeechException::class.java) { Speech.mcpArguments("not json", "text", "x") }
        assertEquals("text", Speech.textParam(obj("""{"type":"object","properties":{"voice_id":{"type":"string"},"text":{"type":"string"}}}""")))
        assertEquals("words", Speech.textParam(obj("""{"type":"object","properties":{"speed":{"type":"number"},"words":{"type":"string"}}}""")))
        assertEquals("wav", Speech.extension("audio/wav; charset=binary"))
        assertEquals("mp3", Speech.extension(null))
    }

    @Test
    fun theVoiceIsOfferedOnceThereIsOne() {
        assertFalse(Speech.ready(AppSettings()))
        assertTrue(Speech.ready(AppSettings(speechBaseUrl = "https://api.example.com/v1", speechModel = "m")))
        assertTrue(Speech.ready(AppSettings(speechEngine = SpeechEngine.ElevenLabs.key, elevenVoice = "abc")))
        assertFalse(Speech.ready(AppSettings(speechEngine = SpeechEngine.Mcp.key, speechMcpServer = "s")))
        assertTrue(Speech.ready(AppSettings(speechEngine = SpeechEngine.Mcp.key, speechMcpServer = "s", speechMcpTool = "t")))
    }

    @Test
    fun aVoiceMessageGoesBackAsTheSendVoiceItWas() {
        val ta = CompanionEntity(id = 1, name = "星", apiBaseUrl = "", apiModel = "", createdAt = 0)
        val now = LocalDateTime.of(2026, 9, 28, 22, 0).atZone(ZoneId.of("Asia/Shanghai"))
        val voice = MessageAudios.encode(MessageAudio("voice_1.mp3", 2000))
        val history = listOf(
            MessageEntity(id = 1, conversationId = 1, role = "user", content = "我去睡啦", createdAt = 0),
            MessageEntity(id = 2, conversationId = 1, role = "assistant", content = "晚安，早点睡", createdAt = 1, audio = voice),
            MessageEntity(id = 3, conversationId = 1, role = "user", content = "嗯", createdAt = 2),
        )
        val on = Prompt.messages(AppSettings(), ta, history, now, setOf(ToolGroup.Speak))
        val call = on[2]
        assertEquals(ToolSpecs.sendVoice.name, call.toolCalls.single().name)
        assertEquals("晚安，早点睡", obj(call.toolCalls.single().arguments)["text"]!!.jsonPrimitive.content)
        assertEquals(ToolSpecs.SENT, on[3].content)
        // Not offered: a line that says it was a voice message, not typed words to copy.
        val off = Prompt.messages(AppSettings(), ta, history, now, setOf(ToolGroup.Messages))
        assertEquals("（语音）晚安，早点睡", off[2].content)
        assertTrue(off[2].toolCalls.isEmpty())
    }
}
