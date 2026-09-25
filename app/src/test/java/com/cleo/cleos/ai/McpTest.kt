package com.cleo.cleos.ai

import com.cleo.cleos.data.McpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTest {
    private val server = McpServer(id = "a1b2c3d4-0000", name = "咖啡")

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun requestsAreJsonRpc() {
        assertEquals("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""", Mcp.request(3, "tools/list"))
        assertEquals("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", Mcp.notification("notifications/initialized"))
        val init = Mcp.initializeParams("0.15.0")
        assertEquals(Mcp.PROTOCOL, init["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("Cleos", init["clientInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun theAnswerIsFoundInAStreamOfEvents() {
        val lines = listOf(
            "event: message",
            """data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}""",
            "",
            ": a comment",
            "event: message",
            """data: {"jsonrpc":"2.0","id":7,""",
            """data: "result":{"ok":true}}""",
            "",
        )
        assertEquals("true", Mcp.resultOf(Mcp.fromEvents(lines.iterator(), 7))["ok"]!!.jsonPrimitive.content)
        // The last event may end the stream without a blank line after it.
        assertEquals("true", Mcp.resultOf(Mcp.fromEvents(lines.dropLast(1).iterator(), 7))["ok"]!!.jsonPrimitive.content)
        // A stream that ends before the answer.
        assertThrows(McpException::class.java) { Mcp.fromEvents(lines.take(3).iterator(), 7) }
    }

    @Test
    fun anAnswerInJsonAndAnError() {
        assertEquals(JsonObject(emptyMap()), Mcp.resultOf(Mcp.fromJson("""{"jsonrpc":"2.0","id":2,"result":{}}""", 2)))
        assertThrows(McpException::class.java) { Mcp.fromJson("<html>not here</html>", 2) }
        assertThrows(McpException::class.java) { Mcp.fromJson("""{"jsonrpc":"2.0","id":9,"result":{}}""", 2) }
        val e = assertThrows(McpException::class.java) {
            Mcp.resultOf(obj("""{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Unknown tool"}}"""))
        }
        assertEquals("服务回了错误：Unknown tool", e.message)
    }

    @Test
    fun toolsAreReadOneByOne() {
        val result = obj(
            """{"tools":[
                {"name":"get_menu","title":"查菜单","description":"看看有什么","inputSchema":{"type":"object","${'$'}schema":"x"},"annotations":{"readOnlyHint":true}},
                {"description":"没有名字：一个读不了的工具不连累别的"},
                {"name":"place.order","description":"下单","inputSchema":{"type":"object","properties":{"drink":{"type":"string"}},"required":["drink"]}}
            ]}""",
        )
        val tools = Mcp.tools(server, result)
        assertEquals(listOf("get_menu", "place.order"), tools.map { it.name })
        assertEquals(listOf("mcp_a1b2_get_menu", "mcp_a1b2_place_order"), tools.map { it.fnName })
        assertTrue(tools[0].readOnly)
        assertFalse(tools[1].readOnly)
        assertEquals(listOf("查菜单", "place.order"), tools.map { it.title })
        assertFalse("\$schema" in tools[0].inputSchema)
        assertEquals(JsonObject(emptyMap()), tools[0].inputSchema["properties"])
        assertEquals("【咖啡】看看有什么", tools[0].spec.description)
        assertEquals("用咖啡的查菜单", tools[0].spec.action)
        assertEquals(64, Mcp.fnName("id", "x".repeat(100)).length)
    }

    @Test
    fun aResultIsTextForTheModel() {
        fun text(json: String) = Mcp.text(obj(json))
        assertEquals(
            "生椰拿铁 18 元\n[一张图片]",
            text("""{"content":[{"type":"text","text":"生椰拿铁 18 元"},{"type":"image","data":"AA","mimeType":"image/png"}]}"""),
        )
        assertEquals("出错了：卖完了", text("""{"content":[{"type":"text","text":"卖完了"}],"isError":true}"""))
        assertEquals("（没有内容）", text("""{"content":[]}"""))
        assertTrue(text("""{"content":[{"type":"text","text":"${"字".repeat(7000)}"}]}""").endsWith("（太长，后面省略了）"))
    }

    @Test
    fun keysStayOutOfSight() {
        assertEquals("abc", Mcp.bearer("Bearer abc"))
        assertEquals("abc", Mcp.bearer("  bearer abc "))
        assertNull(Mcp.bearer(" "))
        assertEquals("X-Api-Key" to "k1", Mcp.header("X-Api-Key: k1"))
        assertNull(Mcp.header("no colon"))
        assertNull(Mcp.header("two words: x"))
        assertEquals(
            "https://mcp.example.com/servers?key=••••&v=1",
            Mcp.displayUrl("https://mcp.example.com/servers?key=sk-123&v=1"),
        )
        assertEquals("https://example.com/mcp", Mcp.displayUrl("https://example.com/mcp"))
        assertEquals("drink：生椰拿铁\nsize：大杯", Mcp.preview(obj("""{"drink":"生椰拿铁","size":"大杯"}""")))
        assertEquals("（不带参数）", Mcp.preview(JsonObject(emptyMap())))
    }
}
