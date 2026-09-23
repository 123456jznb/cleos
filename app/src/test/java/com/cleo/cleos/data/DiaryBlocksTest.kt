package com.cleo.cleos.data

import com.cleo.cleos.ai.ApiEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DiaryBlocksTest {
    @Test
    fun roundTripKeepsOrder() {
        val blocks = listOf(
            DiaryBlock.Text("早上去了河边。"),
            DiaryBlock.Image("a.jpg", 1600, 1200),
            DiaryBlock.Text("下午睡了个午觉。"),
        )
        assertEquals(blocks, DiaryBlocks.decode(DiaryBlocks.encode(blocks)))
    }

    @Test
    fun unreadableContentBecomesTextInsteadOfDisappearing() {
        assertEquals(listOf(DiaryBlock.Text("not json")), DiaryBlocks.decode("not json"))
    }

    @Test
    fun plainTextSkipsImages() {
        val blocks = listOf(DiaryBlock.Text("一"), DiaryBlock.Image("a.jpg", 1, 1), DiaryBlock.Text("二"))
        assertEquals("一\n二", DiaryBlocks.plainText(blocks))
    }
}

class ApiEndpointTest {
    @Test
    fun baseUrlsWithOrWithoutThePath() {
        assertEquals("https://api.deepseek.com/chat/completions", ApiEndpoint("https://api.deepseek.com", "k", "m").chatUrl)
        assertEquals("https://api.openai.com/v1/chat/completions", ApiEndpoint("https://api.openai.com/v1/", "k", "m").chatUrl)
        assertEquals("https://x.io/v1/chat/completions", ApiEndpoint(" https://x.io/v1/chat/completions ", "k", "m").chatUrl)
        assertEquals("https://x.io/v1/models", ApiEndpoint("https://x.io/v1/chat/completions", "k", "m").modelsUrl)
    }

    @Test
    fun keysAreFiledByAddressHoweverItIsWritten() {
        val a = addressOf("https://api.deepseek.com")
        assertEquals(a, addressOf(" https://API.DeepSeek.com/ "))
        assertEquals(a, addressOf("https://api.deepseek.com/chat/completions"))
        assertNotEquals(a, addressOf("https://api.openai.com/v1"))
    }
}
