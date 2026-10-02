package com.cleo.cleos.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checking that an endpoint works.
 *
 * Written after a real afternoon lost to this: a user's address was right and her API key
 * was pasted in half. The app tested the connection by listing models, that list came back
 * in an unexpected shape, and so the app said the address looked wrong — the one thing that
 * was fine — and never mentioned the key. She changed the address instead, onto 智谱's old
 * `/v1`, which answers HTTP 200 to anything, and from there no error could reach her at all.
 */
class ConnectionCheckTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(s: String) = json.parseToJsonElement(s).jsonObject

    @Test
    fun `the probe asks for one token and does not stream`() {
        val body = probeBody("glm-4.7-flash")
        assertEquals(JsonPrimitive("glm-4.7-flash"), body["model"])
        assertEquals(JsonPrimitive(false), body["stream"])
        assertEquals(JsonPrimitive(1), body["max_tokens"])
    }

    @Test
    fun `the probe sends exactly one short message`() {
        val messages = probeBody("m")["messages"]!!.let { json.parseToJsonElement(it.toString()) }
        val list = messages.toString()
        assertTrue(list, list.contains("\"role\":\"user\""))
        assertEquals(1, Regex("\"role\"").findAll(list).count())
    }

    @Test
    fun `智谱's old endpoint says why, in its own words`() {
        // Verbatim from https://open.bigmodel.cn/api/paas/v1/chat/completions, HTTP 200.
        val said = notAChatReply(
            obj("""{"code":1001,"msg":"Header中未收到Authorization参数，无法进行身份验证。","success":false}"""),
        )
        assertTrue(said, said.contains("无法进行身份验证"))
    }

    @Test
    fun `an OpenAI-shaped error is unwrapped too`() {
        val said = notAChatReply(obj("""{"error":{"code":"1001","message":"余额不足"}}"""))
        assertTrue(said, said.contains("余额不足"))
    }

    @Test
    fun `a bare message field counts as well`() {
        assertTrue(notAChatReply(obj("""{"message":"model not found"}""")).contains("model not found"))
    }

    @Test
    fun `saying nothing at all still gets a usable sentence`() {
        val said = notAChatReply(obj("""{"ok":true}"""))
        assertTrue(said, said.contains("没有按聊天接口回话"))
    }

    @Test
    fun `no complaint ever sends someone looking for a v1`() {
        // The old wording told everyone to "check whether the address is at /v1". 智谱 is
        // /v4 and DeepSeek is /v1, so it was wrong half the time — and acting on it moved
        // people onto an address that could no longer report their real problem.
        val complaints = listOf(
            notAChatReply(obj("""{"ok":true}""")),
            notAChatReply(obj("""{"code":1001,"msg":"x"}""")),
        )
        for (c in complaints) assertFalse(c, c.contains("/v1"))
    }

    @Test
    fun `the models URL is the same whichever way the address is written`() {
        val expected = "https://open.bigmodel.cn/api/paas/v4/models"
        for (written in listOf(
            "https://open.bigmodel.cn/api/paas/v4",
            "https://open.bigmodel.cn/api/paas/v4/",
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
        )) {
            assertEquals(written, expected, ApiEndpoint(written, "k", "m").modelsUrl)
        }
    }
}
