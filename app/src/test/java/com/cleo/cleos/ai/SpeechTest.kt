package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.MessageAudio
import com.cleo.cleos.data.MessageAudios
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.data.decodeVoices
import com.cleo.cleos.data.encodeVoices
import kotlinx.serialization.json.Json
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

class SpeechTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun settingsFromBeforeFindTheirService() {
        // "api" was every OpenAI-shaped service; the address says which it was, and its voice comes along.
        assertEquals(
            "siliconflow" to mapOf("siliconflow" to "FunAudioLLM/CosyVoice2-0.5B:claire"),
            Speech.migrate("api", "https://api.siliconflow.cn/v1", "FunAudioLLM/CosyVoice2-0.5B:claire", emptyMap()),
        )
        assertEquals("mossland", Speech.migrate("api", "https://api.mosi.cn/v1/", "abc", emptyMap()).first)
        assertEquals("openai", Speech.migrate("api", "https://api.openai.com/v1", "", emptyMap()).first)
        assertEquals("a relay stays as it was", "api" to emptyMap<String, String>(), Speech.migrate("api", "https://relay.example.com/v1", "x", emptyMap()))
        assertEquals("never set up", "", Speech.migrate("api", "", "", emptyMap()).first)
        assertEquals("the MCP tool is gone", "", Speech.migrate("mcp", "", "", emptyMap()).first)
        assertEquals("elevenlabs", Speech.migrate("elevenlabs", "", "", emptyMap()).first)
        // Already moved: a voice picked since isn't overwritten by the old field.
        assertEquals(mapOf("siliconflow" to "new"), Speech.migrate("api", "https://api.siliconflow.cn/v1", "old", mapOf("siliconflow" to "new")).second)
        assertEquals(mapOf("minimax" to "female-yujie"), decodeVoices(encodeVoices(mapOf("minimax" to "female-yujie"))))
        assertEquals(emptyMap<String, String>(), decodeVoices("not json"))
    }

    @Test
    fun eachServiceIsReadyWithWhatItNeeds() {
        assertFalse(Speech.ready(AppSettings()))
        assertTrue(Speech.ready(AppSettings(speechEngine = "siliconflow")))
        assertTrue(Speech.ready(AppSettings(speechEngine = "minimax")))
        assertFalse(Speech.ready(AppSettings(speechEngine = "api", speechBaseUrl = "https://relay.example.com/v1")))
        assertTrue(Speech.ready(AppSettings(speechEngine = "api", speechBaseUrl = "https://relay.example.com/v1", speechModel = "tts-1")))
        assertFalse(Speech.ready(AppSettings(speechEngine = "elevenlabs")))
        assertTrue(Speech.ready(AppSettings(speechEngine = "elevenlabs", elevenVoice = "abc")))
        // A voice not picked is the service's first; one picked is that one.
        assertEquals("female-shaonv", Speech.voice(AppSettings(), VoiceService.MiniMax))
        assertEquals("female-yujie", Speech.voice(AppSettings(speechVoices = mapOf("minimax" to "female-yujie")), VoiceService.MiniMax))
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:anna", Speech.voice(AppSettings(), VoiceService.SiliconFlow))
        // MiniMax's keys go by site: the mainland's and the international one's are different keys.
        assertEquals(Speech.MINIMAX_CN, Speech.keyAddress(AppSettings(), VoiceService.MiniMax))
        assertEquals(Speech.MINIMAX_GLOBAL, Speech.keyAddress(AppSettings(minimaxGlobal = true), VoiceService.MiniMax))
        assertEquals("https://relay.example.com/v1", Speech.keyAddress(AppSettings(speechBaseUrl = " https://relay.example.com/v1 "), VoiceService.Other))
    }

    @Test
    fun theRequestsAreTheServicesShapes() {
        val api = obj(Speech.openAiBody("FunAudioLLM/CosyVoice2-0.5B", "FunAudioLLM/CosyVoice2-0.5B:anna", "晚安"))
        assertEquals("晚安", api["input"]!!.jsonPrimitive.content)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:anna", api["voice"]!!.jsonPrimitive.content)
        assertEquals("mp3", api["response_format"]!!.jsonPrimitive.content)
        // Mossland wants the voice as voice_id, and nothing under voice.
        val moss = obj(Speech.openAiBody(Speech.MOSSLAND_MODEL, "806c9695-6160-404e-8722-4f788d935af3", "晚安", voiceField = "voice_id"))
        assertEquals("806c9695-6160-404e-8722-4f788d935af3", moss["voice_id"]!!.jsonPrimitive.content)
        assertFalse("voice" in moss)
        val mm = obj(Speech.miniMaxBody("female-shaonv", "晚安"))
        assertEquals(Speech.MINIMAX_MODEL, mm["model"]!!.jsonPrimitive.content)
        assertEquals("晚安", mm["text"]!!.jsonPrimitive.content)
        assertEquals("female-shaonv", mm["voice_setting"]!!.jsonObject["voice_id"]!!.jsonPrimitive.content)
        assertEquals("mp3", mm["audio_setting"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertEquals("hex", mm["output_format"]!!.jsonPrimitive.content)
        assertEquals(Speech.ELEVENLABS_MODEL, obj(Speech.elevenLabsBody("", "晚安"))["model_id"]!!.jsonPrimitive.content)
        // Zhipu's GLM-TTS has no mp3: wav there, mp3 everywhere else.
        assertEquals("wav", Speech.speechFormat("https://open.bigmodel.cn/api/paas/v4", "glm-tts"))
        assertEquals("wav", Speech.speechFormat("https://relay.example.com/v1", "GLM-TTS"))
        assertEquals("mp3", Speech.speechFormat("https://relay.example.com/v1", "tts-1"))
        assertEquals("wav", obj(Speech.openAiBody("glm-tts", "tongtong", "晚安", format = "wav"))["response_format"]!!.jsonPrimitive.content)
        assertEquals("https://api.example.com/v1/audio/speech", Speech.speechUrl("https://api.example.com/v1/"))
        assertEquals("https://api.example.com/v1/audio/speech", Speech.speechUrl("https://api.example.com/v1/chat/completions"))
        assertEquals("wav", Speech.extension("audio/wav; charset=binary"))
        assertEquals("mp3", Speech.extension(null))
    }

    @Test
    fun miniMaxAnswersInHexAndRefusesInBaseResp() {
        assertArrayEquals(
            byteArrayOf(0x49, 0x44, 0x33, -1),
            Speech.miniMaxAudio("""{"data":{"audio":"494433ff","status":2},"base_resp":{"status_code":0,"status_msg":"success"}}"""),
        )
        // HTTP 200 with the refusal inside: the wrong site's key is the one people hit.
        val wrongKey = assertThrows(SpeechException::class.java) {
            Speech.miniMaxAudio("""{"base_resp":{"status_code":1004,"status_msg":"login fail"}}""")
        }
        assertTrue(wrongKey.message!!.contains("国内版和国际版"))
        assertTrue(assertThrows(SpeechException::class.java) {
            Speech.miniMaxAudio("""{"base_resp":{"status_code":2038,"status_msg":"x"}}""")
        }.message!!.contains("实名认证"))
        assertThrows(SpeechException::class.java) { Speech.miniMaxAudio("""{"data":{"audio":"49x"},"base_resp":{"status_code":0}}""") }
        assertThrows(SpeechException::class.java) { Speech.miniMaxAudio("""{"data":{},"base_resp":{"status_code":0}}""") }
        assertNull(Speech.miniMaxProblem(obj("""{"base_resp":{"status_code":0}}""")))
    }

    @Test
    fun eachServicesListOfVoicesIsRead() {
        val mm = Speech.miniMaxVoiceList(
            """{"system_voice":[{"voice_id":"female-shaonv","voice_name":"少女音色","description":["清脆"]}],""" +
                """"voice_cloning":[{"voice_id":"my-voice-1","description":[]}],"base_resp":{"status_code":0}}""",
        )
        assertEquals(listOf(VoiceOption("my-voice-1", "我的音色"), VoiceOption("female-shaonv", "少女音色")), mm)
        assertEquals(
            listOf(VoiceOption("806c9695", "轻快灵动女声"), VoiceOption("abc", "abc")),
            Speech.mosslandVoiceList("""{"object":"list","data":[{"id":"806c9695","name":"轻快灵动女声"},{"id":"abc","name":""}],"has_more":false}"""),
        )
        assertEquals(
            listOf(VoiceOption("21m00Tcm4TlvDq8ikWAM", "Rachel")),
            Speech.elevenLabsVoiceList("""{"voices":[{"voice_id":"21m00Tcm4TlvDq8ikWAM","name":"Rachel","category":"premade"}]}"""),
        )
        assertThrows(SpeechException::class.java) { Speech.miniMaxVoiceList("""{"base_resp":{"status_code":1004,"status_msg":"login fail"}}""") }
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
