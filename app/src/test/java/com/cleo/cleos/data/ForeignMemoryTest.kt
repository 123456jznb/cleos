package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Third-party memory files: a JSON array, one wrapped in an object, or plain text / Markdown. */
class ForeignMemoryTest {
    @Test
    fun `json array of strings is read as profile topics`() {
        val out = ForeignMemory.parse("""["喜欢喝咖啡","住在上海"]""")
        assertEquals(2, out.size)
        assertEquals(listOf("profile", "profile"), out.map { it.kind })
        assertEquals("喜欢喝咖啡", out[0].name)
        assertEquals("住在上海", out[1].name)
    }

    @Test
    fun `json object wrapping the array keeps name and details`() {
        val out = ForeignMemory.parse(
            """{"memories":[{"name":"称呼","summary":"喜欢被叫小名","details":["a","b"]}]}""",
        )
        assertEquals(1, out.size)
        assertEquals("称呼", out[0].name)
        assertEquals("喜欢被叫小名", out[0].summary)
        assertEquals(2, out[0].details.size)
    }

    @Test
    fun `plain text splits on blank lines and bullets`() {
        val out = ForeignMemory.parse("称呼: 喜欢被叫小名\n\n- 喜欢喝咖啡\n- 住在上海")
        assertEquals(3, out.size)
        assertEquals("称呼", out[0].name)
        assertEquals("喜欢喝咖啡", out[1].name)
        assertEquals("住在上海", out[2].name)
    }
}
