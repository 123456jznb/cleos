package com.cleo.cleos.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamParserTest {
    private fun run(vararg payloads: String): Pair<List<ChatEvent>, List<ToolCall>> {
        val p = StreamParser()
        val events = payloads.flatMap { p.feed(it) }
        return events to p.toolCalls()
    }

    @Test
    fun openAiStyleFragmentsAreJoinedByIndex() {
        val (events, calls) = run(
            """{"choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"add_todo","arguments":""}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"title\":"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"交报告\"}"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
        )
        assertTrue(events.isEmpty())
        assertEquals(listOf(ToolCall("call_1", "add_todo", """{"title":"交报告"}""")), calls)
    }

    @Test
    fun parallelCallsInterleavedStayApart() {
        val (_, calls) = run(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"list_todos","arguments":"{"}},{"index":1,"id":"b","function":{"name":"get_weather","arguments":"{\"ci"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"ty\":\"杭州\"}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}}]}""",
        )
        assertEquals(
            listOf(ToolCall("a", "list_todos", "{}"), ToolCall("b", "get_weather", """{"city":"杭州"}""")),
            calls,
        )
    }

    @Test
    fun wholeCallsWithoutIndexAreTwoCallsWhenTheIdsDiffer() {
        val (_, calls) = run(
            """{"choices":[{"delta":{"tool_calls":[{"id":"x1","type":"function","function":{"name":"list_todos","arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"id":"x2","type":"function","function":{"name":"get_weather","arguments":"{}"}}]}}]}""",
        )
        assertEquals(listOf("list_todos", "get_weather"), calls.map { it.name })
    }

    @Test
    fun aPieceWithoutIndexOrIdContinuesTheLastCall() {
        val (_, calls) = run(
            """{"choices":[{"delta":{"tool_calls":[{"id":"x1","function":{"name":"add_todo","arguments":"{\"title\""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":":\"买牛奶\"}"}}]}}]}""",
        )
        assertEquals(listOf(ToolCall("x1", "add_todo", """{"title":"买牛奶"}""")), calls)
    }

    @Test
    fun argumentsSentAsAnObjectBecomeText() {
        val (_, calls) = run(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"add_todo","arguments":{"title":"买菜"}}}]}}]}""",
        )
        assertEquals("""{"title":"买菜"}""", calls.single().arguments)
    }

    @Test
    fun aCallWithoutAnIdGetsOne() {
        val (_, calls) = run("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"list_todos","arguments":"{}"}}]}}]}""")
        assertEquals("call_0", calls.single().id)
    }

    @Test
    fun reasoningTextAndCallsInOneReply() {
        val (events, calls) = run(
            """{"choices":[{"delta":{"reasoning_content":"对方要记"}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"一件事","content":null}}]}""",
            """{"choices":[{"delta":{"content":"好，我记一下"}}]}""",
            """{"choices":[{"delta":{"content":"","tool_calls":[{"index":0,"id":"c","function":{"name":"add_todo","arguments":"{\"title\":\"a\"}"}}]}}]}""",
        )
        assertEquals(
            listOf(ChatEvent.Reasoning("对方要记"), ChatEvent.Reasoning("一件事"), ChatEvent.Delta("好，我记一下")),
            events,
        )
        assertEquals(1, calls.size)
    }

    @Test
    fun nullToolCallsAndEmptyChunksAreHarmless() {
        val (events, calls) = run(
            """{"choices":[{"delta":{"content":"嗯","tool_calls":null}}]}""",
            """{"choices":[]}""",
            """not json at all""",
        )
        assertEquals(listOf(ChatEvent.Delta("嗯")), events)
        assertTrue(calls.isEmpty())
    }

    @Test(expected = ChatException::class)
    fun anErrorInTheStreamThrows() {
        StreamParser().feed("""{"error":{"message":"context too long"}}""")
    }
}

class RequestBodyTest {
    private val call = ToolCall("call_1", "add_todo", """{"title":"交报告"}""")

    private fun body(messages: List<ApiMessage>, tools: List<ToolSpec> = emptyList()): JsonObject =
        Json.parseToJsonElement(requestBody("m", messages, tools).toString()).jsonObject

    @Test
    fun aCallWithoutWordsGoesBackWithNullContentAndItsResultFollows() {
        val b = body(
            listOf(
                ApiMessage("user", "记一下"),
                ApiMessage("assistant", "", listOf(call), reasoning = "想了想"),
                ApiMessage("tool", "已添加", toolCallId = "call_1"),
            ),
        )
        val msgs = b["messages"]!!.jsonArray
        val assistant = msgs[1].jsonObject
        assertEquals(JsonNull, assistant["content"])
        assertEquals("想了想", assistant["reasoning_content"]!!.jsonPrimitive.content)
        val fn = assistant["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
        assertEquals("add_todo", fn["name"]!!.jsonPrimitive.content)
        assertEquals("""{"title":"交报告"}""", fn["arguments"]!!.jsonPrimitive.content)
        assertEquals("call_1", msgs[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertFalse("no tools offered", "tools" in b)
    }

    @Test
    fun plainMessagesCarryNoToolFields() {
        val m = body(listOf(ApiMessage("assistant", "早", reasoning = "ignored without calls")))["messages"]!!.jsonArray[0].jsonObject
        assertEquals(setOf("role", "content"), m.keys)
    }

    @Test
    fun brokenArgumentsAreNotEchoed() {
        val b = body(listOf(ApiMessage("assistant", "", listOf(call.copy(arguments = """{"title":"交"""))), ApiMessage("tool", "x", toolCallId = "call_1")))
        val args = b["messages"]!!.jsonArray[0].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!
            .jsonObject["arguments"]!!.jsonPrimitive.content
        assertEquals("{}", args)
    }

    @Test
    fun offeredToolsAreListed() {
        val b = body(listOf(ApiMessage("user", "hi")), listOf(ToolSpecs.addTodo, ToolSpecs.getWeather))
        val names = b["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("add_todo", "get_weather"), names)
        val params = b["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals("title", params["required"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun thinkingIsAskedForAndEveryReplyCarriesItsReasoning() {
        // An earlier turn, then a turn still under way with a call and its result.
        val messages = listOf(
            ApiMessage("system", "s"),
            ApiMessage("user", "早"),
            ApiMessage("assistant", "早呀"),
            ApiMessage("user", "记一下"),
            ApiMessage("assistant", "", listOf(call), reasoning = "想了想"),
            ApiMessage("tool", "已添加", toolCallId = "call_1"),
        )
        val on = Json.parseToJsonElement(requestBody("m", messages, emptyList(), thinking = true).toString()).jsonObject
        assertEquals("enabled", on["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val sent = on["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("", sent[2]["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("想了想", sent[4]["reasoning_content"]!!.jsonPrimitive.content)
        assertFalse("reasoning_content" in sent[1])
        assertFalse("reasoning_content" in sent[5])
        // Without it, only the call in the turn under way carries its reasoning.
        val off = body(messages)
        assertFalse("thinking" in off)
        val plain = off["messages"]!!.jsonArray.map { it.jsonObject }
        assertFalse("reasoning_content" in plain[2])
        assertEquals("想了想", plain[4]["reasoning_content"]!!.jsonPrimitive.content)
    }
}
